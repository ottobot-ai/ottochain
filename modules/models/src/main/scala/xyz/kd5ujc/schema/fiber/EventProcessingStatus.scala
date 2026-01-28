package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.schema.SnapshotOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait EventProcessingStatus

object EventProcessingStatus {

  final case class Success(
    sequenceNumber: Long,
    processedAt:    SnapshotOrdinal
  ) extends EventProcessingStatus

  final case class GuardFailed(
    reason:             String,
    attemptedAt:        SnapshotOrdinal,
    attemptedEventType: EventType
  ) extends EventProcessingStatus

  final case class ExecutionFailed(
    reason:             String,
    attemptedAt:        SnapshotOrdinal,
    attemptedEventType: EventType,
    gasUsed:            Long,
    depth:              Int
  ) extends EventProcessingStatus

  final case class ValidationFailed(
    reason:      String,
    attemptedAt: SnapshotOrdinal
  ) extends EventProcessingStatus

  final case class DepthExceeded(
    attemptedAt: SnapshotOrdinal,
    depth:       Int,
    maxDepth:    Int,
    gasUsed:     Long
  ) extends EventProcessingStatus

  final case class GasExhausted(
    attemptedAt: SnapshotOrdinal,
    gasUsed:     Long,
    gasLimit:    Long,
    depth:       Int
  ) extends EventProcessingStatus

  case object Initialized extends EventProcessingStatus
}
