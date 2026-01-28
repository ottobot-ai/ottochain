package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.UUID

import cats.data.{EitherT, NonEmptySet, Validated}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Monad}

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.json_logic.{BoolValue, ConstExpression}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.{EventType, FiberStatus, StateId, StateMachineDefinition}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.{Limits, ValidationResult}
import xyz.kd5ujc.shared_data.syntax.calculatedState._

/**
 * Validation rules specific to state machine fiber operations.
 *
 * Organized by validation layer:
 * - L1: Structural validations that can run at Data-L1 with only OnChain state
 * - L0: Contextual validations that require CalculatedState and/or signature proofs
 */
object FiberRules {

  // ============================================================================
  // L1 Rules - Structural validations (Data-L1 layer, OnChain state only)
  // ============================================================================

  object L1 {

    /** Validates a state machine definition is structurally valid */
    def validStateMachineDefinition[F[_]: Applicative](
      definition: StateMachineDefinition
    ): F[ValidationResult] = {
      val validations: List[ValidationResult] = List(
        // Must have at least one state
        Validated.condNec(
          definition.states.nonEmpty,
          (),
          Errors.NoStatesInDefinition: DataApplicationValidationError
        ),
        // Initial state must exist in states
        Validated.condNec(
          definition.states.contains(definition.initialState),
          (),
          Errors.InitialStateNotFound(definition.initialState): DataApplicationValidationError
        ),
        // All transitions must reference valid states
        definition.transitions.map { transition =>
          List(
            Validated.condNec(
              definition.states.contains(transition.from),
              (),
              Errors.TransitionFromInvalidState(transition.from): DataApplicationValidationError
            ),
            Validated.condNec(
              definition.states.contains(transition.to),
              (),
              Errors.TransitionToInvalidState(transition.to): DataApplicationValidationError
            )
          ).combineAll
        }.combineAll,
        // No exact duplicate transitions
        Validated.condNec(
          definition.transitions.groupBy(identity).forall(_._2.size == 1),
          (),
          Errors.DuplicateTransitions: DataApplicationValidationError
        ),
        // No ambiguous transitions (multiple from same state+event with unconditional guards)
        Validated.condNec(
          !definition.transitions
            .groupBy(t => (t.from, t.eventType))
            .exists { case (_, transitions) =>
              transitions.size > 1 && transitions.exists { t =>
                t.guard match {
                  case ConstExpression(BoolValue(true)) => true
                  case _                                => false
                }
              }
            },
          (),
          Errors.AmbiguousTransitions: DataApplicationValidationError
        )
      )

      validations.combineAll.pure[F]
    }

    /** Validates definition size limits (states, transitions) */
    def definitionWithinLimits[F[_]: Applicative](
      definition: StateMachineDefinition
    ): F[ValidationResult] = {
      val maxPerState = definition.transitions
        .groupBy(_.from)
        .values
        .map(_.size)
        .maxOption
        .getOrElse(0)

      val validations: List[ValidationResult] = List(
        Validated.condNec(
          definition.states.size <= Limits.MaxStates,
          (),
          Errors.TooManyStates(definition.states.size, Limits.MaxStates): DataApplicationValidationError
        ),
        Validated.condNec(
          definition.transitions.size <= Limits.MaxTransitions,
          (),
          Errors.TooManyTransitions(definition.transitions.size, Limits.MaxTransitions): DataApplicationValidationError
        ),
        Validated.condNec(
          maxPerState <= Limits.MaxTransitionsPerState,
          (),
          Errors.TooManyTransitionsPerState(maxPerState, Limits.MaxTransitionsPerState): DataApplicationValidationError
        )
      )

      validations.combineAll.pure[F]
    }

    /** Validates all guard and effect expressions in a definition are within depth limits */
    def definitionExpressionsWithinDepthLimits[F[_]: Applicative](
      definition: StateMachineDefinition
    ): F[ValidationResult] = {
      val guardValidations = definition.transitions.zipWithIndex.map { case (t, idx) =>
        CommonRules.expressionWithinDepthLimit(t.guard, s"transition[$idx].guard")
      }

      val effectValidations = definition.transitions.zipWithIndex.map { case (t, idx) =>
        CommonRules.expressionWithinDepthLimit(t.effect, s"transition[$idx].effect")
      }

      (guardValidations ++ effectValidations).sequence.map(_.combineAll)
    }

    /** Validates parent fiber exists in on-chain state (L1 structural check) */
    def parentFiberExistsInOnChain[F[_]: Applicative](
      parentFiberId: Option[UUID],
      state:         OnChain
    ): F[ValidationResult] =
      parentFiberId.fold(().validNec[DataApplicationValidationError].pure[F]) { parentId =>
        Validated
          .condNec(
            state.latest.contains(parentId),
            (),
            Errors.ParentFiberNotFound(parentId): DataApplicationValidationError
          )
          .pure[F]
      }
  }

  // ============================================================================
  // L0 Rules - Contextual validations (Metagraph-L0 layer, full state + proofs)
  // ============================================================================

  object L0 {

    /** Validates that signature proofs are present */
    def hasProofs[F[_]: Applicative](
      proofs: NonEmptySet[SignatureProof]
    ): F[ValidationResult] =
      Validated
        .condNec(
          proofs.nonEmpty,
          (),
          Errors.NoProofsProvided: DataApplicationValidationError
        )
        .pure[F]

    /** Validates that a fiber is in Active status */
    def fiberIsActive[F[_]: Monad](
      cid:   UUID,
      state: CalculatedState
    ): F[ValidationResult] = (for {
      record <- state.getFiberRecord(cid)
      result <- EitherT.cond[F](
        record.status == FiberStatus.Active,
        (),
        Errors.FiberNotActive(cid): DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    /** Validates that the update is signed by at least one fiber owner */
    def updateSignedByOwners[F[_]: Async: SecurityProvider](
      cid:    UUID,
      proofs: NonEmptySet[SignatureProof],
      state:  CalculatedState
    ): F[ValidationResult] = (for {
      record          <- state.getFiberRecord(cid)
      signerAddresses <- EitherT.liftF(proofs.toList.traverse(_.id.toAddress))
      signerSet = signerAddresses.toSet
      result <- EitherT.cond[F](
        signerSet.intersect(record.owners).nonEmpty,
        (),
        Errors.NotSignedByOwner: DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    /** Validates that a transition exists for the given state+event combination */
    def transitionExists[F[_]: Monad](
      cid:       UUID,
      eventType: EventType,
      state:     CalculatedState
    ): F[ValidationResult] = (for {
      record <- state.getFiberRecord(cid)
      hasTransition = record.definition.transitionMap.contains((record.currentState, eventType))
      result <- EitherT.cond[F](
        hasTransition,
        (),
        Errors.NoTransitionForEvent(record.currentState, eventType): DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    /** Validates that parent fiber is active (L0 check with CalculatedState) */
    def parentFiberActive[F[_]: Monad](
      parentFiberId: Option[UUID],
      state:         CalculatedState
    ): F[ValidationResult] =
      parentFiberId.fold(().validNec[DataApplicationValidationError].pure[F]) { parentId =>
        state
          .getFiber(parentId)
          .fold(
            (Errors.ParentFiberNotFound(parentId): DataApplicationValidationError).invalidNec[Unit].pure[F]
          ) { fiber =>
            Validated
              .condNec(
                fiber.status == FiberStatus.Active,
                (),
                Errors.ParentFiberNotActive(parentId): DataApplicationValidationError
              )
              .pure[F]
          }
      }
  }

  // ============================================================================
  // Errors - Fiber-specific validation errors
  // ============================================================================

  object Errors {

    // --- Definition structure errors ---

    final case object NoStatesInDefinition extends DataApplicationValidationError {
      override val message: String = "State machine definition has no states"
    }

    final case class InitialStateNotFound(state: StateId) extends DataApplicationValidationError {
      override val message: String = s"Initial state ${state.value} not found in states"
    }

    final case class TransitionFromInvalidState(state: StateId) extends DataApplicationValidationError {
      override val message: String = s"Transition references invalid from state: ${state.value}"
    }

    final case class TransitionToInvalidState(state: StateId) extends DataApplicationValidationError {
      override val message: String = s"Transition references invalid to state: ${state.value}"
    }

    final case object DuplicateTransitions extends DataApplicationValidationError {

      override val message: String =
        "Exact duplicate transitions detected (same from, to, event, guard, effect, and dependencies)"
    }

    final case object AmbiguousTransitions extends DataApplicationValidationError {

      override val message: String =
        "Ambiguous transitions detected: multiple transitions from same state+event with at least one unconditional guard"
    }

    // --- Definition limit errors ---

    final case class TooManyStates(count: Int, max: Int) extends DataApplicationValidationError {
      override val message: String = s"Too many states: $count (max: $max)"
    }

    final case class TooManyTransitions(count: Int, max: Int) extends DataApplicationValidationError {
      override val message: String = s"Too many transitions: $count (max: $max)"
    }

    final case class TooManyTransitionsPerState(count: Int, max: Int) extends DataApplicationValidationError {
      override val message: String = s"Too many transitions per state: $count (max: $max)"
    }

    // --- Fiber state errors ---

    final case class FiberNotFound(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Fiber $cid not found"
    }

    final case class FiberNotActive(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Fiber $cid is not active"
    }

    final case object MalformedFiberRecord extends DataApplicationValidationError {
      override val message: String = "Malformed fiber record found in state"
    }

    // --- Parent fiber errors ---

    final case class ParentFiberNotFound(parentId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Parent fiber $parentId not found"
    }

    final case class ParentFiberNotActive(parentId: UUID) extends DataApplicationValidationError {
      override val message: String = s"Parent fiber $parentId is not active"
    }

    // --- Signature errors ---

    final case object NoProofsProvided extends DataApplicationValidationError {
      override val message: String = "No signature proofs provided"
    }

    final case object NotSignedByOwner extends DataApplicationValidationError {
      override val message: String = "Update not signed by any fiber owner"
    }

    // --- Transition errors ---

    final case class NoTransitionForEvent(
      state:     StateId,
      eventType: EventType
    ) extends DataApplicationValidationError {
      override val message: String = s"No transition from state ${state.value} for event ${eventType.value}"
    }
  }
}
