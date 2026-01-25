package xyz.kd5ujc.shared_data.fiber.domain

import java.util.UUID

/**
 * Execution state tracked via StateT.
 * Automatically threads through all fiber processing.
 *
 * @param depth           Current trigger chain depth
 * @param gasUsed         Total gas consumed so far
 * @param processedInputs Set of (fiberId, inputKey) pairs for cycle detection
 * @param triggerChain    Debug trace of trigger processing order
 */
final case class ExecutionState(
  depth:           Int,
  gasUsed:         Long,
  processedInputs: Set[(UUID, String)],
  triggerChain:    List[TriggerTraceEntry] = List.empty
) {

  def incrementDepth: ExecutionState =
    copy(depth = depth + 1)

  def chargeGas(amount: Long): ExecutionState =
    copy(gasUsed = gasUsed + amount)

  def markProcessed(fiberId: UUID, inputKey: String): ExecutionState =
    copy(processedInputs = processedInputs + (fiberId -> inputKey))

  def wasProcessed(fiberId: UUID, inputKey: String): Boolean =
    processedInputs.contains((fiberId, inputKey))

  /**
   * Record a trigger processing for debugging.
   */
  def recordTrigger(
    targetFiberId: UUID,
    sourceFiberId: Option[UUID],
    inputKey:      String,
    gasUsed:       Long
  ): ExecutionState =
    copy(triggerChain =
      triggerChain :+ TriggerTraceEntry(
        depth = depth,
        targetFiberId = targetFiberId,
        sourceFiberId = sourceFiberId,
        inputKey = inputKey,
        gasUsed = gasUsed
      )
    )
}

object ExecutionState {
  def initial: ExecutionState = ExecutionState(0, 0L, Set.empty, List.empty)
}

/**
 * Trace entry for debugging trigger processing order.
 *
 * @param depth         The depth at which this trigger was processed
 * @param targetFiberId The fiber that received the trigger
 * @param sourceFiberId The fiber that initiated the trigger (None for external)
 * @param inputKey      The event type or method name
 * @param gasUsed       Gas consumed by this trigger evaluation
 */
final case class TriggerTraceEntry(
  depth:         Int,
  targetFiberId: UUID,
  sourceFiberId: Option[UUID],
  inputKey:      String,
  gasUsed:       Long
)

/**
 * Execution limits (immutable configuration).
 *
 * @param maxDepth         Maximum trigger chain depth
 * @param maxGas           Maximum gas for entire transaction
 * @param maxStateSizeBytes Maximum size of resulting state after effect execution
 */
final case class ExecutionLimits(
  maxDepth:          Int = 10,
  maxGas:            Long = 10_000_000L,
  maxStateSizeBytes: Int = 1_048_576 // 1MB
)
