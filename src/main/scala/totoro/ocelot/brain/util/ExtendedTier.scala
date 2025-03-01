package totoro.ocelot.brain.util

import totoro.ocelot.brain.util.Tier.Tier

object ExtendedTier extends Enumeration {
  type ExtendedTier = ExtendedTierVal

  val One: ExtendedTier = ExtendedTierVal("1", "tier.one")
  val OneHalf: ExtendedTier = ExtendedTierVal("1.5", "tier.oneHalf")
  val Two: ExtendedTier = ExtendedTierVal("2", "tier.two")
  val TwoHalf: ExtendedTier = ExtendedTierVal("2.5", "tier.twoHalf")
  val Three: ExtendedTier = ExtendedTierVal("3", "tier.three")
  val ThreeHalf: ExtendedTier = ExtendedTierVal("3.5", "tier.threeHalf")
  val Creative: ExtendedTier = ExtendedTierVal("Creative", "tier.creative")

  protected class ExtendedTierVal(name: String, val label: String) extends super.Val(name) {
    def toTier: Tier = this match {
      case One | OneHalf => Tier.One
      case Two | TwoHalf => Tier.Two
      case Three | ThreeHalf => Tier.Three
      case Creative => Tier.Creative
    }

    def to(end: ExtendedTier): IndexedSeq[ExtendedTier] =
      (id to end.id).map(ExtendedTier(_))
  }

  private object ExtendedTierVal {
    def apply(name: String, label: String): ExtendedTierVal = new ExtendedTierVal(name, label)
    def apply(name: String): ExtendedTierVal = new ExtendedTierVal(name, name)
  }

  import scala.language.implicitConversions
  implicit def valueToExtendedTierVal(x: Value): ExtendedTierVal = x.asInstanceOf[ExtendedTierVal]
}
