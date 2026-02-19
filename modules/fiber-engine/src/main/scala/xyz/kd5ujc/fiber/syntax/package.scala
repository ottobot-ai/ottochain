package xyz.kd5ujc.fiber

/**
 * Syntax extensions for the fiber engine module.
 *
 * Import all syntax with:
 * {{{
 * import xyz.kd5ujc.fiber.syntax.all._
 * }}}
 *
 * Or import specific syntax:
 * {{{
 * import xyz.kd5ujc.fiber.syntax.calculatedState._
 * import xyz.kd5ujc.fiber.syntax.jsonLogicException._
 * }}}
 */
package object syntax {
  object all extends CalculatedStateSyntax with JsonLogicExceptionOps

  val calculatedState: CalculatedStateSyntax.type = CalculatedStateSyntax
  val jsonLogicException: JsonLogicExceptionOps.type = JsonLogicExceptionOps
}
