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
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.schema.Updates.OttochainMessage

import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.SelfAwareStructuredLogger

/**
 * Dispatches webhook notifications to subscribers on snapshot consensus.
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
   * Dispatch rejection notification when ML0 validation fails.
   * This surfaces transactions that were accepted by DL1 but rejected at ML0.
   *
   * Delivery is fire-and-forget to avoid blocking consensus.
   */
  def dispatchRejection(
    ordinal:      SnapshotOrdinal,
    signedUpdate: Signed[OttochainMessage],
    errors:       NonEmptyChain[DataApplicationValidationError]
  ): F[Unit]
}

object WebhookDispatcher {

  /**
   * Create a webhook dispatcher
   *
   * @param client HTTP client for making requests
   * @param registry Subscriber registry
   * @param metagraphId The metagraph token identifier
   */
  def make[F[_]](
    client:      Client[F],
    registry:    SubscriberRegistry[F],
    metagraphId: String
  )(implicit F: Async[F], S: SecurityProvider[F], logger: SelfAwareStructuredLogger[F]): WebhookDispatcher[F] =
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

      def dispatchRejection(
        ordinal:      SnapshotOrdinal,
        signedUpdate: Signed[OttochainMessage],
        errors:       NonEmptyChain[DataApplicationValidationError]
      ): F[Unit] = {
        val update = signedUpdate.value
        val fiberId = extractFiberId(update)
        val updateType = update.getClass.getSimpleName
        val updateHash = signedUpdate.hashCode().toHexString // Simple hash for dedup

        val errorInfos = errors.toList.map { err =>
          ValidationErrorInfo(
            code = err.getClass.getSimpleName.stripSuffix("$"),
            message = err.message
          )
        }

        // Extract signer addresses from proofs (effectful)
        signedUpdate.proofs.toList.traverse(_.id.toAddress).flatMap { addresses =>
          val signerAddresses = addresses.map(_.value.value)

          val notification = RejectionNotification(
            event = "transaction.rejected",
            ordinal = ordinal.value.value,
            timestamp = Instant.now(),
            metagraphId = metagraphId,
            rejection = RejectedUpdate(
              updateType = updateType,
              fiberId = fiberId,
              errors = errorInfos,
              signers = signerAddresses,
              updateHash = updateHash
            )
          )

          val body = notification.asJson.noSpaces

          registry.listActive.flatMap { subscribers =>
            if (subscribers.isEmpty) {
              F.unit
            } else {
              logger.info(
                s"Dispatching rejection webhook for $updateType fiberId=$fiberId to ${subscribers.size} subscribers"
              ) *>
              subscribers.traverse_ { sub =>
                deliverToSubscriber(sub, body)
                  .flatTap(_ => logger.debug(s"Rejection webhook delivered to ${sub.callbackUrl}"))
                  .handleErrorWith { err =>
                    logger.warn(s"Rejection webhook delivery failed for ${sub.callbackUrl}: ${err.getMessage}")
                  }
              }
            }
          }
        }
      }

      private def extractFiberId(update: OttochainMessage): String =
        update match {
          case u: xyz.kd5ujc.schema.Updates.CreateStateMachine     => u.fiberId.toString
          case u: xyz.kd5ujc.schema.Updates.TransitionStateMachine => u.fiberId.toString
          case u: xyz.kd5ujc.schema.Updates.ArchiveStateMachine    => u.fiberId.toString
          case u: xyz.kd5ujc.schema.Updates.CreateScript           => u.fiberId.toString
          case u: xyz.kd5ujc.schema.Updates.InvokeScript           => u.fiberId.toString
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
