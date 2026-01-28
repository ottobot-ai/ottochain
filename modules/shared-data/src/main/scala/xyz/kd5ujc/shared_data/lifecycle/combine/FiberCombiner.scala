package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
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
class FiberCombiner[F[_]: Async: SecurityProvider](
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
    update: Signed[Updates.CreateStateMachine]
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
      status = FiberStatus.Active,
      lastEventStatus = EventProcessingStatus.Initialized,
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
    update: Signed[Updates.TransitionStateMachine]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    input = FiberInput.Transition(update.eventType, update.payload, update.idempotencyKey)
    proofsList = update.proofs.toList

    orchestrator = FiberEngine.make[F](
      current.calculated,
      currentOrdinal,
      ExecutionLimits()
    )

    outcome <- orchestrator.process(update.cid, input, proofsList)

    newState <- outcome match {
      case TransactionResult.Committed(updatedFibers, updatedOracles, statuses, _, _, _) =>
        handleCommittedOutcome(updatedFibers, updatedOracles, statuses)

      case TransactionResult.Aborted(reason, gasUsed, _) =>
        handleAbortedOutcome(update.cid, update.eventType, reason, gasUsed, currentOrdinal)
    }
  } yield newState

  /**
   * Archives a fiber, setting its status to Archived.
   *
   * Archived fibers cannot process events but remain in state for reference.
   */
  def archiveFiber(
    update: Signed[Updates.ArchiveStateMachine]
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
      status = FiberStatus.Archived
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
    statuses:       List[(UUID, EventProcessingStatus)]
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
   * - NoGuardMatched: Records as GuardFailed if from this fiber's guard
   * - Other failures (including FiberNotActive): Records as ExecutionFailed
   */
  private def handleAbortedOutcome(
    fiberId:        UUID,
    eventType:      EventType,
    reason:         FailureReason,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
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

  /**
   * Creates the appropriate failed status based on the failure reason.
   */
  private def createFailedStatus(
    fiberRecord:    Records.StateMachineFiberRecord,
    eventType:      EventType,
    reason:         FailureReason,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal
  ): EventProcessingStatus =
    reason match {
      case FailureReason.NoGuardMatched(fromState, evtType, attemptedGuards)
          if fromState == fiberRecord.currentState && evtType == eventType =>
        EventProcessingStatus.GuardFailed(
          reason = s"No guard matched from ${fromState.value} on ${evtType.value} ($attemptedGuards guards tried)",
          attemptedAt = currentOrdinal,
          attemptedEventType = eventType
        )

      case _ =>
        EventProcessingStatus.ExecutionFailed(
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
  def apply[F[_]: Async: SecurityProvider](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ): FiberCombiner[F] =
    new FiberCombiner[F](current, ctx)
}
