package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.schema.SnapshotOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
final case class EventReceipt(
  sequenceNumber: Long,
  eventType:      EventType,
  ordinal:        SnapshotOrdinal,
  fromState:      StateId,
  toState:        StateId,
  success:        Boolean,
  gasUsed:        Long,
  triggersFired:  Int,
  outputs:        List[StructuredOutput] = List.empty,
  errorMessage:   Option[String] = None
)
