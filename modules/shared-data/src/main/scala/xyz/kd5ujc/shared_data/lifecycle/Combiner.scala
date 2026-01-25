package xyz.kd5ujc.shared_data.lifecycle

import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.fiber.domain.{ExecutionLimits, FiberInput, TransactionOutcome}
import xyz.kd5ujc.shared_data.fiber.engine.{FiberOrchestrator, OracleProcessor}

import monocle.Monocle.toAppliedFocusOps

object Combiner {

  def make[F[_]: Async: SecurityProvider]: CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {
      implicit val jsonLogicEvaluator: JsonLogicEvaluator[F] = JsonLogicEvaluator.tailRecursive[F]

      override def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] = {
        val combiner = new CombinerImpl[F](previous, ctx)

        update.value match {
          case u: Updates.CreateStateMachineFiber => combiner.createStateMachineFiber(Signed(u, update.proofs))
          case u: Updates.ProcessFiberEvent       => combiner.processFiberEvent(Signed(u, update.proofs))
          case u: Updates.ArchiveFiber            => combiner.archiveFiber(Signed(u, update.proofs))
          case u: Updates.CreateScriptOracle      => combiner.createScriptOracle(Signed(u, update.proofs))
          case u: Updates.InvokeScriptOracle      => combiner.invokeScriptOracle(Signed(u, update.proofs))
        }
      }
    }

  private class CombinerImpl[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ) {

    def createStateMachineFiber(
      update: Signed[Updates.CreateStateMachineFiber]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal
      owners         <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)

      // Hash initial data - convert to Json first
      initialDataHash <- update.initialData.computeDigest

      // Create fiber record
      _record = Records.StateMachineFiberRecord(
        cid = update.cid,
        creationOrdinal = currentOrdinal,
        previousUpdateOrdinal = currentOrdinal,
        latestUpdateOrdinal = currentOrdinal,
        definition = update.definition,
        currentState = update.definition.initialState,
        stateData = update.initialData,
        stateDataHash = initialDataHash,
        sequenceNumber = 0,
        owners = owners,
        status = Records.FiberStatus.Active,
        lastEventStatus = Records.EventProcessingStatus.Initialized,
        parentFiberId = update.parentFiberId
      )

      _onchain <- current.onChain
        .focus(_.latest)
        .modify(_.updated(update.cid, initialDataHash))
        .pure[F]

      _calculated <- current.calculated
        .focus(_.stateMachines)
        .modify(_.updated(update.cid, _record))
        .pure[F]

    } yield DataState(_onchain, _calculated)

    def processFiberEvent(
      update: Signed[Updates.ProcessFiberEvent]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      // Build input from event
      input = FiberInput.Transition(update.event.eventType, update.event.payload)
      proofsList = update.proofs.toList

      // Create orchestrator and process
      orchestrator = FiberOrchestrator.make[F](
        current.calculated,
        currentOrdinal,
        ExecutionLimits()
      )

      outcome <- orchestrator.process(update.cid, input, proofsList)

      // Handle outcome
      newState <- outcome match {
        case TransactionOutcome.Committed(updatedFibers, updatedOracles, statuses, _, _, _) =>
          // Apply updated fibers
          val stateWithFibers = updatedFibers.foldLeft(current.calculated) { case (state, (fiberId, fiber)) =>
            val fiberStatuses = statuses.filter(_._1 == fiberId).map(_._2)
            val fiberWithBatch = fiber.copy(eventBatch = fiberStatuses)
            state.copy(stateMachines = state.stateMachines.updated(fiberId, fiberWithBatch))
          }

          // Apply updated oracles
          val finalCalcState = stateWithFibers.copy(
            scriptOracles = stateWithFibers.scriptOracles ++ updatedOracles
          )

          // Update hashes
          val updatedHashes = updatedFibers.map { case (fid, fiber) => fid -> fiber.stateDataHash }
          val oracleHashes = updatedOracles.flatMap { case (oid, oracle) => oracle.stateDataHash.map(oid -> _) }
          val newOnchain = current.onChain.copy(latest = current.onChain.latest ++ updatedHashes ++ oracleHashes)

          DataState(newOnchain, finalCalcState).pure[F]

        case TransactionOutcome.Aborted(reason, gasUsed, _) =>
          // Get fiber record for failure status update
          current.calculated.stateMachines.get(update.cid) match {
            case Some(fiberRecord) =>
              fiberRecord.stateData.computeDigest.map { statusHash =>
                val failedStatus = reason match {
                  // Only treat as GuardFailed if the failure is from this fiber's own guard
                  // (same fromState and eventType as what we're processing)
                  case StateMachine.FailureReason.NoGuardMatched(fromState, eventType, attemptedGuards)
                      if fromState == fiberRecord.currentState && eventType == update.event.eventType =>
                    Records.EventProcessingStatus.GuardFailed(
                      reason =
                        s"No guard matched from ${fromState.value} on ${eventType.value} ($attemptedGuards guards tried)",
                      attemptedAt = currentOrdinal,
                      attemptedEventType = update.event.eventType
                    )
                  case _ =>
                    Records.EventProcessingStatus.ExecutionFailed(
                      reason = reason.toMessage,
                      attemptedAt = currentOrdinal,
                      attemptedEventType = update.event.eventType,
                      gasUsed = gasUsed,
                      depth = 0
                    )
                }

                val failedFiber = fiberRecord.copy(
                  previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
                  latestUpdateOrdinal = currentOrdinal,
                  lastEventStatus = failedStatus,
                  eventBatch = List(failedStatus)
                )

                val newCalculated = current.calculated.copy(
                  stateMachines = current.calculated.stateMachines.updated(update.cid, failedFiber)
                )

                val newOnchain = current.onChain.copy(
                  latest = current.onChain.latest.updated(update.cid, statusHash)
                )

                DataState(newOnchain, newCalculated)
              }

            case None =>
              Async[F].raiseError(new RuntimeException(s"Fiber ${update.cid} not found"))
          }
      }
    } yield newState

    def archiveFiber(
      update: Signed[Updates.ArchiveFiber]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      // Get existing fiber
      fiberRecord <- current.calculated.stateMachines
        .get(update.cid)
        .collect { case r: Records.StateMachineFiberRecord => r }
        .fold(
          Async[F].raiseError[Records.StateMachineFiberRecord](
            new RuntimeException(s"Fiber ${update.cid} not found")
          )
        )(_.pure[F])

      // Update status to archived
      updatedFiber = fiberRecord.copy(
        previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
        latestUpdateOrdinal = currentOrdinal,
        status = Records.FiberStatus.Archived
      )

      _calculated <- current.calculated
        .focus(_.stateMachines)
        .modify(_.updated(update.cid, updatedFiber))
        .pure[F]

    } yield DataState(current.onChain, _calculated)

    private def getCurrentOrdinal: F[SnapshotOrdinal] =
      ctx.getLastCurrencySnapshot.flatMap { latestSnapshot =>
        Sync[F].fromOption(
          latestSnapshot.map(_.signed.value.ordinal.next),
          new RuntimeException("Combiner failed to retrieve latest currency snapshot!")
        )
      }

    def createScriptOracle(
      update: Signed[Updates.CreateScriptOracle]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal
      result         <- OracleProcessor.createScriptOracle(current, update, currentOrdinal)
    } yield result

    def invokeScriptOracle(
      update: Signed[Updates.InvokeScriptOracle]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      oracleRecord <- current.calculated.scriptOracles
        .get(update.cid)
        .fold(
          Async[F].raiseError[Records.ScriptOracleFiberRecord](
            new RuntimeException(s"Oracle ${update.cid} not found")
          )
        )(_.pure[F])

      caller <- update.proofs.toList.headOption
        .fold(Async[F].raiseError[Address](new RuntimeException("No proof provided")))(
          _.id.toAddress
        )

      processingResult <- OracleProcessor.invokeScriptOracle(current, update, currentOrdinal)

      newState <- processingResult match {
        case StateMachine.OracleSuccess(newStateData, returnValue, gasUsed) =>
          for {
            newStateHash <- newStateData.traverse(_.computeDigest)

            invocation = Records.OracleInvocation(
              method = update.method,
              args = update.args,
              result = returnValue,
              gasUsed = gasUsed,
              invokedAt = currentOrdinal,
              invokedBy = caller
            )

            updatedLog = (invocation :: oracleRecord.invocationLog).take(oracleRecord.maxLogSize)

            updatedOracle = oracleRecord.copy(
              stateData = newStateData,
              stateDataHash = newStateHash,
              latestUpdateOrdinal = currentOrdinal,
              invocationCount = oracleRecord.invocationCount + 1,
              invocationLog = updatedLog
            )

            _calculated = current.calculated.copy(
              scriptOracles = current.calculated.scriptOracles.updated(update.cid, updatedOracle)
            )

            _onchain = newStateHash.fold(current.onChain) { hash =>
              current.onChain
                .focus(_.latest)
                .modify(_.updated(update.cid, hash))
            }
          } yield DataState(_onchain, _calculated)

        case StateMachine.Failure(reason) =>
          Async[F].raiseError(new RuntimeException(s"Oracle invocation failed: $reason"))

        case other =>
          Async[F].raiseError(new RuntimeException(s"Unexpected oracle result: $other"))
      }
    } yield newState
  }
}
