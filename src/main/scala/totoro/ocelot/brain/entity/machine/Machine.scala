package totoro.ocelot.brain.entity.machine

import totoro.ocelot.brain.entity.fs.{FileSystem, FileSystemAPI}
import totoro.ocelot.brain.entity.machine.Callbacks.InnerCallback
import totoro.ocelot.brain.entity.traits.{CallBudget, DeviceInfo, DiskActivityAware, Environment, MachineHost, Processor}
import totoro.ocelot.brain.event.{BeepEvent, BeepPatternEvent, EventBus, MachineCrashEvent}
import totoro.ocelot.brain.nbt.ExtendedNBT._
import totoro.ocelot.brain.nbt._
import totoro.ocelot.brain.network._
import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.util.ResultWrapper.result
import totoro.ocelot.brain.workspace.Workspace
import totoro.ocelot.brain.{Ocelot, Settings}

import java.util
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class Machine(val host: MachineHost) extends Environment with Context with Runnable with DeviceInfo with DiskActivityAware {
  override val node: Component = Network.newNode(this, Visibility.Network).
    withComponent("computer", Visibility.Neighbors).
    create()

  val tmp: Option[FileSystem] = if (Settings.get.tmpSize > 0) {
    Option(FileSystemAPI.asManagedEnvironment(FileSystemAPI.
      fromMemory(Settings.get.tmpSize * 1024), "tmpfs", 5))
  } else None

  var architecture: Architecture = _

  private[machine] val state = mutable.Stack(MachineAPI.State.Stopped)

  private val _components = mutable.Map.empty[String, String]

  private val addedComponents = mutable.Set.empty[Component]

  private val _users = mutable.Set.empty[String]

  private val signals = mutable.Queue.empty[MachineAPI.Signal]

  var maxComponents = 0

  private var maxCallBudget = 1.0

  private var hasMemory = false

  @volatile private var callBudget = 0.0

  // We want to ignore the call limit in synchronized calls to avoid errors.
  private var inSynchronizedCall = false

  // ----------------------------------------------------------------------- //

  var worldTime = 0L // Game-world time for os.time().

  private var uptime = 0L // Game-world time [ticks] for os.uptime().

  private var cpuTotal = 0L // Pseudo-real-world time [ns] for os.clock().

  private var cpuStart = 0L // Pseudo-real-world time [ns] for os.clock().

  private var remainIdle = 0 // Ticks left to sleep before resuming.

  private var remainingPause = 0 // Ticks left to wait before resuming.

  private var usersChanged = false // Send updated users list to clients?

  private var message: Option[String] = None // For error messages.

  private val maxSignalQueueSize = Settings.get.maxSignalQueueSize

  private val _latestInfo = new AtomicReference[LatestInfo](LatestInfo(0, 0, 0, 0, 0, 0))

  private case class LatestInfo(executionStart: Long, executionEnd: Long, freeMemory: Int, totalMemory: Int,
                                callBudget: Double, maxCallBudget: Double)

  // ----------------------------------------------------------------------- //

  def onHostChanged(): Unit = {
    val components = host.inventory
    maxComponents = components.foldLeft(0)((sum, entity) => sum + (entity match {
      case processor: Processor => processor.supportedComponents
      case _ => 0
    }))
    val callBudgets = components.collect({
      case entity: CallBudget => entity.callBudget
    })
    maxCallBudget = if (callBudgets.isEmpty) 1.0 else callBudgets.sum / callBudgets.size
    var newArchitecture: Architecture = null
    components.find {
      case processor: Processor =>
        Option(processor.architecture) match {
          case Some(clazz) =>
            if (architecture == null || architecture.getClass != clazz) try {
              newArchitecture = clazz.getConstructor(classOf[totoro.ocelot.brain.entity.machine.Machine]).newInstance(this)
            } catch {
              case t: Throwable => Ocelot.log.warn("Failed instantiating a CPU architecture.", t)
            }
            else {
              newArchitecture = architecture
            }
            true
          case _ => false
        }
      case _ => false
    }
    // This needs to operate synchronized against the worker thread, to avoid the
    // architecture changing while it is currently being executed.
    if (newArchitecture != architecture) this.synchronized {
      architecture = newArchitecture
      if (architecture != null && node.network != null) architecture.onConnect()
    }
    hasMemory = Option(architecture).fold(false)(_.recomputeMemory(components))
  }

  def components: util.Map[String, String] = _components.asJava

  def componentCount: Int = (_components.foldLeft(0.0)((acc, entry) => entry match {
    case (_, name) => acc + (if (name != "filesystem") 1.0 else 0.25)
  }) + addedComponents.foldLeft(0.0)((acc, component) => acc + (if (component.name != "filesystem") 1 else 0.25)) - 1).toInt // -1 = this computer

  def tmpAddress: String = tmp.fold(null: String)(_.node.address)

  def lastError: String = message.orNull

  def users: Array[String] = _users.synchronized(_users.toArray)

  def upTime(): Double = {
    // Convert from old saves (set to -timeStarted on load).
    if (uptime < 0) {
      uptime = worldTime + uptime
    }
    // World time is in ticks, and each second has 20 ticks. Since we
    // want uptime() to return real seconds, though, we'll divide it
    // accordingly.
    uptime / 20.0
  }

  def cpuTime: Double = (cpuTotal + (System.nanoTime() - cpuStart)) * 10e-10

  def latestMemoryUsage: (Int, Int) = {
    val info = _latestInfo.get()
    (info.freeMemory, info.totalMemory)
  }

  def latestExecutionInfo: (Long, Long) = {
    val info = _latestInfo.get()
    (info.executionStart, info.executionEnd)
  }

  def latestCallBudget: (Double, Double) = {
    val info = _latestInfo.get()
    (info.callBudget, info.maxCallBudget)
  }

  // ----------------------------------------------------------------------- //

  override def getDeviceInfo: Map[String, String] = host match {
    case deviceInfo: DeviceInfo => deviceInfo.getDeviceInfo
    case _ => null
  }

  // ----------------------------------------------------------------------- //

  def canInteract(player: String): Boolean = !Settings.get.canComputersBeOwned ||
    _users.synchronized(_users.isEmpty || _users.contains(player))

  def isRunning: Boolean = state.synchronized(state.top != MachineAPI.State.Stopped && state.top != MachineAPI.State.Stopping)

  def isPaused: Boolean = state.synchronized(state.top == MachineAPI.State.Paused && remainingPause > 0)

  override def start(): Boolean = state.synchronized(state.top match {
    case MachineAPI.State.Stopped if node.network != null =>
      onHostChanged()
      processAddedComponents()
      verifyComponents()
      if (architecture == null || maxComponents == 0) {
        beep("-")
        crash("Error.NoCPU")
        false
      }
      else if (componentCount > maxComponents) {
        beep("-..")
        crash("Error.ComponentOverflow")
        false
      }
      else if (!hasMemory) {
        beep("-.")
        crash("Error.NoRAM")
        false
      }
      else if (!init()) {
        beep("--")
        false
      }
      else {
        switchTo(MachineAPI.State.Starting)
        uptime = 0
        node.sendToReachable("computer.started")
        true
      }
    case MachineAPI.State.Paused if remainingPause > 0 =>
      remainingPause = 0
      true
    case MachineAPI.State.Stopping =>
      switchTo(MachineAPI.State.Restarting)
      true
    case _ =>
      false
  })

  def pause(seconds: Double): Boolean = {
    val ticksToPause = math.max((seconds * 20).toInt, 0)

    def shouldPause(state: MachineAPI.State.Value) = state match {
      case MachineAPI.State.Stopping | MachineAPI.State.Stopped => false
      case MachineAPI.State.Paused if ticksToPause <= remainingPause => false
      case _ => true
    }

    if (shouldPause(state.synchronized(state.top))) {
      // Check again when we get the lock, might have changed since.
      Machine.this.synchronized(state.synchronized(if (shouldPause(state.top)) {
        if (state.top != MachineAPI.State.Paused) {
          assert(!state.contains(MachineAPI.State.Paused))
          state.push(MachineAPI.State.Paused)
        }
        remainingPause = ticksToPause
        return true
      }))
    }
    false
  }

  override def stop(): Boolean = state.synchronized(state.headOption match {
    case Some(MachineAPI.State.Stopped | MachineAPI.State.Stopping) =>
      false
    case _ =>
      state.push(MachineAPI.State.Stopping)
      tryClose()
      true
  })

  def consumeCallBudget(callCost: Double): Unit = {
    if (architecture.isInitialized && !inSynchronizedCall) {
      val clampedCost = math.max(0.001, callCost)
      if (clampedCost > callBudget) {
        throw new LimitReachedException()
      }
      callBudget -= clampedCost
    }
  }

  def beep(frequency: Short, duration: Short): Unit = {
    EventBus.send(BeepEvent(this.node.address, frequency, duration))
  }

  def beep(pattern: String): Unit = {
    EventBus.send(BeepPatternEvent(this.node.address, pattern))
  }

  def crash(message: String): Boolean = {
    EventBus.send(MachineCrashEvent(this.node.address, message))
    this.message = Option(message)
    state.synchronized {
      val result = stop()
      if (state.top == MachineAPI.State.Stopping) {
        // When crashing, make sure there's no "Running" left in the stack.
        state.clear()
        state.push(MachineAPI.State.Stopping)
      }
      result
    }
  }

  def convertArg(param: Any): AnyRef = {
    param match {
      case arg: java.lang.Boolean => arg
      case arg: java.lang.Character => Double.box(arg.toDouble)
      case arg: java.lang.Long => arg
      case arg: java.lang.Number => Double.box(arg.doubleValue)
      case arg: java.lang.String => arg
      case arg: Array[Byte] => arg
      case arg: NBTTagCompound => arg
      case arg =>
        Ocelot.log.warn("Trying to push signal with an unsupported argument of type " + arg.getClass.getName)
        null
    }
  }

  override def signal(name: String, args: Any*): Boolean = {
    state.synchronized(state.top match {
      case MachineAPI.State.Stopped | MachineAPI.State.Stopping => return false
      case _ => signals.synchronized {
        if (signals.size >= maxSignalQueueSize) return false
        else if (args == null) {
          signals.enqueue(new MachineAPI.Signal(name, Array.empty))
        }
        else {
          signals.enqueue(new MachineAPI.Signal(name, args.map {
            case null | () | None => null
            case arg: java.util.Map[_, _] =>
              val convertedMap = new mutable.HashMap[AnyRef, AnyRef]
              for ((key, value) <- arg.asScala) {
                val convertedKey = convertArg(key)
                if (convertedKey != null) {
                  val convertedValue = convertArg(value)
                  if (convertedValue != null) {
                    convertedMap += convertedKey -> convertedValue
                  }
                }
              }
              convertedMap
            case arg => convertArg(arg)
          }.toArray[AnyRef]))
        }
      }
    })

    if (architecture != null) architecture.onSignal()
    true
  }

  def popSignal(): MachineAPI.Signal = signals.synchronized(if (signals.isEmpty) null else signals.dequeue().convert())

  def methods(value: scala.AnyRef): util.Map[String, Callback] =
    Callbacks(value).map((entry: (String, InnerCallback)) => {
      val (name, callback) = entry
      name -> callback.annotation
    }).asJava

  def invoke(address: String, method: String, args: Array[AnyRef]): Array[AnyRef] = {
    if (node != null && node.network != null) {
      Option(node.network.node(address)) match {
        case Some(component: Component) if component.canBeSeenFrom(node) || component == node =>
          val annotation = component.annotation(method)
          if (annotation.direct) {
            consumeCallBudget(1.0 / annotation.limit)
          }
          component.invoke(method, this, args: _*)
        case _ => throw new IllegalArgumentException("no such component")
      }
    }
    else {
      // Not really, but makes the VM stop, which is what we want in this case,
      // because it means we've been disconnected / disposed already.
      throw new LimitReachedException()
    }
  }

  def invoke(value: Value, method: String, args: Array[AnyRef]): Array[AnyRef] = {
    Callbacks(value).get(method) match {
      case Some(callback) =>
        val annotation = callback.annotation
        if (annotation.direct) {
          consumeCallBudget(1.0 / annotation.limit)
        }
        val arguments = new Arguments(Seq(args: _*))
        Registry.convert(callback(value, this, arguments))
      case _ => throw new NoSuchMethodException()
    }
  }

  def addUser(name: String) {
    if (_users.size >= Settings.get.maxUsers)
      throw new Exception("too many users")

    if (_users.contains(name))
      throw new Exception("user exists")
    if (name.length > Settings.get.maxUsernameLength)
      throw new Exception("username too long")

    _users.synchronized {
      _users += name
      usersChanged = true
    }
  }

  def removeUser(name: String): Boolean = _users.synchronized {
    val success = _users.remove(name)
    if (success) {
      usersChanged = true
    }
    success
  }

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function():boolean -- Starts the computer. Returns true if the state changed.""")
  def start(context: Context, args: Arguments): Array[AnyRef] =
    result(!isPaused && start())

  @Callback(doc = """function():boolean -- Stops the computer. Returns true if the state changed.""")
  def stop(context: Context, args: Arguments): Array[AnyRef] =
    result(stop())

  @Callback(direct = true, doc = """function():boolean -- Returns whether the computer is running.""")
  def isRunning(context: Context, args: Arguments): Array[AnyRef] =
    result(isRunning)

  @Callback(doc = """function([frequency:string or number[, duration:number]]) -- Plays a tone, useful to alert users via audible feedback.""")
  def beep(context: Context, args: Arguments): Array[AnyRef] = {
    if (args.count == 1 && args.isString(0)) {
      beep(args.checkString(0))
    } else {
      val frequency = args.optInteger(0, 440)
      if (frequency < 20 || frequency > 2000) {
        throw new IllegalArgumentException("invalid frequency, must be in [20, 2000]")
      }
      val duration = args.optDouble(1, 0.1)
      val durationInMilliseconds = math.max(50, math.min(5000, (duration * 1000).toInt))
      beep(frequency.toShort, durationInMilliseconds.toShort)
      context.pause(durationInMilliseconds / 1000.0)
    }
    null
  }

  @Callback(direct = true, doc = """function():table -- Collect information on all connected devices.""")
  def getDeviceInfo(context: Context, args: Arguments): Array[AnyRef] = {
    context.pause(1) // Iterating all nodes is potentially expensive, and I see no practical reason for having to call this frequently.
    Array[AnyRef](node.network.nodes.map(n => (n, n.host)).collect {
      case (n: Component, deviceInfo: DeviceInfo) =>
        if (n.canBeSeenFrom(node) || n == node) {
          Option(deviceInfo.getDeviceInfo) match {
            case Some(info) => Option(n.address -> info)
            case _ => None
          }
        }
        else None
      case (n, deviceInfo: DeviceInfo) =>
        if (n.canBeReachedFrom(node)) {
          Option(deviceInfo.getDeviceInfo) match {
            case Some(info) => Option(n.address -> info)
            case _ => None
          }
        }
        else None
    }.collect { case Some(kvp) => kvp }.toMap)
  }

  @Callback(doc = """function():table -- Returns a map of program name to disk label for known programs.""")
  def getProgramLocations(context: Context, args: Arguments): Array[AnyRef] =
    result(ProgramLocations.getMappings(MachineAPI.getArchitectureName(architecture.getClass)))

  // ----------------------------------------------------------------------- //

  def isExecuting: Boolean = state.synchronized(state.contains(MachineAPI.State.Running))

  override val needUpdate = true

  override def update(): Unit = if (state.synchronized(state.top != MachineAPI.State.Stopped)) {
    // Add components that were added since the last update to the actual list
    // of components if we can see them. We use this delayed approach to avoid
    // issues with components that have a visibility lower than their
    // reachability, because in that case if they get connected in the wrong
    // order we wouldn't add them (since they'd be invisible in their connect
    // message, and only become visible with a later node-to-node connection,
    // but that wouldn't trigger a connect message anymore due to the higher
    // reachability).
    processAddedComponents()

    // Component overflow check, crash if too many components are connected, to
    // avoid confusion on the user's side due to components not showing up.
    if (componentCount > maxComponents) {
      beep("-..")
      crash("Error.ComponentOverflow")
    }

    // Update world time for time() and uptime().
    worldTime = host.workspace.getIngameTime
    uptime += 1

    if (remainIdle > 0) {
      remainIdle -= 1
    }

    // Reset direct call budget.
    callBudget = maxCallBudget

    // Check if we should switch states. These are all the states in which we're
    // guaranteed that the executor thread isn't running anymore.
    state.synchronized(state.top) match {
      // Booting up.
      case MachineAPI.State.Starting =>
        verifyComponents()
        switchTo(MachineAPI.State.Yielded)
      // Computer is rebooting.
      case MachineAPI.State.Restarting =>
        close()
        if (Settings.get.eraseTmpOnReboot) {
          tmp.foreach(_.node.remove()) // To force deleting contents.
          tmp.foreach(tmp => node.connect(tmp.node))
        }
        node.sendToReachable("computer.stopped")
        start()
      // Resume from pauses based on sleep or signal underflow.
      case MachineAPI.State.Sleeping if remainIdle <= 0 || signals.nonEmpty =>
        switchTo(MachineAPI.State.Yielded)
      // Resume in case we paused  because the game was paused.
      case MachineAPI.State.Paused =>
        if (remainingPause > 0) {
          remainingPause -= 1
        }
        else {
          verifyComponents() // In case we're resuming after loading.
          state.pop()
          switchTo(state.top) // Trigger execution if necessary.
        }
      // Perform a synchronized call (message sending).
      case MachineAPI.State.SynchronizedCall =>
        // We switch into running state, since we'll behave as though the call
        // were performed from our executor thread.
        switchTo(MachineAPI.State.Running)
        try {
          inSynchronizedCall = true
          architecture.runSynchronized()
          inSynchronizedCall = false
          // Check if the callback called pause() or stop().
          state.top match {
            case MachineAPI.State.Running =>
              switchTo(MachineAPI.State.SynchronizedReturn)
            case MachineAPI.State.Paused =>
              state.pop() // Paused
              state.pop() // Running, no switchTo to avoid new future.
              state.push(MachineAPI.State.SynchronizedReturn)
              state.push(MachineAPI.State.Paused)
            case MachineAPI.State.Stopping =>
              state.clear()
              state.push(MachineAPI.State.Stopping)
            case _ => throw new AssertionError()
          }
        }
        catch {
          case e: java.lang.Error if e.getMessage == "not enough memory" =>
            crash("Error.OutOfMemory")
          case e: Throwable =>
            Ocelot.log.warn("Faulty architecture implementation for synchronized calls.", e)
            crash("Error.InternalError")
        }
        finally {
          inSynchronizedCall = false
        }
      case _ => // Nothing special to do, just avoid match errors.
    }

    // Finally check if we should stop the computer. We cannot lock the state
    // because we may have to wait for the executor thread to finish, which
    // might turn into a deadlock depending on where it currently is.
    state.synchronized(state.top) match {
      // Computer is shutting down.
      case MachineAPI.State.Stopping => Machine.this.synchronized(state.synchronized(tryClose()))
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  override def onMessage(message: Message) {
    message.data match {
      case Array(name: String, args@_*) if message.name == "computer.signal" =>
        signal(name, Seq(message.source.address) ++ args: _*)
      case Array(player: User, name: String, args@_*) if message.name == "computer.checked_signal" =>
        if (canInteract(player.nickname))
          signal(name, Seq(message.source.address) ++ args: _*)
      case _ =>
        if (message.name == "computer.start" && !isPaused) start()
        else if (message.name == "computer.stop") stop()
    }
  }

  override def onConnect(node: Node) {
    if (node == this.node) {
      _components += this.node.address -> this.node.name
      tmp.foreach(fs => node.connect(fs.node))
      Option(architecture).foreach(_.onConnect())
    }
    else {
      node match {
        case component: Component => addComponent(component)
        case _ =>
      }
    }
    // For computers, to generate the components in their inventory.
    host.onMachineConnect(node)
  }

  override def onDisconnect(node: Node) {
    if (node == this.node) {
      close()
      tmp.foreach(_.node.remove())
    }
    else {
      node match {
        case component: Component => removeComponent(component)
        case _ =>
      }
    }
    // For computers, to save the components in their inventory.
    host.onMachineDisconnect(node)
  }

  // ----------------------------------------------------------------------- //

  def addComponent(component: Component) {
    if (!_components.contains(component.address)) {
      addedComponents += component
    }
  }

  def removeComponent(component: Component) {
    if (_components.contains(component.address)) {
      _components.synchronized(_components -= component.address)
      signal("component_removed", component.address, component.name)
    }
    addedComponents -= component
  }

  private def processAddedComponents() {
    if (addedComponents.nonEmpty) {
      for (component <- addedComponents) {
        if (component.canBeSeenFrom(node)) {
          _components.synchronized(_components += component.address -> component.name)
          // Skip the signal if we're not initialized yet, since we'd generate a
          // duplicate in the startup script otherwise.
          if (architecture != null && architecture.isInitialized) {
            signal("component_added", component.address, component.name)
          }
        }
      }
      addedComponents.clear()
    }
  }

  private def verifyComponents() {
    val invalid = mutable.Set.empty[String]
    for ((address, name) <- _components) {
      node.network.node(address) match {
        case component: Component if component.name == name => // All is well.
        case _ =>
          if (name == "filesystem") {
            Ocelot.log.trace(s"A component of type '$name' disappeared ($address)! This usually means that it didn't save its node.")
            Ocelot.log.trace("If this was a file system provided by a ComputerCraft peripheral, this is normal.")
          }
          else Ocelot.log.warn(s"A component of type '$name' disappeared ($address)! This usually means that it didn't save its node.")
          signal("component_removed", address, name)
          invalid += address
      }
    }
    for (address <- invalid) {
      _components -= address
    }
  }

  // ----------------------------------------------------------------------- //

  private final val StateTag = "state"
  private final val UsersTag = "users"
  private final val MessageTag = "message"
  private final val ComponentsTag = "components"
  private final val AddressTag = "address"
  private final val NameTag = "name"
  private final val TmpTag = "tmp"
  private final val SignalsTag = "signals"
  private final val ArgsTag = "args"
  private final val LengthTag = "length"
  private final val ArgPrefixTag = "arg"
  private final val UptimeTag = "uptime"
  private final val CPUTimeTag = "cpuTime"
  private final val RemainingPauseTag = "remainingPause"

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = Machine.this.synchronized(state.synchronized {
    assert(state.top == MachineAPI.State.Stopped || state.top == MachineAPI.State.Paused)
    close()
    state.clear()

    super.load(nbt, workspace)

    state.pushAll(nbt.getIntArray(StateTag).reverseMap(MachineAPI.State(_)))
    nbt.getTagList(UsersTag, NBT.TAG_STRING).foreach((tag: NBTTagString) => _users += tag.getString)
    if (nbt.hasKey(MessageTag)) {
      message = Some(nbt.getString(MessageTag))
    }

    _components ++= nbt.getTagList(ComponentsTag, NBT.TAG_COMPOUND).map((tag: NBTTagCompound) =>
      tag.getString(AddressTag) -> tag.getString(NameTag))

    tmp.foreach(fs => {
      if (nbt.hasKey(TmpTag)) fs.load(nbt.getCompoundTag(TmpTag), workspace)
    })

    if (state.nonEmpty && isRunning && init()) try {
      architecture.load(nbt)

      signals ++= nbt.getTagList(SignalsTag, NBT.TAG_COMPOUND).map((signalNbt: NBTTagCompound) => {
        val argsNbt = signalNbt.getCompoundTag(ArgsTag)
        val argsLength = argsNbt.getInteger(LengthTag)
        new MachineAPI.Signal(signalNbt.getString(NameTag),
          (0 until argsLength).map(ArgPrefixTag + _).map(argsNbt.getTag).map {
            case tag: NBTTagByte if tag.getByte == -1 => null
            case tag: NBTTagByte => Boolean.box(tag.getByte == 1)
            case tag: NBTTagLong => Long.box(tag.getLong)
            case tag: NBTTagDouble => Double.box(tag.getDouble)
            case tag: NBTTagString => tag.getString
            case tag: NBTTagByteArray => tag.getByteArray
            case tag: NBTTagList =>
              val data = mutable.Map.empty[String, String]
              for (i <- 0 until tag.tagCount by 2) {
                data += tag.getStringTagAt(i) -> tag.getStringTagAt(i + 1)
              }
              data
            case tag: NBTTagCompound => tag
            case _ => null
          }.toArray[AnyRef])
      })

      uptime = nbt.getLong(UptimeTag)
      cpuTotal = nbt.getLong(CPUTimeTag)
      remainingPause = nbt.getInteger(RemainingPauseTag)

      // Delay execution for a second to allow the world around us to settle.
      if (state.top != MachineAPI.State.Restarting) {
        pause(Settings.get.startupDelay)
      }
    }
    catch {
      case t: Throwable =>
        Ocelot.log.error(
          s"""Unexpected error loading a state of computer. State: ${state.headOption.fold("no state")(_.toString)}.""", t)
        close()
    }
    else {
      // Clean up in case we got a weird state stack.
      onHostChanged()
      close()
    }
  })

  override def save(nbt: NBTTagCompound): Unit = Machine.this.synchronized(state.synchronized {
    // The lock on 'this' should guarantee that this never happens regularly.
    // If something other than regular saving tries to save while we are executing code,
    // e.g. SpongeForge saving during robot.move due to block changes being captured,
    // just don't save this at all. What could possibly go wrong?
    if (isExecuting) return

    // Make sure we don't continue running until everything has saved.
    pause(0.05)

    super.save(nbt)

    // Make sure the component list is up-to-date.
    processAddedComponents()

    nbt.setIntArray(StateTag, state.map(_.id).toArray)
    nbt.setNewTagList(UsersTag, _users)
    message.foreach(nbt.setString(MessageTag, _))

    val componentsNbt = new NBTTagList()
    for ((address, name) <- _components) {
      val componentNbt = new NBTTagCompound()
      componentNbt.setString(AddressTag, address)
      componentNbt.setString(NameTag, name)
      componentsNbt.appendTag(componentNbt)
    }
    nbt.setTag(ComponentsTag, componentsNbt)

    val tmpNbt = new NBTTagCompound
    tmp.foreach(fs => fs.save(tmpNbt))
    nbt.setTag(TmpTag, tmpNbt)

    if (state.top != MachineAPI.State.Stopped) try {
      architecture.save(nbt)

      val signalsNbt = new NBTTagList()
      for (s <- signals.iterator) {
        val signalNbt = new NBTTagCompound()
        signalNbt.setString(NameTag, s.name)
        signalNbt.setNewCompoundTag(ArgsTag, args => {
          args.setInteger(LengthTag, s.args.length)
          s.args.zipWithIndex.foreach {
            case (null, i) => args.setByte(ArgPrefixTag + i, -1)
            case (arg: java.lang.Boolean, i) => args.setByte(ArgPrefixTag + i, if (arg) 1 else 0)
            case (arg: java.lang.Long, i) => args.setLong(ArgPrefixTag + i, arg)
            case (arg: java.lang.Double, i) => args.setDouble(ArgPrefixTag + i, arg)
            case (arg: String, i) => args.setString(ArgPrefixTag + i, arg)
            case (arg: Array[Byte], i) => args.setByteArray(ArgPrefixTag + i, arg)
            case (arg: Map[_, _], i) =>
              val list = new NBTTagList()
              for ((key, value) <- arg) {
                list.append(key.toString)
                list.append(value.toString)
              }
              args.setTag(ArgPrefixTag + i, list)
            case (arg: NBTTagCompound, i) => args.setTag(ArgPrefixTag + i, arg)
            case (_, i) => args.setByte(ArgPrefixTag + i, -1)
          }
        })
        signalsNbt.appendTag(signalNbt)
      }
      nbt.setTag(SignalsTag, signalsNbt)

      nbt.setLong(UptimeTag, uptime)
      nbt.setLong(CPUTimeTag, cpuTotal)
      nbt.setInteger(RemainingPauseTag, remainingPause)
    }
    catch {
      case t: Throwable =>
        Ocelot.log.error(
          s"""Unexpected error saving a state of computer. State: ${state.headOption.fold("no state")(_.toString)}. """, t)
    }
  })

  // ----------------------------------------------------------------------- //

  private def init(): Boolean = {
    onHostChanged()
    if (architecture == null) return false

    // Reset error state.
    message = None

    // Clear any left-over signals from a previous run.
    signals.clear()

    // Connect the `/tmp` node to our owner. We're not in a network in
    // case we're loading, which is why we have to check it here.
    if (node.network != null) {
      tmp.foreach(fs => node.connect(fs.node))
    }

    try {
      return architecture.initialize()
    }
    catch {
      case ex: Throwable =>
        Ocelot.log.warn("Failed initializing computer.", ex)
        close()
    }
    false
  }

  def tryClose(): Boolean =
    if (isExecuting) false
    else {
      close()
      tmp.foreach(_.node.remove()) // To force deleting contents.
      if (node.network != null) {
        tmp.foreach(tmp => node.connect(tmp.node))
      }
      node.sendToReachable("computer.stopped")
      true
    }

  private def close(): Unit =
    if (state.synchronized(state.isEmpty || state.top != MachineAPI.State.Stopped)) {
      // Give up the state lock, then get the more generic lock on this instance first
      // before locking on state again. Always must be in that order to avoid deadlocks.
      this.synchronized(state.synchronized {
        state.clear()
        state.push(MachineAPI.State.Stopped)
        Option(architecture).foreach(_.close())
        signals.clear()
        uptime = 0
        cpuTotal = 0
        cpuStart = 0
        remainIdle = 0
      })
    }

  // ----------------------------------------------------------------------- //

  private def switchTo(value: MachineAPI.State.Value) = state.synchronized {
    val result = state.pop()
    if (value == MachineAPI.State.Stopping || value == MachineAPI.State.Restarting) {
      state.clear()
    }
    state.push(value)
    if (value == MachineAPI.State.Yielded || value == MachineAPI.State.SynchronizedReturn) {
      remainIdle = 0
      MachineAPI.threadPool.schedule(this, Settings.get.executionDelay, TimeUnit.MILLISECONDS)
    }
    result
  }

  private def isGamePaused = false

  // This is a really high level lock that we only use for saving and loading.
  override def run(): Unit = Machine.this.synchronized {
    val isSynchronizedReturn = state.synchronized {
      if (state.top != MachineAPI.State.Yielded &&
        state.top != MachineAPI.State.SynchronizedReturn) {
        return
      }
      // See if the game appears to be paused, in which case we also pause.
      if (isGamePaused) {
        state.push(MachineAPI.State.Paused)
        return
      }
      switchTo(MachineAPI.State.Running) == MachineAPI.State.SynchronizedReturn
    }

    cpuStart = System.nanoTime()
    _latestInfo.getAndUpdate(info => info.copy(executionStart = cpuStart))

    try {
      val result = architecture.runThreaded(isSynchronizedReturn)

      // Check if someone called pause() or stop() in the meantime.
      state.synchronized {
        state.top match {
          case MachineAPI.State.Running =>
            result match {
              case result: ExecutionResult.Sleep =>
                signals.synchronized {
                  // Immediately check for signals to allow processing more than one
                  // signal per game tick.
                  if (signals.isEmpty && result.ticks > 0) {
                    switchTo(MachineAPI.State.Sleeping)
                    remainIdle = result.ticks
                  } else {
                    switchTo(MachineAPI.State.Yielded)
                  }
                }
              case _: ExecutionResult.SynchronizedCall =>
                switchTo(MachineAPI.State.SynchronizedCall)
              case result: ExecutionResult.Shutdown =>
                if (result.reboot) {
                  switchTo(MachineAPI.State.Restarting)
                }
                else {
                  switchTo(MachineAPI.State.Stopping)
                }
              case result: ExecutionResult.Error =>
                beep("--")
                crash(Option(result.message).getOrElse("unknown error"))
            }
          case MachineAPI.State.Paused =>
            state.pop() // Paused
            state.pop() // Running, no switchTo to avoid new future.
            result match {
              case result: ExecutionResult.Sleep =>
                remainIdle = result.ticks
                state.push(MachineAPI.State.Sleeping)
              case _: ExecutionResult.SynchronizedCall =>
                state.push(MachineAPI.State.SynchronizedCall)
              case result: ExecutionResult.Shutdown =>
                if (result.reboot) {
                  state.push(MachineAPI.State.Restarting)
                }
                else {
                  state.push(MachineAPI.State.Stopping)
                }
              case result: ExecutionResult.Error =>
                crash(Option(result.message).getOrElse("unknown error"))
            }
            state.push(MachineAPI.State.Paused)
          case MachineAPI.State.Stopping =>
            state.clear()
            state.push(MachineAPI.State.Stopping)
          case MachineAPI.State.Restarting =>
          // Nothing to do!
          case _ => throw new AssertionError("Invalid state in executor post-processing.")
        }
        assert(!isExecuting)
      }
    }
    catch {
      case e: Throwable =>
        Ocelot.log.warn("Architecture's runThreaded threw an error. This should never happen!", e)
        crash("Error.InternalError")
    }

    _latestInfo.getAndUpdate(info => info.copy(
      freeMemory = architecture.freeMemory,
      totalMemory = architecture.totalMemory,
      executionEnd = System.nanoTime(),
      callBudget = callBudget,
      maxCallBudget = maxCallBudget
    ))

    // Keep track of time spent executing the computer.
    cpuTotal += System.nanoTime() - cpuStart
  }
}
