package xyz.kd5ujc.metagraph_l0

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Parallel}

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.metagraph_sdk.MetagraphCommonService
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, CombinerService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.CurrencyIncrementalSnapshotOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.schema.Updates.WorkchainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}

import org.http4s.HttpRoutes
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object ML0Service {

  def make[F[+_]: Async: Parallel: SecurityProvider]: F[BaseDataApplicationL0Service[F]] = for {
    checkpointService        <- CheckpointService.make[F, CalculatedState](CalculatedState.genesis)
    combiner                 <- Combiner.make[F].pure[F]
    validator                <- Validator.make[F]
    dataApplicationL0Service <- makeBaseApplicationL0Service(checkpointService, combiner, validator).pure[F]
  } yield dataApplicationL0Service

  private def makeBaseApplicationL0Service[F[+_]: Async](
    checkpointService: CheckpointService[F, CalculatedState],
    combiner:          CombinerService[F, WorkchainMessage, OnChain, CalculatedState],
    validator:         ValidationService[F, WorkchainMessage, OnChain, CalculatedState]
  ): BaseDataApplicationL0Service[F] =
    BaseDataApplicationL0Service[F, WorkchainMessage, OnChain, CalculatedState](
      new MetagraphCommonService[F, WorkchainMessage, OnChain, CalculatedState, L0NodeContext[F]]
        with DataApplicationL0Service[F, WorkchainMessage, OnChain, CalculatedState] {

        private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(ML0Service.getClass)

        override def genesis: DataState[OnChain, CalculatedState] =
          DataState(OnChain.genesis, CalculatedState.genesis)

        override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit
          A: Applicative[F]
        ): F[Unit] =
          (for {
            signedUpdates <- snapshot.signed.value.getSignedUpdates[WorkchainMessage]
            _             <- logger.info(s"Got ${signedUpdates.size} updates for ordinal: ${snapshot.ordinal.value}")
          } yield ()).handleErrorWith(logger.error(_)("Error during onSnapshotConsensusResult"))

        override def validateData(
          state:   DataState[OnChain, CalculatedState],
          updates: NonEmptyList[Signed[WorkchainMessage]]
        )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          validator.validateDataParallel(state, updates)

        override def combine(
          state:   DataState[OnChain, CalculatedState],
          updates: List[Signed[WorkchainMessage]]
        )(implicit context: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
          combiner.foldLeft(state, updates)

        override def getCalculatedState(implicit
          context: L0NodeContext[F]
        ): F[(SnapshotOrdinal, CalculatedState)] =
          checkpointService.get.map { case Checkpoint(ordinal, state) => ordinal -> state }

        override def setCalculatedState(ordinal: SnapshotOrdinal, state: CalculatedState)(implicit
          context: L0NodeContext[F]
        ): F[Boolean] = checkpointService.set(Checkpoint(ordinal, state))

        override def hashCalculatedState(state: CalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
          state.computeDigest

        override def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] =
          new ML0CustomRoutes[F](checkpointService).public
      }
    )
}
