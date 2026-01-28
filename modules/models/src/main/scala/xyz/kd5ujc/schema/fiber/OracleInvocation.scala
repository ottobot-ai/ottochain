package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
final case class OracleInvocation(
  method:    String,
  args:      JsonLogicValue,
  result:    JsonLogicValue,
  gasUsed:   Long,
  invokedAt: SnapshotOrdinal,
  invokedBy: Address
)
