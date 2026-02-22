package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder)
sealed trait AccessControlPolicy

object AccessControlPolicy {
  case object Public extends AccessControlPolicy
  final case class Whitelist(addresses: Set[Address]) extends AccessControlPolicy
  final case class FiberOwned(fiberId: UUID) extends AccessControlPolicy
}
