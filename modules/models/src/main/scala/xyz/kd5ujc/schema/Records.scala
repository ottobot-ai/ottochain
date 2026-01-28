package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber._

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object Records {

  sealed trait FiberRecord {
    def cid: UUID
    def status: FiberStatus
    def owners: Set[Address]
    def creationOrdinal: SnapshotOrdinal
    def latestUpdateOrdinal: SnapshotOrdinal
  }

  @derive(encoder, decoder)
  final case class StateMachineFiberRecord(
    cid:                   UUID,
    creationOrdinal:       SnapshotOrdinal,
    previousUpdateOrdinal: SnapshotOrdinal,
    latestUpdateOrdinal:   SnapshotOrdinal,
    definition:            StateMachineDefinition,
    currentState:          StateId,
    stateData:             JsonLogicValue,
    stateDataHash:         Hash,
    sequenceNumber:        Long,
    owners:                Set[Address],
    status:                FiberStatus,
    lastEventStatus:       EventProcessingStatus,
    eventBatch:            List[EventProcessingStatus] = List.empty,
    maxEventBatchSize:     Int = 100,
    parentFiberId:         Option[UUID] = None,
    childFiberIds:         Set[UUID] = Set.empty,
    eventLog:              List[EventReceipt] = List.empty,
    maxLogSize:            Int = 100
  ) extends FiberRecord

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
  ) extends FiberRecord
}
