package totoro.ocelot.brain.event

import totoro.ocelot.brain.event.FileSystemActivityType.ActivityType
import totoro.ocelot.brain.network.Node

import scala.collection.mutable
import scala.reflect.{ClassTag, classTag}

/**
  * Main Ocelot event bus for a feedback from the components.
  * Computer beeping, modem LED flashing, screen rendering - all of it goes here,
  * and must be listened to.
  */
object EventBus {
  private val listeners = mutable.HashMap.empty[Class[_ <: Event], mutable.HashSet[(_ <: Event) => Unit]]
  private val canceledSet = mutable.HashSet.empty[Subscription]
  private var dispatchInProgress: Boolean = false

  /**
    * Creates a subscription for an event [[E]].
    *
    * If the listener is already registered for the event, returns the associated subscription handle without creating
    * a new one.
    *
    * @note Events are dispatched according to their runtime class.
    *       If the subscription is created not for the concrete runtime class of an event but its superclass,
    *       the listener will not be invoked.
    * @tparam E the type to derive the runtime class of an event from
    * @return a handle to manage the subscription
    */
  def subscribe[E <: Event : ClassTag](listener: E => Unit): Subscription = {
    val runtimeClass = classTag[E].runtimeClass.asSubclass(classOf[Event])
    val listenerSet = listeners.getOrElseUpdate(runtimeClass, mutable.HashSet.empty)

    listenerSet += listener

    new Subscription(runtimeClass, listener)
  }

  /**
    * Dispatches an event to listeners subscribed to its runtime class.
    */
  def send(event: Event): Unit = {
    val runtimeClass = event.getClass

    dispatchInProgress = true
    listeners.get(runtimeClass)
      .iterator
      .flatMap(_.iterator)
      .filterNot(listener => canceledSet.contains(new Subscription(runtimeClass, listener)))
      .foreach(listener => listener.asInstanceOf[Event => Unit](event))
    dispatchInProgress = false

    canceledSet.foreach(_.remove())
    canceledSet.clear()
  }

  private val fileSystemAccessTimeouts = mutable.WeakHashMap.empty[Node, Long]
  def sendDiskActivity(node: Node, activityType: ActivityType): Unit = {
    fileSystemAccessTimeouts.get(node) match {
      case Some(timeout) if timeout > System.currentTimeMillis() => // Cooldown.
      case _ =>
        send(FileSystemActivityEvent(node.address, activityType))
        fileSystemAccessTimeouts.put(node, System.currentTimeMillis() + 500)
    }
  }

  final class Subscription private[EventBus](private val runtimeClass: Class[_ <: Event],
                                             private val listener: (_ <: Event) => Unit) {
    // does not immediately remove the subscription to avoid messing up iterators during dispatch
    def cancel(): Unit =
      if (dispatchInProgress) canceledSet += this
      else remove()

    private[EventBus] def remove(): Unit = {
      listeners.get(runtimeClass).foreach(_.remove(listener))
    }
  }
}