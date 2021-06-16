package totoro.ocelot.brain.entity.machine.luac

import java.nio.charset.StandardCharsets

sealed abstract class LuaNumber {
  def toLong: Long

  def toDouble: Double
}

object LuaNumber {
  case class Integer(value: Long) extends LuaNumber {
    def toLong: Long = value

    def toDouble: scala.Double = value.toDouble
  }

  case class Double(value: scala.Double) extends LuaNumber {
    def toLong: Long = value.toLong

    def toDouble: scala.Double = value
  }
}

sealed abstract class LuaValue

object LuaValue {
  case class Nil() extends LuaValue

  case class Boolean(value: scala.Boolean) extends LuaValue

  case class Number(value: LuaNumber) extends LuaValue

  case class String(value: List[Byte]) extends LuaValue {
    def toUtf8String: scala.Predef.String = new scala.Predef.String(value.toArray, StandardCharsets.UTF_8)
  }

  case class Function(address: Long) extends LuaValue

  case class Userdata(address: Long) extends LuaValue

  case class Thread(address: Long) extends LuaValue

  case class Table(value: Map[LuaValue, LuaValue]) extends LuaValue
}