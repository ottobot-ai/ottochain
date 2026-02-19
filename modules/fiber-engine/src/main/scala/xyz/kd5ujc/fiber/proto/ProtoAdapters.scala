package xyz.kd5ujc.fiber.proto

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.FiberStatus

import ottochain.v1.{
  FiberStatus => ProtoFiberStatus,
  ScriptFiberRecord => ProtoScriptRecord,
  StateMachineFiberRecord => ProtoSMRecord
}

/**
 * Bidirectional converters between proto-generated (ScalaPB) types and
 * the fiber engine's rich runtime types.
 *
 * Use these at persistence/network boundaries where proto wire format is needed.
 *
 * Design notes:
 * - Proto types use String for fiber IDs; runtime types use UUID
 * - Proto types use Long for ordinals; runtime types use NonNegLong (refined)
 * - Proto types use google.protobuf.Value for state data; runtime uses JsonLogicValue
 * - Full bidirectional conversion (fromProto) requires JSON-parsing for data fields
 *   and is left as a future enhancement. Currently we only provide toProto.
 */
object ProtoAdapters {

  // ─────────────────────────────────────────────
  // FiberStatus conversions
  // ─────────────────────────────────────────────

  def toProtoFiberStatus(status: FiberStatus): ProtoFiberStatus = status match {
    case FiberStatus.Active   => ProtoFiberStatus.FIBER_STATUS_ACTIVE
    case FiberStatus.Archived => ProtoFiberStatus.FIBER_STATUS_ARCHIVED
    case FiberStatus.Failed   => ProtoFiberStatus.FIBER_STATUS_FAILED
  }

  def fromProtoFiberStatus(status: ProtoFiberStatus): Option[FiberStatus] = status match {
    case ProtoFiberStatus.FIBER_STATUS_ACTIVE   => Some(FiberStatus.Active)
    case ProtoFiberStatus.FIBER_STATUS_ARCHIVED => Some(FiberStatus.Archived)
    case ProtoFiberStatus.FIBER_STATUS_FAILED   => Some(FiberStatus.Failed)
    case _                                      => None
  }

  // ─────────────────────────────────────────────
  // StateMachineFiberRecord → Proto
  // ─────────────────────────────────────────────

  def toProtoSMRecord(record: Records.StateMachineFiberRecord): ProtoSMRecord =
    ProtoSMRecord(
      fiberId = record.fiberId.toString,
      creationOrdinal = Some(ottochain.v1.SnapshotOrdinal(record.creationOrdinal.value.value)),
      previousUpdateOrdinal = Some(ottochain.v1.SnapshotOrdinal(record.previousUpdateOrdinal.value.value)),
      latestUpdateOrdinal = Some(ottochain.v1.SnapshotOrdinal(record.latestUpdateOrdinal.value.value)),
      currentState = Some(ottochain.v1.StateId(record.currentState.value)),
      stateDataHash = Some(ottochain.v1.HashValue(record.stateDataHash.value)),
      sequenceNumber = Some(ottochain.v1.FiberOrdinal(record.sequenceNumber.value.value)),
      owners = record.owners.map(a => ottochain.v1.Address(a.value.value)).toSeq,
      status = toProtoFiberStatus(record.status),
      parentFiberId = record.parentFiberId.map(_.toString),
      childFiberIds = record.childFiberIds.map(_.toString).toSeq
    )

  // ─────────────────────────────────────────────
  // ScriptFiberRecord → Proto
  // ─────────────────────────────────────────────

  def toProtoScriptRecord(record: Records.ScriptFiberRecord): ProtoScriptRecord =
    ProtoScriptRecord(
      fiberId = record.fiberId.toString,
      creationOrdinal = Some(ottochain.v1.SnapshotOrdinal(record.creationOrdinal.value.value)),
      latestUpdateOrdinal = Some(ottochain.v1.SnapshotOrdinal(record.latestUpdateOrdinal.value.value)),
      sequenceNumber = Some(ottochain.v1.FiberOrdinal(record.sequenceNumber.value.value)),
      owners = record.owners.map(a => ottochain.v1.Address(a.value.value)).toSeq,
      status = toProtoFiberStatus(record.status)
    )
}
