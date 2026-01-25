package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.ConstExpression
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.fiber.engine.ExecutionTInstances._

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Processes cascading triggers atomically using ExecutionT for state management.
 *
 * Uses FiberEvaluator internally, so triggers can target either
 * state machines or oracles uniformly.
 *
 * Semantics:
 * - Processes triggers sequentially (order matters for gas accounting)
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

  def make[F[_]: Async](
    evaluatorFactory: CalculatedState => FiberEvaluator[F],
    ordinal:          SnapshotOrdinal,
    limits:           ExecutionLimits,
    gasConfig:        GasConfig
  ): TriggerDispatcher[F] =
    new TriggerDispatcher[F] {

      private val logger: SelfAwareStructuredLogger[F] =
        Slf4jLogger.getLoggerFromClass(TriggerDispatcher.getClass)

      def dispatch(
        triggers:  List[FiberTrigger],
        baseState: CalculatedState
      ): F[TransactionOutcome] = {
        val initialState = ExecutionState.initial.incrementDepth
        processWithTransaction(triggers, baseState, List.empty)
          .runA(initialState)
      }

      private def processWithTransaction(
        remainingTriggers: List[FiberTrigger],
        txnState:          CalculatedState,
        statuses:          List[(UUID, Records.EventProcessingStatus)]
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
                    case Right((nextState, newStatuses, moreTriggers)) =>
                      processWithTransaction(
                        moreTriggers ++ rest,
                        nextState,
                        statuses ++ newStatuses
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
        (CalculatedState, List[(UUID, Records.EventProcessingStatus)], List[FiberTrigger])

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
                  // Target fiber not found - this is a failure
                  logger
                    .warn(
                      s"Trigger target fiber $fiberId not found. " +
                      s"Source: ${trigger.sourceFiberId.getOrElse("external")}, " +
                      s"Input: ${trigger.input.inputKey}"
                    )
                    .liftExec
                    .as(
                      (StateMachine.FailureReason.TriggerTargetNotFound(
                        fiberId,
                        trigger.sourceFiberId
                      ): StateMachine.FailureReason).asLeft[TriggerResult]
                    )

                case Some(oracle: Records.ScriptOracleFiberRecord) =>
                  processOracleTrigger(trigger, oracle, state)

                case Some(sm: Records.StateMachineFiberRecord) =>
                  processStateMachineTrigger(trigger, sm, state)
              }
            }
        } yield result
      }

      private def processStateMachineTrigger(
        trigger: FiberTrigger,
        sm:      Records.StateMachineFiberRecord,
        state:   CalculatedState
      ): ExecutionT[F, Either[StateMachine.FailureReason, TriggerResult]] =
        for {
          _ <- ExecutionOps.markProcessed[ExecutionT[F, *]](sm.cid, trigger.input.inputKey)

          evaluator = evaluatorFactory(state)
          outcomeResult <- evaluator.evaluate(sm, trigger.input, List.empty).liftExec

          result <- outcomeResult match {
            case FiberOutcome.Success(newStateData, newStateId, triggers, _, _, _, gasUsed) =>
              for {
                _ <- ExecutionOps.chargeGas[ExecutionT[F, *]](gasUsed)

                hash <- newStateData.computeDigest.liftExec

                status = Records.EventProcessingStatus.Success(
                  sequenceNumber = sm.sequenceNumber + 1,
                  processedAt = ordinal
                )

                updatedFiber = sm.copy(
                  currentState = newStateId.getOrElse(sm.currentState),
                  stateData = newStateData,
                  stateDataHash = hash,
                  sequenceNumber = sm.sequenceNumber + 1,
                  latestUpdateOrdinal = ordinal,
                  lastEventStatus = status,
                  eventBatch = (sm.eventBatch :+ status).takeRight(sm.maxEventBatchSize)
                )

                updatedState = state.updateFiber(updatedFiber)

                // Convert triggers to UnifiedTriggers, including source fiber ID
                unifiedTriggers = triggers.map { t =>
                  val payload = t.payloadExpr match {
                    case ConstExpression(value) => value
                    case _                      => NullValue
                  }
                  FiberTrigger(
                    targetFiberId = t.targetMachineId,
                    input = FiberInput.Transition(t.eventType, payload),
                    sourceFiberId = Some(sm.cid)
                  )
                }

                _ <- ExecutionOps.incrementDepth[ExecutionT[F, *]]
              } yield (updatedState, List((sm.cid, status)), unifiedTriggers)
                .asRight[StateMachine.FailureReason]

            case FiberOutcome.GuardFailed(attemptedCount, guardGasUsed) =>
              // EVM semantics: charge gas even for failed guard evaluations
              for {
                _ <- ExecutionOps.chargeGas[ExecutionT[F, *]](guardGasUsed)
              } yield {
                val eventType = trigger.input match {
                  case FiberInput.Transition(et, _)      => et
                  case FiberInput.MethodCall(m, _, _, _) => StateMachine.EventType(m)
                }
                (StateMachine.FailureReason.NoGuardMatched(
                  sm.currentState,
                  eventType,
                  attemptedCount
                ): StateMachine.FailureReason).asLeft[TriggerResult]
              }

            case FiberOutcome.Failed(reason) =>
              reason.asLeft[TriggerResult].pureExec[F]
          }
        } yield result

      private def processOracleTrigger(
        trigger: FiberTrigger,
        oracle:  Records.ScriptOracleFiberRecord,
        state:   CalculatedState
      ): ExecutionT[F, Either[StateMachine.FailureReason, TriggerResult]] =
        for {
          _            <- ExecutionOps.markProcessed[ExecutionT[F, *]](oracle.cid, trigger.input.inputKey)
          remainingGas <- ExecutionOps.remainingGasWithLimits[ExecutionT[F, *]](limits)

          // Extract method and args from trigger input
          (method, args) = trigger.input match {
            case FiberInput.Transition(eventType, payload) => (eventType.value, payload)
            case FiberInput.MethodCall(m, a, _, _)         => (m, a)
          }

          // Type alias for EitherT to avoid inference issues
          // Return (TriggerResult, gasUsed) tuple so we can charge gas after unwrapping EitherT
          result <- {
            type OracleET[A] = EitherT[F, StateMachine.FailureReason, A]

            val computation: OracleET[(TriggerResult, Long)] = for {
              // Get caller address from source fiber (supports both state machines and oracles)
              callerAddress <- EitherT.fromOption[F](
                trigger.sourceFiberId.flatMap { fiberId =>
                  state.getFiber(fiberId).flatMap(_.owners.headOption)
                },
                StateMachine.FailureReason.Other(
                  "Unable to determine caller for oracle invocation"
                ): StateMachine.FailureReason
              )

              // Validate access control
              _ <- EitherT[F, StateMachine.FailureReason, Unit](
                OracleProcessor.validateAccess(oracle.accessControl, callerAddress, oracle.cid)
              )

              // Build input data for oracle script
              inputData = MapValue(
                Map(
                  ReservedKeys.METHOD -> StrValue(method),
                  ReservedKeys.ARGS   -> args,
                  ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
                )
              )

              // Evaluate oracle script
              scriptResult <- EitherT[F, StateMachine.FailureReason, EvaluationResult[JsonLogicValue]](
                JsonLogicEvaluator
                  .tailRecursive[F]
                  .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
                  .map {
                    case Right(result) =>
                      result.asRight[StateMachine.FailureReason]
                    case Left(_: GasExhaustedException) =>
                      (StateMachine.FailureReason.GasExhaustedFailure(
                        limits.maxGas,
                        limits.maxGas,
                        StateMachine.GasExhaustionPhase.Oracle
                      ): StateMachine.FailureReason).asLeft[EvaluationResult[JsonLogicValue]]
                    case Left(err) =>
                      (StateMachine.FailureReason.EffectEvaluationError(err.getMessage): StateMachine.FailureReason)
                        .asLeft[EvaluationResult[JsonLogicValue]]
                  }
              )

              scriptGasUsed = scriptResult.gasUsed.amount
              evaluationResult = scriptResult.value

              // Extract state and result from oracle output
              stateAndResult <- EitherT.liftF[F, StateMachine.FailureReason, (Option[JsonLogicValue], JsonLogicValue)](
                OracleProcessor.extractStateAndResult(evaluationResult)
              )
              (newStateData, returnValue) = stateAndResult

              // Check for validation failures
              _ <- {
                val checkResult: Either[StateMachine.FailureReason, Unit] = returnValue match {
                  case BoolValue(false) =>
                    (StateMachine.FailureReason.Other(
                      s"Oracle invocation returned false for method: $method"
                    ): StateMachine.FailureReason)
                      .asLeft[Unit]
                  case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
                    val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
                    (StateMachine.FailureReason.Other(
                      s"Oracle validation failed for method $method: $errorMsg"
                    ): StateMachine.FailureReason)
                      .asLeft[Unit]
                  case _ => ().asRight[StateMachine.FailureReason]
                }
                EitherT.fromEither[F](checkResult)
              }

              // Compute new hash
              newHash <- EitherT
                .liftF[F, StateMachine.FailureReason, Option[io.constellationnetwork.security.hash.Hash]](
                  newStateData.traverse(_.computeDigest)
                )

              // Create invocation record
              invocation = Records.OracleInvocation(
                method = method,
                args = args,
                result = returnValue,
                gasUsed = scriptGasUsed,
                invokedAt = ordinal,
                invokedBy = callerAddress
              )

              newLog = (invocation :: oracle.invocationLog).take(oracle.maxLogSize)

              // Update oracle record
              updatedOracle = oracle.copy(
                stateData = newStateData,
                stateDataHash = newHash,
                latestUpdateOrdinal = ordinal,
                invocationCount = oracle.invocationCount + 1,
                invocationLog = newLog
              )

              updatedState = state.updateFiber(updatedOracle)

              triggerResult = (
                updatedState,
                List.empty[(UUID, Records.EventProcessingStatus)],
                List.empty[FiberTrigger]
              ): TriggerResult

            } yield (triggerResult, scriptGasUsed)

            // Run EitherT and lift result, charge gas and increment depth from the returned tuple
            computation.value.liftExec.flatMap {
              case Right((triggerResult, oracleGasUsed)) =>
                for {
                  _ <- ExecutionOps.chargeGas[ExecutionT[F, *]](oracleGasUsed)
                  _ <- ExecutionOps.incrementDepth[ExecutionT[F, *]]
                } yield triggerResult.asRight[StateMachine.FailureReason]
              case Left(reason) =>
                reason.asLeft[TriggerResult].pureExec[F]
            }
          }
        } yield result
    }
}
