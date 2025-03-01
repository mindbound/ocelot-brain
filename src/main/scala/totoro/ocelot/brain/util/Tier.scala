package totoro.ocelot.brain.util

import totoro.ocelot.brain.util.ExtendedTier.ExtendedTier

//object Tier {
//  final val None = -1
//  final val One = 0
//  final val Two = 1
//  final val Three = 2
//  final val Four = 3
//  final val Five = 4
//  final val Six = 5
//  final val Any = Int.MaxValue
//}

object Tier extends Enumeration {
  type Tier = TierVal

  val One: Tier = TierVal("1", "tier.one")
  val Two: Tier = TierVal("2", "tier.two")
  val Three: Tier = TierVal("3", "tier.three")
  val Creative: Tier = TierVal("Creative", "tier.creative")

  protected class TierVal(name: String, val label: String) extends super.Val(name) {
    /**
      * The tier number, starting from 1.
      *
      * @see [[id]] if you need the 0-based index
      */
    def num: Int = this match {
      case One => 1
      case Two => 2
      case Three => 3
      case Creative => 4
    }

    def isCreative: Boolean = this == Creative

    def saturatingAdd(n: Int): TierVal = {
      Tier((id + n) max 0 min (maxId - 1))
    }

    def saturatingSub(n: Int): TierVal = saturatingAdd(-n)

    def toExtended(half: Boolean): ExtendedTier = this match {
      case One if half => ExtendedTier.OneHalf
      case One => ExtendedTier.One

      case Two if half => ExtendedTier.TwoHalf
      case Two => ExtendedTier.Two

      case Three if half => ExtendedTier.ThreeHalf
      case Three => ExtendedTier.Three

      case Creative => ExtendedTier.Creative
    }

    def to(end: Tier): IndexedSeq[Tier] =
      (id to end.id).map(Tier(_))
  }

  private object TierVal {
    def apply(name: String, label: String): TierVal = new TierVal(name, label)
    def apply(name: String): TierVal = new TierVal(name, name)
  }

  import scala.language.implicitConversions
  implicit def valueToTierVal(x: Value): TierVal = x.asInstanceOf[TierVal]
}
