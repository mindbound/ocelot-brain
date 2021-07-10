package totoro.ocelot.brain.entity.machine.luac

import totoro.ocelot.brain.Ocelot
import totoro.ocelot.brain.entity.machine.ExtendedLuaState.extendLuaState

class OcelotAPI(owner: NativeLuaArchitecture) extends NativeLuaAPI(owner) {
  def initialize(): Unit = {
    lua.newTable(0, 2)

    lua.pushScalaFunction(_ => {
      owner.trapLock.synchronized {
        while (owner.trapRequested) {
          owner.trapLock.wait()
        }
      }

      0
    })
    lua.setField(-2, "trap")

    lua.pushScalaFunction(lua => {
      lua.pushString(s"${Ocelot.Name} v${Ocelot.Version}, extra milk edition, insert your own joke here, I'm outta here")

      1
    })
    lua.setField(-2, "version")

    lua.pushScalaFunction(lua => owner.trapLock.synchronized {
      lua.pushBoolean(owner.trapRequested)

      1
    })
    lua.setField(-2, "isTrapRequested")

    lua.setGlobal("ocelot")
  }
}
