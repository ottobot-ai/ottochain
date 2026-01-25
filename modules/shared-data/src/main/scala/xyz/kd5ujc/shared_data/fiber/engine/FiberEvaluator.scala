package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.Functor
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain.FiberOutcome.FailureReasonOps
import xyz.kd5ujc.shared_data.fiber.domain._

/**
 * Unified evaluator for both state machine and oracle fibers.
 *
 * Dispatches to appropriate evaluation strategy based on fiber type:
 * - StateMachineFiberRecord + Transition → guard/effect evaluation
 * - ScriptOracleFiberRecord + MethodCall → script evaluation
 *
 * Invalid combinations (SM + MethodCall, Oracle + Transition) return Failed.
 */
trait FiberEvaluator[F[_]] {

  def evaluate(
    fiber:  Records.FiberRecord,
    input:  FiberInput,
    proofs: List[SignatureProof]
  ): F[FiberOutcome]
}

object FiberEvaluator {

  /**
   * Create FiberEvaluator with explicit parameters.
   */
  def make[F[_]: Async: JsonLogicEvaluator](
    contextProvider: ContextProvider[F],
    calculatedState: CalculatedState,
    ordinal:         SnapshotOrdinal,
    limits:          ExecutionLimits,
    gasConfig:       GasConfig,
    fiberGasConfig:  FiberGasConfig = FiberGasConfig.Default
  ): FiberEvaluator[F] =
    new FiberEvaluator[F] {

      def evaluate(
        fiber:  Records.FiberRecord,
        input:  FiberInput,
        proofs: List[SignatureProof]
      ): F[FiberOutcome] = (fiber, input) match {
        case (sm: Records.StateMachineFiberRecord, FiberInput.Transition(eventType, payload)) =>
          evaluateStateMachine(sm, eventType, payload, proofs)

        case (oracle: Records.ScriptOracleFiberRecord, FiberInput.MethodCall(method, args, caller, _)) =>
          evaluateOracle(oracle, method, args, caller)

        case (sm: Records.StateMachineFiberRecord, _: FiberInput.MethodCall) =>
          StateMachine.FailureReason
            .FiberInputMismatch(sm.cid, "StateMachineFiberRecord", "MethodCall")
            .pureOutcome[F]

        case (oracle: Records.ScriptOracleFiberRecord, _: FiberInput.Transition) =>
          StateMachine.FailureReason
            .FiberInputMismatch(oracle.cid, "ScriptOracleFiberRecord", "Transition")
            .pureOutcome[F]
      }

      // ──────────────────────────────────────────────────────────────────────────
      // State Machine Evaluation
      // ──────────────────────────────────────────────────────────────────────────

      private def evaluateStateMachine(
        fiber:     Records.StateMachineFiberRecord,
        eventType: StateMachine.EventType,
        payload:   JsonLogicValue,
        proofs:    List[SignatureProof]
      ): F[FiberOutcome] = {
        val event = StateMachine.Event(eventType, payload)

        fiber.definition.transitionMap
          .get((fiber.currentState, eventType))
          .fold(
            StateMachine.FailureReason.NoTransitionFound(fiber.currentState, eventType).pureOutcome[F]
          )(
            tryTransitions(fiber, event, proofs, _, attemptedGuards = 0, accumulatedGuardGas = 0L)
          )
      }

      private def tryTransitions(
        fiber:               Records.StateMachineFiberRecord,
        event:               StateMachine.Event,
        proofs:              List[SignatureProof],
        transitions:         List[StateMachine.Transition],
        attemptedGuards:     Int,
        accumulatedGuardGas: Long
      ): F[FiberOutcome] =
        transitions match {
          case Nil =>
            (FiberOutcome.GuardFailed(attemptedGuards, accumulatedGuardGas): FiberOutcome).pure[F]

          case transition :: rest =>
            for {
              contextData <- contextProvider.buildContext(
                fiber,
                FiberInput.Transition(event.eventType, event.payload),
                proofs,
                transition.dependencies
              )
              result <- evaluateGuardAndApply(
                fiber,
                transition,
                event,
                contextData,
                proofs,
                rest,
                attemptedGuards,
                accumulatedGuardGas
              )
            } yield result
        }

      private def evaluateGuardAndApply(
        fiber:               Records.StateMachineFiberRecord,
        transition:          StateMachine.Transition,
        event:               StateMachine.Event,
        contextData:         JsonLogicValue,
        proofs:              List[SignatureProof],
        rest:                List[StateMachine.Transition],
        attemptedGuards:     Int,
        accumulatedGuardGas: Long
      ): F[FiberOutcome] = {
        val remainingGas = (limits.maxGas - accumulatedGuardGas).max(0L)

        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(transition.guard, contextData, None, GasLimit(remainingGas), gasConfig)
          .flatMap[FiberOutcome] {
            case Right(EvaluationResult(BoolValue(true), guardGasUsed, _, _)) =>
              executeEffect(fiber, transition, contextData, accumulatedGuardGas + guardGasUsed.amount)

            case Right(EvaluationResult(BoolValue(false), guardGasUsed, _, _)) =>
              tryTransitions(fiber, event, proofs, rest, attemptedGuards + 1, accumulatedGuardGas + guardGasUsed.amount)

            case Right(EvaluationResult(other, _, _, _)) =>
              StateMachine.FailureReason
                .GuardEvaluationError(s"Guard returned non-boolean: ${other.getClass.getSimpleName}")
                .pureOutcome[F]

            case Left(_: GasExhaustedException) =>
              StateMachine.FailureReason
                .GasExhaustedFailure(accumulatedGuardGas, limits.maxGas, StateMachine.GasExhaustionPhase.Guard)
                .pureOutcome[F]

            case Left(err) =>
              StateMachine.FailureReason.GuardEvaluationError(err.getMessage).pureOutcome[F]
          }
      }

      // ──────────────────────────────────────────────────────────────────────────
      // Effect Execution
      // ──────────────────────────────────────────────────────────────────────────

      private def executeEffect(
        fiber:       Records.StateMachineFiberRecord,
        transition:  StateMachine.Transition,
        contextData: JsonLogicValue,
        guardGas:    Long
      ): F[FiberOutcome] =
        fiber.stateData match {
          case currentMap: MapValue =>
            evaluateEffectExpression(transition, contextData, guardGas).flatMap {
              case Left(reason) => reason.pureOutcome[F]
              case Right((effectResult, gasAfterEffect)) =>
                processEffectResult(fiber.cid, currentMap, transition, effectResult, contextData, gasAfterEffect)
            }

          case _ =>
            StateMachine.FailureReason.EffectEvaluationError("State data must be MapValue").pureOutcome[F]
        }

      private def evaluateEffectExpression(
        transition:  StateMachine.Transition,
        contextData: JsonLogicValue,
        guardGas:    Long
      ): F[Either[StateMachine.FailureReason, (JsonLogicValue, Long)]] = {
        val remainingGas = limits.maxGas - guardGas

        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(transition.effect, contextData, None, GasLimit(remainingGas), gasConfig)
          .map {
            case Right(EvaluationResult(effectResult, effectGasUsed, _, _)) =>
              (effectResult, guardGas + effectGasUsed.amount).asRight

            case Left(_: GasExhaustedException) =>
              StateMachine.FailureReason
                .GasExhaustedFailure(guardGas, limits.maxGas, StateMachine.GasExhaustionPhase.Effect)
                .asLeft

            case Left(err) =>
              StateMachine.FailureReason.EffectEvaluationError(err.getMessage).asLeft
          }
      }

      private def processEffectResult(
        fiberId:        UUID,
        currentMap:     MapValue,
        transition:     StateMachine.Transition,
        effectResult:   JsonLogicValue,
        contextData:    JsonLogicValue,
        gasAfterEffect: Long
      ): F[FiberOutcome] =
        for {
          // Validate state size (runtime check - only known after effect execution)
          sizeCheck <- validateStateSize(effectResult)
          result <- sizeCheck match {
            case Left(reason) => reason.pureOutcome[F]
            case Right(_) =>
              buildSuccessOutcome(fiberId, currentMap, transition, effectResult, contextData, gasAfterEffect)
          }
        } yield result

      private def validateStateSize(effectResult: JsonLogicValue): F[Either[StateMachine.FailureReason, Unit]] =
        JsonBinaryCodec[F, JsonLogicValue]
          .serialize(effectResult)
          .map { bytes =>
            val size = bytes.length
            if (size <= limits.maxStateSizeBytes) ().asRight
            else StateMachine.FailureReason.StateSizeTooLarge(size, limits.maxStateSizeBytes).asLeft
          }

      private def buildSuccessOutcome(
        fiberId:        UUID,
        currentMap:     MapValue,
        transition:     StateMachine.Transition,
        effectResult:   JsonLogicValue,
        contextData:    JsonLogicValue,
        gasAfterEffect: Long
      ): F[FiberOutcome] =
        for {
          // Extract side effects from result
          outputs <- EffectExtractor.extractOutputs(effectResult).pure[F]
          spawnMachines = EffectExtractor.extractSpawnDirectivesFromExpression(transition.effect)
          triggerEvents <- EffectExtractor.extractTriggerEvents(effectResult, contextData)
          oracleCall    <- EffectExtractor.extractOracleCall(effectResult, contextData)
          allTriggers = triggerEvents ++ oracleCall.toList

          // Calculate final gas with orchestration overhead
          gasForTriggers = allTriggers.size * fiberGasConfig.triggerEvent.amount
          gasForSpawns = spawnMachines.size * fiberGasConfig.spawnDirective.amount
          finalGasUsed = gasAfterEffect + gasForTriggers + gasForSpawns

          result <-
            if (finalGasUsed > limits.maxGas)
              StateMachine.FailureReason
                .GasExhaustedFailure(finalGasUsed, limits.maxGas, StateMachine.GasExhaustionPhase.Effect)
                .pureOutcome[F]
            else
              StateMerger.make[F].mergeEffectIntoState(currentMap, effectResult).map[FiberOutcome] {
                case Right(newStateData) =>
                  FiberOutcome.Success(
                    newStateData = newStateData,
                    newStateId = Some(transition.to),
                    triggers = allTriggers,
                    spawns = spawnMachines,
                    outputs = outputs,
                    returnValue = None,
                    gasUsed = finalGasUsed
                  )
                case Left(reason) => reason.asOutcome
              }
        } yield result

      // ──────────────────────────────────────────────────────────────────────────
      // Oracle Evaluation
      // ──────────────────────────────────────────────────────────────────────────

      private def evaluateOracle(
        oracle: Records.ScriptOracleFiberRecord,
        method: String,
        args:   JsonLogicValue,
        caller: io.constellationnetwork.schema.address.Address
      ): F[FiberOutcome] =
        OracleProcessor.validateAccess[F](oracle.accessControl, caller, oracle.cid).flatMap {
          case Left(reason) => reason.pureOutcome[F]

          case Right(_) =>
            val inputData = MapValue(
              Map(
                ReservedKeys.METHOD -> StrValue(method),
                ReservedKeys.ARGS   -> args,
                ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
              )
            )

            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(limits.maxGas), gasConfig)
              .flatMap {
                case Right(EvaluationResult(evaluationResult, gasUsed, _, _)) =>
                  OracleProcessor.extractStateAndResult[F](evaluationResult).map { case (newStateData, returnValue) =>
                    FiberOutcome.Success(
                      newStateData = newStateData.getOrElse(oracle.stateData.getOrElse(NullValue)),
                      newStateId = None,
                      triggers = List.empty,
                      spawns = List.empty,
                      outputs = List.empty,
                      returnValue = Some(returnValue),
                      gasUsed = gasUsed.amount
                    )
                  }

                case Left(_: GasExhaustedException) =>
                  StateMachine.FailureReason
                    .GasExhaustedFailure(limits.maxGas, limits.maxGas, StateMachine.GasExhaustionPhase.Oracle)
                    .pureOutcome[F]

                case Left(err) =>
                  StateMachine.FailureReason.EffectEvaluationError(err.getMessage).pureOutcome[F]
              }
        }
    }

  /**
   * Create FiberEvaluator by reading configuration from FiberContext.
   * This is the MTL-aware factory that works within FiberT.
   */
  def make[F[_]: Async: SecurityProvider: JsonLogicEvaluator, G[_]: Functor](implicit
    A: Ask[G, FiberContext]
  ): G[FiberEvaluator[F]] =
    ExecutionOps.askContext[G].map { ctx =>
      val contextProvider = ContextProvider.make[F](ctx.calculatedState)
      make[F](contextProvider, ctx.calculatedState, ctx.ordinal, ctx.limits, ctx.gasConfig, ctx.fiberGasConfig)
    }
}
