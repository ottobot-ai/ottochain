package xyz.kd5ujc.shared_data.fiber

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberTInstances._
import xyz.kd5ujc.shared_data.syntax.calculatedState._

/**
 * Top-level orchestrator for fiber processing using FiberT monad transformer.
 *
 * Composes FiberEvaluator, TriggerDispatcher, and SpawnProcessor
 * to handle complete event/invocation processing including cascades.
 *
 * Processing flow:
 * 1. Create FiberContext with ordinal, limits, gas config
 * 2. Lookup fiber by ID and validate it's active
 * 3. Evaluate fiber (guards/effects for SM, script for Oracle)
 * 4. On success:
 *    a. Validate and process spawns (creates child fibers)
 *    b. Build effective state with spawns visible to triggers
 *    c. Process triggers (cascading evaluations)
 * 5. Commit or abort based on trigger results
 */
trait FiberEngine[F[_]] {

  def process(
    fiberId: UUID,
    input:   FiberInput,
    proofs:  List[SignatureProof]
  ): F[TransactionResult]
}

object FiberEngine {

  def make[F[_]: Async: SecurityProvider](
    calculatedState: CalculatedState,
    ordinal:         SnapshotOrdinal,
    limits:          ExecutionLimits = ExecutionLimits(),
    gasConfig:       GasConfig = GasConfig.Default,
    fiberGasConfig:  FiberGasConfig = FiberGasConfig.Default
  ): FiberEngine[F] = {
    new FiberEngine[F] {

      def process(
        fiberId: UUID,
        input:   FiberInput,
        proofs:  List[SignatureProof]
      ): F[TransactionResult] =
        processInternal(fiberId, input, proofs)
          .run(FiberContext(ordinal, limits, gasConfig, fiberGasConfig))
          .runA(ExecutionState.initial)

      private def processInternal(
        fiberId: UUID,
        input:   FiberInput,
        proofs:  List[SignatureProof]
      ): FiberT[F, TransactionResult] =
        calculatedState.getFiber(fiberId) match {
          case None =>
            abortWithReason(FailureReason.FiberNotFound(fiberId))

          case Some(fiber) if fiber.status != FiberStatus.Active =>
            abortWithReason(FailureReason.FiberNotActive(fiberId, fiber.status.toString))

          case Some(fiber) =>
            processActiveFiber(fiber, input, proofs)
        }

      private def abortWithReason(reason: FailureReason): FiberT[F, TransactionResult] =
        (TransactionResult.Aborted(reason, 0L): TransactionResult).pureFiber[F]

      private def processActiveFiber(
        fiber:  Records.FiberRecord,
        input:  FiberInput,
        proofs: List[SignatureProof]
      ): FiberT[F, TransactionResult] =
        for {
          outcomeResult <- FiberEvaluator.make[F, FiberT[F, *]](calculatedState).evaluate(fiber, input, proofs)
          result <- outcomeResult match {
            case FiberOutcome.Success(newStateData, newStateId, fiberTriggers, spawns, _, returnValue) =>
              fiber match {
                case sm: Records.StateMachineFiberRecord =>
                  processStateMachineSuccess(sm, input, newStateData, newStateId, fiberTriggers, spawns)

                case oracle: Records.ScriptOracleFiberRecord =>
                  processOracleSuccess(oracle, input, newStateData, returnValue)
              }

            case FiberOutcome.GuardFailed(attemptedCount) =>
              handleGuardFailed(fiber, input, attemptedCount)

            case FiberOutcome.Failed(reason) =>
              for {
                gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
              } yield TransactionResult.Aborted(reason, gasUsed): TransactionResult
          }
        } yield result

      private def handleGuardFailed(
        fiber:          Records.FiberRecord,
        input:          FiberInput,
        attemptedCount: Int
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
        } yield fiber match {
          case sm: Records.StateMachineFiberRecord =>
            val eventType = input match {
              case FiberInput.Transition(et, _, _) => et
              case _                               => EventType("unknown")
            }
            TransactionResult.Aborted(
              FailureReason.NoGuardMatched(sm.currentState, eventType, attemptedCount),
              gasUsed
            ): TransactionResult

          case other =>
            TransactionResult.Aborted(
              FailureReason.FiberInputMismatch(other.cid, other.getClass.getSimpleName, "GuardEvaluation"),
              gasUsed
            ): TransactionResult
        }

      private def processStateMachineSuccess(
        sm:           Records.StateMachineFiberRecord,
        input:        FiberInput,
        newStateData: JsonLogicValue,
        newStateId:   Option[StateId],
        triggers:     List[FiberTrigger],
        spawns:       List[SpawnDirective]
      ): FiberT[F, TransactionResult] =
        for {
          hash <- newStateData.computeDigest.liftFiber

          status = EventProcessingStatus.Success(
            sequenceNumber = sm.sequenceNumber + 1,
            processedAt = ordinal
          )

          updatedFiber = sm.copy(
            previousUpdateOrdinal = sm.latestUpdateOrdinal,
            latestUpdateOrdinal = ordinal,
            currentState = newStateId.getOrElse(sm.currentState),
            stateData = newStateData,
            stateDataHash = hash,
            sequenceNumber = sm.sequenceNumber + 1,
            lastEventStatus = status,
            eventBatch = List(status)
          )

          // Process spawns - gas is charged via StateT automatically
          spawnResult <- processSpawnsValidated(spawns, updatedFiber, input)

          result <- spawnResult match {
            case Left(errors) =>
              // Take the first error as the primary failure reason
              // Get current gas from state (includes spawn gas already charged)
              for {
                currentGas <- ExecutionOps.getGasUsed[FiberT[F, *]]
              } yield TransactionResult.Aborted(errors.head, currentGas): TransactionResult

            case Right(spawnedFibers) =>
              // Gas for spawns already charged via StateT
              completeStateMachineTransaction(sm, updatedFiber, spawnedFibers, triggers, status)
          }
        } yield result

      private def processSpawnsValidated(
        spawns:       List[SpawnDirective],
        updatedFiber: Records.StateMachineFiberRecord,
        input:        FiberInput
      ): FiberT[F, Either[NonEmptyList[FailureReason], List[Records.StateMachineFiberRecord]]] =
        spawns.isEmpty
          .pure[FiberT[F, *]]
          .ifM(
            ifTrue = List
              .empty[Records.StateMachineFiberRecord]
              .asRight[NonEmptyList[FailureReason]]
              .pureFiber[F],
            ifFalse = {
              val processor = SpawnProcessor.make[F, FiberT[F, *]]
              for {
                contextData <- ContextProvider
                  .make[F](calculatedState)
                  .buildTriggerContext(updatedFiber, input)
                  .liftFiber
                knownFibers = calculatedState.stateMachines.keySet ++ calculatedState.scriptOracles.keySet
                result <- processor.processSpawnsValidated(spawns, updatedFiber, contextData, knownFibers)
              } yield result
            }
          )

      private def completeStateMachineTransaction(
        originalFiber: Records.StateMachineFiberRecord,
        updatedFiber:  Records.StateMachineFiberRecord,
        spawnedFibers: List[Records.StateMachineFiberRecord],
        triggers:      List[FiberTrigger],
        status:        EventProcessingStatus
      ): FiberT[F, TransactionResult] = {
        // Update parent's childFiberIds
        val parentWithChildren = updatedFiber.copy(
          childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.cid)
        )

        // Build state with spawns visible to triggers
        val stateWithSpawns = spawnedFibers.foldLeft(
          calculatedState.updateFiber(parentWithChildren)
        ) { case (state, child) =>
          state.updateFiber(child)
        }

        // Process triggers if any
        triggers.isEmpty
          .pure[FiberT[F, *]]
          .ifM(
            ifTrue = commitWithoutTriggers(originalFiber.cid, parentWithChildren, spawnedFibers, status),
            ifFalse = dispatchTriggers(
              originalFiber.cid,
              spawnedFibers,
              triggers,
              stateWithSpawns,
              status
            )
          )
      }

      private def commitWithoutTriggers(
        primaryFiberId: UUID,
        updatedFiber:   Records.StateMachineFiberRecord,
        spawnedFibers:  List[Records.StateMachineFiberRecord],
        status:         EventProcessingStatus
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
          depth   <- ExecutionOps.getDepth[FiberT[F, *]]
        } yield {
          val allMachines = Map(primaryFiberId -> updatedFiber) ++ spawnedFibers.map(f => f.cid -> f).toMap
          TransactionResult.Committed(
            updatedStateMachines = allMachines,
            updatedOracles = Map.empty,
            statuses = List((primaryFiberId, status)),
            totalGasUsed = gasUsed,
            maxDepth = depth
          ): TransactionResult
        }

      private def dispatchTriggers(
        primaryFiberId:  UUID,
        spawnedFibers:   List[Records.StateMachineFiberRecord],
        triggers:        List[FiberTrigger],
        stateWithSpawns: CalculatedState,
        status:          EventProcessingStatus
      ): FiberT[F, TransactionResult] = {
        val dispatcher = TriggerDispatcher.make[F, FiberT[F, *]]

        dispatcher.dispatch(triggers, stateWithSpawns).map {
          case TransactionResult.Committed(machines, oracles, statuses, totalGas, maxDepth, opCount) =>
            // Merge spawned fibers - trigger results may have updated them
            val allMachines = spawnedFibers.map(f => f.cid -> f).toMap ++ machines
            TransactionResult.Committed(
              updatedStateMachines = allMachines,
              updatedOracles = oracles,
              statuses = (primaryFiberId, status) :: statuses,
              totalGasUsed = totalGas, // Gas is cumulative — no manual addition needed
              maxDepth = maxDepth,
              operationCount = opCount
            ): TransactionResult

          case aborted: TransactionResult.Aborted =>
            aborted
        }
      }

      private def processOracleSuccess(
        oracle:       Records.ScriptOracleFiberRecord,
        input:        FiberInput,
        newStateData: JsonLogicValue,
        returnValue:  Option[JsonLogicValue]
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
          depth   <- ExecutionOps.getDepth[FiberT[F, *]]

          newHash <- newStateData.some.traverse(_.computeDigest).liftFiber

          // Extract method, args, and caller from input
          // Oracles are invoked via MethodCall; Transition is for state machines
          (method, args, caller) <- input match {
            case FiberInput.MethodCall(m, a, c, _) =>
              (m, a, c).pureFiber[F]
            case FiberInput.Transition(et, _, _) =>
              Async[F]
                .raiseError[(String, JsonLogicValue, io.constellationnetwork.schema.address.Address)](
                  new RuntimeException(
                    s"Oracle ${oracle.cid} received Transition input (event: ${et.value}). Oracles only support MethodCall input."
                  )
                )
                .liftFiber
          }

          // Create invocation log entry with actual return value
          invocation = OracleInvocation(
            method = method,
            args = args,
            result = returnValue.getOrElse(io.constellationnetwork.metagraph_sdk.json_logic.NullValue),
            gasUsed = gasUsed,
            invokedAt = ordinal,
            invokedBy = caller
          )

          updatedLog = (invocation :: oracle.invocationLog).take(oracle.maxLogSize)

          updatedOracle = oracle.copy(
            stateData = Some(newStateData),
            stateDataHash = newHash,
            latestUpdateOrdinal = ordinal,
            invocationCount = oracle.invocationCount + 1,
            invocationLog = updatedLog
          )
        } yield TransactionResult.Committed(
          updatedStateMachines = Map.empty,
          updatedOracles = Map(oracle.cid -> updatedOracle),
          statuses = List.empty,
          totalGasUsed = gasUsed,
          maxDepth = depth
        )
    }
  }
}
