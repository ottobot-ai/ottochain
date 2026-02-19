package xyz.kd5ujc.shared_data.identity.agent

import java.util.UUID

import cats.effect.IO

import xyz.kd5ujc.schema.delegation.DelegationCredential

/**
 * Manages delegation lifecycle for the OttoChain agent identity system.
 *
 * Implements the Session Key delegation pattern where a delegator grants
 * a relayer time-limited, scoped authority to submit transactions on their behalf.
 *
 * === Session Key Model ===
 * 1. Relayer bonds stake as collateral (slashable on misbehavior)
 * 2. Delegator approves session key with specified scope and limits
 * 3. Relayer submits transactions signed with session key
 * 4. Delegation expires or is revoked when complete
 *
 * === Tier Requirements ===
 * | Tier | Min Reputation Score | Min Stake Bond |
 * |------|---------------------|---------------|
 * | BASIC | 0.0 | 500 |
 * | STANDARD | 0.3 | 1000 |
 * | ADVANCED | 0.6 | 2000 |
 * | EXPERT | 0.9 | 5000 |
 */
object DelegationManager {

  /** Minimum ordinals a delegation can be valid for */
  val MinDelegationDuration: Long = 1L

  /** Maximum ordinals a delegation can be valid for (~7 days at ~1s/ordinal) */
  val MaxDelegationDuration: Long = 604800L

  /** Maximum spend limit per delegation */
  val MaxSpendLimit: Long = Long.MaxValue / 2

  /** Tier definitions: (minReputationScore, minStakeBond) */
  val TierRequirements: Map[String, (Double, Long)] = Map(
    "BASIC"    -> (0.0, 500L),
    "STANDARD" -> (0.3, 1000L),
    "ADVANCED" -> (0.6, 2000L),
    "EXPERT"   -> (0.9, 5000L)
  )

  // ─────────────────────────────────────────────
  // Types
  // ─────────────────────────────────────────────

  /** Result of a delegation request validation */
  final case class ValidationResult(
    isValid: Boolean,
    errors:  List[String]
  )

  object ValidationResult {
    val valid: ValidationResult = ValidationResult(isValid = true, errors = Nil)
    def invalid(errors: List[String]): ValidationResult = ValidationResult(isValid = false, errors)
    def invalid(error:  String): ValidationResult = invalid(List(error))
  }

  /** A stake bond posted as collateral for a delegation */
  final case class StakeBond(
    bondId:       UUID,
    agentAddress: String,
    amount:       Long,
    tier:         String,
    isSlashed:    Boolean = false
  )

  /**
   * State tracking all delegations and reputation for a specific agent.
   */
  final case class DelegationAgentState(
    agentAddress:     String,
    reputation:       ReputationScoring.ReputationState,
    activeSessions:   List[DelegationCredential],
    revokedSessions:  List[UUID],
    stakeBonds:       List[StakeBond],
    totalStakeBonded: Long
  )

  // ─────────────────────────────────────────────
  // Lifecycle Operations
  // ─────────────────────────────────────────────

  /**
   * Initialize delegation state for a new agent.
   *
   * @param agentAddress  The agent's DAG address
   * @param reputation    Initial reputation state
   */
  def initializeAgentDelegationState(
    agentAddress: String,
    reputation:   ReputationScoring.ReputationState
  ): DelegationAgentState =
    DelegationAgentState(
      agentAddress = agentAddress,
      reputation = reputation,
      activeSessions = Nil,
      revokedSessions = Nil,
      stakeBonds = Nil,
      totalStakeBonded = 0L
    )

  /**
   * Bond stake as collateral for delegation authorization.
   *
   * Higher tiers allow larger delegation scopes and longer durations.
   *
   * @param agentAddress  The relayer's address
   * @param amount        Amount to bond (must meet tier minimum)
   * @param tier          Requested tier ("BASIC" | "STANDARD" | "ADVANCED" | "EXPERT")
   */
  def bondStakeForDelegation(
    agentAddress: String,
    amount:       Long,
    tier:         String
  ): IO[StakeBond] = {
    val (_, minStake) = TierRequirements.getOrElse(tier, (0.0, DelegationCredential.MinStakeBond))
    if (amount < minStake)
      IO.raiseError(
        new IllegalArgumentException(
          s"Insufficient stake for tier $tier: need $minStake, got $amount"
        )
      )
    else
      IO.pure(StakeBond(UUID.randomUUID(), agentAddress, amount, tier))
  }

  /**
   * Validate a delegation request before creating the session.
   *
   * Checks:
   * 1. Agent has sufficient reputation for the requested tier
   * 2. Stake bond meets minimum for the tier
   * 3. Requested scope is non-empty
   * 4. Duration is within bounds
   * 5. Spend limit is positive
   *
   * @param state               Current agent delegation state
   * @param requestedOperations Requested scope
   * @param stakeAmount         Proposed stake bond amount
   * @param sessionDuration     Delegation lifetime in ordinals
   * @param maxSpendLimit       Maximum spending authorized
   */
  def validateDelegationRequest(
    state:               DelegationAgentState,
    requestedOperations: List[String],
    stakeAmount:         Long,
    sessionDuration:     Long,
    maxSpendLimit:       Long
  ): IO[ValidationResult] = IO {
    val errors = List.newBuilder[String]

    if (requestedOperations.isEmpty)
      errors += "Delegation scope must be non-empty"

    if (stakeAmount < DelegationCredential.MinStakeBond)
      errors += s"Stake amount $stakeAmount is below minimum ${DelegationCredential.MinStakeBond}"

    if (sessionDuration < MinDelegationDuration || sessionDuration > MaxDelegationDuration)
      errors += s"Session duration $sessionDuration out of bounds [$MinDelegationDuration, $MaxDelegationDuration]"

    if (maxSpendLimit <= 0)
      errors += "Spend limit must be positive"

    // Check reputation tier
    val score = state.reputation.score
    val eligibleTier = TierRequirements
      .filter { case (_, (minScore, minStake)) => score >= minScore && stakeAmount >= minStake }
      .keys
      .toList
    if (eligibleTier.isEmpty)
      errors += s"Insufficient reputation (${score}) or stake ($stakeAmount) for any delegation tier"

    val built = errors.result()
    if (built.isEmpty) ValidationResult.valid else ValidationResult.invalid(built)
  }

  /**
   * Create an active delegation session.
   *
   * The returned [[DelegationCredential]] should be persisted to [[CalculatedState]]
   * via the appropriate update message.
   *
   * @param delegatorAddress  The delegating principal's address
   * @param relayerAddress    The authorized relayer's address
   * @param sessionPublicKey  Public key of the session key (hex string)
   * @param scope             Authorized operation types
   * @param stakeBondAmount   Collateral amount bonded
   * @param maxSpendLimit     Spending cap for this delegation
   * @param durationOrdinals  How long this delegation is valid (in ordinals)
   * @param currentOrdinal    Current snapshot ordinal
   */
  def createDelegationSession(
    delegatorAddress: String,
    relayerAddress:   String,
    sessionPublicKey: String,
    scope:            List[String],
    stakeBondAmount:  Long,
    maxSpendLimit:    Long,
    durationOrdinals: Long,
    currentOrdinal:   Long = 0L
  ): IO[DelegationCredential] =
    IO.pure(
      DelegationCredential(
        delegationId = UUID.randomUUID(),
        delegatorAddr = delegatorAddress,
        relayerAddr = relayerAddress,
        sessionKey = sessionPublicKey,
        scope = scope,
        spendLimit = maxSpendLimit,
        spendUsed = 0L,
        createdAtOrdinal = currentOrdinal,
        expiresAtOrdinal = currentOrdinal + durationOrdinals,
        stakeBonded = stakeBondAmount,
        isRevoked = false
      )
    )

  /**
   * Record spending against a delegation's limit.
   *
   * @param credential  The active delegation
   * @param amount      Amount spent in this transaction
   * @return Updated credential with accumulated spending, or error if over limit
   */
  def recordSpending(
    credential: DelegationCredential,
    amount:     Long
  ): Either[String, DelegationCredential] =
    if (!credential.canSpend(amount))
      Left(s"Spend limit exceeded: limit=${credential.spendLimit}, used=${credential.spendUsed}, requested=$amount")
    else
      Right(credential.copy(spendUsed = credential.spendUsed + amount))

  /**
   * Revoke an active delegation.
   *
   * @param credential  The delegation to revoke
   * @return Revoked credential (isRevoked = true)
   */
  def revoke(credential: DelegationCredential): DelegationCredential =
    credential.copy(isRevoked = true)

  /**
   * Emergency revoke + slash the stake bond.
   *
   * @param credential  The delegation to revoke
   * @param bond        The associated stake bond
   * @return (RevokedCredential, SlashedBond)
   */
  def emergencyRevokeAndSlash(
    credential: DelegationCredential,
    bond:       StakeBond
  ): (DelegationCredential, StakeBond) =
    (credential.copy(isRevoked = true), bond.copy(isSlashed = true))

  /**
   * Look up the active delegation for a given relayer submitting on behalf of a delegator.
   *
   * @param delegations    All delegations in state
   * @param relayerAddr    The relayer's address
   * @param delegatorAddr  The delegator's address
   * @param currentOrdinal For expiry check
   */
  def findActiveDelegation(
    delegations:    Map[UUID, DelegationCredential],
    relayerAddr:    String,
    delegatorAddr:  String,
    currentOrdinal: Long
  ): Option[DelegationCredential] =
    delegations.values.find { d =>
      d.relayerAddr == relayerAddr &&
      d.delegatorAddr == delegatorAddr &&
      d.isActive(currentOrdinal)
    }
}
