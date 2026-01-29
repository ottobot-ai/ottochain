package xyz.kd5ujc.shared_data.fiber.core

import java.util.UUID

import cats.data.Chain

import xyz.kd5ujc.schema.fiber.FiberLogEntry

/**
 * Execution state tracked via StateT.
 * Automatically threads through all fiber processing.
 *
 * @param depth           Current trigger chain depth
 * @param gasUsed         Total gas consumed so far
 * @param processedInputs Set of (fiberId, inputKey) pairs for cycle detection
 * @param logEntries      Accumulated log entries (receipts + invocations) for this transaction
 */
final case class ExecutionState(
  depth:           Int,
  gasUsed:         Long,
  processedInputs: Set[(UUID, String)],
  logEntries:      Chain[FiberLogEntry]
) {

  def incrementDepth: ExecutionState =
    copy(depth = depth + 1)

  def chargeGas(amount: Long): ExecutionState =
    copy(gasUsed = gasUsed + amount)

  def markProcessed(fiberId: UUID, inputKey: String): ExecutionState =
    copy(processedInputs = processedInputs + (fiberId -> inputKey))

  def wasProcessed(fiberId: UUID, inputKey: String): Boolean =
    processedInputs.contains((fiberId, inputKey))

  def appendLog(entry: FiberLogEntry): ExecutionState =
    copy(logEntries = logEntries :+ entry)
}

object ExecutionState {
  def initial: ExecutionState = ExecutionState(0, 0L, Set.empty, Chain.empty)
}
