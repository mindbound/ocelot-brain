package totoro.ocelot.brain.entity

import totoro.ocelot.brain.Constants
import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context}
import totoro.ocelot.brain.entity.traits.DeviceInfo.{DeviceAttribute, DeviceClass}
import totoro.ocelot.brain.entity.traits.{ComponentInventory, DeviceInfo, DiskActivityAware, Entity, Environment}
import totoro.ocelot.brain.network.{Component, Network, Node, Visibility}

class FloppyDiskDrive extends Entity with Environment with ComponentInventory with DeviceInfo with DiskActivityAware {
  override val node: Component = Network.newNode(this, Visibility.Network).
    withComponent("disk_drive").
    create()

  def filesystemNode: Option[Node] = inventory(0).get match {
    case Some(environment: Environment) => Option(environment.node)
    case _ => None
  }

  // ----------------------------------------------------------------------- //

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Disk,
    DeviceAttribute.Description -> "Floppy disk drive",
    DeviceAttribute.Vendor -> Constants.DeviceInfo.DefaultVendor,
    DeviceAttribute.Product -> "Exponat #266"
  )

  override def getDeviceInfo: Map[String, String] = deviceInfo

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function():boolean -- Checks whether some medium is currently in the drive.""")
  def isEmpty(context: Context, args: Arguments): Array[AnyRef] = {
    result(filesystemNode.isEmpty)
  }

  @Callback(doc = """function([velocity:number]):boolean -- Eject the currently present medium from the drive.""")
  def eject(context: Context, args: Arguments): Array[AnyRef] = {
    result(inventory.clear())
  }

  @Callback(doc = """function(): string -- Return the internal floppy disk address""")
  def media(context: Context, args: Arguments): Array[AnyRef] = {
    if (filesystemNode.isEmpty)
      result((), "drive is empty")
    else
      result(filesystemNode.head.address)
  }

  // ----------------------------------------------------------------------- //

  override def onEntityAdded(slot: Slot, entity: Entity): Unit = {
    super.onEntityAdded(slot, entity)
    entity match {
      case environment: Environment => environment.node match {
        case component: Component => component.setVisibility(Visibility.Network)
      }
      case _ =>
    }
  }
}
