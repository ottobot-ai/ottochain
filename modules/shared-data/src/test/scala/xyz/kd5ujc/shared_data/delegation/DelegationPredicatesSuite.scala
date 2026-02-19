package xyz.kd5ujc.shared_data.delegation

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator

import xyz.kd5ujc.schema.delegation.{DelegationContext, DelegationCredential, DelegationPredicates}
import xyz.kd5ujc.shared_data.identity.agent.DelegationManager

import weaver.SimpleIOSuite

/**
 * Tests for JLVM delegation predicate expressions.
 *
 * Validates that the delegation policy expressions (the "JLVM operators")
 * evaluate correctly given delegation context injection from [[DelegationContext]].
 *
 * These tests prove the design: delegation validation works through standard
 * JLVM context variables + existing operators, without modifying the metakit library.
 */
object DelegationPredicatesSuite extends SimpleIOSuite {

  // ─────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────

  private val evaluator: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]

  /** Evaluate a JSON Logic expression against a context */
  private def eval(
    expr:    JsonLogicExpression,
    context: JsonLogicValue
  ): IO[Either[JsonLogicException, JsonLogicValue]] =
    evaluator.evaluate(expr, context)

  /** Returns true if expression evaluates to a truthy value */
  private def isTruthy(expr: JsonLogicExpression, context: JsonLogicValue): IO[Boolean] =
    eval(expr, context).map {
      case Right(v) => v.isTruthy
      case Left(_)  => false
    }

  /** Build a delegation context for a credential */
  private def mkCtx(
    credential:     DelegationCredential,
    currentOrdinal: Long,
    extra:          Map[String, JsonLogicValue] = Map.empty
  ): MapValue =
    MapValue(DelegationContext.fromCredential(credential, currentOrdinal).value ++ extra)

  /** Make a test delegation credential */
  private def mkCredential(
    scope:      List[String] = List("market"),
    spendLimit: Long = 5000L,
    spendUsed:  Long = 0L,
    duration:   Long = 10000L,
    isRevoked:  Boolean = false
  ): IO[DelegationCredential] =
    DelegationManager
      .createDelegationSession(
        delegatorAddress = "DAG_delegator",
        relayerAddress = "DAG_relayer",
        sessionPublicKey = "0xsession",
        scope = scope,
        stakeBondAmount = 1000L,
        maxSpendLimit = spendLimit,
        durationOrdinals = duration,
        currentOrdinal = 0L
      )
      .map(_.copy(spendUsed = spendUsed, isRevoked = isRevoked))

  // ─────────────────────────────────────────────
  // isDelegationActive / verify_delegation
  // ─────────────────────────────────────────────

  test("isDelegationActive: true for active delegation") {
    for {
      cred <- mkCredential()
      ctx = mkCtx(cred, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.isDelegationActive, ctx)
    } yield expect(result)
  }

  test("isDelegationActive: false for revoked delegation") {
    for {
      cred <- mkCredential(isRevoked = true)
      ctx = mkCtx(cred, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.isDelegationActive, ctx)
    } yield expect(!result)
  }

  test("isDelegationActive: false when no delegation context") {
    val ctx = DelegationContext.noDelegation
    isTruthy(DelegationPredicates.isDelegationActive, ctx).map(r => expect(!r))
  }

  // ─────────────────────────────────────────────
  // notExpired / check_delegation_expiry
  // ─────────────────────────────────────────────

  test("notExpired: true when current ordinal is before expiry") {
    for {
      // expires at ordinal 10000
      cred <- mkCredential(duration = 10000L)
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("$ordinal" -> IntValue(100L)))
      result <- isTruthy(DelegationPredicates.notExpired, ctx)
    } yield expect(result)
  }

  test("notExpired: false when current ordinal exceeds expiry") {
    for {
      // expires at ordinal 100 (duration from 0)
      cred <- mkCredential(duration = 100L)
      // inject expired ordinal — expiresAtOrdinal=100, current=200
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("$ordinal" -> IntValue(200L)))
      result <- isTruthy(DelegationPredicates.notExpired, ctx)
    } yield expect(!result)
  }

  // ─────────────────────────────────────────────
  // hasScope / validate_delegation_scope
  // ─────────────────────────────────────────────

  test("hasScope: true for operation in delegation scope") {
    for {
      cred <- mkCredential(scope = List("market", "contract"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.hasScope("market"), ctx)
    } yield expect(result)
  }

  test("hasScope: false for operation not in delegation scope") {
    for {
      cred <- mkCredential(scope = List("market"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.hasScope("governance"), ctx)
    } yield expect(!result)
  }

  test("hasScope: wildcard '*' grants all operations") {
    for {
      cred <- mkCredential(scope = List("*"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      r1 <- isTruthy(DelegationPredicates.hasScope("market"), ctx)
      r2 <- isTruthy(DelegationPredicates.hasScope("governance"), ctx)
      r3 <- isTruthy(DelegationPredicates.hasScope("contract"), ctx)
    } yield expect.all(r1, r2, r3)
  }

  // ─────────────────────────────────────────────
  // withinSpendLimit
  // ─────────────────────────────────────────────

  test("withinSpendLimit: true when amount is within remaining limit") {
    for {
      cred <- mkCredential(spendLimit = 1000L, spendUsed = 300L) // 700 remaining
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("amount" -> IntValue(500L)))
      result <- isTruthy(DelegationPredicates.withinSpendLimit("amount"), ctx)
    } yield expect(result)
  }

  test("withinSpendLimit: false when amount exceeds remaining limit") {
    for {
      cred <- mkCredential(spendLimit = 1000L, spendUsed = 800L) // 200 remaining
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("amount" -> IntValue(500L)))
      result <- isTruthy(DelegationPredicates.withinSpendLimit("amount"), ctx)
    } yield expect(!result)
  }

  // ─────────────────────────────────────────────
  // requireDelegation (compound / full validation)
  // ─────────────────────────────────────────────

  test("requireDelegation: true for active market delegation within expiry") {
    for {
      cred <- mkCredential(scope = List("market"))
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("$ordinal" -> IntValue(100L)))
      result <- isTruthy(DelegationPredicates.requireDelegation("market"), ctx)
    } yield expect(result)
  }

  test("requireDelegation: false when scope doesn't match") {
    for {
      cred <- mkCredential(scope = List("market"))
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("$ordinal" -> IntValue(100L)))
      result <- isTruthy(DelegationPredicates.requireDelegation("governance"), ctx)
    } yield expect(!result)
  }

  test("requireDelegation: false for revoked delegation") {
    for {
      cred <- mkCredential(scope = List("market"), isRevoked = true)
      ctx = mkCtx(cred, currentOrdinal = 100L, Map("$ordinal" -> IntValue(100L)))
      result <- isTruthy(DelegationPredicates.requireDelegation("market"), ctx)
    } yield expect(!result)
  }

  test("requireDelegationWithSpend: false when spend is over limit") {
    for {
      cred <- mkCredential(scope = List("market"), spendLimit = 1000L, spendUsed = 900L)
      ctx = mkCtx(
        cred,
        currentOrdinal = 100L,
        Map("$ordinal" -> IntValue(100L), "amount" -> IntValue(500L))
      )
      result <- isTruthy(DelegationPredicates.requireDelegationWithSpend("market", "amount"), ctx)
    } yield expect(!result)
  }

  // ─────────────────────────────────────────────
  // notRevoked / revocation_check
  // ─────────────────────────────────────────────

  test("notRevoked: true for non-revoked credential") {
    for {
      cred <- mkCredential()
      ctx = mkCtx(cred, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.notRevoked, ctx)
    } yield expect(result)
  }

  test("notRevoked: false after revocation") {
    for {
      cred <- mkCredential()
      revoked = DelegationManager.revoke(cred)
      ctx = mkCtx(revoked, currentOrdinal = 100L)
      result <- isTruthy(DelegationPredicates.notRevoked, ctx)
    } yield expect(!result)
  }

  // ─────────────────────────────────────────────
  // Composition helpers
  // ─────────────────────────────────────────────

  test("and composition: both predicates must be true") {
    for {
      cred <- mkCredential(scope = List("market", "contract"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      combined = DelegationPredicates.and(
        DelegationPredicates.hasScope("market"),
        DelegationPredicates.hasScope("contract")
      )
      result <- isTruthy(combined, ctx)
    } yield expect(result)
  }

  test("or composition: either predicate being true suffices") {
    for {
      cred <- mkCredential(scope = List("market"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      // market is in scope, governance is not — OR should be true
      combined = DelegationPredicates.or(
        DelegationPredicates.hasScope("market"),
        DelegationPredicates.hasScope("governance")
      )
      result <- isTruthy(combined, ctx)
    } yield expect(result)
  }

  test("not composition: inverts predicate") {
    for {
      cred <- mkCredential(scope = List("market"))
      ctx = mkCtx(cred, currentOrdinal = 100L)
      // governance NOT in scope → not(hasScope("governance")) should be true
      negated = DelegationPredicates.not(DelegationPredicates.hasScope("governance"))
      result <- isTruthy(negated, ctx)
    } yield expect(result)
  }

  // ─────────────────────────────────────────────
  // DelegationContext helpers
  // ─────────────────────────────────────────────

  test("DelegationContext.noDelegation disables all delegation predicates") {
    val ctx = DelegationContext.noDelegation
    for {
      r1 <- isTruthy(DelegationPredicates.isDelegationActive, ctx)
      r2 <- isTruthy(DelegationPredicates.notRevoked, ctx)
    } yield expect.all(!r1, !r2)
  }

  test("DelegationCredential.hasScope works for exact match") {
    for {
      cred <- mkCredential(scope = List("market"))
    } yield expect.all(
      cred.hasScope("market"),
      !cred.hasScope("governance")
    )
  }

  test("DelegationCredential.hasScope: wildcard grants all operations") {
    for {
      cred <- mkCredential(scope = List("*"))
    } yield expect.all(
      cred.hasScope("market"),
      cred.hasScope("governance"),
      cred.hasScope("contract")
    )
  }

  test("DelegationCredential.canSpend respects accumulated spending") {
    for {
      cred <- mkCredential(spendLimit = 1000L, spendUsed = 700L)
    } yield expect.all(
      cred.canSpend(300L), // exactly at limit
      !cred.canSpend(301L) // one over
    )
  }
}
