package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.Monad
import cats.data.StateT
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.StateMachine
import xyz.kd5ujc.shared_data.fiber.domain.{ExecutionLimits, ExecutionState, FiberContext}

/**
 * MTL-style operations for execution tracking and context access.
 *
 * These operations work in any G[_] with the appropriate MTL instances,
 * enabling use in both ExecutionT and FiberT contexts.
 */
object ExecutionOps {

  // === MTL-style state operations (work in any G[_] with Stateful) ===

  /** Charge gas for an operation */
  def chargeGas[G[_]](amount: Long)(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.chargeGas(amount))

  /** Increment depth for recursive call */
  def incrementDepth[G[_]](implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.incrementDepth)

  /** Mark a (fiberId, inputKey) as processed for cycle detection */
  def markProcessed[G[_]](
    fiberId:  UUID,
    inputKey: String
  )(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.markProcessed(fiberId, inputKey))

  /** Check if (fiberId, inputKey) already processed (cycle detection) */
  def checkCycle[G[_]](
    fiberId:  UUID,
    inputKey: String
  )(implicit S: Stateful[G, ExecutionState]): G[Boolean] =
    S.inspect(_.wasProcessed(fiberId, inputKey))

  /** Get current execution state */
  def getState[G[_]](implicit S: Stateful[G, ExecutionState]): G[ExecutionState] =
    S.get

  /** Get current gas used */
  def getGasUsed[G[_]](implicit S: Stateful[G, ExecutionState]): G[Long] =
    S.inspect(_.gasUsed)

  /** Get current depth */
  def getDepth[G[_]](implicit S: Stateful[G, ExecutionState]): G[Int] =
    S.inspect(_.depth)

  /** Record a trigger for debugging */
  def recordTrigger[G[_]](
    targetFiberId: UUID,
    sourceFiberId: Option[UUID],
    inputKey:      String,
    gasUsed:       Long
  )(implicit S: Stateful[G, ExecutionState]): G[Unit] =
    S.modify(_.recordTrigger(targetFiberId, sourceFiberId, inputKey, gasUsed))

  /** Get the trigger chain for debugging */
  def getTriggerChain[G[_]](implicit
    S: Stateful[G, ExecutionState]
  ): G[List[xyz.kd5ujc.shared_data.fiber.domain.TriggerTraceEntry]] =
    S.inspect(_.triggerChain)

  // === MTL-style context operations (work in any G[_] with Ask) ===

  /** Get the full fiber context */
  def askContext[G[_]](implicit A: Ask[G, FiberContext]): G[FiberContext] =
    A.ask

  /** Get the snapshot ordinal from context */
  def askOrdinal[G[_]](implicit A: Ask[G, FiberContext]): G[SnapshotOrdinal] =
    A.reader(_.ordinal)

  /** Get execution limits from context */
  def askLimits[G[_]](implicit A: Ask[G, FiberContext]): G[ExecutionLimits] =
    A.reader(_.limits)

  /** Get gas config from context */
  def askGasConfig[G[_]](implicit
    A: Ask[G, FiberContext]
  ): G[io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig] =
    A.reader(_.gasConfig)

  /** Get calculated state from context */
  def askCalculatedState[G[_]](implicit
    A: Ask[G, FiberContext]
  ): G[xyz.kd5ujc.schema.CalculatedState] =
    A.reader(_.calculatedState)

  // === Combined operations using both state and context ===

  /** Check if limits exceeded, return failure reason if so */
  def checkLimits[G[_]: Monad](implicit
    S: Stateful[G, ExecutionState],
    A: Ask[G, FiberContext]
  ): G[Option[StateMachine.FailureReason]] =
    for {
      limits  <- askLimits
      gasUsed <- getGasUsed
      depth   <- getDepth
    } yield
      if (depth >= limits.maxDepth)
        Some(StateMachine.FailureReason.DepthExceeded(depth, limits.maxDepth))
      else if (gasUsed >= limits.maxGas)
        Some(
          StateMachine.FailureReason
            .GasExhaustedFailure(gasUsed, limits.maxGas, StateMachine.GasExhaustionPhase.Trigger)
        )
      else None

  /** Get remaining gas budget */
  def remainingGas[G[_]: Monad](implicit
    S: Stateful[G, ExecutionState],
    A: Ask[G, FiberContext]
  ): G[Long] =
    for {
      limits  <- askLimits
      gasUsed <- getGasUsed
    } yield (limits.maxGas - gasUsed).max(0L)

  // === StateT-specific operations (for ExecutionT without Ask context) ===

  /** Get remaining gas budget using explicit limits parameter */
  def remainingGasWithLimits[G[_]: Monad](
    limits: ExecutionLimits
  )(implicit S: Stateful[G, ExecutionState]): G[Long] =
    getGasUsed[G].map(used => (limits.maxGas - used).max(0L))

  /** Check if limits exceeded using explicit limits parameter */
  def checkLimitsWithExplicit[F[_]: Monad](
    limits: ExecutionLimits
  ): ExecutionT[F, Option[StateMachine.FailureReason]] =
    StateT.inspect { state =>
      if (state.depth >= limits.maxDepth)
        Some(StateMachine.FailureReason.DepthExceeded(state.depth, limits.maxDepth))
      else if (state.gasUsed >= limits.maxGas)
        Some(
          StateMachine.FailureReason
            .GasExhaustedFailure(state.gasUsed, limits.maxGas, StateMachine.GasExhaustionPhase.Trigger)
        )
      else None
    }
}
