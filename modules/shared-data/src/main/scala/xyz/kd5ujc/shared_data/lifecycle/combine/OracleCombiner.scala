package xyz.kd5ujc.shared_data.lifecycle.combine

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.{FiberEngine, OracleProcessor}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner operations for script oracles.
 *
 * Handles creation and invocation of script oracles.
 * Uses the syntax extensions for atomic state updates.
 *
 * @param current The current DataState to operate on
 * @param ctx     The L0NodeContext for accessing snapshot ordinals
 */
class OracleCombiner[F[_]: Async: SecurityProvider](
  current: DataState[OnChain, CalculatedState],
  ctx:     L0NodeContext[F]
) {

  /**
   * Creates a new script oracle from the update.
   *
   * Delegates to OracleProcessor for the actual creation logic.
   */
  def createScriptOracle(
    update: Signed[Updates.CreateScriptOracle]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal
    result         <- OracleProcessor.createScriptOracle(current, update, currentOrdinal)
  } yield result

  /**
   * Invokes a script oracle method.
   *
   * Delegates to FiberOrchestrator for consistent gas metering and
   * unified processing semantics with state machine transitions.
   */
  def invokeScriptOracle(
    update: Signed[Updates.InvokeScriptOracle]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    // Verify oracle exists before processing
    _ <- current.calculated.scriptOracles
      .get(update.cid)
      .fold(
        Async[F].raiseError[Records.ScriptOracleFiberRecord](
          new RuntimeException(s"Oracle ${update.cid} not found")
        )
      )(_.pure[F])

    caller <- update.proofs.toList.headOption
      .fold(Async[F].raiseError[Address](new RuntimeException("No proof provided")))(
        _.id.toAddress
      )

    // Delegate to FiberOrchestrator for consistent gas metering
    orchestrator = FiberEngine.make[F](
      current.calculated,
      currentOrdinal,
      ExecutionLimits()
    )

    input = FiberInput.MethodCall(
      method = update.method,
      args = update.args,
      caller = caller,
      idempotencyKey = None
    )

    outcome <- orchestrator.process(update.cid, input, update.proofs.toList)

    newState <- outcome match {
      case TransactionResult.Committed(_, updatedOracles, _, _, _, _) =>
        // The orchestrator now adds the invocation log entry with the actual return value
        updatedOracles.get(update.cid) match {
          case Some(updatedOracle) =>
            // Oracle was updated by orchestrator - use it directly
            current.withOracle(update.cid, updatedOracle, updatedOracle.stateDataHash).pure[F]

          case None =>
            // Oracle didn't update state - shouldn't happen for successful invocations
            Async[F].raiseError(new RuntimeException(s"Oracle ${update.cid} not found in orchestrator result"))
        }

      case TransactionResult.Aborted(reason, _, _) =>
        Async[F].raiseError(new RuntimeException(s"Oracle invocation failed: ${reason.toMessage}"))
    }
  } yield newState

}

object OracleCombiner {

  /**
   * Creates a new OracleCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ): OracleCombiner[F] =
    new OracleCombiner[F](current, ctx)
}
