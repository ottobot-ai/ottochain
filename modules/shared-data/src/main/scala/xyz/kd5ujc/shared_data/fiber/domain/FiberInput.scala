package xyz.kd5ujc.shared_data.fiber.domain

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.StateMachine

/**
 * Unified input for fiber processing.
 * Supports both state machine transitions and oracle method calls.
 */
sealed trait FiberInput {

  /** Key used for cycle detection (eventType or method name) */
  def inputKey: String
}

object FiberInput {

  /** State machine transition input */
  final case class Transition(
    eventType: StateMachine.EventType,
    payload:   JsonLogicValue
  ) extends FiberInput {
    def inputKey: String = eventType.value
  }

  /**
   * Oracle method call input.
   *
   * @param method Method name to invoke
   * @param args Arguments for the method
   * @param caller Address of the caller
   * @param idempotencyKey Optional key for idempotency tracking.
   *                       If provided, duplicate invocations with the same key
   *                       within a time window will be rejected.
   */
  final case class MethodCall(
    method:         String,
    args:           JsonLogicValue,
    caller:         Address,
    idempotencyKey: Option[String] = None
  ) extends FiberInput {
    def inputKey: String = method
  }
}
