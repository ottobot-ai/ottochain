package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.data.OptionT
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.StateMachine
import xyz.kd5ujc.shared_data.fiber.domain.ReservedKeys

/**
 * Parses JsonLogicExpression and JsonLogicValue into domain types.
 *
 * Provides bidirectional conversion between:
 * - JsonLogicExpression ↔ StateMachineDefinition, Transition, State
 * - JsonLogicValue ↔ same domain types
 * - JsonLogicValue → JsonLogicExpression (valueToExpression)
 */
object ExpressionParser {

  def parseStateMachineDefinitionFromExpression(
    defExpr: JsonLogicExpression
  ): Option[StateMachine.StateMachineDefinition] =
    defExpr match {
      case MapExpression(defMap) =>
        (for {
          statesExpr       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states           <- OptionT.fromOption[cats.Id](parseStatesFromExpression(statesExpr))
          initialStateExpr <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState     <- OptionT.fromOption[cats.Id](parseInitialState(initialStateExpr))
          transitionsExpr  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions      <- OptionT.fromOption[cats.Id](parseTransitionsFromExpression(transitionsExpr))
        } yield {
          val metadata = defMap.get(ReservedKeys.METADATA).flatMap {
            case ConstExpression(v) => v.some
            case _                  => none
          }
          StateMachine.StateMachineDefinition(states, initialState, transitions, metadata)
        }).value

      case ConstExpression(v) =>
        parseStateMachineDefinition(v)

      case _ => none
    }

  def parseInitialState(expr: JsonLogicExpression): Option[StateMachine.StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
          case _                            => none
        }
      case _ => none
    }

  def parseStatesFromExpression(
    statesExpr: JsonLogicExpression
  ): Option[Map[StateMachine.StateId, StateMachine.State]] =
    statesExpr match {
      case MapExpression(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateExpr) =>
            stateExpr match {
              case MapExpression(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .flatMap {
                    case ConstExpression(BoolValue(b)) => b.some
                    case _                             => none
                  }
                  .getOrElse(false)
                val metadata = stateMap.get(ReservedKeys.METADATA).flatMap {
                  case ConstExpression(v) => v.some
                  case _                  => none
                }
                (StateMachine.StateId(stateId) -> StateMachine.State(
                  id = StateMachine.StateId(stateId),
                  isFinal = isFinal,
                  metadata = metadata
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  def parseTransitionsFromExpression(
    transitionsExpr: JsonLogicExpression
  ): Option[List[StateMachine.Transition]] =
    transitionsExpr match {
      case ArrayExpression(transitionsList) =>
        transitionsList.traverse {
          case MapExpression(transMap) =>
            (for {
              fromExpr      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from          <- OptionT.fromOption[cats.Id](parseStateId(fromExpr))
              toExpr        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to            <- OptionT.fromOption[cats.Id](parseStateId(toExpr))
              eventTypeExpr <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_TYPE))
              eventType     <- OptionT.fromOption[cats.Id](parseEventType(eventTypeExpr))
              guard         <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effect        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
            } yield {
              val dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .flatMap {
                  case ArrayExpression(deps) =>
                    deps
                      .flatMap {
                        case ConstExpression(StrValue(id)) => scala.util.Try(UUID.fromString(id)).toOption
                        case _                             => none
                      }
                      .toSet
                      .some
                  case _ => none
                }
                .getOrElse(Set.empty)

              StateMachine.Transition(from, to, eventType, guard, effect, dependencies)
            }).value
          case _ => none
        }
      case _ => none
    }

  def parseStateId(expr: JsonLogicExpression): Option[StateMachine.StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
          case _                            => none
        }
      case _ => none
    }

  def parseEventType(expr: JsonLogicExpression): Option[StateMachine.EventType] =
    expr match {
      case ConstExpression(StrValue(et)) => StateMachine.EventType(et).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(et)) => StateMachine.EventType(et).some
          case _                             => none
        }
      case _ => none
    }

  def parseStateMachineDefinition(defValue: JsonLogicValue): Option[StateMachine.StateMachineDefinition] =
    defValue match {
      case MapValue(defMap) =>
        (for {
          statesValue       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states            <- OptionT.fromOption[cats.Id](parseStates(statesValue))
          initialStateValue <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState      <- OptionT.fromOption[cats.Id](parseInitialStateValue(initialStateValue))
          transitionsValue  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions       <- OptionT.fromOption[cats.Id](parseTransitions(transitionsValue))
        } yield StateMachine.StateMachineDefinition(
          states = states,
          initialState = initialState,
          transitions = transitions,
          metadata = defMap.get(ReservedKeys.METADATA)
        )).value
      case _ => none
    }

  def parseInitialStateValue(value: JsonLogicValue): Option[StateMachine.StateId] =
    value match {
      case StrValue(s) => StateMachine.StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateMachine.StateId(s) }
      case _           => none
    }

  def valueToExpression(value: JsonLogicValue): JsonLogicExpression = value match {
    case MapValue(m) if m.size == 1 && m.contains(ReservedKeys.VAR) =>
      m.get(ReservedKeys.VAR) match {
        case Some(StrValue(path))                               => VarExpression(Left(path), none)
        case Some(ArrayValue(List(StrValue(path), defaultVal))) => VarExpression(Left(path), defaultVal.some)
        case Some(ArrayValue(List(StrValue(path))))             => VarExpression(Left(path), none)
        case Some(other)                                        => VarExpression(Right(valueToExpression(other)), none)
        case None                                               => ConstExpression(value)
      }
    case MapValue(m)   => MapExpression(m.view.mapValues(valueToExpression).toMap)
    case ArrayValue(a) => ArrayExpression(a.map(valueToExpression))
    case other         => ConstExpression(other)
  }

  def parseStates(statesValue: JsonLogicValue): Option[Map[StateMachine.StateId, StateMachine.State]] =
    statesValue match {
      case MapValue(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateValue) =>
            stateValue match {
              case MapValue(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .collect { case BoolValue(b) =>
                    b
                  }
                  .getOrElse(false)
                (StateMachine.StateId(stateId) -> StateMachine.State(
                  id = StateMachine.StateId(stateId),
                  isFinal = isFinal,
                  metadata = stateMap.get(ReservedKeys.METADATA)
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  def parseTransitions(transitionsValue: JsonLogicValue): Option[List[StateMachine.Transition]] =
    transitionsValue match {
      case ArrayValue(transitionsList) =>
        transitionsList.traverse {
          case MapValue(transMap) =>
            (for {
              fromValue      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from           <- OptionT.fromOption[cats.Id](parseStateIdValue(fromValue))
              toValue        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to             <- OptionT.fromOption[cats.Id](parseStateIdValue(toValue))
              eventTypeValue <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_TYPE))
              eventType      <- OptionT.fromOption[cats.Id](parseEventTypeValue(eventTypeValue))
              guardValue     <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effectValue    <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
            } yield {
              val dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .collect { case ArrayValue(deps) =>
                  deps.flatMap {
                    case StrValue(id) => scala.util.Try(UUID.fromString(id)).toOption
                    case _            => none
                  }.toSet
                }
                .getOrElse(Set.empty)

              StateMachine.Transition(
                from = from,
                to = to,
                eventType = eventType,
                guard = valueToExpression(guardValue),
                effect = valueToExpression(effectValue),
                dependencies = dependencies
              )
            }).value
          case _ => none
        }
      case _ => none
    }

  def parseStateIdValue(value: JsonLogicValue): Option[StateMachine.StateId] =
    value match {
      case StrValue(s) => StateMachine.StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateMachine.StateId(s) }
      case _           => none
    }

  def parseEventTypeValue(value: JsonLogicValue): Option[StateMachine.EventType] =
    value match {
      case StrValue(et) => StateMachine.EventType(et).some
      case MapValue(m)  => m.get(ReservedKeys.VALUE).collect { case StrValue(et) => StateMachine.EventType(et) }
      case _            => none
    }
}
