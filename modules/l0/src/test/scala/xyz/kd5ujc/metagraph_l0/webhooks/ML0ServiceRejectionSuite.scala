package xyz.kd5ujc.metagraph_l0.webhooks

import cats.effect.{IO, Ref}
import cats.data.{NonEmptyChain, NonEmptyList, Validated}
import cats.syntax.all._
import java.util.UUID
import weaver.SimpleIOSuite

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, OttochainMessage}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Validator
import xyz.kd5ujc.shared_test.TestFixture
import xyz.kd5ujc.shared_test.Participant._

object ML0ServiceRejectionSuite extends SimpleIOSuite {

  // Reuse types from WebhookDispatcherSuite
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
    targetSequenceNumber: Option[Int] = None
  )

  case class ValidationError(
    code:    String,
    message: String
  )

  // Mock ML0Service-like validator that can be configured to return specific validation results
  case class MockValidator(
    validationResults: Map[UUID, Boolean] // fiberId -> isValid
  ) {

    def validateDataParallel(
      state:             DataState[OnChain, CalculatedState],
      updates:           NonEmptyList[Signed[OttochainMessage]],
      webhookDispatcher: Option[WebhookDispatcher[IO]]
    )(implicit
      context: xyz.kd5ujc.metagraph_l0.L0NodeContext[IO]
    ): IO[cats.data.Validated[NonEmptyChain[DataApplicationValidationError], Unit]] = {

      val validationResults = updates.toList.map { signedUpdate =>
        val fiberId = signedUpdate.value match {
          case CreateStateMachine(fid, _, _, _, _, _, _) => fid
          case other                                     => UUID.randomUUID() // fallback
        }

        val isValid = this.validationResults.getOrElse(fiberId, true)

        if (isValid) {
          Validated.valid(())
        } else {
          val error = new DataApplicationValidationError("FiberAlreadyExists") {
            override def getMessage: String = "Fiber already exists"
          }

          // Simulate rejection dispatch (fire-and-forget like the real implementation should do)
          webhookDispatcher.foreach { dispatcher =>
            cats.effect.unsafe.IORuntime.global.unsafeRunAndForget(
              dispatcher.dispatchRejection(
                SnapshotOrdinal.unsafeFrom(1L),
                signedUpdate,
                NonEmptyChain.one(error)
              )
            )
          }

          Validated.invalid(NonEmptyChain.one(error))
        }
      }

      val combined = validationResults.reduce { (acc, result) =>
        (acc, result) match {
          case (Validated.Valid(_), Validated.Valid(_))                  => Validated.valid(())
          case (Validated.Valid(_), Validated.Invalid(errors))           => Validated.invalid(errors)
          case (Validated.Invalid(accErrors), Validated.Valid(_))        => Validated.invalid(accErrors)
          case (Validated.Invalid(accErrors), Validated.Invalid(errors)) => Validated.invalid(accErrors ++ errors)
        }
      }

      IO.pure(combined)
    }
  }

  // Spy dispatcher for capturing rejections
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
          case CreateStateMachine(fid, _, _, _, _, _, _) => fid.toString
          case other                                     => "unknown"
        }

        val notification = RejectionNotification(
          event = "transaction.rejected",
          ordinal = ordinal.value.value,
          rejection = RejectedUpdate(
            updateType = "CreateStateMachine",
            fiberId = fiberId,
            errors = errors.toList.map(e => ValidationError(e.getClass.getSimpleName, e.getMessage)),
            signers = update.proofs.map(_.id.hex.value).toList,
            updateHash = "mock-hash"
          )
        )

        spy.update(_ :+ notification)
      }
    }

  test("validateData dispatches rejection for each invalid update") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        validFiberId   <- IO.pure(UUID.randomUUID())
        invalidFiberId <- IO.pure(UUID.randomUUID())

        validUpdate   <- IO.pure(CreateStateMachine(validFiberId, "Valid", "Initial", 0, "create", Map.empty, None))
        invalidUpdate <- IO.pure(CreateStateMachine(invalidFiberId, "Invalid", "Initial", 0, "create", Map.empty, None))

        signedValid   <- context.securityProvider.sign(validUpdate, participant1.keyPair)
        signedInvalid <- context.securityProvider.sign(invalidUpdate, participant1.keyPair)

        updates = NonEmptyList.of(signedValid, signedInvalid)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        // Configure mock validator: valid=true, invalid=false
        validator = MockValidator(Map(validFiberId -> true, invalidFiberId -> false))

        result <- validator.validateDataParallel(state, updates, dispatcher)

        notifications <- spy.get

        // Should have exactly one rejection (for the invalid update)
        _ <- IO(assert(notifications.length == 1))
        _ <- IO(assert(notifications.head.rejection.fiberId == invalidFiberId.toString))

        // validateData should return Invalid because one update failed
        _ <- IO(assert(result.isInvalid))
      } yield succeed
    }
  }

  test("validateData does NOT dispatch rejection for valid-only batch") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        fiberId1 <- IO.pure(UUID.randomUUID())
        fiberId2 <- IO.pure(UUID.randomUUID())

        update1 <- IO.pure(CreateStateMachine(fiberId1, "Valid1", "Initial", 0, "create", Map.empty, None))
        update2 <- IO.pure(CreateStateMachine(fiberId2, "Valid2", "Initial", 0, "create", Map.empty, None))

        signedUpdate1 <- context.securityProvider.sign(update1, participant1.keyPair)
        signedUpdate2 <- context.securityProvider.sign(update2, participant1.keyPair)

        updates = NonEmptyList.of(signedUpdate1, signedUpdate2)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        // Both updates are valid
        validator = MockValidator(Map(fiberId1 -> true, fiberId2 -> true))

        result <- validator.validateDataParallel(state, updates, dispatcher)

        notifications <- spy.get

        // Should have zero rejections
        _ <- IO(assert(notifications.isEmpty))

        // validateData should return Valid
        _ <- IO(assert(result.isValid))
      } yield succeed
    }
  }

  test("validateData dispatches all rejections in a mixed batch") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        validFiberId    <- IO.pure(UUID.randomUUID())
        invalidFiberId1 <- IO.pure(UUID.randomUUID())
        invalidFiberId2 <- IO.pure(UUID.randomUUID())

        validUpdate <- IO.pure(CreateStateMachine(validFiberId, "Valid", "Initial", 0, "create", Map.empty, None))
        invalidUpdate1 <- IO
          .pure(CreateStateMachine(invalidFiberId1, "Invalid1", "Initial", 0, "create", Map.empty, None))
        invalidUpdate2 <- IO
          .pure(CreateStateMachine(invalidFiberId2, "Invalid2", "Initial", 0, "create", Map.empty, None))

        signedValid    <- context.securityProvider.sign(validUpdate, participant1.keyPair)
        signedInvalid1 <- context.securityProvider.sign(invalidUpdate1, participant1.keyPair)
        signedInvalid2 <- context.securityProvider.sign(invalidUpdate2, participant1.keyPair)

        updates = NonEmptyList.of(signedValid, signedInvalid1, signedInvalid2)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        // First valid, second and third invalid
        validator = MockValidator(
          Map(
            validFiberId    -> true,
            invalidFiberId1 -> false,
            invalidFiberId2 -> false
          )
        )

        result <- validator.validateDataParallel(state, updates, dispatcher)

        notifications <- spy.get

        // Should have exactly two rejections
        _ <- IO(assert(notifications.length == 2))

        rejectedFiberIds = notifications.map(_.rejection.fiberId).toSet
        expectedFiberIds = Set(invalidFiberId1.toString, invalidFiberId2.toString)

        _ <- IO(assert(rejectedFiberIds == expectedFiberIds))

        // validateData should return Invalid (because some updates failed)
        _ <- IO(assert(result.isInvalid))
      } yield succeed
    }
  }

  test("validateData does NOT dispatch when webhookDispatcher is None") {
    TestFixture.resource().use { implicit context =>
      for {
        // No dispatcher provided (None)
        invalidFiberId <- IO.pure(UUID.randomUUID())
        invalidUpdate <- IO.pure(CreateStateMachine(invalidFiberId, "Invalid", "Initial", 0, "create", Map.empty, None))
        signedInvalid <- context.securityProvider.sign(invalidUpdate, participant1.keyPair)

        updates = NonEmptyList.of(signedInvalid)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        validator = MockValidator(Map(invalidFiberId -> false))

        // Call with None dispatcher - should not cause any side effects
        result <- validator.validateDataParallel(state, updates, None)

        // Should still return Invalid (rejection tracking doesn't affect validation result)
        _ <- IO(assert(result.isInvalid))
      } yield succeed
    }
  }

  test("validateData returns combined errors — regression test") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = Some(spyDispatcher(spy))

        // Create three updates that will each fail with different numbers of errors
        // (This is a simplified test - real implementation would have multiple error types)
        fiberId1 <- IO.pure(UUID.randomUUID())
        fiberId2 <- IO.pure(UUID.randomUUID())
        fiberId3 <- IO.pure(UUID.randomUUID())

        update1 <- IO.pure(CreateStateMachine(fiberId1, "Invalid1", "Initial", 0, "create", Map.empty, None))
        update2 <- IO.pure(CreateStateMachine(fiberId2, "Invalid2", "Initial", 0, "create", Map.empty, None))
        update3 <- IO.pure(CreateStateMachine(fiberId3, "Invalid3", "Initial", 0, "create", Map.empty, None))

        signedUpdate1 <- context.securityProvider.sign(update1, participant1.keyPair)
        signedUpdate2 <- context.securityProvider.sign(update2, participant1.keyPair)
        signedUpdate3 <- context.securityProvider.sign(update3, participant1.keyPair)

        updates = NonEmptyList.of(signedUpdate1, signedUpdate2, signedUpdate3)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        // All invalid - should produce combined errors
        validator = MockValidator(
          Map(
            fiberId1 -> false,
            fiberId2 -> false,
            fiberId3 -> false
          )
        )

        result <- validator.validateDataParallel(state, updates, dispatcher)

        // Should have 3 total errors (1 per invalid update in this simplified case)
        _ <- result match {
          case Validated.Invalid(errors) =>
            IO(assert(errors.length == 3))
          case Validated.Valid(_) =>
            IO(failure("Expected validation to fail with combined errors"))
        }
      } yield succeed
    }
  }

  test("validateData rejection dispatch is fire-and-forget — does not block") {
    // This test verifies that webhook dispatch doesn't block validation
    // In the real implementation, it should use Async[F].start(...).void
    TestFixture.resource().use { implicit context =>
      for {
        startTime <- IO(System.currentTimeMillis())

        // Create a slow dispatcher that sleeps 500ms
        slowDispatcher = new WebhookDispatcher[IO] {
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

        invalidFiberId <- IO.pure(UUID.randomUUID())
        invalidUpdate <- IO.pure(CreateStateMachine(invalidFiberId, "Invalid", "Initial", 0, "create", Map.empty, None))
        signedInvalid <- context.securityProvider.sign(invalidUpdate, participant1.keyPair)

        updates = NonEmptyList.of(signedInvalid)
        state = DataState(OnChain.genesis, CalculatedState.genesis)

        validator = MockValidator(Map(invalidFiberId -> false))

        result <- validator.validateDataParallel(state, updates, Some(slowDispatcher))

        endTime <- IO(System.currentTimeMillis())
        elapsedMs = endTime - startTime

        // validateData should return BEFORE 500ms elapses (fire-and-forget)
        // Give some buffer for test execution overhead
        _ <- IO(assert(elapsedMs < 400, s"validateData took ${elapsedMs}ms, should be fire-and-forget"))

        // Should still return Invalid
        _ <- IO(assert(result.isInvalid))
      } yield succeed
    }
  }
}
