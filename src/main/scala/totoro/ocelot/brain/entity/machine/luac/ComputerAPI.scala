package totoro.ocelot.brain.entity.machine.luac

import totoro.ocelot.brain.Settings
import totoro.ocelot.brain.entity.machine.ExtendedLuaState.extendLuaState
import totoro.ocelot.brain.entity.machine.MachineAPI
import totoro.ocelot.brain.entity.traits.{MutableProcessor, Processor}

class ComputerAPI(owner: NativeLuaArchitecture) extends NativeLuaAPI(owner) {
  def initialize() {
    // Computer API, stuff that kinda belongs to os, but we don't want to
    // clutter it.
    lua.newTable()

    // Allow getting the real world time for timeouts.
    lua.pushScalaFunction(lua => {
      lua.pushNumber(System.currentTimeMillis() / 1000.0)
      1
    })
    lua.setField(-2, "realTime")

    // The time the computer has been running, as opposed to the CPU time.
    lua.pushScalaFunction(lua => {
      lua.pushNumber(machine.upTime())
      1
    })
    lua.setField(-2, "uptime")

    // Allow the computer to figure out its own id in the component network.
    lua.pushScalaFunction(lua => {
      Option(node.address) match {
        case None => lua.pushNil()
        case Some(address) => lua.pushString(address)
      }
      1
    })
    lua.setField(-2, "address")

    lua.pushScalaFunction(lua => {
      lua.pushInteger(owner.freeMemory)
      1
    })
    lua.setField(-2, "freeMemory")

    // Allow the system to read how much memory it uses and has available.
    lua.pushScalaFunction(lua => {
      lua.pushInteger(owner.totalMemory)
      1
    })
    lua.setField(-2, "totalMemory")

    lua.pushScalaFunction(lua => {
      lua.pushBoolean(machine.signal(lua.checkString(1), lua.toSimpleJavaObjects(2): _*))
      1
    })
    lua.setField(-2, "pushSignal")

    // And it's /tmp address...
    lua.pushScalaFunction(lua => {
      val address = machine.tmpAddress
      if (address == null) lua.pushNil()
      else lua.pushString(address)
      1
    })
    lua.setField(-2, "tmpAddress")

    // User management.
    lua.pushScalaFunction(lua => {
      val users = machine.users
      users.foreach(lua.pushString)
      users.length
    })
    lua.setField(-2, "users")

    lua.pushScalaFunction(lua => {
      val user = lua.checkString(1)
      try {
        machine.addUser(user)
        lua.pushBoolean(true)
        1
      } catch {
        case e: Throwable =>
          lua.pushNil()
          lua.pushString(Option(e.getMessage).getOrElse(e.toString))
          2
      }
    })
    lua.setField(-2, "addUser")

    lua.pushScalaFunction(lua => {
      lua.pushBoolean(machine.removeUser(lua.checkString(1)))
      1
    })
    lua.setField(-2, "removeUser")

    lua.pushScalaFunction(lua => {
      lua.pushNumber(Settings.get.bufferComputer)
      1
    })
    lua.setField(-2, "energy")

    lua.pushScalaFunction(lua => {
      lua.pushNumber(Settings.get.bufferComputer)
      1
    })
    lua.setField(-2, "maxEnergy")

    lua.pushScalaFunction(lua => {
      machine.host.inventory.collectFirst {
        case processor: MutableProcessor => processor.allArchitectures
        case processor: Processor => Seq(processor.architecture)
      } match {
        case Some(architectures) =>
          lua.pushValue(architectures.map(MachineAPI.getArchitectureName).toArray)
        case _ =>
          lua.newTable()
      }
      1
    })
    lua.setField(-2, "getArchitectures")

    lua.pushScalaFunction(lua => {
      machine.host.inventory.collectFirst {
        case processor: Processor =>
          lua.pushString(MachineAPI.getArchitectureName(processor.architecture))
          1
      }.getOrElse(0)
    })
    lua.setField(-2, "getArchitecture")

    lua.pushScalaFunction(lua => {
      val archName = lua.checkString(1)
      machine.host.inventory.collectFirst {
        case processor: MutableProcessor =>
          processor.allArchitectures.find(arch => MachineAPI.getArchitectureName(arch) == archName) match {
            case Some(archClass) =>
              if (archClass != processor.architecture) {
                processor.setArchitecture(archClass)
                lua.pushBoolean(true)
              } else {
                lua.pushBoolean(false)
              }
              1
            case _ =>
              lua.pushNil()
              lua.pushString("unknown architecture")
              2
          }
      }.getOrElse(0)
    })
    lua.setField(-2, "setArchitecture")

    // Set the computer table.
    lua.setGlobal("computer")
  }
}
