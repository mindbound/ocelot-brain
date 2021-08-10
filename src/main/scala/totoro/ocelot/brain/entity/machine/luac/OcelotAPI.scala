package totoro.ocelot.brain.entity.machine.luac

import li.cil.repack.com.naef.jnlua.LuaType
import totoro.ocelot.brain.{Ocelot, Settings}
import totoro.ocelot.brain.entity.machine.ExtendedLuaState.extendLuaState

class OcelotAPI(owner: NativeLuaArchitecture) extends NativeLuaAPI(owner) {
  def initialize(): Unit = {
    lua.newTable(0, 4)

    lua.pushScalaFunction(lua => {
      lua.checkType(1, LuaType.THREAD)

      owner.trapLock.synchronized {
        if (lua.isString(2)) {
          lua.toString(2) match {
            case "step" | "step-instruction" =>
              owner.trapRequested = true

            case _ =>
          }
        }

        if (owner.trapRequested) {
          owner.fetchSnapshot()
          owner.trapped = true
        }

        while (owner.trapRequested) {
          owner.trapLock.wait()
        }

        owner.debugCommand match {
          case Some(command) => lua.pushString(command.toString)
          case None => lua.pushNil()
        }

        owner.debugCommand = None
        owner.trapped = false

        1
      }
    })
    lua.setField(-2, "trap")

    lua.pushScalaFunction(lua => {
      lua.pushString(s"${Ocelot.Name} v${Ocelot.Version}, extra milk edition, insert your own joke here, I'm outta here")

      1
    })
    lua.setField(-2, "version")

    lua.pushScalaFunction(lua => owner.trapLock.synchronized {
      lua.pushBoolean(owner.debugEnabled)

      1
    })
    lua.setField(-2, "isDebugEnabled")

    lua.pushScalaFunction(lua => {
      lua.checkType(1, LuaType.FUNCTION)
      lua.checkType(2, LuaType.STRING)

      val funcAddr = lua.toPointer(1)
      val source = lua.toByteArray(2).toList

      owner.registerSourceMapping(funcAddr, source)

      0
    })
    lua.setField(-2, "registerSource")

    lua.setGlobal("ocelot")
  }
}
