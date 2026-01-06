package xyz.kd5ujc.data_l1

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.metagraph_sdk.MetagraphCommonService
import io.constellationnetwork.metagraph_sdk.lifecycle.ValidationService
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Validator

import org.http4s._

object DataL1Service {

  def make[F[+_]: Async: Parallel: SecurityProvider]: F[BaseDataApplicationL1Service[F]] = for {
    validator <- Validator.make[F]
    l1Service <- makeBaseApplicationL1Service(validator).pure[F]
  } yield l1Service

  private def makeBaseApplicationL1Service[F[+_]: Async](
    validator: ValidationService[F, OttochainMessage, OnChain, CalculatedState]
  ): BaseDataApplicationL1Service[F] =
    BaseDataApplicationL1Service[F, OttochainMessage, OnChain, CalculatedState](
      new MetagraphCommonService[F, OttochainMessage, OnChain, CalculatedState, L1NodeContext[F]]
        with DataApplicationL1Service[F, OttochainMessage, OnChain, CalculatedState] {

        override def validateUpdate(
          update: OttochainMessage
        )(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          validator.validateUpdate(update)

        override def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] =
          new DataL1CustomRoutes[F].public
      }
    )
}
