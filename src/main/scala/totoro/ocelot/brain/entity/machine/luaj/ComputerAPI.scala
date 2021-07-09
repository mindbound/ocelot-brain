package totoro.ocelot.brain.entity.machine.luaj

import li.cil.repack.org.luaj.vm2.{LuaValue, Varargs}
import totoro.ocelot.brain.Settings
import totoro.ocelot.brain.entity.machine.MachineAPI
import totoro.ocelot.brain.entity.machine.ScalaClosure._
import totoro.ocelot.brain.entity.traits.{MutableProcessor, Processor}

class ComputerAPI(owner: LuaJLuaArchitecture) extends LuaJAPI(owner) {
  override def initialize() {
    // Computer API, stuff that kinda belongs to os, but we don't want to
    // clutter it.
    val computer = LuaValue.tableOf()

    // Allow getting the real world time for timeouts.
    computer.set("realTime", (_: Varargs) => LuaValue.valueOf(System.currentTimeMillis() / 1000.0))

    computer.set("uptime", (_: Varargs) => LuaValue.valueOf(machine.upTime()))

    // Allow the computer to figure out its own id in the component network.
    computer.set("address", (_: Varargs) => Option(node.address) match {
      case Some(address) => LuaValue.valueOf(address)
      case _ => LuaValue.NIL
    })

    computer.set("freeMemory", (_: Varargs) => LuaValue.valueOf(owner.freeMemory))

    computer.set("totalMemory", (_: Varargs) => LuaValue.valueOf(owner.totalMemory))

    computer.set("pushSignal", (args: Varargs) => LuaValue.valueOf(machine.signal(args.checkjstring(1), toSimpleJavaObjects(args, 2): _*)))

    // And it's /tmp address...
    computer.set("tmpAddress", (_: Varargs) => {
      val address = machine.tmpAddress
      if (address == null) LuaValue.NIL
      else LuaValue.valueOf(address)
    })

    // User management.
    computer.set("users", (_: Varargs) => LuaValue.varargsOf(machine.users.map(LuaValue.valueOf)))

    computer.set("addUser", (args: Varargs) => {
      machine.addUser(args.checkjstring(1))
      LuaValue.TRUE
    })

    computer.set("removeUser", (args: Varargs) => LuaValue.valueOf(machine.removeUser(args.checkjstring(1))))

    computer.set("energy", (_: Varargs) => LuaValue.valueOf(Settings.get.bufferComputer))

    computer.set("maxEnergy", (_: Varargs) => LuaValue.valueOf(Settings.get.bufferComputer))

    computer.set("getArchitectures", (_: Varargs) => {
      machine.host.inventory.collectFirst {
        case processor: MutableProcessor => processor.allArchitectures.toSeq
        case processor: Processor => Seq(processor.architecture)
      } match {
        case Some(architectures) => LuaValue.listOf(architectures.map(MachineAPI.getArchitectureName).map(LuaValue.valueOf).toArray)
        case _ => LuaValue.tableOf()
      }
    })

    computer.set("getArchitecture", (_: Varargs) => {
      machine.host.inventory.collectFirst {
        case processor: Processor => LuaValue.valueOf(MachineAPI.getArchitectureName(processor.architecture))
      }.getOrElse(LuaValue.NONE)
    })

    computer.set("setArchitecture", (args: Varargs) => {
      val archName = args.checkjstring(1)
      machine.host.inventory.collectFirst {
        case processor: MutableProcessor => processor.allArchitectures.find(arch => MachineAPI.getArchitectureName(arch) == archName) match {
          case Some(archClass) =>
            if (archClass != processor.architecture) {
              processor.setArchitecture(archClass)
              LuaValue.TRUE
            }
            else {
              LuaValue.FALSE
            }
          case _ =>
            LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("unknown architecture"))
        }
      }.getOrElse(LuaValue.NONE)
    })

    // Set the computer table.
    lua.set("computer", computer)
  }
}
