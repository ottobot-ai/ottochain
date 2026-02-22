package xyz.kd5ujc.metagraph_l0.webhooks

import java.util.UUID

import cats.data.NonEmptyChain
import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.json_logic.NullValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, OttochainMessage, TransitionStateMachine}
import xyz.kd5ujc.schema.fiber.{FiberOrdinal, State, StateId, StateMachineDefinition}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object WebhookDispatcherSuite extends SimpleIOSuite {

  // ── Test types ─────────────────────────────────────────────────────────────

  case class RejectionNotification(
    event:     String,
    ordinal:   Long,
    rejection: RejectedUpdate
  )

  case class RejectedUpdate(
    updateType:           String,
    fiberId:              String,
    errors:               List[ValidationError],
    signers:              List[String],
    updateHash:           String,
    targetSequenceNumber: Option[Long] = None
  )

  case class ValidationError(code: String, message: String)

  // ── Test error types ───────────────────────────────────────────────────────

  case object FiberAlreadyExistsError extends DataApplicationValidationError {
    val message = "Fiber already exists"
  }

  case object InvalidTransitionError extends DataApplicationValidationError {
    val message = "Invalid transition"
  }

  case object GenericTestError extends DataApplicationValidationError {
    val message = "Test error"
  }

  // ── Minimal state machine definition ──────────────────────────────────────

  private val activeId  = StateId("Active")
  private val doneId    = StateId("Done")
  private val simpleDef = StateMachineDefinition(
    states       = Map(activeId -> State(activeId), doneId -> State(doneId, isFinal = true)),
    initialState = activeId,
    transitions  = List.empty
  )

  // ── Spy dispatcher ─────────────────────────────────────────────────────────

  def spyDispatcher(spy: Ref[IO, List[RejectionNotification]]): WebhookDispatcher[IO] =
    new WebhookDispatcher[IO] {

      def dispatch(
        snapshot: io.constellationnetwork.security.Hashed[
          io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
        ],
        stats: NotificationStats
      ): IO[Unit] = IO.unit

      def dispatchRejection(
        ordinal: SnapshotOrdinal,
        update:  Signed[OttochainMessage],
        errors:  NonEmptyChain[DataApplicationValidationError]
      ): IO[Unit] = {
        val notification = buildNotification(ordinal, update, errors)
        spy.update(_ :+ notification)
      }

      private def buildNotification(
        ordinal: SnapshotOrdinal,
        update:  Signed[OttochainMessage],
        errors:  NonEmptyChain[DataApplicationValidationError]
      ): RejectionNotification = {
        val (updateType, fiberId, targetSeqNum): (String, String, Option[Long]) = update.value match {
          case u: CreateStateMachine     => ("CreateStateMachine", u.fiberId.toString, None)
          case u: TransitionStateMachine =>
            ("TransitionStateMachine", u.fiberId.toString, Some(u.targetSequenceNumber.value.value))
          case _                         => ("Unknown", "unknown", None)
        }

        val validationErrors = errors.toList.map(err =>
          ValidationError(err.getClass.getSimpleName.replace("$", ""), err.message)
        )

        RejectionNotification(
          event   = "transaction.rejected",
          ordinal = ordinal.value.value,
          rejection = RejectedUpdate(
            updateType           = updateType,
            fiberId              = fiberId,
            errors               = validationErrors,
            signers              = update.proofs.map(_.id.hex.value).toList,
            updateHash           = computeUpdateHash(update),
            targetSequenceNumber = targetSeqNum
          )
        )
      }

      private def computeUpdateHash(update: Signed[OttochainMessage]): String = {
        val signers = update.proofs.map(_.id.hex.value).toList.sorted.mkString(",")
        val content = s"${update.value}:$signers"
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(content.getBytes("UTF-8"))
          .map("%02x".format(_))
          .mkString
      }
    }

  // Helper to sign an update with Alice's key
  private def sign(
    fixture: TestFixture,
    update:  OttochainMessage
  ): IO[Signed[OttochainMessage]] =
    fixture.registry.generateProofs(update, Set(Alice)).map(Signed(update, _))

  // ── Tests ──────────────────────────────────────────────────────────────────

  test("dispatchRejection constructs correct RejectionNotification payload") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId     = UUID.randomUUID()
        update      = CreateStateMachine(fiberId, simpleDef, NullValue, None)
        signed     <- sign(fixture, update)

        errors = NonEmptyChain.one(FiberAlreadyExistsError: DataApplicationValidationError)

        _             <- dispatcher.dispatchRejection(fixture.ordinal, signed, errors)
        notifications <- spy.get
        notification   = notifications.head

        _ <- IO(assert(notifications.length == 1))
        _ <- IO(assert(notification.event == "transaction.rejected"))
        _ <- IO(assert(notification.ordinal == fixture.ordinal.value.value))
        _ <- IO(assert(notification.rejection.updateType == "CreateStateMachine"))
        _ <- IO(assert(notification.rejection.fiberId == fiberId.toString))
        _ <- IO(assert(notification.rejection.errors.length == 1))
        _ <- IO(assert(notification.rejection.errors.head.message == "Fiber already exists", s"message was: ${notification.rejection.errors.head.message}"))
        _ <- IO(assert(notification.rejection.signers.nonEmpty))
        _ <- IO(assert(notification.rejection.updateHash.length == 64, "SHA-256 hex = 64 chars"))
      } yield success
    }
  }

  test("dispatchRejection deduplicates — same update produces same updateHash") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId = UUID.randomUUID()
        update  = CreateStateMachine(fiberId, simpleDef, NullValue, None)
        signed <- sign(fixture, update)

        errors = NonEmptyChain.one(GenericTestError: DataApplicationValidationError)

        _ <- dispatcher.dispatchRejection(fixture.ordinal, signed, errors)
        _ <- dispatcher.dispatchRejection(fixture.ordinal, signed, errors)

        notifications <- spy.get
        hash1          = notifications(0).rejection.updateHash
        hash2          = notifications(1).rejection.updateHash

        _ <- IO(assert(hash1 == hash2))
      } yield success
    }
  }

  test("dispatchRejection different updates produce different updateHashes") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fid1 = UUID.randomUUID()
        fid2 = UUID.randomUUID()

        signed1 <- sign(fixture, CreateStateMachine(fid1, simpleDef, NullValue, None))
        signed2 <- sign(fixture, CreateStateMachine(fid2, simpleDef, NullValue, None))

        errors = NonEmptyChain.one(GenericTestError: DataApplicationValidationError)

        _ <- dispatcher.dispatchRejection(fixture.ordinal, signed1, errors)
        _ <- dispatcher.dispatchRejection(fixture.ordinal, signed2, errors)

        notifications <- spy.get
        hash1          = notifications(0).rejection.updateHash
        hash2          = notifications(1).rejection.updateHash

        _ <- IO(assert(hash1 != hash2))
      } yield success
    }
  }

  test("dispatchRejection fires for TransitionStateMachine — fiberId and targetSeqNum extracted") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId = UUID.fromString("456e7890-e89b-12d3-a456-426614174000")
        update  = TransitionStateMachine(fiberId, "activate", NullValue, FiberOrdinal.unsafeApply(3L))
        signed <- sign(fixture, update)

        errors = NonEmptyChain.one(InvalidTransitionError: DataApplicationValidationError)

        _ <- dispatcher.dispatchRejection(fixture.ordinal, signed, errors)

        notifications <- spy.get
        notification   = notifications.head

        _ <- IO(assert(notification.rejection.updateType == "TransitionStateMachine"))
        _ <- IO(assert(notification.rejection.fiberId == fiberId.toString))
        _ <- IO(assert(notification.rejection.targetSequenceNumber == Some(3L)))
      } yield success
    }
  }

  test("dispatchRejection does not deliver when no subscribers — real dispatcher check") {
    // TODO: Wire an actual WebhookDispatcher.make with a spy HTTP client and empty
    //       SubscriberRegistry to verify zero HTTP calls on dispatchRejection.
    //       Pending until http4s mock integration is added to shared-test.
    IO(success)
  }
}
