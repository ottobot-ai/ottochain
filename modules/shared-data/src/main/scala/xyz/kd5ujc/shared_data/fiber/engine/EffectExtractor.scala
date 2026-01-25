package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator

import xyz.kd5ujc.schema.{Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain.ReservedKeys

/**
 * Extracts side effects from transition effect results.
 *
 * Effect results can contain special keys that signal side effects:
 * - _triggers: Cross-machine event triggers
 * - _oracleCall: Oracle invocation
 * - _outputs: External system outputs
 * - _spawn: Child machine creation
 *
 * Supports two result formats:
 * - MapValue: Direct key lookup (e.g., `{"_triggers": [...]}`)
 * - ArrayValue: Tuple format (e.g., `[["_triggers", [...]]]`)
 */
object EffectExtractor {

  /**
   * Extract a value from an effect result by reserved key.
   * Handles both MapValue (direct key) and ArrayValue (tuple format) representations.
   */
  private def extractByKey(effectResult: JsonLogicValue, key: String): Option[JsonLogicValue] =
    effectResult match {
      case MapValue(map) =>
        map.get(key)

      case ArrayValue(updates) =>
        updates.collectFirst {
          case ArrayValue(List(StrValue(k), value)) if k == key =>
            value
        }

      case _ => None
    }

  /**
   * Extract an array value from an effect result by reserved key.
   * Returns empty list if key not found or value is not an array.
   */
  private def extractArrayByKey(effectResult: JsonLogicValue, key: String): List[JsonLogicValue] =
    extractByKey(effectResult, key).collect { case ArrayValue(items) => items }.getOrElse(List.empty)

  /**
   * Process a list of JsonLogicValues, extracting items of type A.
   * Effectfully maps over items and flattens the results.
   */
  private def extractFromArray[F[_]: Async, A](
    items:     List[JsonLogicValue],
    extractor: JsonLogicValue => F[Option[A]]
  ): F[List[A]] =
    items.traverse(extractor).map(_.flatten)

  /**
   * Process a list of JsonLogicValues, extracting items of type A (pure version).
   */
  private def extractFromArrayPure[A](
    items:     List[JsonLogicValue],
    extractor: JsonLogicValue => Option[A]
  ): List[A] =
    items.flatMap(extractor)

  def extractTriggerEvents[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[List[StateMachine.TriggerEvent]] = {
    val triggers = extractArrayByKey(effectResult, ReservedKeys.TRIGGERS)
    extractFromArray(triggers, parseTriggerEvent[F](_, contextData))
  }

  private def parseTriggerEvent[F[_]: Async: JsonLogicEvaluator](
    value:       JsonLogicValue,
    contextData: JsonLogicValue
  ): F[Option[StateMachine.TriggerEvent]] =
    value match {
      case MapValue(triggerMap) =>
        (for {
          targetIdStr <- OptionT.fromOption[F](
            triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect { case StrValue(id) => id }
          )
          targetId <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(targetIdStr)).toOption)
          eventType <- OptionT.fromOption[F](
            triggerMap.get(ReservedKeys.EVENT_TYPE).collect { case StrValue(et) => StateMachine.EventType(et) }
          )
          payloadValue <- OptionT.fromOption[F](triggerMap.get(ReservedKeys.PAYLOAD))
          payloadExpr = ExpressionParser.valueToExpression(payloadValue)
          evaluatedPayload <- OptionT(
            JsonLogicEvaluator.tailRecursive[F].evaluate(payloadExpr, contextData, None).map(_.toOption)
          )
        } yield StateMachine.TriggerEvent(
          targetMachineId = targetId,
          eventType = eventType,
          payloadExpr = ConstExpression(evaluatedPayload)
        )).value
      case _ => none[StateMachine.TriggerEvent].pure[F]
    }

  def extractOracleCall[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[Option[StateMachine.TriggerEvent]] =
    extractByKey(effectResult, ReservedKeys.ORACLE_CALL) match {
      case Some(MapValue(oracleCallMap)) =>
        (for {
          cidStr    <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.CID).collect { case StrValue(id) => id })
          targetId  <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(cidStr)).toOption)
          method    <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.METHOD).collect { case StrValue(m) => m })
          argsValue <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.ARGS))
          argsExpr = ExpressionParser.valueToExpression(argsValue)
          evaluatedArgs <- OptionT(
            JsonLogicEvaluator.tailRecursive[F].evaluate(argsExpr, contextData, None).map(_.toOption)
          )
        } yield StateMachine.TriggerEvent(
          targetMachineId = targetId,
          eventType = StateMachine.EventType(method),
          payloadExpr = ConstExpression(evaluatedArgs)
        )).value
      case _ => none[StateMachine.TriggerEvent].pure[F]
    }

  def extractOutputs(effectResult: JsonLogicValue): List[Records.StructuredOutput] = {
    val outputs = extractArrayByKey(effectResult, ReservedKeys.OUTPUTS)
    extractFromArrayPure(outputs, parseOutput)
  }

  private def parseOutput(value: JsonLogicValue): Option[Records.StructuredOutput] =
    value match {
      case MapValue(outputMap) =>
        for {
          outputType <- outputMap.get(ReservedKeys.OUTPUT_TYPE).collect { case StrValue(t) => t }
          data       <- outputMap.get(ReservedKeys.DATA)
          destination = outputMap.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
        } yield Records.StructuredOutput(outputType, data, destination)
      case _ => None
    }

  def extractSpawnDirectivesFromExpression(effectExpr: JsonLogicExpression): List[StateMachine.SpawnDirective] =
    effectExpr match {
      case MapExpression(map) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayExpression(spawns) =>
            spawns.flatMap(parseSpawnFromExpression)
          }
          .getOrElse(List.empty)

      case ConstExpression(MapValue(map)) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayValue(spawns) =>
            spawns.flatMap(parseSpawnFromValue)
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  private def parseSpawnFromExpression(expr: JsonLogicExpression): Option[StateMachine.SpawnDirective] =
    expr match {
      case MapExpression(spawnMap) =>
        for {
          childIdExpr     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defExpr         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition      <- ExpressionParser.parseStateMachineDefinitionFromExpression(defExpr)
          initialDataExpr <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield StateMachine.SpawnDirective(
          childIdExpr = childIdExpr,
          definition = definition,
          initialData = initialDataExpr,
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS)
        )
      case _ => None
    }

  private def parseSpawnFromValue(value: JsonLogicValue): Option[StateMachine.SpawnDirective] =
    value match {
      case MapValue(spawnMap) =>
        for {
          childIdValue     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defValue         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition       <- ExpressionParser.parseStateMachineDefinition(defValue)
          initialDataValue <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield StateMachine.SpawnDirective(
          childIdExpr = ConstExpression(childIdValue),
          definition = definition,
          initialData = ConstExpression(initialDataValue),
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS).map(ConstExpression(_))
        )
      case _ => None
    }
}
