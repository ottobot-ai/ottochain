package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/**
 * Supertype for log entries emitted by fibers.
 *
 * State machines emit EventReceipt entries.
 * Script oracles emit OracleInvocation entries.
 *
 * These are collected per-ordinal in OnChain.latestLogs for external signaling.
 */
@derive(encoder, decoder)
sealed trait FiberLogEntry {
  def fiberId: UUID
}

object FiberLogEntry {

  @derive(encoder, decoder)
  final case class EventReceipt(
    fiberId:        UUID,
    sequenceNumber: Long,
    eventType:      EventType,
    ordinal:        SnapshotOrdinal,
    fromState:      StateId,
    toState:        StateId,
    success:        Boolean,
    gasUsed:        Long,
    triggersFired:  Int,
    outputs:        List[StructuredOutput] = List.empty,
    errorMessage:   Option[String] = None,
    sourceFiberId:  Option[UUID] = None
  ) extends FiberLogEntry

  @derive(encoder, decoder)
  final case class OracleInvocation(
    fiberId:   UUID,
    method:    String,
    args:      JsonLogicValue,
    result:    JsonLogicValue,
    gasUsed:   Long,
    invokedAt: SnapshotOrdinal,
    invokedBy: Address
  ) extends FiberLogEntry
}
