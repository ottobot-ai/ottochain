package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.fiber.engine.ExecutionTInstances._
import xyz.kd5ujc.shared_data.syntax.calculatedState._

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Processes cascading triggers atomically using ExecutionT for state management.
 *
 * Uses TriggerHandler internally for unified trigger processing across fiber types.
 *
 * Semantics:
 * - Processes triggers depth-first (cascade triggers prepended to queue)
 * - If any trigger fails, entire transaction aborts (all-or-nothing)
 * - Respects depth and gas limits via ExecutionT state
 * - Detects cycles via (fiberId, inputKey) tracking
 */
trait TriggerDispatcher[F[_]] {

  def dispatch(
    triggers:  List[FiberTrigger],
    baseState: CalculatedState
  ): F[TransactionOutcome]
}

object TriggerDispatcher {

  def make[F[_]: Async: JsonLogicEvaluator](
    evaluatorFactory: CalculatedState => FiberEvaluator[F],
    ordinal:          SnapshotOrdinal,
    limits:           ExecutionLimits,
    gasConfig:        GasConfig
  ): TriggerDispatcher[F] =
    new TriggerDispatcher[F] {

      private val logger: SelfAwareStructuredLogger[F] =
        Slf4jLogger.getLoggerFromClass(TriggerDispatcher.getClass)

      private val handler: TriggerHandler[F] =
        CompositeTriggerHandler.make[F](evaluatorFactory, ordinal, limits, gasConfig)

      def dispatch(
        triggers:  List[FiberTrigger],
        baseState: CalculatedState
      ): F[TransactionOutcome] = {
        val initialState = ExecutionState.initial.incrementDepth
        processWithTransaction(triggers, baseState, List.empty, List.empty)
          .runA(initialState)
      }

      private def processWithTransaction(
        remainingTriggers: List[FiberTrigger],
        txnState:          CalculatedState,
        statuses:          List[(UUID, Records.EventProcessingStatus)],
        triggerChain:      List[TriggerChainEntry]
      ): ExecutionT[F, TransactionOutcome] =
        for {
          limitCheck <- ExecutionOps.checkLimitsWithExplicit[F](limits)
          result <- limitCheck match {
            case Some(reason) =>
              for {
                gasUsed <- ExecutionOps.getGasUsed[ExecutionT[F, *]]
                depth   <- ExecutionOps.getDepth[ExecutionT[F, *]]
              } yield TransactionOutcome.Aborted(reason, gasUsed, depth): TransactionOutcome

            case None =>
              remainingTriggers match {
                case Nil =>
                  for {
                    gasUsed <- ExecutionOps.getGasUsed[ExecutionT[F, *]]
                    depth   <- ExecutionOps.getDepth[ExecutionT[F, *]]
                  } yield TransactionOutcome.Committed(
                    updatedStateMachines = txnState.stateMachines,
                    updatedOracles = txnState.scriptOracles,
                    statuses = statuses,
                    totalGasUsed = gasUsed,
                    maxDepth = depth
                  ): TransactionOutcome

                case trigger :: rest =>
                  processSingleTrigger(trigger, txnState).flatMap {
                    case Right((nextState, newStatuses, moreTriggers, chainEntry)) =>
                      processWithTransaction(
                        moreTriggers ++ rest, // Depth-first: cascade triggers processed before siblings
                        nextState,
                        statuses ++ newStatuses,
                        triggerChain :+ chainEntry
                      )
                    case Left(reason) =>
                      for {
                        gasUsed <- ExecutionOps.getGasUsed[ExecutionT[F, *]]
                        depth   <- ExecutionOps.getDepth[ExecutionT[F, *]]
                      } yield TransactionOutcome.Aborted(reason, gasUsed, depth): TransactionOutcome
                  }
              }
          }
        } yield result

      private type TriggerResult =
        (CalculatedState, List[(UUID, Records.EventProcessingStatus)], List[FiberTrigger], TriggerChainEntry)

      private def processSingleTrigger(
        trigger: FiberTrigger,
        state:   CalculatedState
      ): ExecutionT[F, Either[StateMachine.FailureReason, TriggerResult]] = {
        val fiberId = trigger.targetFiberId
        val inputKey = trigger.input.inputKey

        for {
          isCycle <- ExecutionOps.checkCycle[ExecutionT[F, *]](fiberId, inputKey)
          result <-
            if (isCycle) {
              (StateMachine.FailureReason
                .CycleDetected(fiberId, StateMachine.EventType(inputKey)): StateMachine.FailureReason)
                .asLeft[TriggerResult]
                .pureExec[F]
            } else {
              state.getFiber(fiberId) match {
                case None =>
                  logger
                    .warn(
                      s"Trigger target fiber $fiberId not found. " +
                      s"Source: ${trigger.sourceFiberId.getOrElse("external")}, " +
                      s"Input: $inputKey"
                    )
                    .liftExec
                    .as(
                      (StateMachine.FailureReason.TriggerTargetNotFound(
                        fiberId,
                        trigger.sourceFiberId
                      ): StateMachine.FailureReason).asLeft[TriggerResult]
                    )

                case Some(fiber) =>
                  processWithHandler(trigger, fiber, state)
              }
            }
        } yield result
      }

      private def processWithHandler(
        trigger: FiberTrigger,
        fiber:   Records.FiberRecord,
        state:   CalculatedState
      ): ExecutionT[F, Either[StateMachine.FailureReason, TriggerResult]] =
        for {
          _             <- ExecutionOps.markProcessed[ExecutionT[F, *]](fiber.cid, trigger.input.inputKey)
          remainingGas  <- ExecutionOps.remainingGasWithLimits[ExecutionT[F, *]](limits)
          handlerResult <- handler.handle(trigger, fiber, state, remainingGas).liftExec

          result <- handlerResult match {
            case TriggerHandlerResult.Success(updatedState, statuses, cascadeTriggers, gasUsed) =>
              for {
                _ <- ExecutionOps.chargeGas[ExecutionT[F, *]](gasUsed)
                _ <- ExecutionOps.incrementDepth[ExecutionT[F, *]]
                _ <- ExecutionOps.recordTrigger[ExecutionT[F, *]](
                  trigger.targetFiberId,
                  trigger.sourceFiberId,
                  trigger.input.inputKey,
                  gasUsed
                )
              } yield {
                val chainEntry = TriggerChainEntry(
                  targetFiberId = trigger.targetFiberId,
                  sourceFiberId = trigger.sourceFiberId,
                  inputKey = trigger.input.inputKey,
                  cascadeCount = cascadeTriggers.size,
                  gasUsed = gasUsed
                )
                (updatedState, statuses, cascadeTriggers, chainEntry).asRight[StateMachine.FailureReason]
              }

            case TriggerHandlerResult.Failed(reason, gasUsed) =>
              for {
                _ <- ExecutionOps.chargeGas[ExecutionT[F, *]](gasUsed)
              } yield reason.asLeft[TriggerResult]
          }
        } yield result
    }
}
