package xyz.kd5ujc.schema.delegation

import java.util.UUID

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/**
 * Represents an active session-key delegation.
 *
 * A delegation allows a relayer to submit transactions on behalf of a delegator
 * within the specified scope and spending limits, until the session expires or
 * is explicitly revoked.
 *
 * @param delegationId   Unique identifier for this delegation
 * @param delegatorAddr  The address that granted authority
 * @param relayerAddr    The address authorized to relay transactions
 * @param sessionKey     Public key of the relayer's session key (hex-encoded)
 * @param scope          Operations this delegation authorizes (e.g. "market", "contract")
 * @param spendLimit     Maximum total spending authorized (in base units)
 * @param spendUsed      Running total of spending consumed by this delegation
 * @param createdAtOrdinal Snapshot ordinal when delegation was created
 * @param expiresAtOrdinal Snapshot ordinal after which delegation is invalid
 * @param stakeBonded    Amount of stake bonded by the relayer as collateral
 * @param isRevoked      Whether this delegation has been explicitly revoked
 */
@derive(decoder, encoder)
final case class DelegationCredential(
  delegationId:     UUID,
  delegatorAddr:    String,
  relayerAddr:      String,
  sessionKey:       String,
  scope:            List[String],
  spendLimit:       Long,
  spendUsed:        Long,
  createdAtOrdinal: Long,
  expiresAtOrdinal: Long,
  stakeBonded:      Long,
  isRevoked:        Boolean
) {

  def isActive(currentOrdinal: Long): Boolean =
    !isRevoked && currentOrdinal <= expiresAtOrdinal

  def hasScope(operation: String): Boolean =
    scope.exists(s => s == operation || s == "*")

  def spendRemaining: Long = spendLimit - spendUsed

  def canSpend(amount: Long): Boolean = spendUsed + amount <= spendLimit
}

object DelegationCredential {

  /** Minimum stake bond required to create a delegation (in base units) */
  val MinStakeBond: Long = 500L
}
