package xyz.kd5ujc.schema.fiber

import enumeratum._

sealed trait InputKind extends EnumEntry

object InputKind extends Enum[InputKind] with CirceEnum[InputKind] {
  case object Transition extends InputKind
  case object MethodCall extends InputKind

  val values: IndexedSeq[InputKind] = findValues
}
