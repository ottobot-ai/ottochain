package xyz.kd5ujc.shared_data.delegation

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._

import xyz.kd5ujc.schema.delegation.DelegationCredential
import xyz.kd5ujc.shared_data.identity.agent.{DelegationManager, ReputationScoring}

import weaver.SimpleIOSuite

/**
 * Unit tests for DelegationManager lifecycle operations.
 *
 * Tests the complete delegation lifecycle:
 * - Agent initialization and reputation building
 * - Stake bonding by tier
 * - Request validation
 * - Session creation
 * - Spending tracking
 * - Revocation
 */
object DelegationManagerSuite extends SimpleIOSuite {

  val agentAddress = "test_agent_DAG123"
  val relayerAddress = "test_relayer_DAG456"
  val delegatorAddr = "test_delegator_DAG789"
  val sessionKey = "0xsession_key_hex"

  // ─────────────────────────────────────────────
  // Reputation Scoring
  // ─────────────────────────────────────────────

  test("initializeAgent starts with zero score") {
    val state = ReputationScoring.initializeAgent(agentAddress)
    IO {
      expect.all(
        state.score == 0.0,
        state.totalEvents == 0,
        state.tier == "BASIC",
        state.agentAddress == agentAddress
      )
    }
  }

  test("completion attestations increase score") {
    for {
      initial  <- IO.pure(ReputationScoring.initializeAgent(agentAddress))
      updated1 <- ReputationScoring.updateReputationFromAttestation(initial, ReputationScoring.COMPLETION, 1.0)
      updated2 <- ReputationScoring.updateReputationFromAttestation(updated1, ReputationScoring.COMPLETION, 1.0)
    } yield expect.all(
      updated2.score > initial.score,
      updated2.totalEvents == 2
    )
  }

  test("violation attestations decrease score (clamped at 0)") {
    for {
      initial  <- IO.pure(ReputationScoring.initializeAgent(agentAddress))
      violated <- ReputationScoring.updateReputationFromAttestation(initial, ReputationScoring.VIOLATION, 1.0)
    } yield expect.all(
      violated.score == 0.0, // clamped at 0
      violated.totalEvents == 1
    )
  }

  test("build to ADVANCED tier requires 8 completions from zero") {
    val initial = ReputationScoring.initializeAgent(agentAddress)
    for {
      advanced <- (1 to 8).toList.foldLeftM(initial) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, ReputationScoring.COMPLETION, 1.0)
      }
    } yield expect(advanced.tier == "ADVANCED")
  }

  // ─────────────────────────────────────────────
  // Stake Bonding
  // ─────────────────────────────────────────────

  test("bondStakeForDelegation succeeds with sufficient stake") {
    DelegationManager.bondStakeForDelegation(agentAddress, 2000L, "ADVANCED").map { bond =>
      expect.all(
        bond.agentAddress == agentAddress,
        bond.amount == 2000L,
        bond.tier == "ADVANCED",
        !bond.isSlashed
      )
    }
  }

  test("bondStakeForDelegation fails with insufficient stake for tier") {
    DelegationManager
      .bondStakeForDelegation(agentAddress, 100L, "ADVANCED")
      .attempt
      .map { result =>
        expect(result.isLeft)
      }
  }

  test("bondStakeForDelegation succeeds at BASIC tier with minimum stake") {
    DelegationManager.bondStakeForDelegation(agentAddress, DelegationCredential.MinStakeBond, "BASIC").map { bond =>
      expect.all(
        bond.tier == "BASIC",
        bond.amount >= DelegationCredential.MinStakeBond
      )
    }
  }

  // ─────────────────────────────────────────────
  // Request Validation
  // ─────────────────────────────────────────────

  test("validateDelegationRequest returns valid for good params") {
    val initial = ReputationScoring.initializeAgent(agentAddress)
    for {
      reputation <- (1 to 8).toList.foldLeftM(initial) { (state, _) =>
        ReputationScoring.updateReputationFromAttestation(state, ReputationScoring.COMPLETION, 1.0)
      }
      agentState = DelegationManager.initializeAgentDelegationState(agentAddress, reputation)
      result <- DelegationManager.validateDelegationRequest(
        agentState,
        requestedOperations = List("market", "contract"),
        stakeAmount = 2000L,
        sessionDuration = 21600L,
        maxSpendLimit = 5000L
      )
    } yield expect.all(
      result.isValid,
      result.errors.isEmpty
    )
  }

  test("validateDelegationRequest rejects empty scope") {
    val state = DelegationManager.initializeAgentDelegationState(
      agentAddress,
      ReputationScoring.initializeAgent(agentAddress)
    )
    DelegationManager
      .validateDelegationRequest(
        state,
        requestedOperations = List.empty,
        stakeAmount = 2000L,
        sessionDuration = 21600L,
        maxSpendLimit = 5000L
      )
      .map { result =>
        expect.all(
          !result.isValid,
          result.errors.exists(_.contains("scope"))
        )
      }
  }

  test("validateDelegationRequest rejects insufficient stake") {
    val state = DelegationManager.initializeAgentDelegationState(
      agentAddress,
      ReputationScoring.initializeAgent(agentAddress)
    )
    DelegationManager
      .validateDelegationRequest(
        state,
        requestedOperations = List("market"),
        stakeAmount = 10L,
        sessionDuration = 21600L,
        maxSpendLimit = 5000L
      )
      .map { result =>
        expect(!result.isValid)
      }
  }

  test("validateDelegationRequest rejects out-of-bounds duration") {
    val state = DelegationManager.initializeAgentDelegationState(
      agentAddress,
      ReputationScoring.initializeAgent(agentAddress)
    )
    DelegationManager
      .validateDelegationRequest(
        state,
        requestedOperations = List("market"),
        stakeAmount = 2000L,
        sessionDuration = 0L, // invalid: below min
        maxSpendLimit = 5000L
      )
      .map { result =>
        expect(!result.isValid)
      }
  }

  // ─────────────────────────────────────────────
  // Session Creation
  // ─────────────────────────────────────────────

  test("createDelegationSession creates correct credential") {
    DelegationManager
      .createDelegationSession(
        delegatorAddress = delegatorAddr,
        relayerAddress = relayerAddress,
        sessionPublicKey = sessionKey,
        scope = List("market", "contract"),
        stakeBondAmount = 2000L,
        maxSpendLimit = 5000L,
        durationOrdinals = 21600L,
        currentOrdinal = 100L
      )
      .map { credential =>
        expect.all(
          credential.delegatorAddr == delegatorAddr,
          credential.relayerAddr == relayerAddress,
          credential.sessionKey == sessionKey,
          credential.scope == List("market", "contract"),
          credential.spendLimit == 5000L,
          credential.spendUsed == 0L,
          credential.createdAtOrdinal == 100L,
          credential.expiresAtOrdinal == 100L + 21600L,
          credential.stakeBonded == 2000L,
          !credential.isRevoked,
          credential.isActive(100L),
          !credential.isActive(100L + 21600L + 1L)
        )
      }
  }

  // ─────────────────────────────────────────────
  // Spending Tracking
  // ─────────────────────────────────────────────

  test("recordSpending tracks accumulated spend") {
    for {
      credential <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        2000L,
        1000L,
        3600L,
        0L
      )
      step1 = DelegationManager.recordSpending(credential, 300L)
      step2 = step1.flatMap(DelegationManager.recordSpending(_, 400L))
    } yield expect.all(
      step1.isRight,
      step2.isRight,
      step2.map(_.spendUsed) == Right(700L),
      step2.map(_.spendRemaining) == Right(300L)
    )
  }

  test("recordSpending rejects amount that exceeds limit") {
    for {
      credential <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        2000L,
        1000L,
        3600L,
        0L
      )
      result = DelegationManager.recordSpending(credential, 1500L) // over 1000 limit
    } yield expect(result.isLeft)
  }

  // ─────────────────────────────────────────────
  // Revocation
  // ─────────────────────────────────────────────

  test("revoke marks credential inactive") {
    for {
      credential <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        2000L,
        5000L,
        3600L,
        0L
      )
      revoked = DelegationManager.revoke(credential)
    } yield expect.all(
      revoked.isRevoked,
      !revoked.isActive(0L)
    )
  }

  test("emergencyRevokeAndSlash revokes credential and slashes bond") {
    for {
      credential <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        2000L,
        5000L,
        3600L,
        0L
      )
      bond <- DelegationManager.bondStakeForDelegation(agentAddress, 2000L, "ADVANCED")
      (revokedCred, slashedBond) = DelegationManager.emergencyRevokeAndSlash(credential, bond)
    } yield expect.all(
      revokedCred.isRevoked,
      slashedBond.isSlashed
    )
  }

  // ─────────────────────────────────────────────
  // Delegation lookup
  // ─────────────────────────────────────────────

  test("findActiveDelegation returns matching active delegation") {
    for {
      cred1 <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        1000L,
        5000L,
        3600L,
        10L
      )
      cred2 <- DelegationManager.createDelegationSession(
        "other_delegator",
        "other_relayer",
        "other_key",
        List("contract"),
        1000L,
        5000L,
        3600L,
        10L
      )
      delegations: Map[UUID, DelegationCredential] = Map(
        cred1.delegationId -> cred1,
        cred2.delegationId -> cred2
      )
    } yield {
      val found = DelegationManager.findActiveDelegation(delegations, relayerAddress, delegatorAddr, 50L)
      expect.all(
        found.isDefined,
        found.exists(_.delegationId == cred1.delegationId)
      )
    }
  }

  test("findActiveDelegation returns None for expired delegation") {
    for {
      cred <- DelegationManager.createDelegationSession(
        delegatorAddr,
        relayerAddress,
        sessionKey,
        List("market"),
        1000L,
        5000L,
        100L,
        0L // expires at ordinal 100
      )
      delegations: Map[UUID, DelegationCredential] = Map(cred.delegationId -> cred)
    } yield {
      val found = DelegationManager.findActiveDelegation(delegations, relayerAddress, delegatorAddr, 101L) // expired
      expect(found.isEmpty)
    }
  }
}
