package totoro.ocelot.brain.entity

import totoro.ocelot.brain.entity.traits.DeviceInfo.{DeviceAttribute, DeviceClass}
import totoro.ocelot.brain.entity.traits.{DeviceInfo, Entity, Environment}
import totoro.ocelot.brain.network.{Component, Message, Network, Visibility}
import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.{Constants, Settings}

import scala.collection.mutable

class Keyboard extends Entity with Environment with DeviceInfo {
  override val node: Component = Network.newNode(this, Visibility.Network).
    withComponent("keyboard").
    create()

  val pressedKeys = mutable.Map.empty[User, mutable.Map[Integer, Character]]

  // ----------------------------------------------------------------------- //

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Input,
    DeviceAttribute.Description -> "Keyboard",
    DeviceAttribute.Vendor -> Constants.DeviceInfo.DefaultVendor,
    DeviceAttribute.Product -> "Fancytyper MX-Paws"
  )

  override def getDeviceInfo: Map[String, String] = deviceInfo

  // ----------------------------------------------------------------------- //

  def releasePressedKeys(player: User): Unit = {
    pressedKeys.get(player) match {
      case Some(keys) => for ((code, char) <- keys) {
        if (Settings.get.inputUsername) {
          signal(player, "key_up", char, code, player.nickname)
        }
        else {
          signal(player, "key_up", char, code)
        }
      }
      case _ =>
    }
    pressedKeys.remove(player)
  }

  // ----------------------------------------------------------------------- //

  override def onMessage(message: Message): Unit = {
    message.data match {
      case Array(p: User, char: Character, code: Integer) if message.name == "keyboard.keyDown" =>
        pressedKeys.getOrElseUpdate(p, mutable.Map.empty[Integer, Character]) += code -> char
        if (Settings.get.inputUsername) {
          signal(p, "key_down", char, code, p.nickname)
        }
        else {
          signal(p, "key_down", char, code)
        }
      case Array(p: User, char: Character, code: Integer) if message.name == "keyboard.keyUp" =>
        pressedKeys.get(p) match {
          case Some(keys) if keys.contains(code) =>
            keys -= code
            if (Settings.get.inputUsername) {
              signal(p, "key_up", char, code, p.nickname)
            }
            else {
              signal(p, "key_up", char, code)
            }
          case _ =>
        }
      case Array(p: User, codePt: Integer) if message.name == "keyboard.textInput" =>
        if (Settings.get.inputUsername) {
          signal(p, "text_input", new String(Character.toChars(codePt)), p.nickname)
        }
        else {
          signal(p, "text_input", new String(Character.toChars(codePt)))
        }
      case Array(p: User, value: String) if message.name == "keyboard.clipboard" =>
        for (line <- value.linesWithSeparators) {
          if (Settings.get.inputUsername) {
            signal(p, "clipboard", line, p.nickname)
          }
          else {
            signal(p, "clipboard", line)
          }
        }
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  protected def signal(args: AnyRef*): Unit =
    node.sendToReachable("computer.checked_signal", args: _*)
}
