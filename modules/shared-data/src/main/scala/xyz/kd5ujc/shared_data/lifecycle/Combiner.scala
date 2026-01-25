package xyz.kd5ujc.shared_data.lifecycle

import cats.effect.Async

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.combine.{FiberCombiner, OracleCombiner}

/**
 * Entry point for creating a CombinerService.
 *
 * This object provides a thin facade that delegates to domain-specific
 * combiners in the [[combine]] package.
 *
 * == Combiner Architecture ==
 *
 * - '''FiberCombiner''': Handles state machine fiber operations
 *   - CreateStateMachineFiber: Initialize new fiber with definition
 *   - ProcessFiberEvent: Execute state transitions through orchestrator
 *   - ArchiveFiber: Mark fiber as archived
 *
 * - '''OracleCombiner''': Handles script oracle operations
 *   - CreateScriptOracle: Initialize new oracle with script
 *   - InvokeScriptOracle: Execute oracle method
 *
 * @see [[combine.FiberCombiner]] for fiber operations
 * @see [[combine.OracleCombiner]] for oracle operations
 */
object Combiner {

  /**
   * Creates a CombinerService instance.
   *
   * @return A CombinerService that combines OttochainMessage updates into state
   */
  def make[F[_]: Async: SecurityProvider]: CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {
      implicit val jsonLogicEvaluator: JsonLogicEvaluator[F] = JsonLogicEvaluator.tailRecursive[F]

      override def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] = {
        val fiberCombiner = FiberCombiner[F](previous, ctx)
        val oracleCombiner = OracleCombiner[F](previous, ctx)

        update.value match {
          case u: Updates.CreateStateMachineFiber => fiberCombiner.createStateMachineFiber(Signed(u, update.proofs))
          case u: Updates.ProcessFiberEvent       => fiberCombiner.processFiberEvent(Signed(u, update.proofs))
          case u: Updates.ArchiveFiber            => fiberCombiner.archiveFiber(Signed(u, update.proofs))
          case u: Updates.CreateScriptOracle      => oracleCombiner.createScriptOracle(Signed(u, update.proofs))
          case u: Updates.InvokeScriptOracle      => oracleCombiner.invokeScriptOracle(Signed(u, update.proofs))
        }
      }
    }
}
