package xyz.kd5ujc.schema.fiber

import xyz.kd5ujc.schema.CalculatedState

sealed trait TriggerHandlerResult

object TriggerHandlerResult {

  /**
   * Trigger processed successfully.
   *
   * Gas is tracked via StateT and not carried in the result.
   *
   * @param updatedState   State with the target fiber updated
   * @param receipts       Event receipts produced by this trigger
   * @param cascadeTriggers Additional triggers to process
   */
  final case class Success(
    updatedState:    CalculatedState,
    receipts:        List[EventReceipt],
    cascadeTriggers: List[FiberTrigger]
  ) extends TriggerHandlerResult

  /**
   * Trigger processing failed.
   *
   * Gas consumed before failure is tracked via StateT.
   *
   * @param reason Why the trigger failed
   */
  final case class Failed(
    reason: FailureReason
  ) extends TriggerHandlerResult
}
