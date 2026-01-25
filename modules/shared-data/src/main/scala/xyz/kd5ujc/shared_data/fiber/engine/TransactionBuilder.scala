package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.domain.{FiberTrigger, TransactionOutcome}
import xyz.kd5ujc.shared_data.syntax.calculatedState._

/**
 * Builds a transaction with explicit atomicity boundaries.
 *
 * Key semantics:
 * - Primary fiber updates are tracked separately from spawns
 * - Spawns are staged (visible to triggers) but only committed on full success
 * - Triggers are processed depth-first against the effective state
 * - If any trigger fails, the entire transaction aborts (all-or-nothing)
 *
 * This makes the spawn-before-trigger semantics explicit and debuggable.
 */
final case class TransactionBuilder(
  primaryUpdates:  Map[UUID, Records.FiberRecord],
  pendingSpawns:   Map[UUID, Records.StateMachineFiberRecord],
  pendingTriggers: List[FiberTrigger],
  statuses:        List[(UUID, Records.EventProcessingStatus)],
  gasUsed:         Long,
  depth:           Int,
  triggerChain:    List[TriggerChainEntry] = List.empty
) {

  /**
   * Build the effective state that triggers see.
   * This includes the base state, primary updates, and pending spawns.
   */
  def buildEffectiveState(base: CalculatedState): CalculatedState = {
    val withPrimary = primaryUpdates.foldLeft(base) { case (state, (_, fiber)) =>
      state.updateFiber(fiber)
    }
    pendingSpawns.values.foldLeft(withPrimary) { case (state, spawn) =>
      state.updateFiber(spawn)
    }
  }

  /**
   * Add a primary fiber update.
   */
  def withPrimaryUpdate(fiber: Records.FiberRecord): TransactionBuilder =
    copy(primaryUpdates = primaryUpdates + (fiber.cid -> fiber))

  /**
   * Stage spawned fibers (visible to triggers but not committed yet).
   */
  def withPendingSpawns(spawns: List[Records.StateMachineFiberRecord]): TransactionBuilder =
    copy(pendingSpawns = pendingSpawns ++ spawns.map(s => s.cid -> s))

  /**
   * Add triggers to be processed.
   */
  def withTriggers(triggers: List[FiberTrigger]): TransactionBuilder =
    copy(pendingTriggers = pendingTriggers ++ triggers)

  /**
   * Add a processing status.
   */
  def withStatus(fiberId: UUID, status: Records.EventProcessingStatus): TransactionBuilder =
    copy(statuses = statuses :+ (fiberId -> status))

  /**
   * Add multiple processing statuses.
   */
  def withStatuses(newStatuses: List[(UUID, Records.EventProcessingStatus)]): TransactionBuilder =
    copy(statuses = statuses ++ newStatuses)

  /**
   * Charge gas.
   */
  def chargeGas(amount: Long): TransactionBuilder =
    copy(gasUsed = gasUsed + amount)

  /**
   * Increment depth for recursive trigger processing.
   */
  def incrementDepth: TransactionBuilder =
    copy(depth = depth + 1)

  /**
   * Apply a trigger result to the builder.
   */
  def applyTriggerResult(
    result:  TriggerHandlerResult.Success,
    trigger: FiberTrigger
  ): TransactionBuilder = {
    val entry = TriggerChainEntry(
      targetFiberId = trigger.targetFiberId,
      sourceFiberId = trigger.sourceFiberId,
      inputKey = trigger.input.inputKey,
      cascadeCount = result.cascadeTriggers.size,
      gasUsed = result.gasUsed
    )

    copy(
      // Update primary updates with any fiber changes from the trigger
      primaryUpdates = result.updatedState.stateMachines.filter { case (id, _) =>
        primaryUpdates.contains(id) || !pendingSpawns.contains(id)
      } ++ result.updatedState.scriptOracles.map { case (id, oracle) =>
        id -> (oracle: Records.FiberRecord)
      },
      statuses = statuses ++ result.statuses,
      pendingTriggers = result.cascadeTriggers ++ pendingTriggers.tail,
      gasUsed = gasUsed + result.gasUsed,
      triggerChain = triggerChain :+ entry
    )
  }

  /**
   * Consume the next pending trigger.
   */
  def nextTrigger: Option[(FiberTrigger, TransactionBuilder)] =
    pendingTriggers.headOption.map { trigger =>
      (trigger, copy(pendingTriggers = pendingTriggers.tail))
    }

  /**
   * Check if there are more triggers to process.
   */
  def hasPendingTriggers: Boolean = pendingTriggers.nonEmpty

  /**
   * Commit the transaction, producing a successful outcome.
   * Only call this when all triggers have been processed successfully.
   */
  def commit(base: CalculatedState): TransactionOutcome.Committed = {
    val effectiveState = buildEffectiveState(base)
    TransactionOutcome.Committed(
      updatedStateMachines = effectiveState.stateMachines.filter { case (id, _) =>
        primaryUpdates.contains(id) || pendingSpawns.contains(id)
      },
      updatedOracles = effectiveState.scriptOracles.filter { case (id, _) =>
        primaryUpdates.contains(id)
      },
      statuses = statuses,
      totalGasUsed = gasUsed,
      maxDepth = depth
    )
  }
}

object TransactionBuilder {

  /**
   * Create an empty transaction builder.
   */
  def empty: TransactionBuilder = TransactionBuilder(
    primaryUpdates = Map.empty,
    pendingSpawns = Map.empty,
    pendingTriggers = List.empty,
    statuses = List.empty,
    gasUsed = 0L,
    depth = 0
  )

  /**
   * Create a builder initialized with a primary fiber update.
   */
  def forPrimaryFiber(fiber: Records.FiberRecord): TransactionBuilder =
    empty.withPrimaryUpdate(fiber)
}

/**
 * Entry in the trigger chain for debugging.
 *
 * @param targetFiberId Fiber that was triggered
 * @param sourceFiberId Fiber that initiated the trigger (if any)
 * @param inputKey      The input key (event type or method name)
 * @param cascadeCount  Number of cascading triggers generated
 * @param gasUsed       Gas consumed by this trigger
 */
final case class TriggerChainEntry(
  targetFiberId: UUID,
  sourceFiberId: Option[UUID],
  inputKey:      String,
  cascadeCount:  Int,
  gasUsed:       Long
)
