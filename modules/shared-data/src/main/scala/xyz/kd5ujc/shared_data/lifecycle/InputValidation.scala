package xyz.kd5ujc.shared_data.lifecycle

import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.StateMachine

/**
 * Input validation for deterministic blockchain VM execution.
 * Only validates inputs - no error recovery or retries.
 */
// todo: ensure that incoming json does not contain reserved words (state.count issue from oracle)
object InputValidation {

  final case class ValidationError(field: String, message: String)

  final case class ValidationResult(
    isValid: Boolean,
    errors:  List[ValidationError]
  )

  object ValidationResult {
    val success: ValidationResult = ValidationResult(isValid = true, errors = List.empty)

    def failure(field: String, message: String): ValidationResult =
      ValidationResult(isValid = false, errors = List(ValidationError(field, message)))
  }

  /**
   * Validates expression depth to prevent stack overflow attacks
   */
  def validateExpression(expr: JsonLogicExpression, maxDepth: Int = 100): ValidationResult = {
    def checkDepth(e: JsonLogicExpression, currentDepth: Int): Either[ValidationError, Unit] =
      if (currentDepth > maxDepth) {
        Left(ValidationError("expression", s"Expression depth $currentDepth exceeds maximum of $maxDepth"))
      } else {
        e match {
          case ApplyExpression(_, args) =>
            args.traverse(arg => checkDepth(arg, currentDepth + 1)).map(_ => ())

          case ArrayExpression(items) =>
            items.traverse(item => checkDepth(item, currentDepth + 1)).map(_ => ())

          case MapExpression(map) =>
            map.values.toList.traverse(v => checkDepth(v, currentDepth + 1)).map(_ => ())

          case VarExpression(Right(nested), _) =>
            checkDepth(nested, currentDepth + 1)

          case _ =>
            Right(())
        }
      }

    checkDepth(expr, 0) match {
      case Left(error) =>
        ValidationResult(isValid = false, errors = List(error))
      case Right(_) =>
        ValidationResult.success
    }
  }

  /**
   * Validates state size to prevent memory exhaustion attacks
   */
  def validateStateSize(state: JsonLogicValue, maxSizeBytes: Long = 1048576): ValidationResult = {
    def estimateSize(value: JsonLogicValue, depth: Int = 0): Long = {
      if (depth > 100) return 100 // Prevent infinite recursion

      value match {
        case NullValue     => 4L
        case BoolValue(_)  => 5L
        case IntValue(v)   => 8L + v.toString.length
        case FloatValue(v) => 8L + v.toString.length
        case StrValue(s)   => 4L + s.length.toLong * 2L // UTF-16 encoding estimate
        case ArrayValue(items) =>
          8 + items.map(estimateSize(_, depth + 1)).sum
        case MapValue(map) =>
          8 + map.toList.map { case (k, v) =>
            k.length * 2 + estimateSize(v, depth + 1)
          }.sum
        case FunctionValue(_) => 100 // Should not be in state
      }
    }

    val size = estimateSize(state)
    if (size > maxSizeBytes) {
      ValidationResult.failure("state", s"State size $size bytes exceeds maximum $maxSizeBytes bytes")
    } else {
      ValidationResult.success
    }
  }

  /**
   * Validates event payload to ensure it's safe to process
   */
  def validateEventPayload(
    payload:         JsonLogicValue,
    maxSizeBytes:    Long = 102400, // 100KB
    maxStringLength: Int = 10000,
    maxArraySize:    Int = 1000,
    maxMapSize:      Int = 100
  ): ValidationResult = {

    def validate(value: JsonLogicValue, path: String = "payload"): List[ValidationError] =
      value match {
        case StrValue(s) if s.length > maxStringLength =>
          List(ValidationError(path, s"String length ${s.length} exceeds maximum $maxStringLength"))

        case StrValue(s) if s.exists(_.isControl) =>
          List(ValidationError(path, "String contains control characters"))

        case ArrayValue(items) if items.size > maxArraySize =>
          List(ValidationError(path, s"Array size ${items.size} exceeds maximum $maxArraySize"))

        case ArrayValue(items) =>
          items.zipWithIndex.flatMap { case (item, idx) =>
            validate(item, s"$path[$idx]")
          }

        case MapValue(map) if map.size > maxMapSize =>
          List(ValidationError(path, s"Map size ${map.size} exceeds maximum $maxMapSize"))

        case MapValue(map) =>
          map.toList.flatMap { case (k, v) =>
            validate(v, s"$path.$k")
          }

        case _ => List.empty
      }

    // First check overall size
    validateStateSize(payload, maxSizeBytes) match {
      case ValidationResult(false, sizeErrors) =>
        ValidationResult(false, sizeErrors)

      case _ =>
        // Then check structure
        validate(payload) match {
          case Nil    => ValidationResult.success
          case errors => ValidationResult(false, errors)
        }
    }
  }

  /**
   * Validates gas limit to ensure it's within acceptable bounds
   */
  def validateGasLimit(gasLimit: Long, minGas: Long = 1, maxGas: Long = 10000000): ValidationResult =
    if (gasLimit < minGas) {
      ValidationResult.failure("gasLimit", s"Gas limit $gasLimit below minimum $minGas")
    } else if (gasLimit > maxGas) {
      ValidationResult.failure("gasLimit", s"Gas limit $gasLimit exceeds maximum $maxGas")
    } else {
      ValidationResult.success
    }

  /**
   * Validates a complete transaction before execution
   */
  def validateTransaction(
    fiberId:  java.util.UUID,
    event:    StateMachine.Event,
    gasLimit: Long
  ): ValidationResult = {

    // Validate gas limit
    validateGasLimit(gasLimit) match {
      case result @ ValidationResult(false, _) => return result
      case _                                   => ()
    }

    // Validate event payload
    validateEventPayload(event.payload) match {
      case result @ ValidationResult(false, _) => return result
      case _                                   => ()
    }

    // All validations passed
    ValidationResult.success
  }
}
