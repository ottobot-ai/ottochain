package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataCalculatedState

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

sealed trait FiberRecord

@derive(encoder, decoder)
case class CalculatedState(
  stateMachines: Map[UUID, Records.StateMachineFiberRecord],
  scriptOracles: Map[UUID, Records.ScriptOracleFiberRecord]
) extends DataCalculatedState {
  def records: Map[UUID, Records.StateMachineFiberRecord] = stateMachines
}

object CalculatedState {
  val genesis: CalculatedState = CalculatedState(Map.empty, Map.empty)
}
