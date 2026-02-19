package xyz.kd5ujc.schema.fiber

import enumeratum.{CirceEnum, Enum, EnumEntry}

/**
 * Fiber lifecycle status.
 *
 * @deprecated Prefer `ottochain.v1.FiberStatus` (ScalaPB-generated). Use
 *             `xyz.kd5ujc.fiber.proto.ProtoAdapters.toProtoFiberStatus` for conversion.
 *             This type will be removed once the Constellation DataCalculatedState
 *             interface is updated to use proto types.
 */
sealed trait FiberStatus extends EnumEntry

object FiberStatus extends Enum[FiberStatus] with CirceEnum[FiberStatus] {
  val values: IndexedSeq[FiberStatus] = findValues

  case object Active extends FiberStatus
  case object Archived extends FiberStatus
  case object Failed extends FiberStatus
}
