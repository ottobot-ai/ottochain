package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataCalculatedState

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class CalculatedState(
  stateMachines: Map[UUID, Records.StateMachineFiberRecord],
  scriptOracles: Map[UUID, Records.ScriptOracleFiberRecord]
) extends DataCalculatedState {

  /** Lookup any fiber by ID */
  def getFiber(id: UUID): Option[Records.FiberRecord] =
    stateMachines.get(id).orElse(scriptOracles.get(id))

  /** Update a fiber (dispatches to correct map) */
  def updateFiber(fiber: Records.FiberRecord): CalculatedState = fiber match {
    case sm: Records.StateMachineFiberRecord =>
      copy(stateMachines = stateMachines.updated(sm.cid, sm))
    case oracle: Records.ScriptOracleFiberRecord =>
      copy(scriptOracles = scriptOracles.updated(oracle.cid, oracle))
  }
}

object CalculatedState {
  val genesis: CalculatedState = CalculatedState(Map.empty, Map.empty)
}
