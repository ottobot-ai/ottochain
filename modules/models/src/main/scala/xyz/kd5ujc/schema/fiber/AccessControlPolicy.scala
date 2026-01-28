package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.address.Address

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait AccessControlPolicy

object AccessControlPolicy {
  case object Public extends AccessControlPolicy
  final case class Whitelist(addresses: Set[Address]) extends AccessControlPolicy
  final case class FiberOwned(fiberId: UUID) extends AccessControlPolicy
}
