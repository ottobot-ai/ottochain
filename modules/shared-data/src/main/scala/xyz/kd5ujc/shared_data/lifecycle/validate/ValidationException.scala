package xyz.kd5ujc.shared_data.lifecycle.validate

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError

/**
 * Wrapper exception for raising typed validation errors in effect contexts.
 *
 * This allows using the existing typed validation errors (e.g., FiberRules.Errors.FiberNotActive)
 * with Async[F].raiseError while preserving full type information for pattern matching in tests.
 */
final case class ValidationException(error: DataApplicationValidationError) extends RuntimeException(error.message)
