package xyz.kd5ujc.shared_data.fiber.triggers

import cats.data.EitherT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{~>, Monad}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.{FiberEvaluator, OracleProcessor}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Handles individual trigger processing in G[_] with gas tracked via StateT
 * and config read from FiberContext via Ask.
 *
 * Dispatches to appropriate handler based on fiber type:
 * - StateMachineFiberRecord: delegates to FiberEvaluator for guard/effect evaluation
 * - ScriptOracleFiberRecord: evaluates script directly with gas metering
 */
trait TriggerHandler[G[_]] {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult]
}

object TriggerHandler {

  def make[F[_]: Async: SecurityProvider, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): TriggerHandler[G] =
    (trigger: FiberTrigger, fiber: Records.FiberRecord, state: CalculatedState) =>
      fiber match {
        case _: Records.StateMachineFiberRecord =>
          new StateMachineTriggerHandler[F, G](state)
            .handle(trigger, fiber, state)

        case _: Records.ScriptOracleFiberRecord =>
          new OracleTriggerHandler[F, G]()
            .handle(trigger, fiber, state)
      }
}

class StateMachineTriggerHandler[F[_]: Async: SecurityProvider, G[_]: Monad](
  calculatedState: CalculatedState
)(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case sm: Records.StateMachineFiberRecord =>
        handleStateMachine(trigger, sm, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason
            .FiberInputMismatch(other.cid, other.getClass.getSimpleName, "StateMachineTrigger")
        ): TriggerHandlerResult).pure[G]
    }

  private def handleStateMachine(
    trigger: FiberTrigger,
    sm:      Records.StateMachineFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    for {
      ordinal <- ExecutionOps.askOrdinal[G]
      maxLog  <- ExecutionOps.askLimits[G].map(_.maxLogSize)
      outcome <- FiberEvaluator.make[F, G](calculatedState).evaluate(sm, trigger.input, List.empty)
    } yield outcome match {
      case FiberResult.Success(newStateData, newStateId, triggers, _, outputs, _) =>
        val eventType = trigger.input match {
          case FiberInput.Transition(et, _, _)   => et
          case FiberInput.MethodCall(m, _, _, _) => EventType(m)
        }

        val receipt = EventReceipt(
          fiberId = sm.cid,
          sequenceNumber = sm.sequenceNumber + 1,
          eventType = eventType,
          ordinal = ordinal,
          fromState = sm.currentState,
          toState = newStateId.getOrElse(sm.currentState),
          success = true,
          gasUsed = 0L,
          triggersFired = triggers.size,
          outputs = outputs,
          sourceFiberId = trigger.sourceFiberId
        )

        val updatedFiber = sm.copy(
          currentState = newStateId.getOrElse(sm.currentState),
          stateData = newStateData,
          sequenceNumber = sm.sequenceNumber + 1,
          latestUpdateOrdinal = ordinal,
          lastReceipt = Some(receipt),
          eventLog = (receipt :: sm.eventLog).take(maxLog)
        )

        val updatedState = state.updateFiber(updatedFiber)

        TriggerHandlerResult.Success(
          updatedState = updatedState,
          receipts = List(receipt),
          cascadeTriggers = triggers
        ): TriggerHandlerResult

      case FiberResult.GuardFailed(attemptedCount) =>
        val eventType = trigger.input match {
          case FiberInput.Transition(et, _, _)   => et
          case FiberInput.MethodCall(m, _, _, _) => EventType(m)
        }
        TriggerHandlerResult.Failed(
          FailureReason.NoGuardMatched(sm.currentState, eventType, attemptedCount)
        ): TriggerHandlerResult

      case FiberResult.Failed(reason) =>
        TriggerHandlerResult.Failed(reason): TriggerHandlerResult
    }
}

class OracleTriggerHandler[F[_]: Async, G[_]: Monad]()(implicit
  S:    Stateful[G, ExecutionState],
  A:    Ask[G, FiberContext],
  lift: F ~> G
) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case oracle: Records.ScriptOracleFiberRecord =>
        handleOracle(trigger, oracle, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason.FiberInputMismatch(other.cid, other.getClass.getSimpleName, "OracleTrigger")
        ): TriggerHandlerResult).pure[G]
    }

  private def handleOracle(
    trigger: FiberTrigger,
    oracle:  Records.ScriptOracleFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] = {
    val (method, args) = trigger.input match {
      case FiberInput.Transition(eventType, payload, _) => (eventType.value, payload)
      case FiberInput.MethodCall(m, a, _, _)            => (m, a)
    }

    type OracleET[A] = EitherT[G, TriggerHandlerResult, A]

    val computation: OracleET[TriggerHandlerResult] = for {
      callerAddress <- EitherT.fromOption[G](
        trigger.sourceFiberId.flatMap { fiberId =>
          state.getFiber(fiberId).flatMap(_.owners.headOption)
        },
        TriggerHandlerResult.Failed(
          FailureReason.CallerResolutionFailed(oracle.cid, trigger.sourceFiberId)
        ): TriggerHandlerResult
      )

      _ <- EitherT[G, TriggerHandlerResult, Unit](
        OracleProcessor.validateAccess(oracle.accessControl, callerAddress, oracle.cid, state).liftTo[G].map {
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

      remainingGas <- EitherT.liftF[G, TriggerHandlerResult, Long](
        ExecutionOps.remainingGas[G]
      )

      gasConfig <- EitherT.liftF[G, TriggerHandlerResult, GasConfig](
        ExecutionOps.askGasConfig[G]
      )

      scriptResult <- EitherT[G, TriggerHandlerResult, EvaluationResult[JsonLogicValue]](
        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
          .liftTo[G]
          .flatMap {
            case Right(result) =>
              ExecutionOps.chargeGas[G](result.gasUsed.amount).as(result.asRight[TriggerHandlerResult])

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Oracle).map { reason =>
                (TriggerHandlerResult.Failed(reason): TriggerHandlerResult)
                  .asLeft[EvaluationResult[JsonLogicValue]]
              }
          }
      )

      scriptGasUsed = scriptResult.gasUsed.amount
      evaluationResult = scriptResult.value

      stateAndResult <- EitherT.liftF[G, TriggerHandlerResult, (Option[JsonLogicValue], JsonLogicValue)](
        OracleProcessor.extractStateAndResult(evaluationResult).liftTo[G]
      )
      (newStateData, returnValue) = stateAndResult

      _ <- {
        val checkResult: Either[TriggerHandlerResult, Unit] = returnValue match {
          case BoolValue(false) =>
            Left(
              TriggerHandlerResult.Failed(
                FailureReason.OracleInvocationFailed(oracle.cid, method, Some("returned false"))
              )
            )
          case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
            val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
            Left(
              TriggerHandlerResult.Failed(
                FailureReason
                  .OracleInvocationFailed(oracle.cid, method, Some(s"validation failed: $errorMsg"))
              )
            )
          case _ => Right(())
        }
        EitherT.fromEither[G](checkResult)
      }

      newHash <- EitherT.liftF[G, TriggerHandlerResult, Option[io.constellationnetwork.security.hash.Hash]](
        newStateData.traverse(_.computeDigest).liftTo[G]
      )

      ordinal <- EitherT.liftF[G, TriggerHandlerResult, io.constellationnetwork.schema.SnapshotOrdinal](
        ExecutionOps.askOrdinal[G]
      )

      maxLog <- EitherT.liftF[G, TriggerHandlerResult, Int](
        ExecutionOps.askLimits[G].map(_.maxLogSize)
      )

      invocation = OracleInvocation(
        method = method,
        args = args,
        result = returnValue,
        gasUsed = scriptGasUsed,
        invokedAt = ordinal,
        invokedBy = callerAddress
      )

      newLog = (invocation :: oracle.invocationLog).take(maxLog)

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
      receipts = List.empty,
      cascadeTriggers = List.empty
    ): TriggerHandlerResult

    computation.merge
  }
}
