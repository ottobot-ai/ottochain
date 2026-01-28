package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class SpawnDirective(
  childIdExpr: JsonLogicExpression,
  definition:  StateMachineDefinition,
  initialData: JsonLogicExpression,
  ownersExpr:  Option[JsonLogicExpression] = None
)
