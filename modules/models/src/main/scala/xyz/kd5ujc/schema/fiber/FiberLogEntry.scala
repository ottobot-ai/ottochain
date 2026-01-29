package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.Records

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
    eventName:      String,
    ordinal:        SnapshotOrdinal,
    fromState:      StateId,
    toState:        StateId,
    success:        Boolean,
    gasUsed:        Long,
    triggersFired:  Int,
    errorMessage:   Option[String] = None,
    sourceFiberId:  Option[UUID] = None,
    emittedEvents:  List[EmittedEvent] = List.empty
  ) extends FiberLogEntry

  object EventReceipt {

    def success(
      sm:            Records.StateMachineFiberRecord,
      eventName:     String,
      ordinal:       SnapshotOrdinal,
      gasUsed:       Long,
      newStateId:    Option[StateId],
      triggers:      List[FiberTrigger],
      sourceFiberId: Option[UUID] = None,
      emittedEvents: List[EmittedEvent] = List.empty
    ): EventReceipt = EventReceipt(
      fiberId = sm.cid,
      sequenceNumber = sm.sequenceNumber + 1,
      eventName = eventName,
      ordinal = ordinal,
      fromState = sm.currentState,
      toState = newStateId.getOrElse(sm.currentState),
      success = true,
      gasUsed = gasUsed,
      triggersFired = triggers.size,
      sourceFiberId = sourceFiberId,
      emittedEvents = emittedEvents
    )

    def failure(
      sm:        Records.StateMachineFiberRecord,
      eventName: String,
      ordinal:   SnapshotOrdinal,
      gasUsed:   Long,
      reason:    FailureReason
    ): EventReceipt = EventReceipt(
      fiberId = sm.cid,
      sequenceNumber = sm.sequenceNumber,
      eventName = eventName,
      ordinal = ordinal,
      fromState = sm.currentState,
      toState = sm.currentState,
      success = false,
      gasUsed = gasUsed,
      triggersFired = 0,
      errorMessage = Some(reason.toMessage)
    )
  }

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
