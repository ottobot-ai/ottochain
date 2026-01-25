package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.fiber.engine.OracleProcessor
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationException
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.OracleRules
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
class OracleCombiner[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
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
   * Handles the result from OracleProcessor:
   * - OracleSuccess: Updates oracle state and logs invocation
   * - Failure (AccessDenied): Raises ValidationException
   * - Other failures: Raises RuntimeException
   */
  def invokeScriptOracle(
    update: Signed[Updates.InvokeScriptOracle]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    oracleRecord <- current.calculated.scriptOracles
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

    processingResult <- OracleProcessor.invokeScriptOracle(current, update, currentOrdinal)

    newState <- processingResult match {
      case StateMachine.OracleSuccess(newStateData, returnValue, gasUsed) =>
        handleOracleSuccess(update, oracleRecord, newStateData, returnValue, gasUsed, currentOrdinal, caller)

      case StateMachine.Failure(reason) =>
        handleOracleFailure(update.cid, oracleRecord.accessControl, reason)

      case other =>
        Async[F].raiseError(new RuntimeException(s"Unexpected oracle result: $other"))
    }
  } yield newState

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /**
   * Handles a successful oracle invocation.
   *
   * Updates the oracle record with new state and logs the invocation.
   */
  private def handleOracleSuccess(
    update:         Signed[Updates.InvokeScriptOracle],
    oracleRecord:   Records.ScriptOracleFiberRecord,
    newStateData:   Option[JsonLogicValue],
    returnValue:    JsonLogicValue,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal,
    caller:         Address
  ): F[DataState[OnChain, CalculatedState]] = for {
    newStateHash <- newStateData.traverse(_.computeDigest)

    invocation = Records.OracleInvocation(
      method = update.method,
      args = update.args,
      result = returnValue,
      gasUsed = gasUsed,
      invokedAt = currentOrdinal,
      invokedBy = caller
    )

    updatedLog = (invocation :: oracleRecord.invocationLog).take(oracleRecord.maxLogSize)

    updatedOracle = oracleRecord.copy(
      stateData = newStateData,
      stateDataHash = newStateHash,
      latestUpdateOrdinal = currentOrdinal,
      invocationCount = oracleRecord.invocationCount + 1,
      invocationLog = updatedLog
    )
  } yield current.withOracle(update.cid, updatedOracle, newStateHash)

  /**
   * Handles oracle invocation failure.
   *
   * Raises appropriate exceptions based on failure type.
   */
  private def handleOracleFailure(
    oracleId:      UUID,
    accessControl: Records.AccessControlPolicy,
    reason:        StateMachine.FailureReason
  ): F[DataState[OnChain, CalculatedState]] =
    reason match {
      case StateMachine.FailureReason.AccessDenied(_, _, _, _) =>
        Async[F].raiseError(
          ValidationException(OracleRules.Errors.OracleAccessDenied(oracleId, accessControl))
        )

      case other =>
        Async[F].raiseError(new RuntimeException(s"Oracle invocation failed: ${other.toMessage}"))
    }
}

object OracleCombiner {

  /**
   * Creates a new OracleCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ): OracleCombiner[F] =
    new OracleCombiner[F](current, ctx)
}
