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
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Result of handling a trigger.
 */
sealed trait TriggerHandlerResult

object TriggerHandlerResult {

  /**
   * Trigger processed successfully.
   *
   * @param updatedState   State with the target fiber updated
   * @param statuses       Processing status updates (fiberId -> status)
   * @param cascadeTriggers Additional triggers to process
   * @param gasUsed        Gas consumed by this trigger
   */
  final case class Success(
    updatedState:    CalculatedState,
    statuses:        List[(UUID, Records.EventProcessingStatus)],
    cascadeTriggers: List[FiberTrigger],
    gasUsed:         Long
  ) extends TriggerHandlerResult

  /**
   * Trigger processing failed.
   *
   * @param reason  Why the trigger failed
   * @param gasUsed Gas consumed before failure
   */
  final case class Failed(
    reason:  StateMachine.FailureReason,
    gasUsed: Long = 0L
  ) extends TriggerHandlerResult
}

/**
 * Unified trigger handling algebra.
 *
 * Provides a consistent interface for processing triggers across different fiber types.
 */
trait TriggerHandler[F[_]] {

  def handle(
    trigger:      FiberTrigger,
    fiber:        Records.FiberRecord,
    state:        CalculatedState,
    remainingGas: Long
  ): F[TriggerHandlerResult]
}

/**
 * Trigger handler for state machine fibers.
 */
object StateMachineTriggerHandler {

  def make[F[_]: Async: JsonLogicEvaluator](
    evaluatorFactory: CalculatedState => FiberEvaluator[F],
    ordinal:          SnapshotOrdinal
  ): TriggerHandler[F] =
    new TriggerHandler[F] {

      def handle(
        trigger:      FiberTrigger,
        fiber:        Records.FiberRecord,
        state:        CalculatedState,
        remainingGas: Long
      ): F[TriggerHandlerResult] =
        fiber match {
          case sm: Records.StateMachineFiberRecord =>
            handleStateMachine(trigger, sm, state, remainingGas)
          case other =>
            (TriggerHandlerResult.Failed(
              StateMachine.FailureReason
                .FiberInputMismatch(other.cid, other.getClass.getSimpleName, "StateMachineTrigger")
            ): TriggerHandlerResult).pure[F]
        }

      private def handleStateMachine(
        trigger:      FiberTrigger,
        sm:           Records.StateMachineFiberRecord,
        state:        CalculatedState,
        remainingGas: Long
      ): F[TriggerHandlerResult] = {
        val evaluator = evaluatorFactory(state)

        evaluator.evaluate(sm, trigger.input, List.empty).map {
          case FiberOutcome.Success(newStateData, newStateId, triggers, _, _, _, gasUsed) =>
            val status = Records.EventProcessingStatus.Success(
              sequenceNumber = sm.sequenceNumber + 1,
              processedAt = ordinal
            )

            val updatedFiber = sm.copy(
              currentState = newStateId.getOrElse(sm.currentState),
              stateData = newStateData,
              sequenceNumber = sm.sequenceNumber + 1,
              latestUpdateOrdinal = ordinal,
              lastEventStatus = status,
              eventBatch = (sm.eventBatch :+ status).takeRight(sm.maxEventBatchSize)
            )

            // Hash update handled by orchestrator for atomicity
            val updatedState = state.updateFiber(updatedFiber)

            val cascadeTriggers = triggers.map { t =>
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

            TriggerHandlerResult.Success(
              updatedState = updatedState,
              statuses = List((sm.cid, status)),
              cascadeTriggers = cascadeTriggers,
              gasUsed = gasUsed
            ): TriggerHandlerResult

          case FiberOutcome.GuardFailed(attemptedCount, guardGasUsed) =>
            val eventType = trigger.input match {
              case FiberInput.Transition(et, _)      => et
              case FiberInput.MethodCall(m, _, _, _) => StateMachine.EventType(m)
            }
            TriggerHandlerResult.Failed(
              StateMachine.FailureReason.NoGuardMatched(sm.currentState, eventType, attemptedCount),
              guardGasUsed
            ): TriggerHandlerResult

          case FiberOutcome.Failed(reason) =>
            TriggerHandlerResult.Failed(reason): TriggerHandlerResult
        }
      }
    }
}

/**
 * Trigger handler for oracle fibers.
 */
object OracleTriggerHandler {

  def make[F[_]: Async: JsonLogicEvaluator](
    ordinal:   SnapshotOrdinal,
    limits:    ExecutionLimits,
    gasConfig: GasConfig
  ): TriggerHandler[F] =
    new TriggerHandler[F] {

      def handle(
        trigger:      FiberTrigger,
        fiber:        Records.FiberRecord,
        state:        CalculatedState,
        remainingGas: Long
      ): F[TriggerHandlerResult] =
        fiber match {
          case oracle: Records.ScriptOracleFiberRecord =>
            handleOracle(trigger, oracle, state, remainingGas)
          case other =>
            (TriggerHandlerResult.Failed(
              StateMachine.FailureReason.FiberInputMismatch(other.cid, other.getClass.getSimpleName, "OracleTrigger")
            ): TriggerHandlerResult).pure[F]
        }

      private def handleOracle(
        trigger:      FiberTrigger,
        oracle:       Records.ScriptOracleFiberRecord,
        state:        CalculatedState,
        remainingGas: Long
      ): F[TriggerHandlerResult] = {
        val (method, args) = trigger.input match {
          case FiberInput.Transition(eventType, payload) => (eventType.value, payload)
          case FiberInput.MethodCall(m, a, _, _)         => (m, a)
        }

        type OracleET[A] = EitherT[F, TriggerHandlerResult, A]

        val computation: OracleET[TriggerHandlerResult] = for {
          callerAddress <- EitherT.fromOption[F](
            trigger.sourceFiberId.flatMap { fiberId =>
              state.getFiber(fiberId).flatMap(_.owners.headOption)
            },
            TriggerHandlerResult.Failed(
              StateMachine.FailureReason.CallerResolutionFailed(oracle.cid, trigger.sourceFiberId)
            ): TriggerHandlerResult
          )

          _ <- EitherT[F, TriggerHandlerResult, Unit](
            OracleProcessor.validateAccess(oracle.accessControl, callerAddress, oracle.cid).map {
              case Right(_)     => Right(())
              case Left(reason) => Left(TriggerHandlerResult.Failed(reason))
            }
          )

          inputData = MapValue(
            Map(
              ReservedKeys.METHOD -> StrValue(method),
              ReservedKeys.ARGS   -> args,
              ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
            )
          )

          scriptResult <- EitherT[F, TriggerHandlerResult, EvaluationResult[JsonLogicValue]](
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
              .map {
                case Right(result) =>
                  Right(result)
                case Left(_: GasExhaustedException) =>
                  Left(
                    TriggerHandlerResult.Failed(
                      StateMachine.FailureReason.GasExhaustedFailure(
                        limits.maxGas,
                        limits.maxGas,
                        StateMachine.GasExhaustionPhase.Oracle
                      )
                    )
                  )
                case Left(err) =>
                  Left(
                    TriggerHandlerResult.Failed(
                      StateMachine.FailureReason.EffectEvaluationError(err.getMessage)
                    )
                  )
              }
          )

          scriptGasUsed = scriptResult.gasUsed.amount
          evaluationResult = scriptResult.value

          stateAndResult <- EitherT.liftF[F, TriggerHandlerResult, (Option[JsonLogicValue], JsonLogicValue)](
            OracleProcessor.extractStateAndResult(evaluationResult)
          )
          (newStateData, returnValue) = stateAndResult

          _ <- {
            val checkResult: Either[TriggerHandlerResult, Unit] = returnValue match {
              case BoolValue(false) =>
                Left(
                  TriggerHandlerResult.Failed(
                    StateMachine.FailureReason.OracleInvocationFailed(oracle.cid, method, Some("returned false")),
                    scriptGasUsed
                  )
                )
              case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
                val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
                Left(
                  TriggerHandlerResult.Failed(
                    StateMachine.FailureReason
                      .OracleInvocationFailed(oracle.cid, method, Some(s"validation failed: $errorMsg")),
                    scriptGasUsed
                  )
                )
              case _ => Right(())
            }
            EitherT.fromEither[F](checkResult)
          }

          newHash <- EitherT.liftF[F, TriggerHandlerResult, Option[io.constellationnetwork.security.hash.Hash]](
            newStateData.traverse(_.computeDigest)
          )

          invocation = Records.OracleInvocation(
            method = method,
            args = args,
            result = returnValue,
            gasUsed = scriptGasUsed,
            invokedAt = ordinal,
            invokedBy = callerAddress
          )

          newLog = (invocation :: oracle.invocationLog).take(oracle.maxLogSize)

          updatedOracle = oracle.copy(
            stateData = newStateData,
            stateDataHash = newHash,
            latestUpdateOrdinal = ordinal,
            invocationCount = oracle.invocationCount + 1,
            invocationLog = newLog
          )

          updatedState = state.updateFiber(updatedOracle)

        } yield TriggerHandlerResult.Success(
          updatedState = updatedState,
          statuses = List.empty,
          cascadeTriggers = List.empty,
          gasUsed = scriptGasUsed
        ): TriggerHandlerResult

        computation.merge
      }
    }
}

/**
 * Composite trigger handler that dispatches to the appropriate handler based on fiber type.
 */
object CompositeTriggerHandler {

  def make[F[_]: Async: JsonLogicEvaluator](
    evaluatorFactory: CalculatedState => FiberEvaluator[F],
    ordinal:          SnapshotOrdinal,
    limits:           ExecutionLimits,
    gasConfig:        GasConfig
  ): TriggerHandler[F] = {
    val smHandler = StateMachineTriggerHandler.make[F](evaluatorFactory, ordinal)
    val oracleHandler = OracleTriggerHandler.make[F](ordinal, limits, gasConfig)

    new TriggerHandler[F] {

      def handle(
        trigger:      FiberTrigger,
        fiber:        Records.FiberRecord,
        state:        CalculatedState,
        remainingGas: Long
      ): F[TriggerHandlerResult] =
        fiber match {
          case _: Records.StateMachineFiberRecord =>
            smHandler.handle(trigger, fiber, state, remainingGas)
          case _: Records.ScriptOracleFiberRecord =>
            oracleHandler.handle(trigger, fiber, state, remainingGas)
        }
    }
  }
}
