package totoro.ocelot.brain.entity.machine.luac

import java.io.{FileNotFoundException, IOException}
import com.google.common.base.Strings
import li.cil.repack.com.naef.jnlua._
import totoro.ocelot.brain.entity.machine.ExtendedLuaState.extendLuaState
import totoro.ocelot.brain.entity.machine._
import totoro.ocelot.brain.entity.machine.debug.{Debuggable, StackFrame}
import totoro.ocelot.brain.entity.traits.{Entity, Memory}
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.{Ocelot, Settings}

abstract class StackFrameLuaWhat

object StackFrameLuaWhat {
  case class Lua() extends StackFrameLuaWhat
  case class C() extends StackFrameLuaWhat
  case class Main() extends StackFrameLuaWhat
}

abstract class StackFrameLuaSource

object StackFrameLuaSource {
  case class File(name: scala.Predef.String) extends StackFrameLuaSource
  case class String(code: List[Byte]) extends StackFrameLuaSource
  case class Other(source: scala.Predef.String) extends StackFrameLuaSource
}

case class StackFrameLua(index: Int,
                         name: Option[String],
                         nameWhat: Option[String],
                         what: StackFrameLuaWhat,
                         source: StackFrameLuaSource,
                         currentLine: Option[Long],
                         lines: Option[(Long, Long)],
                         upvalueCount: Int,
                         paramCount: Int,
                         vararg: Boolean,
                         tailCall: Boolean,
                         shortSource: String) extends StackFrame

class NativeLua52Architecture(machine: Machine) extends NativeLuaArchitecture(machine) {
  override def factory: LuaStateFactory.Lua52.type = LuaStateFactory.Lua52

  override def getRegistryIndex: Int = LuaState.lua_registryindex()

  override def transformNumber(index: Int): LuaValue.Number = {
    assert(lua.isNumber(index))
    LuaValue.Number(LuaNumber.Double(lua.toNumber(index)))
  }
}

class NativeLua53Architecture(machine: Machine) extends NativeLuaArchitecture(machine) {
  override def factory: LuaStateFactory.Lua53.type = LuaStateFactory.Lua53

  override def getRegistryIndex: Int = LuaStateFiveThree.lua_registryindex()

  override def transformNumber(index: Int): LuaValue.Number = {
    val absIndex = lua.absIndex(index)
    assert(lua.isNumber(absIndex))

    lua.getGlobal("math") // stack: math
    lua.getField(-1, "type") // stack: math.type math
    lua.pushValue(absIndex) // stack: <number> math.type math
    lua.call(1, 1) // stack: <number type> math
    val numberType = transformString(-1).toUtf8String
    lua.pop(2) // stack restored

    LuaValue.Number(numberType match {
      case "integer" =>
        lua.pushValue(absIndex) // stack: <integer>
        lua.pushValue(-1) // stack: <integer> <integer>
        lua.pushNumber(0x1_0000_0000L) // stack: 0x1_0000_0000 <integer> <integer>
        lua.arith(LuaState.ArithOperator.MOD) // stack: <integer[31:0]> <integer>
        val numberLow = lua.toNumber(-1).toLong

        lua.arith(LuaState.ArithOperator.SUB) // stack: <integer - integer[31:0]>
        lua.pushNumber(0x1_0000_0000L) // stack: 0x1_0000_0000 <integer - integer[31:0]>
        lua.arith(LuaState.ArithOperator.DIV) // stack: <integer[63:32]>
        val numberHigh = lua.toNumber(-1).toLong
        lua.pop(1) // stack restored

        LuaNumber.Integer((numberHigh << 32) | numberLow)

      case "float" => LuaNumber.Double(lua.toNumber(index))
    })
  }
}

abstract class NativeLuaArchitecture(val machine: Machine) extends Architecture with Debuggable {
  protected def factory: LuaStateFactory

  private[machine] var lua: LuaState = _

  private[machine] var kernelMemory = 0

  private[machine] val ramScale = if (factory.is64Bit) Settings.get.ramScaleFor64Bit else 1.0

  private val persistence = new PersistenceAPI(this)

  private val apis = Array(
    new ComponentAPI(this),
    new ComputerAPI(this),
    new OSAPI(this),
    new SystemAPI(this),
    new UnicodeAPI(this),
    new UserdataAPI(this),
    // Persistence has to go last to ensure all other APIs can go into the permanent value table.
    persistence)

  private[machine] def invoke(f: () => Array[AnyRef]): Int = try {
    f() match {
      case results: Array[_] =>
        lua.pushBoolean(true)
        results.foreach(result => lua.pushValue(result))
        1 + results.length
      case _ =>
        lua.pushBoolean(true)
        1
    }
  }
  catch {
    case e: Throwable =>
      if (Settings.get.logLuaCallbackErrors && !e.isInstanceOf[LimitReachedException]) {
        Ocelot.log.warn("Exception in Lua callback.", e)
      }
      e match {
        case _: LimitReachedException =>
          0
        case e: IllegalArgumentException if e.getMessage != null =>
          lua.pushBoolean(false)
          lua.pushString(e.getMessage)
          2
        case e: Throwable if e.getMessage != null =>
          lua.pushBoolean(true)
          lua.pushNil()
          lua.pushString(e.getMessage)
          if (Settings.get.logLuaCallbackErrors) {
            lua.pushString(e.getStackTrace.mkString("", "\n", "\n"))
            4
          }
          else 3
        case _: IndexOutOfBoundsException =>
          lua.pushBoolean(false)
          lua.pushString("index out of bounds")
          2
        case _: IllegalArgumentException =>
          lua.pushBoolean(false)
          lua.pushString("bad argument")
          2
        case _: NoSuchMethodException =>
          lua.pushBoolean(false)
          lua.pushString("no such method")
          2
        case _: FileNotFoundException =>
          lua.pushBoolean(true)
          lua.pushNil()
          lua.pushString("file not found")
          3
        case _: SecurityException =>
          lua.pushBoolean(true)
          lua.pushNil()
          lua.pushString("access denied")
          3
        case _: IOException =>
          lua.pushBoolean(true)
          lua.pushNil()
          lua.pushString("i/o error")
          3
        case _: UnsupportedOperationException =>
          lua.pushBoolean(false)
          lua.pushString("unsupported operation")
          2
        case e: Throwable =>
          Ocelot.log.warn("Unexpected error in Lua callback.", e)
          lua.pushBoolean(true)
          lua.pushNil()
          lua.pushString("unknown error")
          3
      }
  }

  private[machine] def documentation(f: () => String): Int = try {
    val doc = f()
    if (Strings.isNullOrEmpty(doc)) lua.pushNil()
    else lua.pushString(doc)
    1
  }
  catch {
    case _: NoSuchMethodException =>
      lua.pushNil()
      lua.pushString("no such method")
      2
    case t: Throwable =>
      lua.pushNil()
      lua.pushString(if (t.getMessage != null) t.getMessage else t.toString)
      2
  }

  // ----------------------------------------------------------------------- //

  override def isInitialized: Boolean = kernelMemory > 0

  override def recomputeMemory(components: Iterable[Entity]): Boolean = {
    val memory = math.ceil(memoryInBytes(components) * ramScale).toInt
    Option(lua) match {
      case Some(l) if Settings.get.limitMemory =>
        l.setTotalMemory(Int.MaxValue)
        if (kernelMemory > 0) {
          l.setTotalMemory(kernelMemory + memory)
        }
      case _ =>
    }
    memory > 0
  }

  private def memoryInBytes(components: Iterable[Entity]) =
    components.foldLeft(0.0)((acc, entity) => acc + (entity match {
      case memory: Memory => memory.amount * 1024
      case _ => 0
    })).toInt max 0 min Settings.get.maxTotalRam

  // ----------------------------------------------------------------------- //

  override def runSynchronized() {
    // These three asserts are all guaranteed by run().
    assert(lua.getTop == 2)
    assert(lua.isThread(1))
    assert(lua.isFunction(2))

    try {
      // Synchronized call protocol requires the called function to return
      // a table, which holds the results of the call, to be passed back
      // to the coroutine.yield() that triggered the call.
      lua.call(0, 1)
      lua.checkType(2, LuaType.TABLE)
    }
    catch {
      case _: LuaMemoryAllocationException =>
        // This can happen if we run out of memory while converting a Java
        // exception to a string (which we have to do to avoid keeping
        // userdata on the stack, which cannot be persisted).
        throw new java.lang.OutOfMemoryError("not enough memory")
    }
  }

  override def runThreaded(isSynchronizedReturn: Boolean): ExecutionResult = {
    try {
      // The kernel thread will always be at stack index one.
      assert(lua.isThread(1))

      // Resume the Lua state and remember the number of results we get.
      val results = if (isSynchronizedReturn) {
        // If we were doing a synchronized call, continue where we left off.
        assert(lua.getTop == 2)
        assert(lua.isTable(2))
        lua.resume(1, 1)
      }
      else {
        if (kernelMemory == 0) {
          // We're doing the initialization run.
          if (lua.resume(1, 0) > 0) {
            // We expect to get nothing here, if we do we had an error.
            0
          }
          else {
            // Run the garbage collector to get rid of stuff left behind after
            // the initialization phase to get a good estimate of the base
            // memory usage the kernel has (including libraries). We remember
            // that size to grant user-space programs a fixed base amount of
            // memory, regardless of the memory need of the underlying system
            // (which may change across releases).
            lua.gc(LuaState.GcAction.COLLECT, 0)
            kernelMemory = math.max(lua.getTotalMemory - lua.getFreeMemory, 1)
            recomputeMemory(machine.host.inventory)

            // Fake zero sleep to avoid stopping if there are no signals.
            lua.pushInteger(0)
            1
          }
        }
        else machine.popSignal() match {
          case signal if signal != null =>
            lua.pushString(signal.name)
            signal.args.foreach(arg => lua.pushValue(arg))
            lua.resume(1, 1 + signal.args.length)
          case _ =>
            lua.resume(1, 0)
        }
      }

      // Check if the kernel is still alive.
      if (lua.status(1) == LuaState.YIELD) {
        // If we get one function it must be a wrapper for a synchronized
        // call. The protocol is that a closure is pushed that is then called
        // from the main server thread, and returns a table, which is in turn
        // passed to the originating coroutine.yield().
        if (results == 1 && lua.isFunction(2)) {
          new ExecutionResult.SynchronizedCall()
        }
        // Check if we are shutting down, and if so if we're rebooting. This
        // is signalled by boolean values, where `false` means shut down,
        // `true` means reboot (i.e shutdown then start again).
        else if (results == 1 && lua.isBoolean(2)) {
          new ExecutionResult.Shutdown(lua.toBoolean(2))
        }
        else {
          // If we have a single number, that's how long we may wait before
          // resuming the state again. Note that the sleep may be interrupted
          // early if a signal arrives in the meantime. If we have something
          // else we just process the next signal or wait for one.
          val ticks = if (results == 1 && lua.isNumber(2)) (lua.toNumber(2) * 20).toInt else Int.MaxValue
          lua.pop(results)
          new ExecutionResult.Sleep(ticks)
        }
      }
      // The kernel thread returned. If it threw we'd be in the catch below.
      else {
        assert(lua.isThread(1))
        // We're expecting the result of a pcall, if anything, so boolean + (result | string).
        if (!lua.isBoolean(2) || !(lua.isString(3) || lua.isNoneOrNil(3))) {
          Ocelot.log.warn("Kernel returned unexpected results.")
        }
        // The pcall *should* never return normally... but check for it nonetheless.
        if (lua.toBoolean(2)) {
          Ocelot.log.warn("Kernel stopped unexpectedly.")
          new ExecutionResult.Shutdown(false)
        }
        else {
          if (Settings.get.limitMemory) {
            lua.setTotalMemory(Int.MaxValue)
          }
          val error =
            if (lua.isJavaObjectRaw(3)) lua.toJavaObjectRaw(3).toString
            else lua.toString(3)
          if (error != null) new ExecutionResult.Error(error)
          else new ExecutionResult.Error("unknown error")
        }
      }
    }
    catch {
      case e: LuaRuntimeException =>
        Ocelot.log.warn("Kernel crashed. This is a bug!\n" + e.toString + "\tat " + e.getLuaStackTrace.mkString("\n\tat "))
        new ExecutionResult.Error("kernel panic: this is a bug, check your log file and report it")
      case e: LuaGcMetamethodException =>
        if (e.getMessage != null) new ExecutionResult.Error("kernel panic:\n" + e.getMessage)
        else new ExecutionResult.Error("kernel panic:\nerror in garbage collection metamethod")
      case _: LuaMemoryAllocationException =>
        new ExecutionResult.Error("not enough memory")
      case e: java.lang.Error if e.getMessage == "not enough memory" =>
        new ExecutionResult.Error("not enough memory")
    }
  }

  override def onSignal(): Unit = {}

  // ----------------------------------------------------------------------- //

  override def initialize(): Boolean = {
    // Creates a new state with all base libraries and the persistence library
    // loaded into it. This means the state has much more power than it
    // rightfully should have, so we sandbox it a bit in the following.
    factory.createState() match {
      case None =>
        lua = null
        machine.crash("native libraries not available")
        return false
      case Some(value) => lua = value
    }

    apis.foreach(_.initialize())

    lua.load(classOf[Machine].getResourceAsStream(Settings.scriptPath + "machine.lua"), "=machine", "t")
    lua.newThread() // Left as the first value on the stack.

    true
  }

  override def onConnect() {
  }

  override def close() {
    if (lua != null) {
      if (Settings.get.limitMemory) {
        lua.setTotalMemory(Integer.MAX_VALUE)
      }
      lua.close()
    }
    lua = null
    kernelMemory = 0
  }

  // ----------------------------------------------------------------------- //

  // Transition to storing the 'are we in or returning from a sync call' in here
  // so we don't need to check the state. Will need a period where saves are
  // loaded using the old *and* new method and saved using the new.
  @Deprecated
  private def state = machine.asInstanceOf[Machine].state

  override def load(nbt: NBTTagCompound) {
    if (!machine.isRunning) return

    // Unlimit memory use while unpersisting.
    if (Settings.get.limitMemory) {
      lua.setTotalMemory(Integer.MAX_VALUE)
    }

    try {
      // Try unpersisting Lua, because that's what all of the rest depends
      // on. First, clear the stack, meaning the current kernel.
      lua.setTop(0)

      //persistence.unpersist(SaveHandler.load(nbt, machine.node.address + "_kernel"))
      persistence.unpersist(nbt.getByteArray(machine.node.address + "_kernel"))

      if (!lua.isThread(1)) {
        // This shouldn't really happen, but there's a chance it does if
        // the save was corrupt (maybe someone modified the Lua files).
        throw new LuaRuntimeException("Invalid kernel.")
      }
      if (state.contains(MachineAPI.State.SynchronizedCall) || state.contains(MachineAPI.State.SynchronizedReturn)) {
        //persistence.unpersist(SaveHandler.load(nbt, machine.node.address + "_stack"))
        persistence.unpersist(nbt.getByteArray(machine.node.address + "_stack"))
        if (!(if (state.contains(MachineAPI.State.SynchronizedCall)) lua.isFunction(2) else lua.isTable(2))) {
          // Same as with the above, should not really happen normally, but
          // could for the same reasons.
          throw new LuaRuntimeException("Invalid stack.")
        }
      }

      kernelMemory = (nbt.getInteger("kernelMemory") * ramScale).toInt

      for (api <- apis) {
        api.load(nbt)
      }

      try lua.gc(LuaState.GcAction.COLLECT, 0) catch {
        case _: Throwable =>
          Ocelot.log.warn(s"Error cleaning up loaded computer. This either means the server is badly overloaded or a user created an evil __gc method, accidentally or not.")
          machine.crash("error in garbage collector, most likely __gc method timed out")
      }
    } catch {
      case e: LuaRuntimeException => throw new Exception(e.toString + (if (e.getLuaStackTrace.isEmpty) "" else "\tat " + e.getLuaStackTrace.mkString("\n\tat ")), e)
    }

    // Limit memory again.
    recomputeMemory(machine.host.inventory)
  }

  override def save(nbt: NBTTagCompound) {
    // Unlimit memory while persisting.
    if (Settings.get.limitMemory) {
      lua.setTotalMemory(Integer.MAX_VALUE)
    }

    try {
      // Try persisting Lua, because that's what all of the rest depends on.
      // Save the kernel state (which is always at stack index one).
      assert(lua.isThread(1))

      //SaveHandler.scheduleSave(machine.host, nbt, machine.node.address + "_kernel", persistence.persist(1))
      nbt.setByteArray(machine.node.address + "_kernel", persistence.persist(1))

      // While in a driver call we have one object on the global stack: either
      // the function to call the driver with, or the result of the call.
      if (state.contains(MachineAPI.State.SynchronizedCall) || state.contains(MachineAPI.State.SynchronizedReturn)) {
        assert(if (state.contains(MachineAPI.State.SynchronizedCall)) lua.isFunction(2) else lua.isTable(2))

        //SaveHandler.scheduleSave(machine.host, nbt, machine.node.address + "_stack", persistence.persist(2))
        nbt.setByteArray(machine.node.address + "_stack", persistence.persist(2))
      }

      nbt.setInteger("kernelMemory", math.ceil(kernelMemory / ramScale).toInt)

      for (api <- apis) {
        api.save(nbt)
      }

      try lua.gc(LuaState.GcAction.COLLECT, 0) catch {
        case _: Throwable =>
          Ocelot.log.warn(s"Error cleaning up loaded computer. This either means the server is badly overloaded or a user created an evil __gc method, accidentally or not.")
          machine.crash("error in garbage collector, most likely __gc method timed out")
      }
    } catch {
      case e: LuaRuntimeException =>
        Ocelot.log.warn(s"Could not persist computer.\n${e.toString}" + (if (e.getLuaStackTrace.isEmpty) "" else "\tat " + e.getLuaStackTrace.mkString("\n\tat ")))
        nbt.removeTag("state")
      case e: LuaGcMetamethodException =>
        Ocelot.log.warn(s"Could not persist computer.\n${e.toString}")
        nbt.removeTag("state")
    }

    // Limit memory again.
    recomputeMemory(machine.host.inventory)
  }

  // ----------------------------------------------------------------------- //
  // debug interface follows from now on -- an Ocelot extension

  protected def getRegistryIndex: Int

  private def pushGlobalEnvironment(): Unit = {
    lua.pushInteger(LuaState.RIDX_GLOBALS)
    lua.getTable(getRegistryIndex)
  }

  private def pushMainThread(): Unit = {
    lua.pushInteger(LuaState.RIDX_MAINTHREAD)
    lua.getTable(getRegistryIndex)
  }

  private def callDebugFunction(funcName: String, paramCount: Int, resultCount: Int): Unit = {
    pushGlobalEnvironment() // stack: <env> params...
    lua.getField(-1, "debug") // stack: debug <env> params...
    lua.remove(-2) // stack: debug params...
    lua.getField(-1, funcName) // stack: debug[funcName] debug params...
    assert(lua.`type`(-1) == LuaType.FUNCTION, "attempted to call something that was not a function")
    lua.remove(-2) // stack: debug[funcName] params...
    lua.insert(-paramCount - 1) // stack: params... debug[funcName]
    lua.call(paramCount, resultCount) // stack: results...
  }

  protected def transformNil(index: Int): LuaValue.Nil = {
    assert(lua.isNil(index))

    LuaValue.Nil()
  }

  protected def transformBoolean(index: Int): LuaValue.Boolean = {
    assert(lua.isBoolean(index))

    LuaValue.Boolean(lua.toBoolean(index))
  }

  protected def transformNumber(index: Int): LuaValue.Number

  protected def transformString(index: Int): LuaValue.String = {
    assert(lua.isString(index))

    LuaValue.String(lua.toByteArray(index).toList)
  }

  protected def transformFunction(index: Int): LuaValue.Function = {
    assert(lua.isFunction(index))

    LuaValue.Function(lua.toPointer(index))
  }

  protected def transformUserdata(index: Int): LuaValue.Userdata = {
    val `type` = lua.`type`(index)
    assert(`type` == LuaType.USERDATA || `type` == LuaType.LIGHTUSERDATA)

    LuaValue.Userdata(lua.toPointer(index))
  }

  protected def transformThread(index: Int): LuaValue.Thread = {
    assert(lua.isThread(index))

    LuaValue.Thread(lua.toPointer(index))
  }

  protected def transformTable(index: Int): LuaValue.Table = {
    assert(lua.isTable(index))

    val absoluteIndex = lua.absIndex(index)
    lua.pushNil()

    LuaValue.Table(Iterator.continually(lua.next(absoluteIndex)).takeWhile(identity).map(_ => {
      val key = transformValue(-2).get
      val value = transformValue(-1).get
      lua.pop(1)

      (key, value)
    }).toMap)
  }

  protected def transformValue(index: Int): Option[LuaValue] = {
    lua.`type`(index) match {
      case null =>
        None

      case LuaType.NIL =>
        Some(transformNil(index))

      case LuaType.BOOLEAN =>
        Some(transformBoolean(index))

      case LuaType.NUMBER =>
        Some(transformNumber(index))

      case LuaType.STRING =>
        Some(transformString(index))

      case LuaType.FUNCTION =>
        Some(transformFunction(index))

      case LuaType.USERDATA | LuaType.LIGHTUSERDATA =>
        Some(transformUserdata(index))

      case LuaType.THREAD =>
        Some(transformThread(index))

      case LuaType.TABLE =>
        Some(transformTable(index))
    }
  }

  protected def transformNillable[T](transformer: Int => T)(index: Int): Option[T] = {
    assert(!lua.isNone(index))

    if (lua.isNil(index)) {
      None
    } else {
      Some(transformer(index))
    }
  }

  protected def transformField[T](transformer: Int => T, field: String)(index: Int): T = {
    assert(lua.isTable(index))

    lua.getField(index, field)
    val result = transformer(-1)
    lua.pop(1)

    result
  }

  private def getStackFrame(index: Int): Option[StackFrame] = {
    pushMainThread() // stack: <main thread>
    lua.pushString("nSlut") // stack: "nSlut" <main thread>
    callDebugFunction("getinfo", 2, 1) // stack: [nil | <stack frame>]

    val optionalLong = (v: Long) => {
      assert(v >= -1)

      if (v == -1) {
        None
      } else {
        Some(v)
      }
    }

    var result: Option[StackFrame] = None

    if (lua.`type`(-1) == LuaType.TABLE) {
      // stack: <stack frame>
      val name = transformField(transformNillable(transformString), "name")(-1).map(_.toUtf8String)

      val nameWhat = transformField(transformNillable(transformString), "namewhat")(-1).map(_.toUtf8String)

      val what = transformField(transformString, "what")(-1).toUtf8String match {
        case "C" => StackFrameLuaWhat.C()
        case "Lua" => StackFrameLuaWhat.Lua()
        case "main" => StackFrameLuaWhat.Main()
      }

      val source = {
        val sourceStr = transformField(transformString, "source")(-1).value

        sourceStr.headOption match {
          case Some('@') => StackFrameLuaSource.File(LuaValue.String(sourceStr.tail).toUtf8String)
          case Some('=') => StackFrameLuaSource.Other(LuaValue.String(sourceStr.tail).toUtf8String)
          case _ => StackFrameLuaSource.String(sourceStr)
        }
      }

      val currentLine = optionalLong(transformField(transformNumber, "currentline")(-1).value.toLong)
      val lineDefined = optionalLong(transformField(transformNumber, "linedefined")(-1).value.toLong)

      val lastLineDefined =
        optionalLong(transformField(transformNumber, "lastlinedefined")(-1).value.toLong)

      val upvalueCount = transformField(transformNumber, "nups")(-1).value.toLong
      val paramCount = transformField(transformNumber, "nparams")(-1).value.toLong
      val isVararg = transformField(transformBoolean, "isvararg")(-1).value
      val isTailCall = transformField(transformBoolean, "istailcall")(-1).value
      val shortSource = transformField(transformString, "short_src")(-1).toUtf8String

      result = Some(StackFrameLua(
        index = index,
        name = name,
        nameWhat = nameWhat,
        what = what,
        source = source,
        currentLine = currentLine,
        lines = lineDefined.zip(lastLineDefined),
        upvalueCount = upvalueCount.toInt,
        paramCount = paramCount.toInt,
        vararg = isVararg,
        tailCall = isTailCall,
        shortSource = shortSource
      ))
    }

    lua.pop(1) // stack restored

    result
  }

  override def getStackFrames: List[StackFrame] =
    LazyList.from(0).map(getStackFrame).takeWhile(_.nonEmpty).flatten.toList
}
