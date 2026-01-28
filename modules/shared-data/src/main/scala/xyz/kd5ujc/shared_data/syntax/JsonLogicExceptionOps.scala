package xyz.kd5ujc.shared_data.syntax

import cats.Monad
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicException
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasExhaustedException

import xyz.kd5ujc.schema.fiber.{FailureReason, FiberContext, GasExhaustionPhase}
import xyz.kd5ujc.shared_data.fiber.core.{ExecutionOps, ExecutionState}

trait JsonLogicExceptionOps {

  implicit class JsonLogicExceptionSyntax(private val ex: JsonLogicException) {

    /**
     * Convert a JsonLogicException to a FailureReason.
     *
     * GasExhaustedException resolves to GasExhaustedFailure using current gas/limits from state;
     * all other JsonLogicExceptions become EvaluationError with the given phase.
     */
    def toFailureReason[G[_]: Monad](phase: GasExhaustionPhase)(implicit
      S: Stateful[G, ExecutionState],
      A: Ask[G, FiberContext]
    ): G[FailureReason] = ex match {
      case _: GasExhaustedException =>
        for {
          gasUsed <- ExecutionOps.getGasUsed[G]
          limits  <- ExecutionOps.askLimits[G]
        } yield FailureReason.GasExhaustedFailure(gasUsed, limits.maxGas, phase)
      case other =>
        (FailureReason.EvaluationError(phase, other.getMessage): FailureReason).pure[G]
    }
  }
}

object JsonLogicExceptionOps extends JsonLogicExceptionOps
