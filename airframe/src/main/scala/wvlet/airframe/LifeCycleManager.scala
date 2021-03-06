/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe

import java.util.concurrent.atomic.AtomicReference

import wvlet.airframe.surface.Surface
import wvlet.airframe.tracing.Tracer
import wvlet.log.{LogSupport, Logger}

sealed trait LifeCycleStage
case object INIT     extends LifeCycleStage
case object STARTING extends LifeCycleStage
case object STARTED  extends LifeCycleStage
case object STOPPING extends LifeCycleStage
case object STOPPED  extends LifeCycleStage

/**
  * LifeCycleManager manages the life cycle of objects within a Session
  */
class LifeCycleManager(private[airframe] val eventHandler: LifeCycleEventHandler,
                       // EventHandler without lifecycle logger and shutdown hook
                       private[airframe] val coreEventHandler: LifeCycleEventHandler)
    extends LogSupport {
  self =>

  import LifeCycleManager._

  private val state                = new AtomicReference[LifeCycleStage](INIT)
  def currentState: LifeCycleStage = state.get()

  private[airframe] def onInit(t: Surface, injectee: AnyRef): Unit = {
    eventHandler.onInit(this, t, injectee)
  }

  // Session and tracer will be available later
  private[airframe] var session: AirframeSession = _
  private[airframe] var tracer: Tracer           = _

  private[airframe] def setSession(s: AirframeSession): Unit = {
    session = s
    tracer = session.tracer
  }

  def sessionName: String = session.name

  def start: Unit = {
    if (!state.compareAndSet(INIT, STARTING)) {
      throw new IllegalStateException(s"LifeCycle is already starting")
    }

    tracer.onSessionStart(session)
    eventHandler.beforeStart(this)
    // Run start hooks in the registration order
    state.set(STARTED)
    eventHandler.afterStart(this)
  }

  def shutdown: Unit = {
    if (state.compareAndSet(STARTED, STOPPING) || state.compareAndSet(INIT, STOPPING)
        || state.compareAndSet(STARTING, STOPPING)) {

      tracer.beforeSessionShutdown(session)
      eventHandler.beforeShutdown(this)
      // Run shutdown hooks in the reverse registration order
      state.set(STOPPED)

      tracer.onSessionShutdown(session)
      eventHandler.afterShutdown(this)
      tracer.onSessionEnd(session)
    }
  }

  private[airframe] var initHookHolder        = new LifeCycleHookHolder
  private[airframe] var startHookHolder       = new LifeCycleHookHolder
  private[airframe] var preShutdownHookHolder = new LifeCycleHookHolder
  private[airframe] var shutdownHookHolder    = new LifeCycleHookHolder

  def startHooks: Seq[LifeCycleHook]       = startHookHolder.list
  def preShutdownHooks: Seq[LifeCycleHook] = preShutdownHookHolder.list
  def shutdownHooks: Seq[LifeCycleHook]    = shutdownHookHolder.list

  protected def addHook(h: LifeCycleHook)(body: LifeCycleManager => Unit): Unit = {
    // Adding a lifecycle hook in the owner session
    session.findOwnerSessionOf(h.surface) match {
      case Some(s) => body(s.lifeCycleManager)
      case None    => body(this)
    }
  }

  def addInitHook(h: LifeCycleHook): Unit = {
    addHook(h) { l =>
      if (l.initHookHolder.isFirstRegistration(h)) {
        debug(s"[${l.sessionName}] Add an init hook: ${h.surface}")
        h.execute
      } else {
        trace(s"[${l.sessionName}] ${h.injectee} is already initialized")
      }
    }
  }

  def addInjectHook(h: LifeCycleHook): Unit = {
    addHook(h) { l =>
      debug(s"[${l.sessionName}] Running an inject hook: ${h.surface}")
      // Run immediately
      h.execute
    }
  }

  def addStartHook(h: LifeCycleHook): Unit = {
    addHook(h) { l =>
      l.synchronized {
        if (l.startHookHolder.isFirstRegistration(h)) {
          debug(s"[${l.sessionName}] Add a start hook for ${h.surface}")
          val s = l.state.get
          if (s == STARTED) {
            // If a session is already started, run the start hook immediately
            tracer.onStartInstance(session, h.injectee)
            h.execute
          }
        }
      }
    }
  }

  def addPreShutdownHook(h: LifeCycleHook): Unit = {
    addHook(h) { l =>
      l.synchronized {
        if (l.preShutdownHookHolder.isFirstRegistration(h)) {
          debug(s"[${l.sessionName}] Add a pre-shutdown hook for ${h.surface}")
        }
      }
    }
  }

  def addShutdownHook(h: LifeCycleHook): Unit = {
    addHook(h) { l =>
      l.synchronized {
        if (l.shutdownHookHolder.isFirstRegistration(h)) {
          debug(s"[${l.sessionName}] Add a shutdown hook for ${h.surface}")
        }
      }
    }
  }
}

object LifeCycleManager {

  private[airframe] class LifeCycleHookHolder(private var holder: Vector[LifeCycleHook] = Vector.empty) {
    def list: Seq[LifeCycleHook] = holder

    /**
      *  Return true if it is not yet registered
      */
    def isFirstRegistration(x: LifeCycleHook): Boolean = {
      synchronized {
        if (list.exists(_.injectee == x.injectee)) {
          false
        } else {
          // Register this hook
          holder :+= x
          true
        }
      }
    }
  }

  def defaultLifeCycleEventHandler: LifeCycleEventHandler =
    FILOLifeCycleHookExecutor andThen JSR250LifeCycleExecutor
}

object ShowLifeCycleLog extends LifeCycleEventHandler {
  private val logger = Logger.of[LifeCycleManager]

  override def beforeStart(lifeCycleManager: LifeCycleManager): Unit = {
    logger.info(s"[${lifeCycleManager.sessionName}] Starting a new lifecycle ...")
  }

  override def afterStart(lifeCycleManager: LifeCycleManager): Unit = {
    logger.info(s"[${lifeCycleManager.sessionName}] ======== STARTED ========")
  }

  override def beforeShutdown(lifeCycleManager: LifeCycleManager): Unit = {
    logger.info(s"[${lifeCycleManager.sessionName}] Stopping the lifecycle ...")
  }

  override def afterShutdown(lifeCycleManager: LifeCycleManager): Unit = {
    logger.info(s"[${lifeCycleManager.sessionName}] The lifecycle has stopped.")
  }
}

object ShowDebugLifeCycleLog extends LifeCycleEventHandler {
  private val logger = Logger.of[LifeCycleManager]

  override def beforeStart(lifeCycleManager: LifeCycleManager): Unit = {
    logger.debug(s"[${lifeCycleManager.sessionName}] Starting a new lifecycle ...")
  }

  override def afterStart(lifeCycleManager: LifeCycleManager): Unit = {
    logger.debug(s"[${lifeCycleManager.sessionName}] ======== STARTED ========")
  }

  override def beforeShutdown(lifeCycleManager: LifeCycleManager): Unit = {
    logger.debug(s"[${lifeCycleManager.sessionName}] Stopping the lifecycle ...")
  }

  override def afterShutdown(lifeCycleManager: LifeCycleManager): Unit = {
    logger.debug(s"[${lifeCycleManager.sessionName}] The lifecycle has stopped.")
  }
}

/**
  * First In, Last Out (FILO) hook executor.
  *
  * If objects are injected in A -> B -> C order, init an shutdown orders will be:
  * init => A -> B -> C
  * shutdown order => C -> B -> A
  */
object FILOLifeCycleHookExecutor extends LifeCycleEventHandler with LogSupport {
  override def beforeStart(lifeCycleManager: LifeCycleManager): Unit = {
    lifeCycleManager.startHooks.map { h =>
      trace(s"Calling start hook: $h")
      h.execute
    }
  }

  override def beforeShutdown(lifeCycleManager: LifeCycleManager): Unit = {
    // beforeShutdown
    for (h <- lifeCycleManager.preShutdownHooks.reverse) {
      trace(s"Calling pre-shutdown hook: $h")
      lifeCycleManager.tracer.beforeShutdownInstance(lifeCycleManager.session, h.injectee)
      h.execute
    }

    // onShutdown
    val shutdownOrder = lifeCycleManager.shutdownHooks.reverse
    if (shutdownOrder.nonEmpty) {
      debug(s"[${lifeCycleManager.sessionName}] Shutdown order:\n${shutdownOrder.mkString("\n-> ")}")
    }
    shutdownOrder.map { h =>
      trace(s"Calling shutdown hook: $h")
      lifeCycleManager.tracer.onShutdownInstance(lifeCycleManager.session, h.injectee)
      h.execute
    }
  }
}
