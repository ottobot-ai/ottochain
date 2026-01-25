package xyz.kd5ujc.shared_data.lifecycle

import java.util.UUID

import cats.data.{EitherT, NonEmptySet, Validated}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Monad, Parallel}

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.metagraph_sdk.json_logic.{
  BoolValue,
  ConstExpression,
  JsonLogicValue,
  MapValue,
  NullValue
}
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L1ContextOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}

import io.circe.DecodingFailure

object Validator {

  def make[F[_]: Async: Parallel: SecurityProvider]
    : F[ValidationService[F, OttochainMessage, OnChain, CalculatedState]] =
    CheckpointService.make[F, OnChain](OnChain.genesis).map { checkpointService =>
      new ValidationService[F, OttochainMessage, OnChain, CalculatedState] {

        private def onChainCache(context: L1NodeContext[F])(
          f: Checkpoint[OnChain] => F[DataApplicationValidationErrorOr[Unit]]
        ): F[DataApplicationValidationErrorOr[Unit]] =
          checkpointService
            .evalModify[DataApplicationValidationError] { checkpoint =>
              context.getLatestCurrencySnapshot.flatMap {
                case Right(snapshot) if snapshot.ordinal > checkpoint.ordinal =>
                  context.getOnChainState[OnChain].map(_.map(Checkpoint(snapshot.ordinal, _)))
                case Right(_)  => checkpoint.asRight[DataApplicationValidationError].pure[F]
                case Left(err) => err.asLeft[Checkpoint[OnChain]].pure[F]
              }
            }
            .flatMap(_.fold(_.invalidNec[Unit].pure[F], f))

        override def validateUpdate(
          update: OttochainMessage
        )(implicit ctx: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          onChainCache(ctx) { checkpoint =>
            val updateValidator = new UpdateImpl[F](checkpoint.state)

            update match {
              case u: Updates.CreateStateMachineFiber => ().validNec[DataApplicationValidationError].pure[F]
              case u: Updates.ProcessFiberEvent       => ().validNec[DataApplicationValidationError].pure[F]
              case u: Updates.ArchiveFiber            => ().validNec[DataApplicationValidationError].pure[F]
              case u: Updates.CreateScriptOracle      => ().validNec[DataApplicationValidationError].pure[F]
              case u: Updates.InvokeScriptOracle      => ().validNec[DataApplicationValidationError].pure[F]

//              case u: Updates.CreateStateMachineFiber => updateValidator.createFiber(u)
//              case u: Updates.ProcessFiberEvent       => updateValidator.processEvent(u)
//              case u: Updates.ArchiveFiber            => updateValidator.archiveFiber(u)
//              case u: Updates.CreateScriptOracle      => updateValidator.createOracle(u)
//              case u: Updates.InvokeScriptOracle      => updateValidator.invokeOracle(u)
            }
          }

        override def validateSignedUpdate(
          current:      DataState[OnChain, CalculatedState],
          signedUpdate: Signed[OttochainMessage]
        )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] = {
          val updateValidator = new UpdateImpl[F](current.onChain)
          val signedValidator = new SignedUpdateImpl[F](current, signedUpdate.proofs)

          signedUpdate.value match {
            case u: Updates.CreateStateMachineFiber => ().validNec[DataApplicationValidationError].pure[F]
            case u: Updates.ProcessFiberEvent       => ().validNec[DataApplicationValidationError].pure[F]
            case u: Updates.ArchiveFiber            => ().validNec[DataApplicationValidationError].pure[F]
            case u: Updates.CreateScriptOracle      => ().validNec[DataApplicationValidationError].pure[F]
            case u: Updates.InvokeScriptOracle      => ().validNec[DataApplicationValidationError].pure[F]

//            case u: Updates.CreateStateMachineFiber =>
//              for {
//                updateTests <- updateValidator.createFiber(u)
//                signedTests <- signedValidator.createFiber(u)
//              } yield updateTests |+| signedTests
//
//            case u: Updates.ProcessFiberEvent =>
//              for {
//                updateTests <- updateValidator.processEvent(u)
//                signedTests <- signedValidator.processEvent(u)
//              } yield updateTests |+| signedTests
//
//            case u: Updates.ArchiveFiber =>
//              for {
//                updateTests <- updateValidator.archiveFiber(u)
//                signedTests <- signedValidator.archiveFiber(u)
//              } yield updateTests |+| signedTests
//
//            case _: Updates.CreateScriptOracle => ().validNec.pure[F]
//            case _: Updates.InvokeScriptOracle => ().validNec.pure[F]
          }
        }
      }
    }

  private class UpdateImpl[F[_]: Monad](state: OnChain) {

    def createFiber(update: Updates.CreateStateMachineFiber): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.cidNotUsed(update.cid, state)
      test2 <- Rules.validStateMachineDefinition(update.definition)
      test3 <- Rules.initialDataIsMapValue(update.initialData)
    } yield List(test1, test2, test3).combineAll

    def processEvent(update: Updates.ProcessFiberEvent): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.cidIsFound(update.cid, state)
      test2 <- Rules.eventPayloadIsValid(update.event)
    } yield List(test1, test2).combineAll

    def archiveFiber(update: Updates.ArchiveFiber): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.cidIsFound(update.cid, state)
    } yield List(test1).combineAll

    def createOracle(update: Updates.CreateScriptOracle): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.cidNotUsed(update.cid, state)
      test2 <- Rules.initialStateIsMapValueOrNull(update.initialState)
    } yield List(test1, test2).combineAll

    def invokeOracle(update: Updates.InvokeScriptOracle): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.cidIsFound(update.cid, state)
    } yield List(test1).combineAll
  }

  private class SignedUpdateImpl[F[_]: Async: SecurityProvider](
    state:  DataState[OnChain, CalculatedState],
    proofs: NonEmptySet[SignatureProof]
  ) {

    def createFiber(
      _update: Updates.CreateStateMachineFiber
    ): F[DataApplicationValidationErrorOr[Unit]] =
      Rules.hasProofs(proofs)

    def processEvent(
      update: Updates.ProcessFiberEvent
    ): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.fiberIsActive(update.cid, state.calculated)
      test2 <- Rules.updateSignedByOwners(update.cid, proofs, state.calculated)
      test3 <- Rules.transitionExists(update.cid, update.event.eventType, state.calculated)
    } yield List(test1, test2, test3).combineAll

    def archiveFiber(
      update: Updates.ArchiveFiber
    ): F[DataApplicationValidationErrorOr[Unit]] = for {
      test1 <- Rules.fiberIsActive(update.cid, state.calculated)
      test2 <- Rules.updateSignedByOwners(update.cid, proofs, state.calculated)
    } yield List(test1, test2).combineAll
  }

  private object Rules {

    def cidNotUsed[F[_]: Applicative](cid: UUID, state: OnChain): F[DataApplicationValidationErrorOr[Unit]] =
      Validated
        .condNec(
          !state.latest.contains(cid),
          (),
          Errors.FiberAlreadyExists(cid): DataApplicationValidationError
        )
        .pure[F]

    def cidIsFound[F[_]: Applicative](cid: UUID, state: OnChain): F[DataApplicationValidationErrorOr[Unit]] =
      Validated
        .condNec(
          state.latest.contains(cid),
          (),
          Errors.FiberNotFound(cid): DataApplicationValidationError
        )
        .pure[F]

    def validStateMachineDefinition[F[_]: Applicative](
      definition: StateMachine.StateMachineDefinition
    ): F[DataApplicationValidationErrorOr[Unit]] = {
      val validations: List[DataApplicationValidationErrorOr[Unit]] = List(
        // Check has states
        Validated.condNec(
          definition.states.nonEmpty,
          (),
          Errors.NoStatesInDefinition: DataApplicationValidationError
        ),
        // Check initial state exists
        Validated.condNec(
          definition.states.contains(definition.initialState),
          (),
          Errors.InitialStateNotFound(definition.initialState): DataApplicationValidationError
        ),
        // Check transitions reference valid states
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
        // Check no exact duplicate transitions (same from, to, event, guard, effect, dependencies)
        Validated.condNec(
          {
            val duplicates = definition.transitions
              .groupBy(identity)
              .filter(_._2.size > 1)
            duplicates.isEmpty
          },
          (),
          Errors.DuplicateTransitions: DataApplicationValidationError
        ),
        // Check no ambiguous transitions (multiple transitions from same state+event with unconditional guards)
        Validated.condNec(
          {
            val ambiguous = definition.transitions
              .groupBy(t => (t.from, t.eventType))
              .exists { case (_, transitions) =>
                transitions.size > 1 && transitions.exists { t =>
                  t.guard match {
                    case ConstExpression(BoolValue(true)) => true
                    case _                                => false
                  }
                }
              }
            !ambiguous
          },
          (),
          Errors.AmbiguousTransitions: DataApplicationValidationError
        )
      )

      validations.combineAll.pure[F]
    }

    def initialDataIsMapValue[F[_]: Applicative](data: JsonLogicValue): F[DataApplicationValidationErrorOr[Unit]] =
      data match {
        case _: MapValue => ().validNec[DataApplicationValidationError].pure[F]
        case _           => (Errors.InitialDataNotMapValue: DataApplicationValidationError).invalidNec[Unit].pure[F]
      }

    def initialStateIsMapValueOrNull[F[_]: Applicative](
      data: Option[JsonLogicValue]
    ): F[DataApplicationValidationErrorOr[Unit]] =
      data match {
        case None              => ().validNec[DataApplicationValidationError].pure[F]
        case Some(NullValue)   => ().validNec[DataApplicationValidationError].pure[F]
        case Some(_: MapValue) => ().validNec[DataApplicationValidationError].pure[F]
        case _ => (Errors.OracleInitialStateInvalid: DataApplicationValidationError).invalidNec[Unit].pure[F]
      }

    def eventPayloadIsValid[F[_]: Applicative](event: StateMachine.Event): F[DataApplicationValidationErrorOr[Unit]] =
      event.payload match {
        case NullValue => (Errors.NullEventPayload: DataApplicationValidationError).invalidNec[Unit].pure[F]
        case _         => ().validNec[DataApplicationValidationError].pure[F]
      }

    def hasProofs[F[_]: Applicative](
      proofs: NonEmptySet[SignatureProof]
    ): F[DataApplicationValidationErrorOr[Unit]] =
      Validated
        .condNec(
          proofs.nonEmpty,
          (),
          Errors.NoProofsProvided: DataApplicationValidationError
        )
        .pure[F]

    def fiberIsActive[F[_]: Monad](
      cid:   UUID,
      state: CalculatedState
    ): F[DataApplicationValidationErrorOr[Unit]] = (for {
      record <- ValidatorUtils.getFiberRecord(cid, state)
      result <- EitherT.cond(
        record.status == Records.FiberStatus.Active,
        (),
        Errors.FiberNotActive(cid): DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    def updateSignedByOwners[F[_]: Async: SecurityProvider](
      cid:    UUID,
      proofs: NonEmptySet[SignatureProof],
      state:  CalculatedState
    ): F[DataApplicationValidationErrorOr[Unit]] = (for {
      record          <- ValidatorUtils.getFiberRecord(cid, state)
      signerAddresses <- EitherT.liftF(proofs.toList.traverse(_.id.toAddress))
      signerSet = signerAddresses.toSet
      result <- EitherT.cond(
        signerSet.intersect(record.owners).nonEmpty,
        (),
        Errors.NotSignedByOwner: DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )

    def transitionExists[F[_]: Monad](
      cid:       UUID,
      eventType: StateMachine.EventType,
      state:     CalculatedState
    ): F[DataApplicationValidationErrorOr[Unit]] = (for {
      record <- ValidatorUtils.getFiberRecord(cid, state)
      hasTransition = record.definition.transitionMap.contains((record.currentState, eventType))
      result <- EitherT.cond(
        hasTransition,
        (),
        Errors.NoTransitionForEvent(record.currentState, eventType): DataApplicationValidationError
      )
    } yield result).fold(
      _.invalidNec[Unit],
      _.validNec[DataApplicationValidationError]
    )
  }

  private object ValidatorUtils {

    def getFiberRecord[F[_]: Monad](
      cid:   UUID,
      state: CalculatedState
    ): EitherT[F, DataApplicationValidationError, Records.StateMachineFiberRecord] =
      EitherT.fromOption(state.stateMachines.get(cid), Errors.FiberNotFound(cid)).flatMap { record =>
        EitherT.fromEither(record match {
          case value: Records.StateMachineFiberRecord => value.asRight[DataApplicationValidationError]
          case _ => Errors.MalformedFiberRecord.asLeft[Records.StateMachineFiberRecord]
        })
      }
  }

  private object Errors {

    final case class FiberAlreadyExists(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Fiber $cid already exists"
    }

    final case class FiberNotFound(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Fiber $cid not found"
    }

    final case class FiberNotActive(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Fiber $cid is not active"
    }

    final case object NoStatesInDefinition extends DataApplicationValidationError {
      override val message: String = "State machine definition has no states"
    }

    final case class InitialStateNotFound(state: StateMachine.StateId) extends DataApplicationValidationError {
      override val message: String = s"Initial state ${state.value} not found in states"
    }

    final case class TransitionFromInvalidState(state: StateMachine.StateId) extends DataApplicationValidationError {
      override val message: String = s"Transition references invalid from state: ${state.value}"
    }

    final case class TransitionToInvalidState(state: StateMachine.StateId) extends DataApplicationValidationError {
      override val message: String = s"Transition references invalid to state: ${state.value}"
    }

    final case object DuplicateTransitions extends DataApplicationValidationError {

      override val message: String =
        "Exact duplicate transitions detected (same from, to, event, guard, effect, and dependencies)"
    }

    final case object AmbiguousTransitions extends DataApplicationValidationError {

      override val message: String =
        "Ambiguous transitions detected: multiple transitions from same state+event with at least one unconditional guard (true)"
    }

    final case object InitialDataNotMapValue extends DataApplicationValidationError {
      override val message: String = "Initial data must be a MapValue"
    }

    final case class FailedDecodingJson(failure: DecodingFailure) extends DataApplicationValidationError {
      override val message: String = s"Failed to decode JSON: $failure"
    }

    final case object NoProofsProvided extends DataApplicationValidationError {
      override val message: String = "No signature proofs provided"
    }

    final case object NotSignedByOwner extends DataApplicationValidationError {
      override val message: String = "Update not signed by any fiber owner"
    }

    final case class NoTransitionForEvent(
      state:     StateMachine.StateId,
      eventType: StateMachine.EventType
    ) extends DataApplicationValidationError {
      override val message: String = s"No transition from state ${state.value} for event ${eventType.value}"
    }

    final case object MalformedFiberRecord extends DataApplicationValidationError {
      override val message: String = "Malformed fiber record found in state"
    }

    final case object NullEventPayload extends DataApplicationValidationError {
      override val message: String = "Event payload cannot be null"
    }

    final case object OracleInitialStateInvalid extends DataApplicationValidationError {
      override val message: String = "Oracle initial state must be a MapValue or null"
    }
  }
}
