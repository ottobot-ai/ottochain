package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.UUID

import cats.Applicative
import cats.data.{EitherT, NonEmptySet}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationResult
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Validation rules specific to script oracle operations.
 *
 * Organized by validation layer:
 * - L1: Structural validations that can run at Data-L1 with only OnChain state
 * - L0: Contextual validations that require CalculatedState and/or signature proofs
 */
object OracleRules {

  // ============================================================================
  // L1 Rules - Structural validations (Data-L1 layer, OnChain state only)
  // ============================================================================

  object L1 {
    // Oracle L1 rules are primarily covered by CommonRules:
    // - cidNotUsed (for create)
    // - cidIsFound (for invoke)
    // - isMapValueOrNull (for initial state)
    // - expressionWithinDepthLimit (for script program)
    //
    // No additional oracle-specific L1 rules needed at this time.
    // This object exists for consistency and future extensibility.
  }

  // ============================================================================
  // L0 Rules - Contextual validations (Metagraph-L0 layer, full state + proofs)
  // ============================================================================

  object L0 {

    /** Validates oracle access control policy allows the caller */
    def accessControlCheck[F[_]: Async: SecurityProvider](
      oracleId: UUID,
      proofs:   NonEmptySet[SignatureProof],
      state:    CalculatedState
    ): F[ValidationResult] = (for {
      oracle <- EitherT.fromOption[F](
        state.scriptOracles.get(oracleId),
        Errors.OracleNotFound(oracleId): DataApplicationValidationError
      )
      callerAddresses <- EitherT.liftF(proofs.toList.traverse(_.id.toAddress))
      callerSet = callerAddresses.toSet
      authorized <- EitherT.cond[F](
        isAuthorized(oracle.accessControl, callerSet, state),
        (),
        Errors.OracleAccessDenied(oracleId, oracle.accessControl): DataApplicationValidationError
      )
    } yield authorized).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    /** Validates that an oracle exists */
    def oracleExists[F[_]: Applicative](
      oracleId: UUID,
      state:    CalculatedState
    ): F[ValidationResult] =
      state.scriptOracles
        .get(oracleId)
        .fold(
          (Errors.OracleNotFound(oracleId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        )(_ => ().validNec[DataApplicationValidationError].pure[F])

    // ============== Private Helpers ==============

    private def isAuthorized(
      policy:    Records.AccessControlPolicy,
      callerSet: Set[io.constellationnetwork.schema.address.Address],
      state:     CalculatedState
    ): Boolean =
      policy match {
        case Records.AccessControlPolicy.Public => true

        case Records.AccessControlPolicy.FiberOwned(ownerFiberId) =>
          state.getFiber(ownerFiberId).exists { ownerFiber =>
            callerSet.intersect(ownerFiber.owners).nonEmpty
          }

        case Records.AccessControlPolicy.Whitelist(allowed) =>
          callerSet.intersect(allowed).nonEmpty
      }
  }

  // ============================================================================
  // Errors - Oracle-specific validation errors
  // ============================================================================

  object Errors {

    final case class OracleNotFound(oracleId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Oracle $oracleId not found"
    }

    final case class OracleAccessDenied(oracleId: UUID, policy: Records.AccessControlPolicy)
        extends DataApplicationValidationError {
      override val message: String = s"Access denied to oracle $oracleId (policy: $policy)"
    }
  }
}
