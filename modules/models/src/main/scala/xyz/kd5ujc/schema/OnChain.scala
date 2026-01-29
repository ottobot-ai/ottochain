package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataOnChainState
import io.constellationnetwork.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder)
case class FiberCommit(
  recordHash:    Hash,
  stateDataHash: Option[Hash]
)

@derive(decoder, encoder)
case class OnChain(
  latest: Map[UUID, FiberCommit]
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(Map.empty)
}
