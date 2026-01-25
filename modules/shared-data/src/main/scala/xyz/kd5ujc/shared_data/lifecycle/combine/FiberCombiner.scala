package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.fiber.domain.{ExecutionLimits, FiberInput, TransactionOutcome}
import xyz.kd5ujc.shared_data.fiber.engine.FiberOrchestrator
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationException
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.FiberRules
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner operations for state machine fibers.
 *
 * Handles creation, event processing, and archiving of fiber state machines.
 * Uses the syntax extensions for atomic state updates.
 *
 * @param current The current DataState to operate on
 * @param ctx     The L0NodeContext for accessing snapshot ordinals
 */
class FiberCombiner[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
  current: DataState[OnChain, CalculatedState],
  ctx:     L0NodeContext[F]
) {

  /**
   * Creates a new state machine fiber from the update.
   *
   * Initializes the fiber record with:
   * - Initial state from definition
   * - Owners from signature proofs
   * - Active status
   */
  def createStateMachineFiber(
    update: Signed[Updates.CreateStateMachineFiber]
  ): CombineResult[F] = for {
    currentOrdinal  <- ctx.getCurrentOrdinal
    owners          <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
    initialDataHash <- update.initialData.computeDigest

    record = Records.StateMachineFiberRecord(
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
  } yield current.withFiber(update.cid, record, initialDataHash)

  /**
   * Processes a fiber event through the fiber orchestrator.
   *
   * Handles both successful transitions and failures:
   * - Committed: Applies all fiber and oracle updates
   * - Aborted: Records failure status on the fiber
   */
  def processFiberEvent(
    update: Signed[Updates.ProcessFiberEvent]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    input = FiberInput.Transition(update.event.eventType, update.event.payload)
    proofsList = update.proofs.toList

    orchestrator = FiberOrchestrator.make[F](
      current.calculated,
      currentOrdinal,
      ExecutionLimits()
    )

    outcome <- orchestrator.process(update.cid, input, proofsList)

    newState <- outcome match {
      case TransactionOutcome.Committed(updatedFibers, updatedOracles, statuses, _, _, _) =>
        handleCommittedOutcome(updatedFibers, updatedOracles, statuses)

      case TransactionOutcome.Aborted(reason, gasUsed, _) =>
        handleAbortedOutcome(update.cid, update.event.eventType, reason, gasUsed, currentOrdinal)
    }
  } yield newState

  /**
   * Archives a fiber, setting its status to Archived.
   *
   * Archived fibers cannot process events but remain in state for reference.
   */
  def archiveFiber(
    update: Signed[Updates.ArchiveFiber]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    fiberRecord <- current.calculated.stateMachines
      .get(update.cid)
      .collect { case r: Records.StateMachineFiberRecord => r }
      .fold(
        Async[F].raiseError[Records.StateMachineFiberRecord](
          new RuntimeException(s"Fiber ${update.cid} not found")
        )
      )(_.pure[F])

    updatedFiber = fiberRecord.copy(
      previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
      latestUpdateOrdinal = currentOrdinal,
      status = Records.FiberStatus.Archived
    )

    newCalculated = current.calculated.copy(
      stateMachines = current.calculated.stateMachines.updated(update.cid, updatedFiber)
    )
  } yield DataState(current.onChain, newCalculated)

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /**
   * Handles a committed transaction outcome.
   *
   * Applies event batch statuses to fibers before updating state.
   */
  private def handleCommittedOutcome(
    updatedFibers:  Map[UUID, Records.StateMachineFiberRecord],
    updatedOracles: Map[UUID, Records.ScriptOracleFiberRecord],
    statuses:       List[(UUID, Records.EventProcessingStatus)]
  ): F[DataState[OnChain, CalculatedState]] = {
    // Apply event batch statuses to each fiber
    val fibersWithBatches = updatedFibers.map { case (fiberId, fiber) =>
      val fiberStatuses = statuses.filter(_._1 == fiberId).map(_._2)
      fiberId -> fiber.copy(eventBatch = fiberStatuses)
    }

    current.withFibersAndOracles(fibersWithBatches, updatedOracles).pure[F]
  }

  /**
   * Handles an aborted transaction outcome.
   *
   * Records the failure status on the fiber, distinguishing between:
   * - FiberNotActive: Raises a validation error
   * - NoGuardMatched: Records as GuardFailed if from this fiber's guard
   * - Other failures: Records as ExecutionFailed
   */
  private def handleAbortedOutcome(
    fiberId:        UUID,
    eventType:      StateMachine.EventType,
    reason:         StateMachine.FailureReason,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    reason match {
      case StateMachine.FailureReason.FiberNotActive(fid, _) =>
        Async[F].raiseError[DataState[OnChain, CalculatedState]](
          ValidationException(FiberRules.Errors.FiberNotActive(fid))
        )

      case _ =>
        current.calculated.stateMachines.get(fiberId) match {
          case Some(fiberRecord) =>
            fiberRecord.stateData.computeDigest.map { statusHash =>
              val failedStatus = createFailedStatus(fiberRecord, eventType, reason, gasUsed, currentOrdinal)

              val failedFiber = fiberRecord.copy(
                previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
                latestUpdateOrdinal = currentOrdinal,
                lastEventStatus = failedStatus,
                eventBatch = List(failedStatus)
              )

              current.withFiber(fiberId, failedFiber, statusHash)
            }

          case None =>
            Async[F].raiseError(new RuntimeException(s"Fiber $fiberId not found"))
        }
    }

  /**
   * Creates the appropriate failed status based on the failure reason.
   */
  private def createFailedStatus(
    fiberRecord:    Records.StateMachineFiberRecord,
    eventType:      StateMachine.EventType,
    reason:         StateMachine.FailureReason,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal
  ): Records.EventProcessingStatus =
    reason match {
      case StateMachine.FailureReason.NoGuardMatched(fromState, evtType, attemptedGuards)
          if fromState == fiberRecord.currentState && evtType == eventType =>
        Records.EventProcessingStatus.GuardFailed(
          reason = s"No guard matched from ${fromState.value} on ${evtType.value} ($attemptedGuards guards tried)",
          attemptedAt = currentOrdinal,
          attemptedEventType = eventType
        )

      case _ =>
        Records.EventProcessingStatus.ExecutionFailed(
          reason = reason.toMessage,
          attemptedAt = currentOrdinal,
          attemptedEventType = eventType,
          gasUsed = gasUsed,
          depth = 0
        )
    }
}

object FiberCombiner {

  /**
   * Creates a new FiberCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ): FiberCombiner[F] =
    new FiberCombiner[F](current, ctx)
}
