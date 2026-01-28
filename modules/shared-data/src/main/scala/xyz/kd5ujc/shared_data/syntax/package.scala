package xyz.kd5ujc.shared_data

/**
 * Syntax extensions for shared-data module.
 *
 * Import all syntax with:
 * {{{
 * import xyz.kd5ujc.shared_data.syntax.all._
 * }}}
 *
 * Or import specific syntax:
 * {{{
 * import xyz.kd5ujc.shared_data.syntax.dataState._
 * import xyz.kd5ujc.shared_data.syntax.l0NodeContext._
 * }}}
 */
package object syntax {
  object all extends DataStateOps with L0NodeContextOps with CalculatedStateOps with JsonLogicExceptionOps

  val dataState: DataStateOps.type = DataStateOps
  val l0NodeContext: L0NodeContextOps.type = L0NodeContextOps
  val calculatedState: CalculatedStateOps.type = CalculatedStateOps
  val jsonLogicException: JsonLogicExceptionOps.type = JsonLogicExceptionOps
}
