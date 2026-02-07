package xyz.kd5ujc.schema.market

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.fiber.FiberOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.refined._

@derive(encoder, decoder)
final case class MarketFiberRecord(
  fiberId:             UUID,
  marketType:          MarketType,
  creator:             Address,
  title:               String,
  terms:               JsonLogicValue,
  deadline:            SnapshotOrdinal,
  threshold:           NonNegLong,
  commitments:         Map[Address, NonNegLong],
  oracles:             Set[Address],
  quorum:              Int,
  resolutions:         Map[Address, JsonLogicValue],
  status:              MarketState,
  totalCommitted:      NonNegLong,
  claims:              Map[Address, NonNegLong],
  creationOrdinal:     SnapshotOrdinal,
  latestUpdateOrdinal: SnapshotOrdinal,
  sequenceNumber:      FiberOrdinal
)
