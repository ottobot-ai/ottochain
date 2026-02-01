package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataOnChainState
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder)
case class FiberCommit(
  recordHash:     Hash,
  stateDataHash:  Option[Hash],
  sequenceNumber: FiberOrdinal
)

@derive(decoder, encoder)
case class OnChain(
  fiberCommits: SortedMap[UUID, FiberCommit],
  latestLogs:   SortedMap[UUID, List[FiberLogEntry]]
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(SortedMap.empty, SortedMap.empty)
}
