package totoro.ocelot.brain.event

import totoro.ocelot.brain.entity.traits.{Entity, Inventory}

case class InventoryEntityRemovedEvent(slot: Inventory#Slot, entity: Entity) extends Event