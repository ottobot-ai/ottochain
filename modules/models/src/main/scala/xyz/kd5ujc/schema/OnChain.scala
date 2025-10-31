package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataOnChainState
import io.constellationnetwork.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder)
case class OnChain(
  latest: Map[UUID, Hash] // content id -> sha3-256 hash of json content
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(Map.empty)
}
