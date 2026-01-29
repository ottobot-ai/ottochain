package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class Transition(
  from:         StateId,
  to:           StateId,
  eventName:    String,
  guard:        JsonLogicExpression, // Guard condition
  effect:       JsonLogicExpression, // State transformation
  dependencies: Set[UUID] = Set.empty // Other machines this transition reads from
)
