package totoro.ocelot.brain.entity.machine.luac

import java.nio.charset.StandardCharsets
import scala.collection.SeqMap

case class LuaUpvalue(id: Long, name: Option[String], value: LuaValue)

abstract sealed class LuaValue

object LuaValue {
  abstract sealed class Nil extends LuaValue
  case object Nil extends Nil

  case class Boolean(value: scala.Boolean) extends LuaValue

  sealed abstract class Number extends LuaValue {
    def toLong: Long

    def toDouble: Double
  }

  object Number {
    case class Integer(value: Long) extends Number {
      def toLong: Long = value

      def toDouble: scala.Double = value.toDouble
    }

    case class Double(value: scala.Double) extends Number {
      def toLong: Long = value.toLong

      def toDouble: scala.Double = value
    }
  }

  case class String(value: List[Byte]) extends LuaValue {
    def toUtf8String: scala.Predef.String = new scala.Predef.String(value.toArray, StandardCharsets.UTF_8)
  }

  sealed abstract class Function(val address: Long) extends LuaValue

  object Function {
    case class Owned(override val address: Long,
                     info: Info,
                     source: Option[List[Byte]],
                     paramNames: List[scala.Predef.String],
                     upvalues: List[LuaUpvalue]) extends Function(address)

    case class Recursion(override val address: Long) extends Function(address)

    case class Info(nameWhat: Option[scala.Predef.String],
                    name: Option[scala.Predef.String],
                    what: Info.What,
                    source: Info.Source,
                    lines: Option[(Long, Long)],
                    upvalueCount: Int,
                    paramCount: Int,
                    vararg: scala.Boolean,
                    shortSource: scala.Predef.String)

    object Info {
      abstract class What

      object What {
        case object Lua extends What {
          override def toString: scala.Predef.String = "Lua"
        }

        case object C extends What {
          override def toString: scala.Predef.String = "C"
        }

        case object Main extends What {
          override def toString: scala.Predef.String = "main"
        }
      }

      abstract class Source

      object Source {
        case class File(name: LuaValue.String) extends Source
        case class String(code: LuaValue.String) extends Source
        case class Other(source: LuaValue.String) extends Source
      }
    }
  }

  case class Userdata(address: Long) extends LuaValue

  sealed abstract class Thread(val address: Long, val state: Thread.State) extends LuaValue

  object Thread {
    sealed abstract class State

    object State {
      case object Running extends State
      case object Suspended extends State
      case object Normal extends State
      case object Dead extends State
    }

    case class Recursion(override val address: Long, override val state: State) extends Thread(address, state)

    case class Owned(override val address: Long, override val state: State, stack: List[LuaStackFrame])
      extends Thread(address, state)
  }

  sealed abstract class Table(val address: Long) extends LuaValue

  object Table {
    case class Recursion(override val address: Long) extends Table(address)

    case class Owned(override val address: Long,
                     array: List[LuaValue],
                     map: SeqMap[LuaValue, LuaValue]) extends Table(address) {
      def pairs: Iterator[(LuaValue, LuaValue)] =
        array.iterator.zipWithIndex.map {
          case (v, i) => (LuaValue.Number.Integer(i + 1), v)
        } ++ map.iterator
    }
  }
}