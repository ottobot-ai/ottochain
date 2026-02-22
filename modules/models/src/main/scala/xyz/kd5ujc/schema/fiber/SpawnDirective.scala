package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder)
case class SpawnDirective(
  childIdExpr: JsonLogicExpression,
  definition:  StateMachineDefinition,
  initialData: JsonLogicExpression,
  ownersExpr:  Option[JsonLogicExpression] = None
)
