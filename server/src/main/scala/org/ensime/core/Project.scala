package org.ensime.core

import akka.actor._
import akka.actor.{ Actor, ActorRef, ActorSystem, Cancellable, Props }
import akka.event.LoggingReceive
import org.apache.commons.vfs2.FileObject

import org.ensime.api._

import org.ensime.config._
import org.ensime.indexer._
import org.ensime.model._
import org.ensime.server.protocol._
import org.ensime.util._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class Project(
    val config: EnsimeConfig,
    actorSystem: ActorSystem
) extends ProjectEnsimeApiImpl {
  val log = LoggerFactory.getLogger(this.getClass)

  protected val actor = actorSystem.actorOf(Props(new ProjectActor()), "project")

  private val readyPromise = Promise[Unit]()

  protected var analyzer: Option[ActorRef] = None

  private val ensimeVFS = EnsimeVFS()

  private val resolver = new SourceResolver(config, ensimeVFS)

  // TODO: add PresCompiler to the source watcher
  private val sourceWatcher = new SourceWatcher(config, resolver :: Nil, ensimeVFS)
  private val searchService = new SearchService(config, resolver, actorSystem, ensimeVFS)
  private val reTypecheck = new ClassfileListener {
    def reTypeCheck(): Unit = actor ! AskReTypecheck
    def classfileAdded(f: FileObject): Unit = reTypeCheck()
    def classfileChanged(f: FileObject): Unit = reTypeCheck()
    def classfileRemoved(f: FileObject): Unit = reTypeCheck()
  }
  private val classfileWatcher = new ClassfileWatcher(config, searchService :: reTypecheck :: Nil, ensimeVFS)

  import scala.concurrent.ExecutionContext.Implicits.global
  searchService.refresh().onSuccess {
    case (deletes, inserts) =>
      actor ! AsyncEvent(IndexerReadyEvent)
      log.debug(s"indexed $inserts and removed $deletes")
  }

  protected val indexer: ActorRef = actorSystem.actorOf(Props(
    new Indexer(config, searchService, ensimeVFS)
  ), "indexer")

  protected val docServer: ActorRef = actorSystem.actorOf(Props(
    new DocServer(config, true)
  ), "docServer")

  protected var debugger: Option[ActorRef] = None

  def getAnalyzer: ActorRef = {
    analyzer.getOrElse(throw new RuntimeException("Analyzer unavailable."))
  }

  private var undoCounter = 0

  // undos probably shouldn't be stored here. It makes more sense to
  // store them where they are used.
  private val undos: mutable.LinkedHashMap[Int, Undo] = new mutable.LinkedHashMap[Int, Undo]

  class ProjectActor extends Actor {
    case object Retypecheck

    val typecheckDelay = 1000.millis
    val typecheckCooldown = 5000.millis
    private var tick: Option[Cancellable] = None

    private var earliestRetypecheck = Deadline.now

    override def postStop(): Unit = {
      tick.foreach(_.cancel())
    }

    // buffer events until the first client connects
    private var asyncEvents = Vector[EnsimeEvent]()
    private var asyncListeners: List[EnsimeEvent => Unit] = Nil

    override def receive: Receive = waiting orElse ready

    private val ready: Receive = LoggingReceive.withLabel("ready") {
      case Retypecheck =>
        log.warn("Re-typecheck needed")
        analyzer.foreach(_ ! ReloadExistingFilesEvent)
        earliestRetypecheck = typecheckCooldown.fromNow
      case AskReTypecheck =>
        tick.foreach(_.cancel())
        val timeToTypecheck = earliestRetypecheck.timeLeft max typecheckDelay
        tick = Some(context.system.scheduler.scheduleOnce(timeToTypecheck, self, Retypecheck))

      case AddUndo(sum, changes) =>
        addUndo(sum, changes)

      case AsyncEvent(event) =>
        checkReady(event)
        asyncListeners foreach { l =>
          l(event)
        }
      case SubscribeAsync(handler) =>
        asyncListeners ::= handler
        sender ! false
    }

    private[this] var initialisationComplete = false

    private[this] var indexerReady = false
    private[this] var analyserReady = false

    private[this] def checkReady(event: EnsimeEvent): Unit = {
      if (!initialisationComplete) {
        event match {
          case AnalyzerReadyEvent =>
            analyserReady = true
          case IndexerReadyEvent =>
            indexerReady = true
          case _ =>
        }

        if (analyserReady && indexerReady) {
          initialisationComplete = true
          readyPromise.success(())
        }
      }
    }

    private[this] val waiting: Receive = LoggingReceive.withLabel("waiting") {
      case SubscribeAsync(handler) =>
        asyncListeners ::= handler
        asyncEvents.foreach { event => handler(event) }
        asyncEvents = Vector.empty
        context.become(ready, discardOld = true)
        sender ! true
      case AsyncEvent(event) =>
        asyncEvents :+= event
        checkReady(event)
    }
  }

  protected def addUndo(sum: String, changes: Iterable[FileEdit]): Unit = {
    undoCounter += 1
    undos(undoCounter) = Undo(undoCounter, sum, changes.toList)
  }

  override def peekUndo(): Option[Undo] = {
    undos.lastOption.map(_._2)
  }

  override def execUndo(undoId: Int): Either[String, UndoResult] = {
    undos.get(undoId) match {
      case Some(u) =>
        undos.remove(u.id)
        callRPC[Either[String, UndoResult]](getAnalyzer, DoExecUndo(u))
      case _ => Left("No such undo.")
    }
  }

  def initProject(): Future[Unit] = {
    startCompiler()
    shutdownDebugger()
    undos.clear()
    undoCounter = 0
    readyPromise.future
  }

  protected def startCompiler(): Unit = {
    val newAnalyzer = actorSystem.actorOf(Props(
      new Analyzer(actor, indexer, searchService, config, ensimeVFS)
    ), "analyzer")
    analyzer = Some(newAnalyzer)
  }

  protected def acquireDebugger: ActorRef = {
    debugger match {
      case Some(d) => d
      case None =>
        val d = actorSystem.actorOf(Props(new DebugManager(actor, indexer, config)))
        debugger = Some(d)
        d
    }
  }

  protected def shutdownDebugger(): Unit = {
    debugger.foreach(_ ! DebuggerShutdownEvent)
    debugger = None
  }

  protected def shutdownCompiler(): Unit = {
    analyzer.foreach(_ ! PoisonPill)
  }

  def shutdown(): Unit = {
    shutdownCompiler()
    shutdownDebugger()
    classfileWatcher.shutdown()
    sourceWatcher.shutdown()
    searchService.shutdown()
    ensimeVFS.close()
  }

  // TODO Move to server - this does not belong here
  override def shutdownServer(): Unit = {
    val t = new Thread() {
      override def run(): Unit = {
        log.info("Server is exiting...")
        Thread.sleep(1000)
        log.info("Shutting down actor system...")
        actorSystem.shutdown()
        Thread.sleep(1000)
        log.info("Forcing exit...")
        Thread.sleep(200)
        System.exit(0)
      }
    }
    t.start()
  }
}

