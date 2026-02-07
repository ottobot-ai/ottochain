package xyz.kd5ujc.schema.oracle

import java.util.UUID

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.fiber.FiberOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.refined._

@derive(encoder, decoder)
final case class SlashingEvent(
  ordinal: SnapshotOrdinal,
  reason:  SlashingReason,
  amount:  NonNegLong
)

@derive(encoder, decoder)
final case class OracleFiberRecord(
  fiberId:             UUID,
  address:             Address,
  stake:               NonNegLong,
  reputation:          NonNegLong,
  accuracy:            Double,
  marketsResolved:     NonNegLong,
  disputeRate:         Double,
  domains:             Set[String],
  slashingHistory:     List[SlashingEvent],
  status:              OracleState,
  creationOrdinal:     SnapshotOrdinal,
  latestUpdateOrdinal: SnapshotOrdinal,
  sequenceNumber:      FiberOrdinal
)
