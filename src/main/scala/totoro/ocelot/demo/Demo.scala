package totoro.ocelot.demo

import java.nio.file.Files
import totoro.ocelot.brain.Ocelot
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.entity.{CPU, Case, GraphicsCard, HDDManaged, Memory, Redstone, Screen}
import totoro.ocelot.brain.entity.machine.luac.StackFrameLua
import totoro.ocelot.brain.entity.machine.debug.Debuggable
import totoro.ocelot.brain.event._
import totoro.ocelot.brain.loot.Loot
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.nbt.persistence.PersistableString
import totoro.ocelot.brain.util.Tier
import totoro.ocelot.brain.workspace.Workspace

object Demo extends App {
  println("Hi! We are testing Ocelot brains here. Join in!")

  /**
    * We can pass here a custom logger: `Ocelot.initialize(logger)`
    */
  Ocelot.initialize()

  /**
    * All things inside of Ocelot usually are grouped by workspaces.
    * Workspace is like 'world' in Minecraft. It has it's own timeflow,
    * it's own name, random numbers generator and a list of entities ('blocks' and 'items' in Minecraft).
    * Workspace can be serialized to an NBT tag, and then restored back from it.
    * Workspace is also responsible for the lifecycle of all its entities.
    *
    * For things to work correctly, you will usually add new entities to some workspace.
    * Entities then will be managed by this workspace.
    * Entities still can form connections between workspaces, and exchange data.
    */
  val workspace = new Workspace(Files.createTempDirectory("ocelot-1"))

  /**
    * Now we create a new entity - computer case.
    * Straight off we add it to the workspace.
    */
  var computer = workspace.add(new Case(Tier.Four))

  /**
    * Computer components do not need to be added to the workspace explicitly,
    * because they are added to the Case internal inventory (and the case is added to the workspace).
    */
  val cpu = new CPU(Tier.Three)
  computer.add(cpu)

  val gpu = new GraphicsCard(Tier.Three)
  computer.add(gpu)

  val memory = new Memory(Tier.Six)
  computer.add(memory)

  val redstone = new Redstone.Tier1()
  computer.add(redstone)

  /**
    * When creating a new hard drive, you can specify its address.
    * If you will leave it `null`, then new random UUID will be used.
    */

  val hdd = new HDDManaged(Tier.One)
  computer.add(hdd)

  /**
  Custom EEPROM can be created like this:
  **/

//  val eeprom = new EEPROM()
//  eeprom.codeData =
//    """
//      |local gpu = component.proxy((component.list("gpu", true)()))
//      |local fs
//      |
//      |for address in component.list("filesystem", true) do
//      |  if component.invoke(address, "getLabel") == "init" then
//      |    fs = component.proxy(address)
//      |    break
//      |  end
//      |end
//      |
//      |assert(fs, "no init filesystem found")
//      |
//      |gpu.bind((component.list("screen", true)()))
//      |gpu.set(1, 1, "Running script from [59aef805]/init.lua")
//      |
//      |local w, h = gpu.getResolution()
//      |
//      |local file = fs.open("/init.lua", "r")
//      |
//      |local chunk = assert(load(function()
//      |  return fs.read(file, math.huge)
//      |end, "/init.lua", "t"))
//      |
//      |fs.close(file)
//      |gpu.fill(1, 1, w, h, " ")
//      |
//      |local returnValues = table.pack(xpcall(chunk, debug.traceback, ...))
//      |local success = table.remove(returnValues, 1)
//      |
//      |if not success then
//      |  error(returnValues[1], 0)
//      |else
//      |  local data = {}
//      |
//      |  for i = 1, returnValues.n, 1 do
//      |    table.insert(data, tostring(returnValues[i]))
//      |  end
//      |
//      |  gpu.set(1, 1, table.concat(data, ", "))
//      |end
//      |
//      |computer.shutdown()
//    """.stripMargin.getBytes("UTF-8")
//  eeprom.label = "Test BIOS"
//  computer.add(eeprom)

  computer.add(Loot.OpenOsEEPROM.create())
  computer.add(Loot.OpenOsFloppy.create())

  val screen = workspace.add(new Screen(Tier.One))
  computer.connect(screen)

  /**
    * Here we add some custom NBT data to the computer.
    * This data needs to implement the Persistable trait.
    */
  computer.setCustomData(new PersistableString("xxx"))

  // register some event listeners
  EventBus.listenTo(classOf[BeepEvent], { case event: BeepEvent =>
    println(s"[EVENT] Beep (address = ${event.address}, frequency = ${event.frequency}, duration = ${event.duration})")
  })
  EventBus.listenTo(classOf[BeepPatternEvent], { case event: BeepPatternEvent =>
    println(s"[EVENT] Beep (address = ${event.address}, pattern = ${event.pattern})")
  })
  EventBus.listenTo(classOf[MachineCrashEvent], { case event: MachineCrashEvent =>
    println(s"[EVENT] Machine crash! (address = ${event.address}, ${event.message})")
  })
  EventBus.listenTo(classOf[FileSystemActivityEvent], { case event: FileSystemActivityEvent =>
    println(s"[EVENT] Filesystem activity (address = ${event.address})")
  })
  EventBus.listenTo(classOf[TextBufferSetEvent], { case event: TextBufferSetEvent =>
    println(s"[EVENT] Text buffer set (address = ${event.address}, ${event.x}, ${event.y}, ${event.value}, ${event.vertical})")
  })
  EventBus.listenTo(classOf[TextBufferSetForegroundColorEvent], { case event: TextBufferSetForegroundColorEvent =>
    println(s"[EVENT] Foreground color changed (address = ${event.address}, ${event.color})")
  })
  EventBus.listenTo(classOf[TextBufferSetBackgroundColorEvent], { case event: TextBufferSetBackgroundColorEvent =>
    println(s"[EVENT] Background color changed (address = ${event.address}, ${event.color})")
  })

  /**
    * The computer can be turned on or off. By defaults it is turned off.
    */
  computer.turnOn()


  /**
    * The `computer.machine.isRunning` flag will tell you, if the computer is still operational,
    * or had it crashed or stopped the execution otherwise.
    */
  while (workspace.getIngameTime < 20) {
    /**
      * The `update()` method of workspace will update all components in each registered network,
      * that need to be updated.
      * These are usually computers or network cards.
      */
    workspace.update()
    /**
      * 50 milliseconds is the duration of standard Minecraft tick.
      * You can speed the simulation up or slow it down by changing this value.
      */
    Thread.sleep(50)
  }

  /**
    * Make a snapshot ot the system.
    * You need to use an existing NBT compound tag (or create a new one).
    */
  println("... Creating snapshot ...")

  var nbt = new NBTTagCompound()
  workspace.save(nbt)
  println(nbt)

  println("... Loading snapshot ...")

  /**
    * Load workspace from snapshot
    */
  val loadedWorkspace = new Workspace(Files.createTempDirectory("ocelot-2"))
  loadedWorkspace.load(nbt)

  loadedWorkspace.getEntitiesIter.foreach { case environment: Environment =>
      println("Loaded.Entity.Address: " + environment.node.address)
  }

  computer = null
  for (entity: Entity <- loadedWorkspace.getEntitiesIter) {
    entity match {
      case c: Case => computer = c
      case _ =>
    }
  }

  if (computer != null) println("Successfully loaded the snapshot. Continuing the execution...")

  while (loadedWorkspace.getIngameTime < 100) {
    loadedWorkspace.update()
    val frames = computer.machine.architecture match {
      case arch: Debuggable => arch.getStackFrames
      case _ => List.empty
    }

    println("Stack frames retrieved:")
    frames.foreach {
      case f: StackFrameLua =>
        println(
          s"\t${f.index}: name = ${f.name}, nameWhat = ${f.nameWhat}, what = ${f.what}, source = ${f.source}, " +
            s"current line = ${f.currentLine}, lines = ${f.lines}, ups = ${f.upvalueCount}, params = ${f.paramCount}, " +
            s"is vararg = ${f.vararg}, is tail call = ${f.tailCall}, short source = ${f.shortSource}"
        )
      case _ => println("\t¯\\_(ツ)_/¯")
    }

    Thread.sleep(50)
  }

  println("... Creating snapshot ...")
  nbt = new NBTTagCompound()
  loadedWorkspace.save(nbt)
  println(nbt)

  println("... Turning the computer off ...")

  computer.turnOff()

  /**
    * Necessary to successfully complete multi-threaded saving operation, and finalize other Ocelot tasks.
    */
  Ocelot.shutdown()
}
