package xyz.kd5ujc.schema.fiber

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

sealed trait FiberStatus extends EnumEntry with Uppercase

object FiberStatus extends Enum[FiberStatus] with CirceEnum[FiberStatus] {
  val values: IndexedSeq[FiberStatus] = findValues

  case object Active extends FiberStatus
  case object Archived extends FiberStatus
  case object Failed extends FiberStatus
}
