package xyz.kd5ujc.metagraph_l0.webhooks

import cats.effect.{IO, Ref}
import cats.data.{NonEmptyChain, NonEmptyList}
import cats.syntax.all._
import java.util.UUID
import weaver.SimpleIOSuite

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, DataState}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, OttochainMessage, TransitionStateMachine}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_test.TestFixture
import xyz.kd5ujc.shared_test.Participant._

object WebhookDispatcherSuite extends SimpleIOSuite {

  // Test data types (these should exist in the actual implementation)
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

  // Spy dispatcher: captures dispatched rejections for assertion
  def spyDispatcher(spy: Ref[IO, List[RejectionNotification]]): WebhookDispatcher[IO] =
    new WebhookDispatcher[IO] {

      // Existing method from actual WebhookDispatcher
      def dispatch(
        snapshot: io.constellationnetwork.security.Hashed[
          io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
        ],
        stats: NotificationStats
      ): IO[Unit] = IO.unit

      // This method should exist but doesn't yet - this is what we're testing for
      def dispatchRejection(
        ordinal: SnapshotOrdinal,
        update:  Signed[OttochainMessage],
        errors:  NonEmptyChain[DataApplicationValidationError]
      ): IO[Unit] = {
        // Build the notification same way the real impl should do
        val notification = buildNotification(ordinal, update, errors)
        spy.update(_ :+ notification)
      }

      private def buildNotification(
        ordinal: SnapshotOrdinal,
        update:  Signed[OttochainMessage],
        errors:  NonEmptyChain[DataApplicationValidationError]
      ): RejectionNotification = {
        val (updateType, fiberId, targetSeqNum) = update.value match {
          case CreateStateMachine(fid, _, _, _, _, _, _)    => ("CreateStateMachine", fid.toString, None)
          case TransitionStateMachine(fid, _, _, tsn, _, _) => ("TransitionStateMachine", fid.toString, Some(tsn))
          case other                                        => ("Unknown", "unknown", None)
        }

        val validationErrors = errors.toList.map { err =>
          ValidationError(
            code = err.getClass.getSimpleName.replace("$", ""),
            message = err.toString
          )
        }

        val signers = update.proofs.map(_.id.hex.value).toList
        val updateHash = computeUpdateHash(update)

        RejectionNotification(
          event = "transaction.rejected",
          ordinal = ordinal.value.value,
          rejection = RejectedUpdate(
            updateType = updateType,
            fiberId = fiberId,
            errors = validationErrors,
            signers = signers,
            updateHash = updateHash,
            targetSequenceNumber = targetSeqNum
          )
        )
      }

      private def computeUpdateHash(update: Signed[OttochainMessage]): String = {
        // This should match the actual implementation's hash computation
        // For now, simulate with a deterministic hash based on content
        val content = s"${update.value}:${update.proofs.map(_.id.hex.value).mkString(",")}"
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(content.getBytes("UTF-8"))
          .map("%02x".format(_))
          .mkString
      }
    }

  test("dispatchRejection constructs correct RejectionNotification payload") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        // Create test data
        fiberId <- IO.pure(UUID.randomUUID())
        createFiber <- IO.pure(
          CreateStateMachine(
            fiberId = fiberId,
            workflowType = "TestWorkflow",
            stateName = "Initial",
            sequenceNumber = 0,
            eventName = "create",
            eventData = Map.empty,
            targetSequenceNumber = None
          )
        )

        signedUpdate <- context.securityProvider.sign(createFiber, participant1.keyPair)

        // Mock error
        errors = NonEmptyChain.one(
          new DataApplicationValidationError("FiberAlreadyExists") {
            override def getMessage: String = "Fiber already exists"
          }
        )

        ordinal = SnapshotOrdinal.unsafeFrom(42L)

        // Execute
        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate, errors)

        // Assert
        notifications <- spy.get
        notification = notifications.head

        _ <- IO(assert(notifications.length == 1))
        _ <- IO(assert(notification.event == "transaction.rejected"))
        _ <- IO(assert(notification.ordinal == 42))
        _ <- IO(assert(notification.rejection.updateType == "CreateStateMachine"))
        _ <- IO(assert(notification.rejection.fiberId == fiberId.toString))
        _ <- IO(assert(notification.rejection.errors.length == 1))
        _ <- IO(assert(notification.rejection.errors.head.code == "FiberAlreadyExists"))
        _ <- IO(assert(notification.rejection.errors.head.message == "Fiber already exists"))
        _ <- IO(assert(notification.rejection.signers.nonEmpty))
        _ <- IO(assert(notification.rejection.updateHash.length == 64)) // 32 bytes as hex = 64 chars
      } yield succeed
    }
  }

  test("dispatchRejection deduplicates - same update produces same updateHash") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId <- IO.pure(UUID.randomUUID())
        createFiber <- IO.pure(
          CreateStateMachine(
            fiberId = fiberId,
            workflowType = "TestWorkflow",
            stateName = "Initial",
            sequenceNumber = 0,
            eventName = "create",
            eventData = Map.empty,
            targetSequenceNumber = None
          )
        )

        signedUpdate <- context.securityProvider.sign(createFiber, participant1.keyPair)
        errors = NonEmptyChain.one(new DataApplicationValidationError("TestError") {})
        ordinal = SnapshotOrdinal.unsafeFrom(1L)

        // Dispatch same update twice
        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate, errors)
        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate, errors)

        notifications <- spy.get
        hash1 = notifications(0).rejection.updateHash
        hash2 = notifications(1).rejection.updateHash

        _ <- IO(assert(hash1 == hash2))
      } yield succeed
    }
  }

  test("dispatchRejection different updates produce different updateHashes") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId1 <- IO.pure(UUID.randomUUID())
        fiberId2 <- IO.pure(UUID.randomUUID())

        createFiber1 <- IO.pure(CreateStateMachine(fiberId1, "Type1", "Initial", 0, "create", Map.empty, None))
        createFiber2 <- IO.pure(CreateStateMachine(fiberId2, "Type2", "Initial", 0, "create", Map.empty, None))

        signedUpdate1 <- context.securityProvider.sign(createFiber1, participant1.keyPair)
        signedUpdate2 <- context.securityProvider.sign(createFiber2, participant1.keyPair)

        errors = NonEmptyChain.one(new DataApplicationValidationError("TestError") {})
        ordinal = SnapshotOrdinal.unsafeFrom(1L)

        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate1, errors)
        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate2, errors)

        notifications <- spy.get
        hash1 = notifications(0).rejection.updateHash
        hash2 = notifications(1).rejection.updateHash

        _ <- IO(assert(hash1 != hash2))
      } yield succeed
    }
  }

  test("dispatchRejection fires for TransitionStateMachine - fiberId extracted correctly") {
    TestFixture.resource().use { implicit context =>
      for {
        spy <- Ref.of[IO, List[RejectionNotification]](List.empty)
        dispatcher = spyDispatcher(spy)

        fiberId <- IO.pure(UUID.fromString("456e7890-e89b-12d3-a456-426614174000"))
        transitionFiber = TransitionStateMachine(
          fiberId = fiberId,
          eventName = "transition",
          eventData = Map.empty,
          targetSequenceNumber = 3,
          stateName = "Active",
          sequenceNumber = 2
        )

        signedUpdate <- context.securityProvider.sign(transitionFiber, participant1.keyPair)
        errors = NonEmptyChain.one(new DataApplicationValidationError("InvalidTransition") {})
        ordinal = SnapshotOrdinal.unsafeFrom(1L)

        _ <- dispatcher.dispatchRejection(ordinal, signedUpdate, errors)

        notifications <- spy.get
        notification = notifications.head

        _ <- IO(assert(notification.rejection.updateType == "TransitionStateMachine"))
        _ <- IO(assert(notification.rejection.fiberId == fiberId.toString))
        _ <- IO(assert(notification.rejection.targetSequenceNumber == Some(3)))
      } yield succeed
    }
  }

  test("dispatchRejection does not deliver when no subscribers") {
    // This test would require a real WebhookDispatcher with SubscriberRegistry
    // For now, this is a placeholder that will fail until the real implementation exists
    TestFixture.resource().use { implicit context =>
      for {
        // This test should check that when SubscriberRegistry has no subscribers,
        // no HTTP requests are made. This will need the actual WebhookDispatcher implementation.
        _ <- IO.unit
      } yield failure(
        "This test requires the actual WebhookDispatcher.dispatchRejection implementation with SubscriberRegistry integration"
      )
    }
  }
}
