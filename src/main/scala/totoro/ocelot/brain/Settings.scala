package totoro.ocelot.brain

import java.io._
import java.net.{Inet4Address, InetAddress}

import com.google.common.net.InetAddresses
import com.typesafe.config._
import totoro.ocelot.brain.util.ColorDepth

import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

class Settings(val config: Config) {
  // ----------------------------------------------------------------------- //
  // client
  val monochromeColor: Integer = Integer.decode(config.getString("client.monochromeColor"))

  // ----------------------------------------------------------------------- //
  // computer
  val threads: Int = config.getInt("computer.threads") max 1
  val timeout: Double = config.getDouble("computer.timeout") max 0
  val startupDelay: Double = config.getDouble("computer.startupDelay") max 0.05
  val eepromSize: Int = config.getInt("computer.eepromSize") max 0
  val eepromDataSize: Int = config.getInt("computer.eepromDataSize") max 0
  val cpuComponentSupport: Array[Int] = Array(config.getIntList("computer.cpuComponentCount").asScala.toArray: _*) match {
    case Array(tier1, tier2, tier3, tierCreative) =>
      Array(tier1: Int, tier2: Int, tier3: Int, tierCreative: Int)
    case _ =>
      Ocelot.log.warn("Bad number of CPU component counts, ignoring.")
      Array(8, 12, 16, 1024)
  }
  val callBudgets: Array[Double] = Array(config.getDoubleList("computer.callBudgets").asScala.toArray: _*) match {
    case Array(tier1, tier2, tier3) =>
      Array(tier1: Double, tier2: Double, tier3: Double)
    case _ =>
      Ocelot.log.warn("Bad number of call budgets, ignoring.")
      Array(0.5, 1.0, 1.5)
  }
  val canComputersBeOwned: Boolean = config.getBoolean("computer.canComputersBeOwned")
  val maxUsers: Int = config.getInt("computer.maxUsers") max 0
  val maxUsernameLength: Int = config.getInt("computer.maxUsernameLength") max 0
  val eraseTmpOnReboot: Boolean = config.getBoolean("computer.eraseTmpOnReboot")
  val executionDelay: Int = config.getInt("computer.executionDelay") max 0

  // computer.lua
  val allowBytecode: Boolean = config.getBoolean("computer.lua.allowBytecode")
  val allowGC: Boolean = config.getBoolean("computer.lua.allowGC")
  val enableLua53: Boolean = config.getBoolean("computer.lua.enableLua53")
  val defaultLua53: Boolean = config.getBoolean("computer.lua.defaultLua53")
  val ramSizes: Array[Int] = Array(config.getIntList("computer.lua.ramSizes").asScala.toArray: _*) match {
    case Array(tier1, tier2, tier3, tier4, tier5, tier6) =>
      Array(tier1: Int, tier2: Int, tier3: Int, tier4: Int, tier5: Int, tier6: Int)
    case _ =>
      Ocelot.log.warn("Bad number of RAM sizes, ignoring.")
      Array(192, 256, 384, 512, 768, 1024)
  }
  val ramScaleFor64Bit: Double = config.getDouble("computer.lua.ramScaleFor64Bit") max 1
  val maxTotalRam: Int = config.getInt("computer.lua.maxTotalRam") max 0

  // ----------------------------------------------------------------------- //
  // power.buffer
  val bufferComputer: Double = config.getDouble("power.buffer.computer")

  // ----------------------------------------------------------------------- //
  // filesystem
  val fileCost: Int = config.getInt("filesystem.fileCost") max 0
  val bufferChanges: Boolean = config.getBoolean("filesystem.bufferChanges")
  val hddSizes: Array[Int] = Array(config.getIntList("filesystem.hddSizes").asScala.toArray: _*) match {
    case Array(tier1, tier2, tier3) =>
      Array(tier1: Int, tier2: Int, tier3: Int)
    case _ =>
      Ocelot.log.warn("Bad number of HDD sizes, ignoring.")
      Array(1024, 2048, 4096)
  }
  val hddPlatterCounts: Array[Int] = Array(config.getIntList("filesystem.hddPlatterCounts").asScala.toArray: _*) match {
    case Array(tier1, tier2, tier3) =>
      Array(tier1: Int, tier2: Int, tier3: Int)
    case _ =>
      Ocelot.log.warn("Bad number of HDD platter counts, ignoring.")
      Array(2, 4, 6)
  }
  val floppySize: Int = config.getInt("filesystem.floppySize") max 0
  val tmpSize: Int = config.getInt("filesystem.tmpSize") max 0
  val maxHandles: Int = config.getInt("filesystem.maxHandles") max 0
  val maxReadBuffer: Int = config.getInt("filesystem.maxReadBuffer") max 0
  val sectorSeekThreshold: Int = config.getInt("filesystem.sectorSeekThreshold")
  val sectorSeekTime: Double = config.getDouble("filesystem.sectorSeekTime")

  // ----------------------------------------------------------------------- //
  // internet
  val httpEnabled: Boolean = config.getBoolean("internet.enableHttp")
  val httpHeadersEnabled: Boolean = config.getBoolean("internet.enableHttpHeaders")
  val tcpEnabled: Boolean = config.getBoolean("internet.enableTcp")
  val httpHostBlacklist: Array[Settings.AddressValidator] =
    Array(config.getStringList("internet.blacklist").asScala.toArray.map(new Settings.AddressValidator(_)): _*)
  val httpHostWhitelist: Array[Settings.AddressValidator] =
    Array(config.getStringList("internet.whitelist").asScala.toArray.map(new Settings.AddressValidator(_)): _*)
  val httpTimeout: Int = (config.getInt("internet.requestTimeout") max 0) * 1000
  val maxConnections: Int = config.getInt("internet.maxTcpConnections") max 0
  val internetThreads: Int = config.getInt("internet.threads") max 1

  // ----------------------------------------------------------------------- //
  // switch
  val switchDefaultMaxQueueSize: Int = config.getInt("switch.defaultMaxQueueSize") max 1
  val switchQueueSizeUpgrade: Int = config.getInt("switch.queueSizeUpgrade") max 0
  val switchDefaultRelayDelay: Int = config.getInt("switch.defaultRelayDelay") max 1
  val switchRelayDelayUpgrade: Double = config.getDouble("switch.relayDelayUpgrade") max 0
  val switchDefaultRelayAmount: Int = config.getInt("switch.defaultRelayAmount") max 1
  val switchRelayAmountUpgrade: Int = config.getInt("switch.relayAmountUpgrade") max 0

  // ----------------------------------------------------------------------- //
  // hologram
  val hologramMaxScaleByTier: Array[Double] = Array(config.getDoubleList("hologram.maxScale").asScala.toArray: _*) match {
    case Array(tier1, tier2) =>
      Array((tier1: Double) max 1.0, (tier2: Double) max 1.0)
    case _ =>
      Ocelot.log.warn("Bad number of hologram max scales, ignoring.")
      Array(3.0, 4.0)
  }
  val hologramMaxTranslationByTier: Array[Double] = Array(config.getDoubleList("hologram.maxTranslation").asScala.toArray: _*) match {
    case Array(tier1, tier2) =>
      Array((tier1: Double) max 0.0, (tier2: Double) max 0.0)
    case _ =>
      Ocelot.log.warn("Bad number of hologram max translations, ignoring.")
      Array(0.25, 0.5)
  }
  val hologramSetRawDelay: Double = config.getDouble("hologram.setRawDelay") max 0

  // ----------------------------------------------------------------------- //
  // misc
  val inputUsername: Boolean = config.getBoolean("misc.inputUsername")
  val maxNetworkPacketSize: Int = config.getInt("misc.maxNetworkPacketSize") max 0
  // Need at least 4 for nanomachine protocol. Because I can!
  val maxNetworkPacketParts: Int = config.getInt("misc.maxNetworkPacketParts") max 4
  val maxOpenPorts: Array[Int] = Array(config.getIntList("misc.maxOpenPorts").asScala.toArray: _*) match {
    case Array(wired, tier1, tier2) =>
      Array((wired: Int) max 0, (tier1: Int) max 0, (tier2: Int) max 0)
    case _ =>
      Ocelot.log.warn("Bad number of max open ports, ignoring.")
      Array(16, 1, 16)
  }
  val geolyzerRange: Int = config.getInt("misc.geolyzerRange")
  val allowItemStackInspection: Boolean = config.getBoolean("misc.allowItemStackInspection")
  val maxWirelessRange: Array[Double] = Array(config.getDoubleList("misc.maxWirelessRange").asScala.toArray: _*) match {
    case Array(tier1, tier2) =>
      Array((tier1: Double) max 0.0, (tier2: Double) max 0.0)
    case _ =>
      Ocelot.log.warn("Bad number of wireless card max ranges, ignoring.")
      Array(16.0, 400.0)
  }
  val redstoneDelay: Double = config.getDouble("misc.redstoneDelay") max 0

  val rTreeMaxEntries = 10

  val threadPriority: Int = config.getInt("misc.threadPriority")
  val dataCardSoftLimit: Int = config.getInt("misc.dataCardSoftLimit") max 0
  val dataCardHardLimit: Int = config.getInt("misc.dataCardHardLimit") max 0
  val dataCardTimeout: Double = config.getDouble("misc.dataCardTimeout") max 0

  // ----------------------------------------------------------------------- //
  // debug
  val logLuaCallbackErrors: Boolean = config.getBoolean("debug.logCallbackErrors")
  val forceLuaJ: Boolean = config.getBoolean("debug.forceLuaJ")
  val allowUserdata: Boolean = !config.getBoolean("debug.disableUserdata")
  val allowPersistence: Boolean = !config.getBoolean("debug.disablePersistence")
  val limitMemory: Boolean = !config.getBoolean("debug.disableMemoryLimit")
  val forceCaseInsensitive: Boolean = config.getBoolean("debug.forceCaseInsensitiveFS")
  val logFullLibLoadErrors: Boolean = config.getBoolean("debug.logFullNativeLibLoadErrors")
  val forceNativeLib: String = config.getString("debug.forceNativeLibWithName")
  val alwaysTryNative: Boolean = config.getBoolean("debug.alwaysTryNative")
  val debugPersistence: Boolean = config.getBoolean("debug.verbosePersistenceErrors")
  val nativeInTmpDir: Boolean = config.getBoolean("debug.nativeInTmpDir")
  val insertIdsInConverters: Boolean = config.getBoolean("debug.insertIdsInConverters")
  val registerLuaJArchitecture: Boolean = config.getBoolean("debug.registerLuaJArchitecture")
  val disableLocaleChanging: Boolean = config.getBoolean("debug.disableLocaleChanging")

  // >= 1.7.4
  val maxSignalQueueSize: Int =
    (if (config.hasPath("computer.maxSignalQueueSize")) config.getInt("computer.maxSignalQueueSize") else 256) min 256
}

object Settings {
  val resourceDomain = "opencomputers"
  val scriptPath: String = "/assets/" + resourceDomain + "/lua/"
  val screenResolutionsByTier: Array[(Int, Int)] = Array((50, 16), (80, 25), (160, 50))
  val screenDepthsByTier: Array[ColorDepth.Value] = Array(ColorDepth.OneBit, ColorDepth.FourBit, ColorDepth.EightBit)
  val deviceComplexityByTier: Array[Int] = Array(12, 24, 32, 9001)
  var rTreeDebugRenderer = false
  var blockRenderId: Int = -1

  def basicScreenPixels: Int = screenResolutionsByTier(0)._1 * screenResolutionsByTier(0)._2

  private var settings: Settings = _

  def get: Settings = settings

  def load(file: File): Unit = {
    import java.lang.System.{lineSeparator => EOL}
    // typesafe config's internal method for loading the reference.conf file
    // seems to fail on some systems (as does their parseResource method), so
    // we'll have to load the default config manually. This was reported on the
    // Minecraft Forums, I could not reproduce the issue, but this version has
    // reportedly fixed the problem.
    val defaults = {
      val in = classOf[Settings].getResourceAsStream("/application.conf")
      val config = Source.fromInputStream(in)(Codec.UTF8).getLines().mkString("", EOL, EOL)
      in.close()
      ConfigFactory.parseString(config)
    }
    try {
      val source = Source.fromFile(file)(Codec.UTF8)
      val plain = source.getLines().mkString("", EOL, EOL)
      val config = ConfigFactory.parseString(plain)
      settings = new Settings(config.getConfig("opencomputers"))
      source.close()
    }
    catch {
      case e: Throwable =>
        if (file.exists()) {
          Ocelot.log.warn("Failed loading config, using defaults.", e)
        }
        settings = new Settings(defaults.getConfig("opencomputers"))
    }
  }

  val cidrPattern: Regex = """(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?:/(\d{1,2}))""".r

  class AddressValidator(val value: String) {
    val validator: (InetAddress, String) => Boolean = try cidrPattern.findFirstIn(value) match {
      case Some(cidrPattern(address, prefix)) =>
        val addr = InetAddresses.coerceToInteger(InetAddresses.forString(address))
        val mask = 0xFFFFFFFF << (32 - prefix.toInt)
        val min = addr & mask
        val max = min | ~mask
        (inetAddress: InetAddress, _: String) =>
          inetAddress match {
            case v4: Inet4Address =>
              val numeric = InetAddresses.coerceToInteger(v4)
              min <= numeric && numeric <= max
            case _ => true // Can't check IPv6 addresses so we pass them.
          }
      case _ =>
        val address = InetAddress.getByName(value)
        (inetAddress: InetAddress, host: String) => host == value || inetAddress == address
    } catch {
      case t: Throwable =>
        Ocelot.log.warn("Invalid entry in internet blacklist / whitelist: " + value, t)
        (_: InetAddress, _: String) => true
    }

    def apply(inetAddress: InetAddress, host: String): Boolean = validator(inetAddress, host)
  }
}
