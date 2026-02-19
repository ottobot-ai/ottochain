package xyz.kd5ujc.fiber.syntax

import java.util.UUID

import xyz.kd5ujc.schema.{CalculatedState, Records}

/**
 * Extension methods for CalculatedState used by the fiber engine.
 *
 * These extensions are self-contained within the fiber-engine module and
 * do not depend on the shared-data lifecycle/validation layer.
 */
trait CalculatedStateSyntax {

  implicit class CalculatedStateEngineOps(private val state: CalculatedState) {

    /** Lookup any fiber (state machine or script) by ID */
    def getFiber(id: UUID): Option[Records.FiberRecord] =
      state.stateMachines.get(id).orElse(state.scripts.get(id))

    /** Update a fiber in the appropriate map based on its runtime type */
    def updateFiber(fiber: Records.FiberRecord): CalculatedState = fiber match {
      case sm: Records.StateMachineFiberRecord =>
        state.copy(stateMachines = state.stateMachines.updated(sm.fiberId, sm))
      case oracle: Records.ScriptFiberRecord =>
        state.copy(scripts = state.scripts.updated(oracle.fiberId, oracle))
    }
  }
}

object CalculatedStateSyntax extends CalculatedStateSyntax
