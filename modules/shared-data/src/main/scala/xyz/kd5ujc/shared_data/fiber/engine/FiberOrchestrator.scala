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
import xyz.kd5ujc.shared_data.syntax.calculatedState._

/**
 * Top-level orchestrator for fiber processing using FiberT monad transformer.
 *
 * Composes FiberEvaluator, TriggerDispatcher, and SpawnProcessor
 * to handle complete event/invocation processing including cascades.
 *
 * Processing flow:
 * 1. Create FiberContext with calculated state, ordinal, limits, gas config
 * 2. Lookup fiber by ID and validate it's active
 * 3. Evaluate fiber (guards/effects for SM, script for Oracle)
 * 4. On success:
 *    a. Validate and process spawns (creates child fibers)
 *    b. Build effective state with spawns visible to triggers
 *    c. Process triggers (cascading evaluations)
 * 5. Commit or abort based on trigger results
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
  ): FiberOrchestrator[F] = (fiberId: UUID, input: FiberInput, proofs: List[SignatureProof]) =>
    processInternal(fiberId, input, proofs)
      .run(FiberContext(calculatedState, ordinal, limits, gasConfig, fiberGasConfig))
      .runA(ExecutionState.initial)

  private def processInternal[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiberId: UUID,
    input:   FiberInput,
    proofs:  List[SignatureProof]
  ): FiberT[F, TransactionOutcome] =
    ExecutionOps.askContext[FiberT[F, *]].flatMap { ctx =>
      ctx.calculatedState.getFiber(fiberId) match {
        case None =>
          abortWithReason(StateMachine.FailureReason.FiberNotFound(fiberId))

        case Some(fiber) if fiber.status != Records.FiberStatus.Active =>
          abortWithReason(StateMachine.FailureReason.FiberNotActive(fiberId, fiber.status.toString))

        case Some(fiber) =>
          processActiveFiber(fiber, input, proofs)
      }
    }

  private def abortWithReason[F[_]: Async](reason: StateMachine.FailureReason): FiberT[F, TransactionOutcome] =
    (TransactionOutcome.Aborted(
      reason,
      0L
    ): TransactionOutcome).pureFiber[F]

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
          handleGuardFailed(fiber, input, attemptedCount, guardGasUsed)

        case FiberOutcome.Failed(reason) =>
          (TransactionOutcome.Aborted(reason, 0L): TransactionOutcome).pureFiber[F]
      }
    } yield result

  private def handleGuardFailed[F[_]: Async](
    fiber:          Records.FiberRecord,
    input:          FiberInput,
    attemptedCount: Int,
    guardGasUsed:   Long
  ): FiberT[F, TransactionOutcome] =
    fiber match {
      case sm: Records.StateMachineFiberRecord =>
        val eventType = input match {
          case FiberInput.Transition(et, _) => et
          case _                            => StateMachine.EventType("unknown")
        }
        (TransactionOutcome.Aborted(
          StateMachine.FailureReason.NoGuardMatched(sm.currentState, eventType, attemptedCount),
          guardGasUsed
        ): TransactionOutcome).pureFiber[F]

      case other =>
        (TransactionOutcome.Aborted(
          StateMachine.FailureReason.FiberInputMismatch(other.cid, other.getClass.getSimpleName, "GuardEvaluation"),
          guardGasUsed
        ): TransactionOutcome).pureFiber[F]
    }

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

      // Compute hash for new state
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

      // Process spawns using validated approach
      spawnResult <- processSpawnsValidated(spawns, updatedFiber, input, ctx)

      result <- spawnResult match {
        case Left(error) =>
          (TransactionOutcome.Aborted(
            StateMachine.FailureReason.SpawnValidationFailed(sm.cid, List(error.message)),
            gasUsed
          ): TransactionOutcome).pureFiber[F]

        case Right(spawnedFibers) =>
          completeStateMachineTransaction(sm, updatedFiber, spawnedFibers, triggers, status, gasUsed, ctx)
      }
    } yield result

  private def processSpawnsValidated[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    spawns:       List[StateMachine.SpawnDirective],
    updatedFiber: Records.StateMachineFiberRecord,
    input:        FiberInput,
    ctx:          FiberContext
  ): FiberT[F, Either[SpawnProcessingError, List[Records.StateMachineFiberRecord]]] =
    spawns.isEmpty
      .pure[FiberT[F, *]]
      .ifM(
        ifTrue = List.empty[Records.StateMachineFiberRecord].asRight[SpawnProcessingError].pureFiber[F],
        ifFalse = for {
          contextData <- ContextProvider
            .make[F](ctx.calculatedState)
            .buildTriggerContext(updatedFiber, input)
            .liftFiber
          processor <- SpawnProcessor.make[F, FiberT[F, *]]
          knownFibers = ctx.calculatedState.stateMachines.keySet ++ ctx.calculatedState.scriptOracles.keySet
          result <- processor.processSpawnsValidated(spawns, updatedFiber, contextData, knownFibers).liftFiber
        } yield result
      )

  private def completeStateMachineTransaction[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    originalFiber: Records.StateMachineFiberRecord,
    updatedFiber:  Records.StateMachineFiberRecord,
    spawnedFibers: List[Records.StateMachineFiberRecord],
    triggers:      List[StateMachine.TriggerEvent],
    status:        Records.EventProcessingStatus,
    gasUsed:       Long,
    ctx:           FiberContext
  ): FiberT[F, TransactionOutcome] = {
    // Update parent's childFiberIds
    val parentWithChildren = updatedFiber.copy(
      childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.cid)
    )

    // Build state with spawns visible to triggers
    val stateWithSpawns = spawnedFibers.foldLeft(
      ctx.calculatedState.updateFiber(parentWithChildren)
    ) { case (state, child) =>
      state.updateFiber(child)
    }

    // Process triggers if any
    triggers.isEmpty
      .pure[FiberT[F, *]]
      .ifM(
        ifTrue = commitWithoutTriggers(originalFiber.cid, parentWithChildren, spawnedFibers, status, gasUsed),
        ifFalse = dispatchTriggers(
          originalFiber.cid,
          parentWithChildren,
          spawnedFibers,
          triggers,
          stateWithSpawns,
          status,
          gasUsed,
          ctx
        )
      )
  }

  private def commitWithoutTriggers[F[_]: Async](
    primaryFiberId: UUID,
    updatedFiber:   Records.StateMachineFiberRecord,
    spawnedFibers:  List[Records.StateMachineFiberRecord],
    status:         Records.EventProcessingStatus,
    gasUsed:        Long
  ): FiberT[F, TransactionOutcome] =
    for {
      depth <- ExecutionOps.getDepth[FiberT[F, *]]
    } yield {
      val allMachines = Map(primaryFiberId -> updatedFiber) ++ spawnedFibers.map(f => f.cid -> f).toMap
      TransactionOutcome.Committed(
        updatedStateMachines = allMachines,
        updatedOracles = Map.empty,
        statuses = List((primaryFiberId, status)),
        totalGasUsed = gasUsed,
        maxDepth = depth
      ): TransactionOutcome
    }

  private def dispatchTriggers[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    primaryFiberId:  UUID,
    parentFiber:     Records.StateMachineFiberRecord,
    spawnedFibers:   List[Records.StateMachineFiberRecord],
    triggers:        List[StateMachine.TriggerEvent],
    stateWithSpawns: CalculatedState,
    status:          Records.EventProcessingStatus,
    gasUsed:         Long,
    ctx:             FiberContext
  ): FiberT[F, TransactionOutcome] = {
    val unifiedTriggers = triggers.map { t =>
      val payload = t.payloadExpr match {
        case ConstExpression(value) => value
        case _                      => NullValue
      }
      FiberTrigger(t.targetMachineId, FiberInput.Transition(t.eventType, payload), Some(primaryFiberId))
    }

    val evaluatorFactory: CalculatedState => FiberEvaluator[F] =
      state => {
        val ctxProvider = ContextProvider.make[F](state)
        FiberEvaluator.make[F](ctxProvider, state, ctx.ordinal, ctx.limits, ctx.gasConfig, ctx.fiberGasConfig)
      }

    val dispatcher = TriggerDispatcher.make[F](evaluatorFactory, ctx.ordinal, ctx.limits, ctx.gasConfig)

    dispatcher.dispatch(unifiedTriggers, stateWithSpawns).liftFiber.map {
      case TransactionOutcome.Committed(machines, oracles, statuses, totalGas, maxDepth, opCount) =>
        // Merge spawned fibers - trigger results may have updated them
        val allMachines = spawnedFibers.map(f => f.cid -> f).toMap ++ machines
        TransactionOutcome.Committed(
          updatedStateMachines = allMachines,
          updatedOracles = oracles,
          statuses = (primaryFiberId, status) :: statuses,
          totalGasUsed = gasUsed + totalGas,
          maxDepth = maxDepth,
          operationCount = opCount
        ): TransactionOutcome

      case aborted: TransactionOutcome.Aborted =>
        aborted
    }
  }

  private def processOracleSuccess[F[_]: Async](
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
