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

  /** Phase where gas was exhausted during execution */
  @derive(encoder, decoder)
  sealed trait GasExhaustionPhase

  object GasExhaustionPhase {
    case object Guard extends GasExhaustionPhase
    case object Effect extends GasExhaustionPhase
    case object Oracle extends GasExhaustionPhase
    case object Trigger extends GasExhaustionPhase
    case object Spawn extends GasExhaustionPhase
  }

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
      case FailureReason.TriggerTargetNotFound(targetId, sourceId) =>
        s"Trigger target fiber $targetId not found${sourceId.fold("")(s => s" (source: $s)")}"
      case FailureReason.GasExhaustedFailure(gasUsed, gasLimit, phase) =>
        s"Gas exhausted during ${phase.toString.toLowerCase}: $gasUsed of $gasLimit units consumed"
      case FailureReason.FiberInputMismatch(fiberId, fiberType, inputType) =>
        s"Cannot use $inputType input with $fiberType fiber $fiberId"
      case FailureReason.FiberNotFound(fiberId) =>
        s"Fiber $fiberId not found"
      case FailureReason.FiberNotActive(fiberId, status) =>
        s"Fiber $fiberId is not active (status: $status)"
      case FailureReason.DepthExceeded(depth, maxDepth) =>
        s"Trigger cascade depth exceeded: $depth (max: $maxDepth)"
      case FailureReason.OracleInvocationFailed(oracleId, method, errorMsg) =>
        s"Oracle $oracleId method '$method' failed${errorMsg.fold("")(e => s": $e")}"
      case FailureReason.SpawnValidationFailed(parentId, errors) =>
        s"Spawn validation failed for fiber $parentId: ${errors.mkString("; ")}"
      case FailureReason.CallerResolutionFailed(oracleId, sourceId) =>
        s"Unable to determine caller for oracle $oracleId${sourceId.fold("")(s => s" (source fiber: $s)")}"
      case FailureReason.MissingProof(fiberId, operation) =>
        s"No signature proof provided for $operation on fiber $fiberId"
      case FailureReason.StateSizeTooLarge(actualBytes, maxBytes) =>
        s"Resulting state size $actualBytes bytes exceeds maximum $maxBytes bytes"
    }
  }

  object FailureReason {
    case class NoTransitionFound(fromState: StateId, eventType: EventType) extends FailureReason
    case class NoGuardMatched(fromState: StateId, eventType: EventType, attemptedGuards: Int) extends FailureReason
    case class GuardEvaluationError(message: String) extends FailureReason
    case class EffectEvaluationError(message: String) extends FailureReason
    case class CycleDetected(fiberId: UUID, eventType: EventType) extends FailureReason
    case class ValidationFailed(message: String, attemptedAt: SnapshotOrdinal) extends FailureReason
    case class TriggerTargetNotFound(targetFiberId: UUID, sourceFiberId: Option[UUID]) extends FailureReason

    case class AccessDenied(caller: String, resourceId: UUID, policyType: String, details: Option[String])
        extends FailureReason

    /** Structured gas exhaustion failure with phase information */
    case class GasExhaustedFailure(gasUsed: Long, gasLimit: Long, phase: GasExhaustionPhase) extends FailureReason

    /** Input type mismatch between fiber record and input */
    case class FiberInputMismatch(fiberId: UUID, fiberType: String, inputType: String) extends FailureReason

    /** Fiber with given ID not found in calculated state */
    case class FiberNotFound(fiberId: UUID) extends FailureReason

    /** Fiber exists but is not in Active status */
    case class FiberNotActive(fiberId: UUID, currentStatus: String) extends FailureReason

    /** Trigger cascade depth exceeded maximum allowed */
    case class DepthExceeded(depth: Int, maxDepth: Int) extends FailureReason

    /** Oracle invocation failed during trigger handling */
    case class OracleInvocationFailed(oracleId: UUID, method: String, errorMessage: Option[String])
        extends FailureReason

    /** Spawn validation failed for child fibers */
    case class SpawnValidationFailed(parentFiberId: UUID, errors: List[String]) extends FailureReason

    /** Unable to determine caller address for oracle invocation */
    case class CallerResolutionFailed(targetOracleId: UUID, sourceFiberId: Option[UUID]) extends FailureReason

    /** No signature proof provided for operation requiring authorization */
    case class MissingProof(fiberId: UUID, operation: String) extends FailureReason

    /** Resulting state size exceeds maximum allowed */
    case class StateSizeTooLarge(actualBytes: Int, maxBytes: Int) extends FailureReason
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
