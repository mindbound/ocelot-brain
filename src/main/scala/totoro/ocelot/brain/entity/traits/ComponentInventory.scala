package totoro.ocelot.brain.entity.traits

import totoro.ocelot.brain.network.Node

/**
  * Takes care of properly connecting, updating and disconnecting
  * the components in the inventory
  */
trait ComponentInventory extends Inventory with Environment with Entity {
  override def initialize(): Unit = {
    super.initialize()
    connectComponents()
  }

  override def update(): Unit = {
    super.update()
    inventory.entities.foreach { _.update() }
  }

  override def dispose(): Unit = {
    super.dispose()
    disconnectComponents()
  }

  override def onEntityAdded(slot: Slot, entity: Entity): Unit = {
    super.onEntityAdded(slot, entity)

    entity match {
      case environment: Environment =>
        node.connect(environment.node)
    }
  }

  override def onEntityRemoved(slot: Slot, entity: Entity): Unit = {
    entity match {
      case environment: Environment =>
        environment.node.remove()
    }

    super.onEntityRemoved(slot, entity)
  }

  override def onConnect(node: Node): Unit = {
    super.onConnect(node)
    if (node == this.node) {
      connectComponents()
    }
  }

  override def onDisconnect(node: Node): Unit = {
    super.onDisconnect(node)
    if (node == this.node) {
      disconnectComponents()
    }
  }

  private def connectComponents(): Unit = {
    inventory.entities.foreach {
      case environment: Environment => node.connect(environment.node)
    }
  }

  private def disconnectComponents(): Unit = {
    inventory.entities.foreach {
      case environment: Environment => environment.node.remove()
    }
  }
}
