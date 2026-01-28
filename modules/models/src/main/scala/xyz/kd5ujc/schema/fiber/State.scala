package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class State(
  id:       StateId,
  isFinal:  Boolean = false,
  metadata: Option[JsonLogicValue] = None
)
