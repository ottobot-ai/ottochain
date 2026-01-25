package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._

object Records {

  sealed trait FiberStatus extends EnumEntry

  @derive(encoder, decoder)
  sealed trait AccessControlPolicy

  object AccessControlPolicy {
    case object Public extends AccessControlPolicy
    final case class Whitelist(addresses: Set[Address]) extends AccessControlPolicy
    final case class FiberOwned(fiberId: UUID) extends AccessControlPolicy
  }

  @derive(encoder, decoder)
  final case class StructuredOutput(
    outputType:  String,
    data:        JsonLogicValue,
    destination: Option[String] = None
  )

  @derive(encoder, decoder)
  sealed trait EventProcessingStatus

  object EventProcessingStatus {

    final case class Success(
      sequenceNumber: Long,
      processedAt:    SnapshotOrdinal
    ) extends EventProcessingStatus

    final case class GuardFailed(
      reason:             String,
      attemptedAt:        SnapshotOrdinal,
      attemptedEventType: StateMachine.EventType
    ) extends EventProcessingStatus

    final case class ExecutionFailed(
      reason:             String,
      attemptedAt:        SnapshotOrdinal,
      attemptedEventType: StateMachine.EventType,
      gasUsed:            Long,
      depth:              Int
    ) extends EventProcessingStatus

    final case class ValidationFailed(
      reason:      String,
      attemptedAt: SnapshotOrdinal
    ) extends EventProcessingStatus

    final case object Initialized extends EventProcessingStatus
  }

  @derive(encoder, decoder)
  final case class EventReceipt(
    sequenceNumber: Long,
    eventType:      StateMachine.EventType,
    ordinal:        SnapshotOrdinal,
    fromState:      StateMachine.StateId,
    toState:        StateMachine.StateId,
    success:        Boolean,
    gasUsed:        Long,
    triggersFired:  Int,
    outputs:        List[StructuredOutput] = List.empty,
    errorMessage:   Option[String] = None
  )

  @derive(encoder, decoder)
  final case class StateMachineFiberRecord(
    cid:                   UUID,
    creationOrdinal:       SnapshotOrdinal,
    previousUpdateOrdinal: SnapshotOrdinal,
    latestUpdateOrdinal:   SnapshotOrdinal,
    definition:            StateMachine.StateMachineDefinition,
    currentState:          StateMachine.StateId,
    stateData:             JsonLogicValue,
    stateDataHash:         Hash,
    sequenceNumber:        Long,
    owners:                Set[Address],
    status:                FiberStatus,
    lastEventStatus:       EventProcessingStatus,
    eventBatch:            List[EventProcessingStatus] = List.empty,
    parentFiberId:         Option[UUID] = None,
    childFiberIds:         Set[UUID] = Set.empty,
    eventLog:              List[EventReceipt] = List.empty,
    maxLogSize:            Int = 100
  )

  @derive(encoder, decoder)
  final case class OracleInvocation(
    method:    String,
    args:      JsonLogicValue,
    result:    JsonLogicValue,
    gasUsed:   Long,
    invokedAt: SnapshotOrdinal,
    invokedBy: Address
  )

  @derive(encoder, decoder)
  final case class ScriptOracleFiberRecord(
    cid:                 UUID,
    creationOrdinal:     SnapshotOrdinal,
    latestUpdateOrdinal: SnapshotOrdinal,
    scriptProgram:       JsonLogicExpression,
    stateData:           Option[JsonLogicValue],
    stateDataHash:       Option[Hash],
    accessControl:       AccessControlPolicy,
    invocationCount:     Long = 0,
    owners:              Set[Address],
    status:              FiberStatus,
    invocationLog:       List[OracleInvocation] = List.empty,
    maxLogSize:          Int = 100
  )

  object FiberStatus extends Enum[FiberStatus] with CirceEnum[FiberStatus] {
    val values: IndexedSeq[FiberStatus] = findValues

    case object Active extends FiberStatus
    case object Archived extends FiberStatus
    case object Failed extends FiberStatus
  }
}
