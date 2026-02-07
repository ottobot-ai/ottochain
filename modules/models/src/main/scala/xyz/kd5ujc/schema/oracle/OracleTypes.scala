package xyz.kd5ujc.schema.oracle

import enumeratum.{CirceEnum, Enum, EnumEntry}

sealed trait OracleState extends EnumEntry

object OracleState extends Enum[OracleState] with CirceEnum[OracleState] {
  val values: IndexedSeq[OracleState] = findValues

  case object Unregistered extends OracleState
  case object Registered extends OracleState
  case object Active extends OracleState
  case object Slashed extends OracleState
  case object Withdrawn extends OracleState
}

sealed trait SlashingReason extends EnumEntry

object SlashingReason extends Enum[SlashingReason] with CirceEnum[SlashingReason] {
  val values: IndexedSeq[SlashingReason] = findValues

  case object FalseResolution extends SlashingReason
  case object Collusion extends SlashingReason
  case object Inactivity extends SlashingReason
  case object MaliciousBehavior extends SlashingReason
}
