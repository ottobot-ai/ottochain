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
 */
object EffectExtractor {

  def extractTriggerEvents[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[List[StateMachine.TriggerEvent]] =
    effectResult match {
      case MapValue(map) =>
        map
          .get(ReservedKeys.TRIGGERS)
          .collect { case ArrayValue(triggers) =>
            triggers
              .traverse {
                case MapValue(triggerMap) =>
                  (for {
                    targetIdStr <- OptionT.fromOption[F](triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect {
                      case StrValue(id) => id
                    })
                    targetId <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(targetIdStr)).toOption)
                    eventType <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.EVENT_TYPE).collect { case StrValue(et) =>
                        StateMachine.EventType(et)
                      }
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
              .map(_.flatten)
          }
          .getOrElse(List.empty.pure[F])

      case ArrayValue(updates) =>
        updates
          .collectFirst { case ArrayValue(List(StrValue(ReservedKeys.TRIGGERS), ArrayValue(triggers))) =>
            triggers
              .traverse {
                case MapValue(triggerMap) =>
                  (for {
                    targetId <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect { case StrValue(id) =>
                        UUID.fromString(id)
                      }
                    )
                    eventType <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.EVENT_TYPE).collect { case StrValue(et) =>
                        StateMachine.EventType(et)
                      }
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
              .map(_.flatten)
          }
          .getOrElse(List.empty.pure[F])

      case _ => List.empty.pure[F]
    }

  def extractOracleCall[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[Option[StateMachine.TriggerEvent]] =
    effectResult match {
      case MapValue(map) =>
        map.get(ReservedKeys.ORACLE_CALL) match {
          case Some(MapValue(oracleCallMap)) =>
            (for {
              cidStr   <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.CID).collect { case StrValue(id) => id })
              targetId <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(cidStr)).toOption)
              method <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.METHOD).collect { case StrValue(m) => m })
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

      case _ => none[StateMachine.TriggerEvent].pure[F]
    }

  def extractOutputs(effectResult: JsonLogicValue): List[Records.StructuredOutput] =
    effectResult match {
      case MapValue(map) =>
        map
          .get(ReservedKeys.OUTPUTS)
          .collect { case ArrayValue(outputs) =>
            outputs.flatMap {
              case MapValue(outputMap) =>
                (for {
                  outputType <- outputMap.get(ReservedKeys.OUTPUT_TYPE).collect { case StrValue(t) => t }
                  data       <- outputMap.get(ReservedKeys.DATA)
                  destination = outputMap.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
                } yield Records.StructuredOutput(outputType, data, destination)).toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case ArrayValue(updates) =>
        updates
          .collectFirst { case ArrayValue(List(StrValue(ReservedKeys.OUTPUTS), ArrayValue(outputs))) =>
            outputs.flatMap {
              case MapValue(outputMap) =>
                (for {
                  outputType <- outputMap.get(ReservedKeys.OUTPUT_TYPE).collect { case StrValue(t) => t }
                  data       <- outputMap.get(ReservedKeys.DATA)
                  destination = outputMap.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
                } yield Records.StructuredOutput(outputType, data, destination)).toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  def extractSpawnDirectivesFromExpression(effectExpr: JsonLogicExpression): List[StateMachine.SpawnDirective] =
    effectExpr match {
      case MapExpression(map) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayExpression(spawns) =>
            spawns.flatMap {
              case MapExpression(spawnMap) =>
                (for {
                  childIdExpr <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.CHILD_ID))
                  defExpr     <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.DEFINITION))
                  definition <- OptionT.fromOption[cats.Id](
                    ExpressionParser.parseStateMachineDefinitionFromExpression(defExpr)
                  )
                  initialDataExpr <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.INITIAL_DATA))
                } yield StateMachine.SpawnDirective(
                  childIdExpr = childIdExpr,
                  definition = definition,
                  initialData = initialDataExpr,
                  ownersExpr = spawnMap.get(ReservedKeys.OWNERS)
                )).value.toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case ConstExpression(MapValue(map)) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayValue(spawns) =>
            spawns.flatMap {
              case MapValue(spawnMap) =>
                (for {
                  childIdValue <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.CHILD_ID))
                  defValue     <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.DEFINITION))
                  definition   <- OptionT.fromOption[cats.Id](ExpressionParser.parseStateMachineDefinition(defValue))
                  initialDataValue <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.INITIAL_DATA))
                } yield StateMachine.SpawnDirective(
                  childIdExpr = ConstExpression(childIdValue),
                  definition = definition,
                  initialData = ConstExpression(initialDataValue),
                  ownersExpr = spawnMap.get(ReservedKeys.OWNERS).map(ConstExpression(_))
                )).value.toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }
}
