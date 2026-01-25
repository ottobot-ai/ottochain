package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.core.ConstExpression
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, NullValue}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.fiber.engine.FiberTInstances._

/**
 * Top-level orchestrator for fiber processing using FiberT monad transformer.
 *
 * Composes FiberEvaluator, TriggerDispatcher, and SpawnProcessor
 * to handle complete event/invocation processing including cascades.
 *
 * Processing flow:
 * 1. Create FiberContext with calculated state, ordinal, limits, gas config
 * 2. Lookup fiber by ID
 * 3. Validate fiber is active
 * 4. Evaluate fiber (guards/effects for SM, script for Oracle)
 * 5. On success:
 *    a. Process spawns (creates child fibers)
 *    b. Process triggers (cascading evaluations)
 * 6. Run FiberT to produce F[TransactionOutcome]
 */
trait FiberOrchestrator[F[_]] {

  def process(
    fiberId: UUID,
    input:   FiberInput,
    proofs:  List[SignatureProof]
  ): F[TransactionOutcome]
}

object FiberOrchestrator {

  def make[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    calculatedState: CalculatedState,
    ordinal:         SnapshotOrdinal,
    limits:          ExecutionLimits = ExecutionLimits(),
    gasConfig:       GasConfig = GasConfig.Default,
    fiberGasConfig:  FiberGasConfig = FiberGasConfig.Default
  ): FiberOrchestrator[F] =
    new FiberOrchestrator[F] {

      def process(
        fiberId: UUID,
        input:   FiberInput,
        proofs:  List[SignatureProof]
      ): F[TransactionOutcome] = {
        val ctx = FiberContext(calculatedState, ordinal, limits, gasConfig, fiberGasConfig)
        processInternal(fiberId, input, proofs)
          .run(ctx) // Unwrap ReaderT -> ExecutionT[F, TransactionOutcome]
          .runA(ExecutionState.initial) // Unwrap StateT -> F[TransactionOutcome]
      }
    }

  private def processInternal[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiberId: UUID,
    input:   FiberInput,
    proofs:  List[SignatureProof]
  ): FiberT[F, TransactionOutcome] =
    ExecutionOps.askContext[FiberT[F, *]].flatMap {
      _.calculatedState.getFiber(fiberId) match {
        case None =>
          (TransactionOutcome.Aborted(
            StateMachine.FailureReason.Other(s"Fiber $fiberId not found"),
            0L
          ): TransactionOutcome).pureFiber[F]

        case Some(fiber) if fiber.status != Records.FiberStatus.Active =>
          (TransactionOutcome.Aborted(
            StateMachine.FailureReason.Other(s"Fiber $fiberId is not active"),
            0L
          ): TransactionOutcome).pureFiber[F]

        case Some(fiber) =>
          processActiveFiber(fiber, input, proofs)
      }
    }

  private def processActiveFiber[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:  Records.FiberRecord,
    input:  FiberInput,
    proofs: List[SignatureProof]
  ): FiberT[F, TransactionOutcome] =
    for {
      evaluator     <- FiberEvaluator.make[F, FiberT[F, *]]
      outcomeResult <- evaluator.evaluate(fiber, input, proofs).liftFiber
      result <- outcomeResult match {
        case FiberOutcome.Success(newStateData, newStateId, triggers, spawns, outputs, returnValue, gasUsed) =>
          fiber match {
            case sm: Records.StateMachineFiberRecord =>
              processStateMachineSuccess(
                sm,
                input,
                newStateData,
                newStateId,
                triggers,
                spawns,
                outputs,
                gasUsed,
                proofs
              )

            case oracle: Records.ScriptOracleFiberRecord =>
              processOracleSuccess(oracle, newStateData, returnValue, gasUsed)
          }

        case FiberOutcome.GuardFailed(attemptedCount, guardGasUsed) =>
          fiber match {
            case sm: Records.StateMachineFiberRecord =>
              val eventType = input match {
                case FiberInput.Transition(et, _) => et
                case _                            => StateMachine.EventType("unknown")
              }
              // EVM semantics: charge gas even for failed guard evaluations
              (TransactionOutcome.Aborted(
                StateMachine.FailureReason.NoGuardMatched(sm.currentState, eventType, attemptedCount),
                guardGasUsed
              ): TransactionOutcome).pureFiber[F]

            case _ =>
              (TransactionOutcome.Aborted(
                StateMachine.FailureReason.Other("Guard failed on non-state-machine fiber"),
                guardGasUsed
              ): TransactionOutcome).pureFiber[F]
          }

        case FiberOutcome.Failed(reason) =>
          (TransactionOutcome.Aborted(reason, 0L): TransactionOutcome).pureFiber[F]
      }
    } yield result

  private def processStateMachineSuccess[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    sm:           Records.StateMachineFiberRecord,
    input:        FiberInput,
    newStateData: JsonLogicValue,
    newStateId:   Option[StateMachine.StateId],
    triggers:     List[StateMachine.TriggerEvent],
    spawns:       List[StateMachine.SpawnDirective],
    outputs:      List[Records.StructuredOutput],
    gasUsed:      Long,
    proofs:       List[SignatureProof]
  ): FiberT[F, TransactionOutcome] =
    for {
      ctx <- ExecutionOps.askContext[FiberT[F, *]]
      _   <- ExecutionOps.chargeGas[FiberT[F, *]](gasUsed)

      hash <- newStateData.computeDigest.liftFiber

      status = Records.EventProcessingStatus.Success(
        sequenceNumber = sm.sequenceNumber + 1,
        processedAt = ctx.ordinal
      )

      updatedFiber = sm.copy(
        previousUpdateOrdinal = sm.latestUpdateOrdinal,
        latestUpdateOrdinal = ctx.ordinal,
        currentState = newStateId.getOrElse(sm.currentState),
        stateData = newStateData,
        stateDataHash = hash,
        sequenceNumber = sm.sequenceNumber + 1,
        lastEventStatus = status,
        eventBatch = List(status)
      )

      // Process spawns
      spawnedFibers <- spawns.isEmpty
        .pure[FiberT[F, *]]
        .ifM(
          ifTrue = List.empty[Records.StateMachineFiberRecord].pureFiber,
          ifFalse = ContextProvider
            .make[F](ctx.calculatedState)
            .buildTriggerContext(updatedFiber, input)
            .liftFiber
            .flatMap { contextForSpawn =>
              SpawnProcessor
                .make[F, FiberT[F, *]]
                .flatMap(
                  _.processSpawns(spawns, updatedFiber, contextForSpawn).liftFiber
                )
            }
        )

      // Update parent's childFiberIds
      parentWithChildren = updatedFiber.copy(
        childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.cid)
      )

      // Build state with spawns
      stateWithSpawns = spawnedFibers.foldLeft(
        ctx.calculatedState.updateFiber(parentWithChildren)
      ) { case (state, child) =>
        state.updateFiber(child)
      }

      // Process triggers if any
      result <- triggers.isEmpty
        .pure[FiberT[F, *]]
        .ifM(
          ifTrue = for {
            depth <- ExecutionOps.getDepth[FiberT[F, *]]
          } yield {
            val allMachines = Map(sm.cid -> parentWithChildren) ++ spawnedFibers.map(f => f.cid -> f).toMap
            TransactionOutcome.Committed(
              updatedStateMachines = allMachines,
              updatedOracles = Map.empty,
              statuses = List((sm.cid, status)),
              totalGasUsed = gasUsed,
              maxDepth = depth
            ): TransactionOutcome
          },
          ifFalse = {
            val unifiedTriggers = triggers.map { t =>
              val payload = t.payloadExpr match {
                case ConstExpression(value) => value
                case _                      => NullValue
              }
              FiberTrigger(t.targetMachineId, FiberInput.Transition(t.eventType, payload), Some(sm.cid))
            }

            val evaluatorFactory: CalculatedState => FiberEvaluator[F] =
              state => {
                val ctxProvider = ContextProvider.make[F](state)
                FiberEvaluator
                  .make[F](ctxProvider, state, ctx.ordinal, ctx.limits, ctx.gasConfig, ctx.fiberGasConfig)
              }

            val dispatcher = TriggerDispatcher.make[F](evaluatorFactory, ctx.ordinal, ctx.limits, ctx.gasConfig)

            dispatcher.dispatch(unifiedTriggers, stateWithSpawns).liftFiber.map {
              case TransactionOutcome.Committed(machines, oracles, statuses, totalGas, maxDepth, opCount) =>
                // Merge with spawned fibers (trigger results may override)
                val allMachines = spawnedFibers.map(f => f.cid -> f).toMap ++ machines
                TransactionOutcome.Committed(
                  updatedStateMachines = allMachines,
                  updatedOracles = oracles,
                  statuses = (sm.cid, status) :: statuses,
                  totalGasUsed = gasUsed + totalGas,
                  maxDepth = maxDepth,
                  operationCount = opCount
                ): TransactionOutcome

              case aborted: TransactionOutcome.Aborted =>
                aborted
            }
          }
        )
    } yield result

  private def processOracleSuccess[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    oracle:       Records.ScriptOracleFiberRecord,
    newStateData: JsonLogicValue,
    returnValue:  Option[JsonLogicValue],
    gasUsed:      Long
  ): FiberT[F, TransactionOutcome] =
    for {
      ctx   <- ExecutionOps.askContext[FiberT[F, *]]
      _     <- ExecutionOps.chargeGas[FiberT[F, *]](gasUsed)
      depth <- ExecutionOps.getDepth[FiberT[F, *]]

      newHash <- newStateData.some.traverse(_.computeDigest).liftFiber

      updatedOracle = oracle.copy(
        stateData = Some(newStateData),
        stateDataHash = newHash,
        latestUpdateOrdinal = ctx.ordinal,
        invocationCount = oracle.invocationCount + 1
      )
    } yield TransactionOutcome.Committed(
      updatedStateMachines = Map.empty,
      updatedOracles = Map(oracle.cid -> updatedOracle),
      statuses = List.empty,
      totalGasUsed = gasUsed,
      maxDepth = depth
    )
}
