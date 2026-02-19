package xyz.kd5ujc.schema.delegation

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber.ReservedKeys

/**
 * Pre-built JSON Logic expressions for delegation policy enforcement.
 *
 * These expressions operate on delegation context variables injected by
 * [[DelegationContext]] and can be composed with user-defined guard expressions
 * in state machine definitions.
 *
 * === Usage in state machine definitions ===
 * {{{
 * // Guard that requires an active delegation with "market" scope
 * val guard = DelegationPredicates.requireDelegation("market")
 *
 * // Compose with other guards
 * val composedGuard = DelegationPredicates.and(
 *   DelegationPredicates.requireDelegation("contract"),
 *   userDefinedGuard
 * )
 * }}}
 *
 * === Operator implementations ===
 * Since the JLVM (JSON Logic Virtual Machine) uses a fixed operator set, delegation
 * validation is implemented by injecting delegation state as context variables and
 * composing checks using the standard operators (`===`, `>=`, `in`, `and`, etc.).
 *
 * The five "operators" from the card spec map to these predicate factories:
 * - `verify_delegation`       → [[isDelegationActive]]
 * - `check_delegation_expiry` → [[notExpired]]
 * - `validate_delegation_scope` → [[hasScope]]
 * - `revocation_check`        → [[notRevoked]]
 * - `session_key_valid`       → [[sessionKeyValid]] (used with caller proof verification)
 */
object DelegationPredicates {

  // ─────────────────────────────────────────────
  // Core predicate expressions
  // ─────────────────────────────────────────────

  /**
   * `verify_delegation`: delegation.active === true
   *
   * True when a delegation credential exists and is currently active
   * (not revoked, ordinal within range).
   */
  def isDelegationActive: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.EqStrictOp,
      List(
        VarExpression(Left(ReservedKeys.DELEGATION_ACTIVE), Some(BoolValue(false))),
        ConstExpression(BoolValue(true))
      )
    )

  /**
   * `check_delegation_expiry`: current ordinal is within delegation validity window.
   *
   * Expressed as: delegation.expiresAt >= $ordinal
   */
  def notExpired: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.Geq,
      List(
        VarExpression(Left(ReservedKeys.DELEGATION_EXPIRES), Some(IntValue(0))),
        VarExpression(Left("$ordinal"), Some(IntValue(0)))
      )
    )

  /**
   * `validate_delegation_scope`: delegation.scope contains the given operation.
   *
   * True when the delegation scope list contains the requested operation type,
   * or contains the wildcard "*".
   *
   * @param operation The operation type to check (e.g., "market", "contract")
   */
  def hasScope(operation: String): JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.OrOp,
      List(
        // Exact match
        ApplyExpression(
          JsonLogicOp.InOp,
          List(
            ConstExpression(StrValue(operation)),
            VarExpression(Left(ReservedKeys.DELEGATION_SCOPE), Some(ArrayValue(Nil)))
          )
        ),
        // Wildcard match
        ApplyExpression(
          JsonLogicOp.InOp,
          List(
            ConstExpression(StrValue("*")),
            VarExpression(Left(ReservedKeys.DELEGATION_SCOPE), Some(ArrayValue(Nil)))
          )
        )
      )
    )

  /**
   * `revocation_check`: delegation is not revoked.
   *
   * Delegation context injects `active = false` when revoked, so this is
   * equivalent to `isDelegationActive`. Provided as a distinct predicate for clarity.
   */
  def notRevoked: JsonLogicExpression = isDelegationActive

  /**
   * `session_key_valid`: the caller's proof address matches the delegation relayer.
   *
   * True when the first signer's address matches the authorized relayer address
   * registered in the delegation.
   */
  def sessionKeyValid: JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.EqStrictOp,
      List(
        // proofs[0].address
        VarExpression(Left(s"${ReservedKeys.PROOFS}.0.${ReservedKeys.ADDRESS}"), Some(StrValue(""))),
        VarExpression(Left(ReservedKeys.DELEGATION_RELAYER), Some(StrValue("")))
      )
    )

  /**
   * Spending limit check: spendRemaining >= amount.
   *
   * @param amountKey The context key for the transaction amount (default: "amount")
   */
  def withinSpendLimit(amountKey: String = "amount"): JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.Geq,
      List(
        VarExpression(Left(ReservedKeys.DELEGATION_SPEND_REMAIN), Some(IntValue(0))),
        VarExpression(Left(amountKey), Some(IntValue(0)))
      )
    )

  // ─────────────────────────────────────────────
  // Compound predicates
  // ─────────────────────────────────────────────

  /**
   * Full delegation validation: active AND not expired AND has required scope.
   *
   * This is the standard guard to prepend to any state machine transition that
   * requires delegated authority.
   *
   * @param operation The operation type to check scope for
   */
  def requireDelegation(operation: String): JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.AndOp,
      List(
        isDelegationActive,
        notExpired,
        hasScope(operation)
      )
    )

  /**
   * Full delegation validation including spend limit.
   *
   * @param operation The operation type
   * @param amountKey Context key for the transaction amount
   */
  def requireDelegationWithSpend(operation: String, amountKey: String = "amount"): JsonLogicExpression =
    ApplyExpression(
      JsonLogicOp.AndOp,
      List(
        isDelegationActive,
        notExpired,
        hasScope(operation),
        withinSpendLimit(amountKey)
      )
    )

  // ─────────────────────────────────────────────
  // Composition helpers
  // ─────────────────────────────────────────────

  /** Compose two predicates with AND. */
  def and(left: JsonLogicExpression, right: JsonLogicExpression): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.AndOp, List(left, right))

  /** Compose two predicates with OR. */
  def or(left: JsonLogicExpression, right: JsonLogicExpression): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.OrOp, List(left, right))

  /** Negate a predicate. */
  def not(expr: JsonLogicExpression): JsonLogicExpression =
    ApplyExpression(JsonLogicOp.NotOp, List(expr))
}
