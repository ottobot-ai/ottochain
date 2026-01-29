package xyz.kd5ujc.schema.fiber

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
  maxStateSizeBytes: Int = 1_048_576, // 1MB
  maxLogSize:        Int = 100
)
