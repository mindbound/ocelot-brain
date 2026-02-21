package totoro.ocelot.brain

import org.apache.logging.log4j.{LogManager, Logger}
import totoro.ocelot.brain.entity._
import totoro.ocelot.brain.entity.machine.luac.{LuaStateFactory, NativeLua52Architecture, NativeLua53Architecture, NativeLua54Architecture}
import totoro.ocelot.brain.entity.machine.luaj.LuaJLuaArchitecture
import totoro.ocelot.brain.entity.machine.{MachineAPI, Registry}
import totoro.ocelot.brain.loot.Loot
import totoro.ocelot.brain.nbt.persistence.NBTPersistence
import totoro.ocelot.brain.nbt.persistence.NBTPersistence.{MemoryConstructor, TieredConstructor}
import totoro.ocelot.brain.util.{FontUtils, ThreadPoolFactory}

import java.nio.file.{Path, Paths}

object Ocelot {
  final val Name = "Ocelot"

  // do not forget to change the version in `build.sbt`
  final val Version = "0.24.2-online"

  // as taken from OpenComputers repository release tags (for example "1.7.10-forge/1.8.9" -> "1.8.9")
  final val OpenComputersVersion = "1.8.9a"

  def log: Logger = logger.getOrElse(LogManager.getLogger(Name))
  var logger: Option[Logger] = None

  var configPath: Option[Path] = None
  var librariesPath: Option[Path] = None

  // Ocelot™ replacement for FMLCommonHandler.instance.getMinecraftServerInstance.getOnlinePlayerNames
  var isPlayerOnlinePredicate: Option[String => Boolean] = None

  /**
    * This `preInit`, `init`, `postInit` thing is a legacy from Minecraft/Forge life cycle.
    * It can be replaced with simple `initialization` procedure in the future.
    */

  private def preInit(): Unit = {
    log.info("Loading configuration...")
    Settings.load(configPath.map(_.toFile))
  }

  private def init(): Unit = {
    log.info("Loading Lua libraries...")
    LuaStateFactory.init(librariesPath.getOrElse(Paths.get("./")))

    log.info("Registering available machine architectures...")

    if (LuaStateFactory.isAvailable) {
      if (LuaStateFactory.include53) {
        MachineAPI.add(classOf[NativeLua53Architecture], "Lua 5.3")
      }
      if (LuaStateFactory.include54) {
        MachineAPI.add(classOf[NativeLua54Architecture], "Lua 5.4")
      }
      if (LuaStateFactory.include52) {
        MachineAPI.add(classOf[NativeLua52Architecture], "Lua 5.2")
      }
    }
    if (LuaStateFactory.includeLuaJ) {
      MachineAPI.add(classOf[LuaJLuaArchitecture], "LuaJ")
    }

    log.info("Registering loot (floppies and EEPROMs with standard OpenComputers software)...")
    Loot.init()

    log.info("Registering entity constructors (for persistence purposes)...")
    val tieredConstructor = new TieredConstructor()

    NBTPersistence.registerConstructor(classOf[Case].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[Server].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[Microcontroller].getName, tieredConstructor)

    NBTPersistence.registerConstructor(classOf[Screen].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[HologramProjector].getName, tieredConstructor)

    NBTPersistence.registerConstructor(classOf[CPU].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[APU].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[GraphicsCard].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[HDDManaged].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[HDDUnmanaged].getName, tieredConstructor)
    NBTPersistence.registerConstructor(classOf[Memory].getName, new MemoryConstructor())
    NBTPersistence.registerConstructor(classOf[ComponentBus].getName, tieredConstructor)

    FontUtils.init()

    ThreadPoolFactory.safePools.foreach(_.newThreadPool())

    if (Settings.get.internetAccessConfigured) {
      if (Settings.get.internetFilteringRulesInvalid) {
        Ocelot.log.warn("####################################################")
        Ocelot.log.warn("#                                                  #")
        Ocelot.log.warn("#  Could not parse Internet Card filtering rules!  #")
        Ocelot.log.warn("#  Review the server log and adjust the filtering  #")
        Ocelot.log.warn("#  list to ensure it is appropriately configured.  #")
        Ocelot.log.warn("#          (brain.conf => filteringRules)          #")
        Ocelot.log.warn("# Internet access has been automatically disabled. #")
        Ocelot.log.warn("#                                                  #")
        Ocelot.log.warn("####################################################")
      } else {
        Ocelot.log.info(
          f"Successfully applied ${Settings.get.internetFilteringRules.length} Internet Card filtering rules."
        )
      }
    }
  }

  private def postInit(): Unit = {
    // Don't allow registration after this point, to avoid issues.
    Registry.locked = true
  }

  def initialize(logger: Logger): Unit = {
    this.logger = Some(logger)
    initialize()
  }

  def initialize(): Unit = {
    log.info("Brain initialization...")
    log.info(s"Version: ${Ocelot.Version}")
    preInit()
    init()
    postInit()
    log.info("Initialization finished.")
  }

  def shutdown(): Unit = {
    log.info("Preparing for Ocelot shutdown...")
    ThreadPoolFactory.safePools.foreach(_.waitForCompletion())
    log.info("Ocelot is shut down.")
  }
}
