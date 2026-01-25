package xyz.kd5ujc.shared_data.fiber.domain

import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.CalculatedState

/**
 * Read-only context available via ReaderT.
 *
 * @param calculatedState Current calculated state with all fibers
 * @param ordinal Current snapshot ordinal
 * @param limits Execution limits (depth, gas)
 * @param gasConfig JsonLogic VM gas configuration for expression evaluation
 * @param fiberGasConfig Fiber engine gas configuration for orchestration operations
 */
final case class FiberContext(
  calculatedState: CalculatedState,
  ordinal:         SnapshotOrdinal,
  limits:          ExecutionLimits,
  gasConfig:       GasConfig,
  fiberGasConfig:  FiberGasConfig = FiberGasConfig.Default
)
