package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Monad
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.{CreateScriptOracle, InvokeScriptOracle}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{CommonRules, OracleRules}

/**
 * Validators for script oracle operations.
 *
 * Provides separate L1 and L0 validator classes that compose the rules
 * from OracleRules and CommonRules.
 */
object OracleValidator {

  /**
   * L1 Validator - Structural validations at Data-L1 layer.
   *
   * These validations run during API ingestion with only OnChain state available.
   *
   * @param state The current OnChain state for existence checks
   */
  class L1Validator[F[_]: Monad](state: OnChain) {

    /** Validates a CreateScriptOracle update */
    def createOracle(update: CreateScriptOracle): F[ValidationResult] =
      for {
        cidCheck       <- CommonRules.cidNotUsed(update.fiberId, state)
        initialStateOk <- CommonRules.isMapValueOrNull(update.initialState, "initialState")
        scriptDepthOk <- CommonRules.expressionWithinDepthLimit(
          update.scriptProgram,
          "scriptProgram",
          Limits.MaxExpressionDepth
        )
        initialStateSizeOk <- update.initialState.fold(
          ().validNec[io.constellationnetwork.currency.dataApplication.DataApplicationValidationError].pure[F]
        ) { value =>
          CommonRules.valueWithinSizeLimit(value, Limits.MaxInitialDataBytes, "initialState")
        }
      } yield List(cidCheck, initialStateOk, scriptDepthOk, initialStateSizeOk).combineAll

    /** Validates an InvokeScriptOracle update */
    def invokeOracle(update: InvokeScriptOracle): F[ValidationResult] =
      for {
        cidExists     <- CommonRules.cidIsFound(update.fiberId, state)
        argsStructure <- CommonRules.payloadStructureValid(update.args, "args")
        argsSize <- CommonRules.valueWithinSizeLimit(
          update.args,
          Limits.MaxEventPayloadBytes,
          "args"
        )
      } yield List(cidExists, argsStructure, argsSize).combineAll
  }

  /**
   * L0 Validator - Contextual validations at Metagraph-L0 layer.
   *
   * These validations run with full DataState and signature proofs available.
   *
   * @param state  The full DataState (OnChain + CalculatedState)
   * @param proofs The signature proofs from the signed update
   */
  class L0Validator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    /** Validates a CreateScriptOracle update (L0 specific checks) */
    def createOracle(update: CreateScriptOracle): F[ValidationResult] =
      // CreateScriptOracle has no L0-specific validation requirements currently.
      // Anyone can create an oracle; access control applies to invocations.
      ().validNec[io.constellationnetwork.currency.dataApplication.DataApplicationValidationError].pure[F]

    /** Validates an InvokeScriptOracle update (L0 specific checks) */
    def invokeOracle(update: InvokeScriptOracle): F[ValidationResult] =
      OracleRules.L0.accessControlCheck(update.fiberId, proofs, state.calculated)
  }

  /**
   * Combined validator that runs both L1 and L0 validations.
   *
   * Used at the L0 layer where we have full state and need to run all validations.
   */
  class CombinedValidator[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {
    private val l1 = new L1Validator[F](state.onChain)
    private val l0 = new L0Validator[F](state, proofs)

    /** Validates a CreateScriptOracle update (all checks) */
    def createOracle(update: CreateScriptOracle): F[ValidationResult] =
      for {
        l1Result <- l1.createOracle(update)
        l0Result <- l0.createOracle(update)
      } yield l1Result |+| l0Result

    /** Validates an InvokeScriptOracle update (all checks) */
    def invokeOracle(update: InvokeScriptOracle): F[ValidationResult] =
      for {
        l1Result <- l1.invokeOracle(update)
        l0Result <- l0.invokeOracle(update)
      } yield l1Result |+| l0Result
  }
}
