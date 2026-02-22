package xyz.kd5ujc.metagraph_l0.webhooks

import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import cats.data.NonEmptyChain
import cats.effect.kernel.Async
import cats.implicits._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, OttochainMessage, TransitionStateMachine}

import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.SelfAwareStructuredLogger

/**
 * Dispatches webhook notifications to subscribers on snapshot consensus
 * and on individual update validation rejection.
 */
trait WebhookDispatcher[F[_]] {

  /**
   * Dispatch snapshot notification to all active subscribers.
   * This should be called from onSnapshotConsensusResult.
   *
   * Delivery is fire-and-forget to avoid blocking consensus.
   */
  def dispatch(
    snapshot: Hashed[CurrencyIncrementalSnapshot],
    stats:    NotificationStats
  ): F[Unit]

  /**
   * Dispatch a `transaction.rejected` notification to all active subscribers.
   *
   * Called per-update when ML0 validation returns `Invalid`. Delivery is
   * fire-and-forget so it never blocks the validation result.
   *
   * @param ordinal  Current snapshot ordinal (from CheckpointService)
   * @param update   The signed update that was rejected
   * @param errors   Non-empty chain of validation errors
   */
  def dispatchRejection(
    ordinal: SnapshotOrdinal,
    update:  Signed[OttochainMessage],
    errors:  NonEmptyChain[DataApplicationValidationError]
  ): F[Unit]
}

// ── Rejection payload types ──────────────────────────────────────────────────

/** A single validation error in a rejection notification. */
case class RejectionError(code: String, message: String)

/** The rejection detail block embedded in [[RejectionNotification]]. */
case class RejectedUpdate(
  updateType:           String,
  fiberId:              String,
  errors:               List[RejectionError],
  signers:              List[String],
  updateHash:           String,
  targetSequenceNumber: Option[Long]
)

/** Top-level payload for the `transaction.rejected` webhook event. */
case class RejectionNotification(
  event:     String, // always "transaction.rejected"
  ordinal:   Long,
  rejection: RejectedUpdate
)

// Circe codecs for rejection payload
import io.circe.generic.semiauto._
import io.circe.Encoder

object RejectionNotification {
  implicit val rejectionErrorEncoder: Encoder[RejectionError] = deriveEncoder
  implicit val rejectedUpdateEncoder: Encoder[RejectedUpdate] = deriveEncoder
  implicit val rejectionNotifEncoder: Encoder[RejectionNotification] = deriveEncoder
}

// ─────────────────────────────────────────────────────────────────────────────

object WebhookDispatcher {

  /**
   * Create a webhook dispatcher.
   *
   * @param client      HTTP client for making requests
   * @param registry    Subscriber registry
   * @param metagraphId The metagraph token identifier
   */
  def make[F[_]](
    client:      Client[F],
    registry:    SubscriberRegistry[F],
    metagraphId: String
  )(implicit F: Async[F], logger: SelfAwareStructuredLogger[F]): WebhookDispatcher[F] =
    new WebhookDispatcher[F] {

      def dispatch(
        snapshot: Hashed[CurrencyIncrementalSnapshot],
        stats:    NotificationStats
      ): F[Unit] = {
        val notification = SnapshotNotification(
          event = "snapshot.finalized",
          ordinal = snapshot.ordinal.value.value,
          hash = snapshot.hash.value,
          timestamp = Instant.now(),
          metagraphId = metagraphId,
          stats = stats
        )

        val body = notification.asJson.noSpaces

        registry.listActive.flatMap { subscribers =>
          if (subscribers.isEmpty) {
            F.unit
          } else {
            logger.debug(
              s"Dispatching webhook to ${subscribers.size} subscribers for ordinal ${notification.ordinal}"
            ) *>
            subscribers.traverse_ { sub =>
              deliverToSubscriber(sub, body)
                .flatTap(_ => registry.markSuccess(sub.id))
                .flatTap(_ => logger.debug(s"Webhook delivered to ${sub.callbackUrl}"))
                .handleErrorWith { err =>
                  logger.warn(s"Webhook delivery failed for ${sub.callbackUrl}: ${err.getMessage}") *>
                  registry.markFailure(sub.id)
                }
            }
          }
        }
      }

      def dispatchRejection(
        ordinal: SnapshotOrdinal,
        update:  Signed[OttochainMessage],
        errors:  NonEmptyChain[DataApplicationValidationError]
      ): F[Unit] = {
        val (updateType, fiberId, targetSeqNum): (String, String, Option[Long]) = update.value match {
          case u: CreateStateMachine => ("CreateStateMachine", u.fiberId.toString, None)
          case u: TransitionStateMachine =>
            ("TransitionStateMachine", u.fiberId.toString, Some(u.targetSequenceNumber.value.value))
          case other => (other.getClass.getSimpleName, "unknown", None)
        }

        val signers = update.proofs.map(_.id.hex.value).toList
        val updateHash = computeUpdateHash(update)

        val notification = RejectionNotification(
          event = "transaction.rejected",
          ordinal = ordinal.value.value,
          rejection = RejectedUpdate(
            updateType = updateType,
            fiberId = fiberId,
            errors = errors.toList.map(e => RejectionError(e.getClass.getSimpleName.replace("$", ""), e.message)),
            signers = signers,
            updateHash = updateHash,
            targetSequenceNumber = targetSeqNum
          )
        )

        val body = notification.asJson.noSpaces

        registry.listActive.flatMap { subscribers =>
          if (subscribers.isEmpty) F.unit
          else
            logger.debug(
              s"Dispatching rejection webhook to ${subscribers.size} subscribers: $updateType fiberId=$fiberId"
            ) *>
            subscribers.traverse_ { sub =>
              deliverToSubscriber(sub, body)
                .handleErrorWith { err =>
                  logger.warn(s"Rejection webhook delivery failed for ${sub.callbackUrl}: ${err.getMessage}")
                }
            }
        }
      }

      private def computeUpdateHash(update: Signed[OttochainMessage]): String = {
        // Deterministic hash: SHA-256 of JSON-serialised value + ":" + sorted signer hex values
        val signerStr = update.proofs.map(_.id.hex.value).toList.sorted.mkString(",")
        val content = s"${update.value.asJson.noSpaces}:$signerStr"
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8))
          .map("%02x".format(_))
          .mkString
      }

      private def deliverToSubscriber(sub: Subscriber, body: String): F[Unit] =
        Uri.fromString(sub.callbackUrl) match {
          case Left(err) =>
            logger.warn(s"Invalid callback URL ${sub.callbackUrl}: ${err.message}")

          case Right(uri) =>
            val signature = sub.secret.map(computeHmacSignature(body, _))

            val baseRequest = Request[F](Method.POST, uri)
              .withEntity(body)
              .withContentType(`Content-Type`(MediaType.application.json))

            val request = signature.fold(baseRequest) { sig =>
              baseRequest.putHeaders(
                Header.Raw(CIString("X-OttoChain-Signature"), s"sha256=$sig")
              )
            }

            client.expect[Unit](request)
        }

      private def computeHmacSignature(body: String, secret: String): String = {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        mac
          .doFinal(body.getBytes(StandardCharsets.UTF_8))
          .map("%02x".format(_))
          .mkString
      }
    }
}
