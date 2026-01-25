package xyz.kd5ujc.shared_data.fiber.domain

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import xyz.kd5ujc.schema.{Records, StateMachine}

/**
 * Unified outcome from fiber processing.
 */
sealed trait FiberOutcome

object FiberOutcome {

  /**
   * Successful fiber evaluation.
   *
   * @param newStateData Updated state data
   * @param newStateId New state ID (Some for state machines, None for oracles)
   * @param triggers Triggered events for other fibers
   * @param spawns Child fibers to create (state machines only)
   * @param outputs Structured outputs for external systems
   * @param returnValue Return value (Some for oracles, None for state machines)
   * @param gasUsed Gas consumed by this evaluation
   */
  final case class Success(
    newStateData: JsonLogicValue,
    newStateId:   Option[StateMachine.StateId],
    triggers:     List[StateMachine.TriggerEvent],
    spawns:       List[StateMachine.SpawnDirective],
    outputs:      List[Records.StructuredOutput],
    returnValue:  Option[JsonLogicValue],
    gasUsed:      Long
  ) extends FiberOutcome

  /**
   * No guard matched (state machines only).
   *
   * @param attemptedCount Number of guards evaluated before giving up
   * @param gasUsed Gas consumed by all guard evaluations (EVM: charge for failed guards)
   */
  final case class GuardFailed(attemptedCount: Int, gasUsed: Long = 0L) extends FiberOutcome

  /** Evaluation failed with reason */
  final case class Failed(reason: StateMachine.FailureReason) extends FiberOutcome
}
