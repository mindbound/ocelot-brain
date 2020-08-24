package totoro.ocelot.brain.loot

import totoro.ocelot.brain.entity.fs.{FileSystem, FileSystemAPI}
import totoro.ocelot.brain.entity.traits.Entity
import totoro.ocelot.brain.entity.{EEPROM, FloppyManaged}
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.util.DyeColor
import totoro.ocelot.brain.workspace.Workspace
import totoro.ocelot.brain.{Ocelot, Settings}

object Loot {
  var OpenOsEEPROM: LootFactory = _
  var AdvLoaderEEPROM: LootFactory = _
  var CyanBIOSEEPROM: LootFactory = _
  var MineOSEFIEEPROM: LootFactory = _

  var NetworkFloppy: LootFactory = _
  var Plan9kFloppy: LootFactory = _
  var IrcFloppy: LootFactory = _
  var OpenLoaderFloppy: LootFactory = _
  var OpenOsFloppy: LootFactory = _
  var OPPMFloppy: LootFactory = _
  var DataFloppy: LootFactory = _

  def init(): Unit = {
    // EEPROM
    OpenOsEEPROM = new EEPROMFactory("OpenOS BIOS", "bios.lua")
    AdvLoaderEEPROM = new EEPROMFactory("advancedLoader", "advLoader.lua")
    CyanBIOSEEPROM = new EEPROMFactory("Cyan BIOS", "cyan.lua")
    MineOSEFIEEPROM = new EEPROMFactory("MineOS EFI", "mineosEFI.lua")

    // Floppies
    NetworkFloppy = new FloppyFactory("Network (Network Stack)", DyeColor.LIME, "network")
    Plan9kFloppy = new FloppyFactory("Plan9k (Operating System)", DyeColor.RED, "plan9k")
    IrcFloppy = new FloppyFactory("OpenIRC (IRC Client)", DyeColor.LIGHT_BLUE, "irc")
    OpenLoaderFloppy = new FloppyFactory("OpenLoader (Boot Loader)", DyeColor.MAGENTA, "openloader")
    OpenOsFloppy = new FloppyFactory("OpenOS (Operating System)", DyeColor.GREEN, "openos")
    OPPMFloppy = new FloppyFactory("OPPM (Package Manager)", DyeColor.CYAN, "oppm")
    DataFloppy = new FloppyFactory("Data Card Software", DyeColor.PINK, "data")
  }

  // ----------------------------------------------------------------------- //

  abstract class LootFactory {
    def create(): Entity
  }

  class LootFloppy(name: String, color: DyeColor, var path: String)
    extends FloppyManaged(name, color) {

    def this() = this("noname", DyeColor.BLACK, null)

    override protected def generateEnvironment(address: String): FileSystem = {
      FileSystemAPI.asManagedEnvironment(
        FileSystemAPI.fromClass(Ocelot.getClass, Settings.resourceDomain, "loot/" + path),
        label
      )
    }

    private val PathTag = "path"

    override def save(nbt: NBTTagCompound): Unit = {
      super.save(nbt)
      nbt.setString(PathTag, path)
    }

    override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
      // important: we need to load path first to be able to use it later
      //            to initialize the filesystem
      path = nbt.getString(PathTag)
      super.load(nbt, workspace)
    }
  }

  class FloppyFactory(name: String, color: DyeColor, path: String) extends LootFactory {
    override def create(): Entity = {
      new LootFloppy(name, color, path)
    }
  }

  class EEPROMFactory(label: String, file: String, readonly: Boolean = true) extends LootFactory {
    private val code = new Array[Byte](4 * 1024)
    private val count = Ocelot.getClass.getResourceAsStream(Settings.scriptPath + file).read(code)
    private val codeData = code.take(count)

    override def create(): Entity = {
      val eeprom = new EEPROM()
      eeprom.label = label
      eeprom.codeData = codeData
      eeprom.readonly = readonly
      eeprom
    }
  }
}
