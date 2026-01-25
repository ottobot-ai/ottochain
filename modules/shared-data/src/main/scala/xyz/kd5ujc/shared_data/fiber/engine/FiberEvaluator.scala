package xyz.kd5ujc.shared_data.fiber.engine

import cats.Functor
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.lifecycle.InputValidation
import xyz.kd5ujc.shared_data.lifecycle.InputValidation.ValidationResult

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
  def make[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    contextProvider: ContextProvider[F],
    calculatedState: CalculatedState,
    ordinal:         SnapshotOrdinal,
    limits:          ExecutionLimits,
    gasConfig:       GasConfig,
    fiberGasConfig:  FiberGasConfig = FiberGasConfig.Default
  ): FiberEvaluator[F] =
    new FiberEvaluator[F] {

      private val stateMerger: StateMerger[F] = StateMerger.make[F]

      def evaluate(
        fiber:  Records.FiberRecord,
        input:  FiberInput,
        proofs: List[SignatureProof]
      ): F[FiberOutcome] = (fiber, input) match {
        case (sm: Records.StateMachineFiberRecord, FiberInput.Transition(eventType, payload)) =>
          evaluateStateMachine(sm, eventType, payload, proofs)

        case (oracle: Records.ScriptOracleFiberRecord, FiberInput.MethodCall(method, args, caller, _)) =>
          evaluateOracle(oracle, method, args, caller)

        case (_: Records.StateMachineFiberRecord, _: FiberInput.MethodCall) =>
          (FiberOutcome.Failed(
            StateMachine.FailureReason.Other("Cannot use MethodCall input with StateMachineFiberRecord")
          ): FiberOutcome).pure[F]

        case (_: Records.ScriptOracleFiberRecord, _: FiberInput.Transition) =>
          (FiberOutcome.Failed(
            StateMachine.FailureReason.Other("Cannot use Transition input with ScriptOracleFiberRecord")
          ): FiberOutcome).pure[F]
      }

      private def evaluateStateMachine(
        fiber:     Records.StateMachineFiberRecord,
        eventType: StateMachine.EventType,
        payload:   JsonLogicValue,
        proofs:    List[SignatureProof]
      ): F[FiberOutcome] = {
        val event = StateMachine.Event(eventType, payload)
        val validationResult = InputValidation.validateTransaction(fiber.cid, event, limits.maxGas)

        validationResult.isValid
          .pure[F]
          .ifM(
            ifTrue = fiber.definition.transitionMap
              .get((fiber.currentState, eventType))
              .fold[F[FiberOutcome]](
                (FiberOutcome.Failed(
                  StateMachine.FailureReason.NoTransitionFound(fiber.currentState, eventType)
                ): FiberOutcome).pure[F]
              )(transitions =>
                tryTransitions(fiber, event, proofs, transitions, attemptedGuards = 0, accumulatedGuardGas = 0L)
              ),
            ifFalse = (FiberOutcome.Failed(
              StateMachine.FailureReason.ValidationFailed(
                validationResult.errors.map(e => s"${e.field}: ${e.message}").mkString(", "),
                ordinal
              )
            ): FiberOutcome).pure[F]
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
            // EVM semantics: charge for all guard evaluations even when all fail
            (FiberOutcome.GuardFailed(attemptedGuards, accumulatedGuardGas): FiberOutcome).pure[F]

          case transition :: rest =>
            InputValidation.validateExpression(transition.guard) match {
              case ValidationResult(false, errors) =>
                (FiberOutcome.Failed(
                  StateMachine.FailureReason.GuardEvaluationError(
                    s"Invalid guard: ${errors.map(_.message).mkString(", ")}"
                  )
                ): FiberOutcome).pure[F]

              case _ =>
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
        // Calculate remaining gas after previous guards (EVM: deduct from budget)
        val remainingGas = (limits.maxGas - accumulatedGuardGas).max(0L)

        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(transition.guard, contextData, None, GasLimit(remainingGas), gasConfig)
          .flatMap[FiberOutcome] {
            case Right(EvaluationResult(BoolValue(true), guardGasUsed, _, _)) =>
              // Pass total accumulated gas (previous + current) to effect execution
              applyTransition(fiber, transition, event, contextData, accumulatedGuardGas + guardGasUsed.amount)

            case Right(EvaluationResult(BoolValue(false), guardGasUsed, _, _)) =>
              // EVM semantics: accumulate gas even for failed guards
              tryTransitions(
                fiber,
                event,
                proofs,
                rest,
                attemptedGuards + 1,
                accumulatedGuardGas + guardGasUsed.amount
              )

            case Right(EvaluationResult(other, _, _, _)) =>
              (FiberOutcome.Failed(
                StateMachine.FailureReason.GuardEvaluationError(
                  s"Guard returned non-boolean value: ${other.getClass.getSimpleName}"
                )
              ): FiberOutcome).pure[F]

            case Left(_: GasExhaustedException) =>
              (FiberOutcome.Failed(
                StateMachine.FailureReason.GasExhaustedFailure(
                  accumulatedGuardGas,
                  limits.maxGas,
                  StateMachine.GasExhaustionPhase.Guard
                )
              ): FiberOutcome).pure[F]

            case Left(err) =>
              (FiberOutcome.Failed(
                StateMachine.FailureReason.GuardEvaluationError(err.getMessage)
              ): FiberOutcome).pure[F]
          }
      }

      private def applyTransition(
        fiber:       Records.StateMachineFiberRecord,
        transition:  StateMachine.Transition,
        event:       StateMachine.Event,
        contextData: JsonLogicValue,
        guardGas:    Long
      ): F[FiberOutcome] =
        InputValidation.validateExpression(transition.effect) match {
          case ValidationResult(false, errors) =>
            (FiberOutcome.Failed(
              StateMachine.FailureReason.EffectEvaluationError(
                s"Invalid effect: ${errors.map(_.message).mkString(", ")}"
              )
            ): FiberOutcome).pure[F]

          case _ =>
            executeEffect(fiber, transition, event, contextData, guardGas)
        }

      private def executeEffect(
        fiber:       Records.StateMachineFiberRecord,
        transition:  StateMachine.Transition,
        event:       StateMachine.Event,
        contextData: JsonLogicValue,
        guardGas:    Long
      ): F[FiberOutcome] = {
        val remainingGas = limits.maxGas - guardGas

        fiber.stateData match {
          case currentMap: MapValue =>
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluateWithGas(transition.effect, contextData, None, GasLimit(remainingGas), gasConfig)
              .flatMap {
                case Right(EvaluationResult(effectResult, effectGasUsed, _, _)) =>
                  val gasAfterEffect = guardGas + effectGasUsed.amount

                  InputValidation.validateStateSize(effectResult) match {
                    case ValidationResult(false, errors) =>
                      (FiberOutcome.Failed(
                        StateMachine.FailureReason.EffectEvaluationError(
                          s"Effect result too large: ${errors.mkString(", ")}"
                        )
                      ): FiberOutcome).pure[F]

                    case _ =>
                      for {
                        outputs <- EffectExtractor.extractOutputs(effectResult).pure[F]
                        spawnMachines = EffectExtractor.extractSpawnDirectivesFromExpression(transition.effect)
                        triggerEvents <- EffectExtractor.extractTriggerEvents(effectResult, contextData)
                        oracleCall    <- EffectExtractor.extractOracleCall(effectResult, contextData)
                        allTriggers = triggerEvents ++ oracleCall.toList
                        gasForTriggers = allTriggers.size * fiberGasConfig.triggerEvent.amount
                        gasForSpawns = spawnMachines.size * fiberGasConfig.spawnDirective.amount
                        finalGasUsed = gasAfterEffect + gasForTriggers + gasForSpawns
                        result <-
                          if (finalGasUsed > limits.maxGas) {
                            (FiberOutcome.Failed(
                              StateMachine.FailureReason.GasExhaustedFailure(
                                finalGasUsed,
                                limits.maxGas,
                                StateMachine.GasExhaustionPhase.Effect
                              )
                            ): FiberOutcome).pure[F]
                          } else {
                            stateMerger.mergeEffectIntoState(currentMap, effectResult).map {
                              case Right(newStateData) =>
                                FiberOutcome.Success(
                                  newStateData = newStateData,
                                  newStateId = Some(transition.to),
                                  triggers = allTriggers,
                                  spawns = spawnMachines,
                                  outputs = outputs,
                                  returnValue = None,
                                  gasUsed = finalGasUsed
                                ): FiberOutcome
                              case Left(reason) =>
                                FiberOutcome.Failed(reason): FiberOutcome
                            }
                          }
                      } yield result
                  }

                case Left(_: GasExhaustedException) =>
                  (FiberOutcome.Failed(
                    StateMachine.FailureReason.GasExhaustedFailure(
                      guardGas,
                      limits.maxGas,
                      StateMachine.GasExhaustionPhase.Effect
                    )
                  ): FiberOutcome).pure[F]

                case Left(err) =>
                  (FiberOutcome.Failed(
                    StateMachine.FailureReason.EffectEvaluationError(err.getMessage)
                  ): FiberOutcome).pure[F]
              }

          case _ =>
            (FiberOutcome.Failed(
              StateMachine.FailureReason.EffectEvaluationError("State data must be MapValue")
            ): FiberOutcome).pure[F]
        }
      }

      private def evaluateOracle(
        oracle: Records.ScriptOracleFiberRecord,
        method: String,
        args:   JsonLogicValue,
        caller: io.constellationnetwork.schema.address.Address
      ): F[FiberOutcome] =
        OracleProcessor.validateAccess[F](oracle.accessControl, caller, oracle.cid).flatMap {
          case Left(reason) =>
            (FiberOutcome.Failed(reason): FiberOutcome).pure[F]

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
                    ): FiberOutcome
                  }

                case Left(_: GasExhaustedException) =>
                  (FiberOutcome.Failed(
                    StateMachine.FailureReason.GasExhaustedFailure(
                      limits.maxGas,
                      limits.maxGas,
                      StateMachine.GasExhaustionPhase.Oracle
                    )
                  ): FiberOutcome).pure[F]

                case Left(err) =>
                  (FiberOutcome.Failed(
                    StateMachine.FailureReason.EffectEvaluationError(err.getMessage)
                  ): FiberOutcome).pure[F]
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
