package xyz.kd5ujc.shared_data.lifecycle

import java.util.UUID

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, MapValue, NullValue, StrValue}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}

import monocle.Monocle.toAppliedFocusOps

object OracleProcessor {

  def extractStateAndResult[F[_]: Async](
    evaluationResult: JsonLogicValue
  ): F[(Option[JsonLogicValue], JsonLogicValue)] =
    evaluationResult match {
      case MapValue(m) if m.contains("_state") && m.contains("_result") =>
        (m.get("_state"), m.getOrElse("_result", NullValue)).pure[F]

      case MapValue(m) if m.contains("_state") =>
        (m.get("_state"), evaluationResult).pure[F]

      case MapValue(m) if m.contains("_result") =>
        (
          Option.empty[JsonLogicValue],
          m.getOrElse("_result", NullValue)
        ).pure[F]

      case other =>
        (other.some, other).pure[F]
    }

  def createScriptOracle[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current:        DataState[OnChain, CalculatedState],
    update:         Signed[Updates.CreateScriptOracle],
    currentOrdinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] = for {
    owners <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from(_))

    stateDataHashOpt <- update.initialState.traverse[F, Hash](_.computeDigest)

    oracleRecord = Records.ScriptOracleFiberRecord(
      cid = update.cid,
      creationOrdinal = currentOrdinal,
      latestUpdateOrdinal = currentOrdinal,
      scriptProgram = update.scriptProgram,
      stateData = update.initialState,
      stateDataHash = stateDataHashOpt,
      accessControl = update.accessControl,
      owners = owners,
      status = Records.FiberStatus.Active
    )

    _calculated = current.calculated.copy(
      scriptOracles = current.calculated.scriptOracles.updated(update.cid, oracleRecord)
    )

    _onchain = stateDataHashOpt.fold(current.onChain) { hash =>
      current.onChain
        .focus(_.latest)
        .modify(_.updated(update.cid, hash))
    }

  } yield DataState(_onchain, _calculated)

  def invokeScriptOracle[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current:        DataState[OnChain, CalculatedState],
    update:         Signed[Updates.InvokeScriptOracle],
    currentOrdinal: SnapshotOrdinal
  ): F[StateMachine.ProcessingResult] = (for {
    oracleRecord <- EitherT.fromOption[F](
      current.calculated.scriptOracles.get(update.cid),
      StateMachine.FailureReason.Other(s"Oracle ${update.cid} not found")
    )

    _ <- EitherT.fromEither[F](
      if (oracleRecord.status == Records.FiberStatus.Active)
        ().asRight
      else
        (StateMachine.FailureReason.Other(s"Oracle ${update.cid} is not active"): StateMachine.FailureReason).asLeft
    )

    caller <- EitherT
      .fromOption[F](
        update.proofs.toList.headOption,
        StateMachine.FailureReason.Other("No proof provided")
      )
      .flatMap(proof => EitherT.liftF[F, StateMachine.FailureReason, Address](proof.id.toAddress))

    _ <- EitherT(validateAccess(oracleRecord.accessControl, caller, update.cid))

    inputData = MapValue(
      Map(
        "method"  -> StrValue(update.method),
        "args"    -> update.args,
        "content" -> oracleRecord.stateData.getOrElse(NullValue)
      )
    )

    evaluationResult <- EitherT[F, StateMachine.FailureReason, JsonLogicValue](
      JsonLogicEvaluator
        .tailRecursive[F]
        .evaluate(
          oracleRecord.scriptProgram,
          inputData,
          None
        )
        .map(_.leftMap(err => StateMachine.FailureReason.EffectEvaluationError(err.getMessage)))
    )

    (newStateData, returnValue) <- EitherT
      .liftF[F, StateMachine.FailureReason, (Option[JsonLogicValue], JsonLogicValue)](
        extractStateAndResult(evaluationResult)
      )

  } yield StateMachine.OracleSuccess(
    newStateData = newStateData,
    returnValue = returnValue,
    gasUsed = 10L
  ): StateMachine.ProcessingResult).valueOr { reason =>
    StateMachine.Failure(reason): StateMachine.ProcessingResult
  }

  def validateAccess[F[_]: Async](
    policy:     Records.AccessControlPolicy,
    caller:     Address,
    resourceId: UUID
  ): F[Either[StateMachine.FailureReason, Unit]] = policy match {
    case Records.AccessControlPolicy.Public =>
      ().asRight[StateMachine.FailureReason].pure[F]
    case Records.AccessControlPolicy.Whitelist(addresses) =>
      if (addresses.contains(caller))
        ().asRight[StateMachine.FailureReason].pure[F]
      else {
        (StateMachine.FailureReason.AccessDenied(
          caller = caller.show,
          resourceId = resourceId,
          policyType = "Whitelist",
          details = s"Allowed addresses: ${addresses.map(_.show).mkString(", ")}".some
        ): StateMachine.FailureReason).asLeft[Unit].pure[F]
      }
    case Records.AccessControlPolicy.FiberOwned(fiberId) =>
      (StateMachine.FailureReason.AccessDenied(
        caller = caller.show,
        resourceId = resourceId,
        policyType = "FiberOwned",
        details = s"Required owner fiber: $fiberId (not yet implemented)".some
      ): StateMachine.FailureReason).asLeft[Unit].pure[F]
  }
}
