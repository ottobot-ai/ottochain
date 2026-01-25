package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal

import derevo.circe.magnolia.{decoder, encoder, keyDecoder, keyEncoder}
import derevo.derive

object StateMachine {

  @derive(encoder, decoder, keyEncoder, keyDecoder)
  case class StateId(value: String) extends AnyVal

  @derive(encoder, decoder)
  case class EventType(value: String) extends AnyVal

  @derive(encoder, decoder)
  case class State(
    id:       StateId,
    isFinal:  Boolean = false,
    metadata: Option[JsonLogicValue] = None
  )

  @derive(encoder, decoder)
  case class Transition(
    from:         StateId,
    to:           StateId,
    eventType:    EventType,
    guard:        JsonLogicExpression, // Guard condition
    effect:       JsonLogicExpression, // State transformation
    dependencies: Set[UUID] = Set.empty // Other machines this transition reads from
  )

  @derive(encoder, decoder)
  case class TriggerEvent(
    targetMachineId: UUID,
    eventType:       EventType,
    payloadExpr:     JsonLogicExpression // Expression to compute payload
  )

  @derive(encoder, decoder)
  case class SpawnDirective(
    childIdExpr: JsonLogicExpression,
    definition:  StateMachineDefinition,
    initialData: JsonLogicExpression,
    ownersExpr:  Option[JsonLogicExpression] = None
  )

  @derive(encoder, decoder)
  case class StateMachineDefinition(
    states:       Map[StateId, State],
    initialState: StateId,
    transitions:  List[Transition],
    metadata:     Option[JsonLogicValue] = None
  ) {

    // Helper to get transitions by current state + event type
    // Returns list to support multiple transitions with guards (first-match-wins)
    lazy val transitionMap: Map[(StateId, EventType), List[Transition]] =
      transitions.groupBy(t => (t.from, t.eventType))
  }

  @derive(encoder, decoder)
  case class Event(
    eventType:      EventType,
    payload:        JsonLogicValue,
    idempotencyKey: Option[String] = None
  )

  @derive(encoder, decoder)
  case class ExecutionContext(
    depth:           Int = 0,
    maxDepth:        Int = 10,
    gasUsed:         Long = 0L,
    maxGas:          Long = 10_000_000L,
    processedEvents: Set[(UUID, EventType)] = Set.empty
  )

  @derive(encoder, decoder)
  sealed trait ProcessingResult

  @derive(encoder, decoder)
  case class StateMachineSuccess(
    newState:      StateId,
    newStateData:  JsonLogicValue,
    triggerEvents: List[TriggerEvent] = List.empty,
    spawnMachines: List[SpawnDirective] = List.empty,
    receipt:       Option[Records.EventReceipt] = None,
    outputs:       List[Records.StructuredOutput] = List.empty
  ) extends ProcessingResult

  @derive(encoder, decoder)
  case class OracleSuccess(
    newStateData: Option[JsonLogicValue],
    returnValue:  JsonLogicValue,
    gasUsed:      Long
  ) extends ProcessingResult

  @derive(encoder, decoder)
  case class Failure(reason: FailureReason) extends ProcessingResult

  @derive(encoder, decoder)
  case class GasExhausted(gasUsed: Long) extends ProcessingResult

  @derive(encoder, decoder)
  case class DepthExceeded(depth: Int) extends ProcessingResult

  @derive(encoder, decoder)
  sealed trait FailureReason {

    def toMessage: String = this match {
      case FailureReason.NoTransitionFound(fromState, eventType) =>
        s"No transition found from ${fromState.value} for event ${eventType.value}"
      case FailureReason.NoGuardMatched(fromState, eventType, attemptedGuards) =>
        s"No guard matched from ${fromState.value} on ${eventType.value} ($attemptedGuards guards tried)"
      case FailureReason.GuardEvaluationError(msg) =>
        s"Guard evaluation error: $msg"
      case FailureReason.EffectEvaluationError(msg) =>
        s"Effect evaluation error: $msg"
      case FailureReason.CycleDetected(fiberId, eventType) =>
        s"Cycle detected: fiber $fiberId, event ${eventType.value}"
      case FailureReason.ValidationFailed(msg, ordinal) =>
        s"Validation failed at ordinal ${ordinal.value.value}: $msg"
      case FailureReason.AccessDenied(caller, resourceId, policyType, details) =>
        s"Access denied: caller $caller not authorized for resource $resourceId (policy: $policyType)${details.fold("")(d => s" - $d")}"
      case FailureReason.Other(msg) =>
        msg
    }
  }

  object FailureReason {
    case class NoTransitionFound(fromState: StateId, eventType: EventType) extends FailureReason
    case class NoGuardMatched(fromState: StateId, eventType: EventType, attemptedGuards: Int) extends FailureReason
    case class GuardEvaluationError(message: String) extends FailureReason
    case class EffectEvaluationError(message: String) extends FailureReason
    case class CycleDetected(fiberId: UUID, eventType: EventType) extends FailureReason
    case class ValidationFailed(message: String, attemptedAt: SnapshotOrdinal) extends FailureReason

    case class AccessDenied(caller: String, resourceId: UUID, policyType: String, details: Option[String])
        extends FailureReason
    case class Other(message: String) extends FailureReason
  }

  @derive(encoder, decoder)
  sealed trait TransactionResult

  object TransactionResult {

    case class Committed(
      updatedFibers:  Map[UUID, Records.StateMachineFiberRecord],
      updatedOracles: Map[UUID, Records.ScriptOracleFiberRecord] = Map.empty,
      finalContext:   ExecutionContext,
      allStatuses:    List[(UUID, Records.EventProcessingStatus)]
    ) extends TransactionResult

    case class Aborted(
      reason:  FailureReason,
      context: ExecutionContext
    ) extends TransactionResult
  }
}
