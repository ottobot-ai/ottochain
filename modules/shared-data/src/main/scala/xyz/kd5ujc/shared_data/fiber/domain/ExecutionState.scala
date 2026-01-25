package xyz.kd5ujc.shared_data.fiber.domain

import java.util.UUID

/**
 * Execution state tracked via StateT.
 * Automatically threads through all fiber processing.
 */
final case class ExecutionState(
  depth:           Int,
  gasUsed:         Long,
  processedInputs: Set[(UUID, String)]
) {

  def incrementDepth: ExecutionState =
    copy(depth = depth + 1)

  def chargeGas(amount: Long): ExecutionState =
    copy(gasUsed = gasUsed + amount)

  def markProcessed(fiberId: UUID, inputKey: String): ExecutionState =
    copy(processedInputs = processedInputs + (fiberId -> inputKey))

  def wasProcessed(fiberId: UUID, inputKey: String): Boolean =
    processedInputs.contains((fiberId, inputKey))
}

object ExecutionState {
  def initial: ExecutionState = ExecutionState(0, 0L, Set.empty)
}

/**
 * Execution limits (immutable configuration).
 */
final case class ExecutionLimits(
  maxDepth: Int = 10,
  maxGas:   Long = 10_000_000L
)
