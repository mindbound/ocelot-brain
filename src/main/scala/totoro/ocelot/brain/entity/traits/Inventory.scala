package totoro.ocelot.brain.entity.traits

import totoro.ocelot.brain.Ocelot
import totoro.ocelot.brain.event.{EventBus, InventoryEntityAddedEvent, InventoryEntityRemovedEvent}
import totoro.ocelot.brain.nbt.ExtendedNBT._
import totoro.ocelot.brain.nbt.persistence.NBTPersistence
import totoro.ocelot.brain.nbt.{NBT, NBTTagCompound}
import totoro.ocelot.brain.util.Persistable
import totoro.ocelot.brain.workspace.Workspace

import scala.collection.mutable

/**
  * Describes an entity with internal container, which can hold other entities.
  * Such as computer case.
  */
trait Inventory extends WorkspaceAware with Persistable {
  // invariant: (i, e) ∈ slots iff (e, i) ∈ entitySlotIndices
  // in other words, entitySlotIndices is the inverse of slots
  private val slots: mutable.HashMap[Int, Entity] = mutable.HashMap.empty
  private val entitySlotIndices: mutable.HashMap[Entity, Int] = mutable.HashMap.empty

  /**
    * A proxy to the inventory contents.
    */
  final val inventory: InventoryProxy = new InventoryProxy

  /**
    * Called after a new entity is added to the inventory.
    */
  def onEntityAdded(slot: Slot, entity: Entity): Unit = {
    entity match {
      case e: WorkspaceAware => e.workspace = workspace
      case _ =>
    }

    EventBus.send(InventoryEntityAddedEvent(slot, entity))
  }

  /**
    * Called after an entity is removed from the inventory.
    *
    * Note that at the time the method is called, the element is no longer present in the inventory.
    * Therefore, adding it back here effectively cancels entity removal.
    */
  def onEntityRemoved(slot: Slot, entity: Entity): Unit = {
    entity match {
      case e: WorkspaceAware => e.workspace = null
      case _ =>
    }

    EventBus.send(InventoryEntityRemovedEvent(slot, entity))
  }

  override def onWorkspaceChange(newWorkspace: Workspace): Unit = {
    super.onWorkspaceChange(newWorkspace)
    slots.foreach {
      case (_, e: WorkspaceAware) => e.workspace = newWorkspace
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  private final val InventoryTag = "inventory"
  private final val SlotIndexTag = "slot"
  private final val SlotContentsTag = "contents"

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
    super.load(nbt, workspace)
    nbt.getTagList(InventoryTag, NBT.TAG_COMPOUND)
      .iterator[NBTTagCompound]
      .zipWithIndex
      .foreach { case (slotNbt, listIndex) =>
        val (index, contents) = if (!slotNbt.hasKey(SlotIndexTag) || !slotNbt.hasKey(SlotContentsTag)) {
          // Migration code: old saves stored the inventory as a plain array.
          // We use the index in the list as a slot.
          // See https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/issues/27
          (listIndex, slotNbt)
        } else {
          val index = slotNbt.getInteger(SlotIndexTag)
          val contents = slotNbt.getCompoundTag(SlotContentsTag)

          (index, contents)
        }

        NBTPersistence.load(contents, workspace) match {
          case entity: Entity =>
            inventory(index) = entity

          case _ =>
            Ocelot.log.error("Some problems parsing an entity from NBT tag: " + contents)
        }
      }
  }

  override def save(nbt: NBTTagCompound): Unit = {
    super.save(nbt)
    nbt.setNewTagList(InventoryTag,
      slots.map { case (index, entity) =>
        val slotNbt = new NBTTagCompound()
        slotNbt.setInteger(SlotIndexTag, index)
        slotNbt.setTag(SlotContentsTag, NBTPersistence.save(entity))

        slotNbt
      }
    )
  }

  final class Slot private[Inventory](val index: Int) {
    if (index < 0) {
      throw new IllegalArgumentException("negative slot index: " + index)
    }

    def isEmpty: Boolean = get.isEmpty

    def nonEmpty: Boolean = !isEmpty

    def put(entity: Entity): Option[Entity] = inventory.put(index, entity)

    def set(entity: Option[Entity]): Option[Entity] = entity match {
      case Some(entity) => put(entity)
      case None => remove()
    }

    def remove(): Option[Entity] = inventory.remove(index)

    def get: Option[Entity] = slots.get(index)

    def inventory: InventoryProxy = Inventory.this.inventory

    override def equals(other: Any): Boolean = other match {
      case that: Slot =>
        inventory.owner == that.inventory.owner &&
          index == that.index

      case _ => false
    }

    override def hashCode(): Int = {
      val state = Seq(inventory.owner, index)
      state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
    }
  }

  final class InventoryProxy extends Iterable[Slot] {
    val owner: Inventory = Inventory.this

    override def iterator: Iterator[Slot] = slots.keysIterator.map(slot)

    def entities: Iterable[Entity] = entitySlotIndices.view.keys

    def apply(index: Int): Slot = slot(index)

    def update(index: Int, entity: Entity): Unit = slot(index).put(entity)

    def slot(index: Int): Slot = new Slot(index)

    def slot(entity: Entity): Option[Slot] = entitySlotIndices.get(entity).map(slot)

    def clear(): Boolean = {
      if (slots.nonEmpty) {
        // the callback is called AFTER the elements get removed
        val contents = slots.clone()
        slots.clear()
        entitySlotIndices.clear()
        contents.iterator.foreach { case (index, entity) => onEntityRemoved(slot(index), entity) }

        true
      } else false
    }

    private[Inventory] def remove(index: Int): Option[Entity] = {
      slots.remove(index).tapEach(entity => {
        entitySlotIndices.remove(entity)
        onEntityRemoved(slot(index), entity)
      }).headOption
    }

    private[Inventory] def remove(entity: Entity): Option[Entity] = {
      entitySlotIndices.remove(entity) match {
        case Some(index) =>
          slots.remove(index)
          onEntityRemoved(slot(index), entity)

          Some(entity)

        case _ => None
      }
    }

    private[Inventory] def put(index: Int, entity: Entity): Option[Entity] = {
      if (entitySlotIndices.get(entity).contains(index)) {
        return Some(entity)
      }

      val previousEntity = remove(index)
      remove(entity)

      slots += index -> entity
      entitySlotIndices += entity -> index
      onEntityAdded(slot(index), entity)

      previousEntity
    }
  }
}
