package xyz.kd5ujc.schema.fiber

import enumeratum._
import enumeratum.EnumEntry.Uppercase

sealed trait InputKind extends EnumEntry with Uppercase

object InputKind extends Enum[InputKind] with CirceEnum[InputKind] {
  case object Transition extends InputKind
  case object MethodCall extends InputKind

  val values: IndexedSeq[InputKind] = findValues
}
