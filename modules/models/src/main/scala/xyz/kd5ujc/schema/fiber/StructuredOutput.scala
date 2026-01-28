package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
final case class StructuredOutput(
  outputType:  String,
  data:        JsonLogicValue,
  destination: Option[String] = None
)
