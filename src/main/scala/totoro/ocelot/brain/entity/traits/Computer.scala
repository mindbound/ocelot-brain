package totoro.ocelot.brain.entity.traits

import totoro.ocelot.brain.entity.machine.{Machine, MachineAPI}
import totoro.ocelot.brain.nbt.ExtendedNBT._
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.Node
import totoro.ocelot.brain.workspace.Workspace

trait Computer extends Environment with MachineHost with ComponentInventory {

  private lazy val _machine = MachineAPI.create(this)

  override def machine: Machine = _machine

  override def node: Node = machine.node

  // ----------------------------------------------------------------------- //

  override def onMachineConnect(node: Node): Unit = this.onConnect(node)

  override def onMachineDisconnect(node: Node): Unit = this.onDisconnect(node)

  override def componentSlot(address: String): Int = {
    val entity = inventory.entities.find {
      case env: Environment => env.node != null && env.node.address == address
    }

    entity.flatMap(inventory.slot).map(_.index).getOrElse(-1)
  }

  // ----------------------------------------------------------------------- //

  override def needUpdate: Boolean = true

  override def update(): Unit = {
    // If we're not yet in a network we might have just been loaded from disk,
    // meaning there may be other tile entities that also have not re-joined
    // the network. We skip the update this round to allow other tile entities
    // to join the network, too, avoiding issues of missing nodes (e.g. in the
    // GPU which would otherwise lose track of its screen).
    if (isConnected) {
      machine.update()
    }

    super.update()
  }

  override def dispose(): Unit = {
    super.dispose()
    if (machine != null) {
      machine.stop()
    }
  }

  // ----------------------------------------------------------------------- //

  override def onEntityAdded(slot: Slot, entity: Entity): Unit = {
    super.onEntityAdded(slot, entity)

    entity match {
      case _: Memory => machine.onHostChanged()
      case _ =>
    }
  }

  override def onEntityRemoved(slot: Slot, entity: Entity): Unit = {
    entity match {
      case _: Processor => machine.stop()
      case _: Memory => machine.onHostChanged()
      case _ =>
    }

    super.onEntityRemoved(slot, entity)
  }

  // ----------------------------------------------------------------------- //

  private final val ComputerTag = "computer"

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
    super.load(nbt, workspace)
    machine.load(nbt.getCompoundTag(ComputerTag), workspace)
  }

  override def save(nbt: NBTTagCompound): Unit = {
    super.save(nbt)
    if (machine != null) {
      nbt.setNewCompoundTag(ComputerTag, machine.save)
    }
  }
}
