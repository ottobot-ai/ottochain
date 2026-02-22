package xyz.kd5ujc.metagraph_l0.webhooks

import java.util.UUID

import cats.data.{NonEmptyChain, NonEmptyList, Validated}
import cats.effect.{Async, IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.metagraph_sdk.json_logic.NullValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, OttochainMessage}
import xyz.kd5ujc.schema.fiber.{State, StateId, StateMachineDefinition}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Behavioural tests for ML0Service rejection webhook dispatch.
 *
 * These tests use a MockValidator that simulates the per-update validation and
 * fire-and-forget rejection dispatch that the real ML0Service.validateData
 * should implement. They verify dispatch semantics without a full L0 node harness.
 */
object ML0ServiceRejectionSuite extends SimpleIOSuite {

  // ── Types ──────────────────────────────────────────────────────────────────

  case class RejectionNotification(
    event:     String,
    ordinal:   Long,
    rejection: RejectedUpdate
  )

  case class RejectedUpdate(
    updateType: String,
    fiberId:    String,
    errors:     List[ValidationError],
    signers:    List[String],
    updateHash: String
  )

  case class ValidationError(code: String, message: String)

  // ── Test error types ───────────────────────────────────────────────────────

  case object FiberAlreadyExists extends DataApplicationValidationError {
    val message = "Fiber already exists"
  }

  // ── Minimal state machine definition ──────────────────────────────────────

  private val activeId  = StateId("Active")
  private val simpleDef = StateMachineDefinition(
    states       = Map(activeId -> State(activeId)),
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
        val fiberId = update.value match {
          case u: CreateStateMachine => u.fiberId.toString
          case _                     => "unknown"
        }

        val notification = RejectionNotification(
          event   = "transaction.rejected",
          ordinal = ordinal.value.value,
          rejection = RejectedUpdate(
            updateType = update.value.getClass.getSimpleName,
            fiberId    = fiberId,
            errors     = errors.toList.map(e =>
              ValidationError(e.getClass.getSimpleName.replace("$", ""), e.message)
            ),
            signers    = update.proofs.map(_.id.hex.value).toList,
            updateHash = "mock-hash"
          )
        )

        spy.update(_ :+ notification)
      }
    }

  // ── MockValidator: simulates ML0Service.validateData per-update semantics ─
  //
  // ML0Service.validateData:
  //  1. Runs validateSignedUpdate per update in parallel
  //  2. For each Invalid result, fires dispatchRejection fire-and-forget
  //  3. Returns combined validation result (Valid/Invalid)
  //
  // MockValidator reproduces this logic for behavioural testing.
  // ──────────────────────────────────────────────────────────────────────────

  final case class MockValidator(validationResults: Map[UUID, Boolean]) {

    def validateDataParallel(
      state:             DataState[OnChain, CalculatedState],
      updates:           NonEmptyList[Signed[OttochainMessage]],
      webhookDispatcher: Option[WebhookDispatcher[IO]],
      ordinal:           SnapshotOrdinal
    ): IO[Validated[NonEmptyChain[DataApplicationValidationError], Unit]] = {

      updates.toList
        .traverse { signedUpdate =>
          val fiberId = signedUpdate.value match {
            case u: CreateStateMachine => u.fiberId
            case _                     => UUID.randomUUID()
          }

          val isValid = validationResults.getOrElse(fiberId, true)

          if (isValid) {
            IO.pure(Validated.valid[NonEmptyChain[DataApplicationValidationError], Unit](()))
          } else {
            val error: DataApplicationValidationError = FiberAlreadyExists
            val result: Validated[NonEmptyChain[DataApplicationValidationError], Unit] =
              Validated.invalid(NonEmptyChain.one(error))

            // Fire-and-forget rejection dispatch — mirrors ML0Service.validateData
            val dispatchEffect = webhookDispatcher match {
              case Some(dispatcher) =>
                Async[IO]
                  .start(
                    dispatcher
                      .dispatchRejection(ordinal, signedUpdate, NonEmptyChain.one(error))
                      .handleErrorWith(err => IO(println(s"Rejection webhook error: ${err.getMessage}")))
                  )
                  .void
              case None =>
                IO.unit
            }

            dispatchEffect.as(result)
          }
        }
        .map(
          _.reduce { (a, b) =>
            (a, b) match {
              case (Validated.Valid(_), Validated.Valid(_))          => Validated.valid(())
              case (Validated.Valid(_), inv @ Validated.Invalid(_))  => inv
              case (inv @ Validated.Invalid(_), Validated.Valid(_))  => inv
              case (Validated.Invalid(e1), Validated.Invalid(e2))    => Validated.invalid(e1 ++ e2)
            }
          }
        )
    }
  }

  // Helper: sign an update with Alice's key via registry
  private def sign(fixture: TestFixture, update: OttochainMessage): IO[Signed[OttochainMessage]] =
    fixture.registry.generateProofs(update, Set(Alice)).map(Signed(update, _))

  // ── Tests ──────────────────────────────────────────────────────────────────

  test("validateData dispatches rejection for each invalid update") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        validFid   = UUID.randomUUID()
        invalidFid = UUID.randomUUID()

        signedValid   <- sign(fixture, CreateStateMachine(validFid, simpleDef, NullValue, None))
        signedInvalid <- sign(fixture, CreateStateMachine(invalidFid, simpleDef, NullValue, None))

        updates   = NonEmptyList.of(signedValid, signedInvalid)
        state     = DataState(OnChain.genesis, CalculatedState.genesis)
        validator = MockValidator(Map(validFid -> true, invalidFid -> false))
        result   <- validator.validateDataParallel(state, updates, dispatcher, fixture.ordinal)

        notifications <- spy.get

        _ <- IO(assert(notifications.length == 1, s"Expected 1 rejection, got ${notifications.length}"))
        _ <- IO(assert(notifications.head.rejection.fiberId == invalidFid.toString))
        _ <- IO(assert(result.isInvalid))
      } yield success
    }
  }

  test("validateData does NOT dispatch rejection for valid-only batch") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        fid1 = UUID.randomUUID()
        fid2 = UUID.randomUUID()

        s1 <- sign(fixture, CreateStateMachine(fid1, simpleDef, NullValue, None))
        s2 <- sign(fixture, CreateStateMachine(fid2, simpleDef, NullValue, None))

        updates   = NonEmptyList.of(s1, s2)
        state     = DataState(OnChain.genesis, CalculatedState.genesis)
        validator = MockValidator(Map(fid1 -> true, fid2 -> true))
        result   <- validator.validateDataParallel(state, updates, dispatcher, fixture.ordinal)

        notifications <- spy.get

        _ <- IO(assert(notifications.isEmpty, "Expected no rejections for valid-only batch"))
        _ <- IO(assert(result.isValid))
      } yield success
    }
  }

  test("validateData dispatches all rejections in a mixed batch") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        validFid    = UUID.randomUUID()
        invalidFid1 = UUID.randomUUID()
        invalidFid2 = UUID.randomUUID()

        sv  <- sign(fixture, CreateStateMachine(validFid, simpleDef, NullValue, None))
        si1 <- sign(fixture, CreateStateMachine(invalidFid1, simpleDef, NullValue, None))
        si2 <- sign(fixture, CreateStateMachine(invalidFid2, simpleDef, NullValue, None))

        updates = NonEmptyList.of(sv, si1, si2)
        state   = DataState(OnChain.genesis, CalculatedState.genesis)

        validator = MockValidator(Map(validFid -> true, invalidFid1 -> false, invalidFid2 -> false))
        result   <- validator.validateDataParallel(state, updates, dispatcher, fixture.ordinal)

        notifications <- spy.get
        rejectedFids   = notifications.map(_.rejection.fiberId).toSet
        expectedFids   = Set(invalidFid1.toString, invalidFid2.toString)

        _ <- IO(assert(notifications.length == 2, s"Expected 2 rejections, got ${notifications.length}"))
        _ <- IO(assert(rejectedFids == expectedFids))
        _ <- IO(assert(result.isInvalid))
      } yield success
    }
  }

  test("validateData does NOT dispatch when webhookDispatcher is None") {
    TestFixture.resource().use { fixture =>
      for {
        invalidFid <- IO(UUID.randomUUID())
        signed     <- sign(fixture, CreateStateMachine(invalidFid, simpleDef, NullValue, None))

        updates   = NonEmptyList.of(signed)
        state     = DataState(OnChain.genesis, CalculatedState.genesis)
        validator = MockValidator(Map(invalidFid -> false))
        // None dispatcher — dispatchRejection must not be called
        result <- validator.validateDataParallel(state, updates, None, fixture.ordinal)

        // Validation still reflects invalid update
        _ <- IO(assert(result.isInvalid))
      } yield success
    }
  }

  test("validateData returns combined errors for all-invalid batch") {
    TestFixture.resource().use { fixture =>
      for {
        spy       <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        fid1 = UUID.randomUUID()
        fid2 = UUID.randomUUID()
        fid3 = UUID.randomUUID()

        s1 <- sign(fixture, CreateStateMachine(fid1, simpleDef, NullValue, None))
        s2 <- sign(fixture, CreateStateMachine(fid2, simpleDef, NullValue, None))
        s3 <- sign(fixture, CreateStateMachine(fid3, simpleDef, NullValue, None))

        updates = NonEmptyList.of(s1, s2, s3)
        state   = DataState(OnChain.genesis, CalculatedState.genesis)

        validator = MockValidator(Map(fid1 -> false, fid2 -> false, fid3 -> false))
        result   <- validator.validateDataParallel(state, updates, dispatcher, fixture.ordinal)

        _ <- result match {
          case Validated.Invalid(errors) =>
            IO(assert(errors.length == 3L, s"Expected 3 combined errors, got ${errors.length}"))
          case Validated.Valid(_) =>
            IO.raiseError(new AssertionError("Expected validation to fail for all-invalid batch"))
        }
      } yield success
    }
  }

  test("validateData rejection dispatch is fire-and-forget — does not block validation") {
    TestFixture.resource().use { fixture =>
      for {
        slowDispatcher <- IO.delay {
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
            ): IO[Unit] = IO.sleep(scala.concurrent.duration.FiniteDuration(500, "milliseconds"))
          }
        }

        invalidFid <- IO(UUID.randomUUID())
        signed     <- sign(fixture, CreateStateMachine(invalidFid, simpleDef, NullValue, None))

        updates   = NonEmptyList.of(signed)
        state     = DataState(OnChain.genesis, CalculatedState.genesis)
        validator = MockValidator(Map(invalidFid -> false))

        startMs <- IO(System.currentTimeMillis())
        result  <- validator.validateDataParallel(state, updates, Some(slowDispatcher), fixture.ordinal)
        endMs   <- IO(System.currentTimeMillis())

        elapsedMs = endMs - startMs

        // Dispatch is fire-and-forget via IO.start — should return well under 500ms
        _ <- IO(assert(elapsedMs < 400, s"validateData blocked for ${elapsedMs}ms (expected <400ms)"))
        _ <- IO(assert(result.isInvalid))
      } yield success
    }
  }
}
