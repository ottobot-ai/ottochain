package xyz.kd5ujc.shared_test

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.Records

/**
 * Extension methods for extracting fields from fiber state data.
 *
 * Usage:
 * {{{
 * import xyz.kd5ujc.shared_test.FiberExtractors._
 *
 * val fiber: Records.StateMachineFiberRecord = ...
 * val counter: Option[BigInt] = fiber.extractInt("counter")
 * val name: Option[String] = fiber.extractString("name")
 * val active: Option[Boolean] = fiber.extractBool("active")
 * }}}
 */
object FiberExtractors {

  implicit class StateMachineFiberRecordOps(private val fiber: Records.StateMachineFiberRecord) extends AnyVal {

    /**
     * Extract an integer field from the fiber's state data.
     */
    def extractInt(field: String): Option[BigInt] = fiber.stateData match {
      case MapValue(m) => m.get(field).collect { case IntValue(v) => v }
      case _           => None
    }

    /**
     * Extract a string field from the fiber's state data.
     */
    def extractString(field: String): Option[String] = fiber.stateData match {
      case MapValue(m) => m.get(field).collect { case StrValue(v) => v }
      case _           => None
    }

    /**
     * Extract a boolean field from the fiber's state data.
     */
    def extractBool(field: String): Option[Boolean] = fiber.stateData match {
      case MapValue(m) => m.get(field).collect { case BoolValue(v) => v }
      case _           => None
    }

    /**
     * Extract a nested map field from the fiber's state data.
     */
    def extractMap(field: String): Option[Map[String, JsonLogicValue]] = fiber.stateData match {
      case MapValue(m) => m.get(field).collect { case MapValue(v) => v }
      case _           => None
    }

    /**
     * Extract an array field from the fiber's state data.
     */
    def extractArray(field: String): Option[List[JsonLogicValue]] = fiber.stateData match {
      case MapValue(m) => m.get(field).collect { case ArrayValue(v) => v }
      case _           => None
    }
  }

  implicit class OptionFiberRecordOps(private val maybeFiber: Option[Records.StateMachineFiberRecord]) extends AnyVal {

    /**
     * Extract an integer field from an optional fiber's state data.
     */
    def extractInt(field: String): Option[BigInt] =
      maybeFiber.flatMap(_.extractInt(field))

    /**
     * Extract a string field from an optional fiber's state data.
     */
    def extractString(field: String): Option[String] =
      maybeFiber.flatMap(_.extractString(field))

    /**
     * Extract a boolean field from an optional fiber's state data.
     */
    def extractBool(field: String): Option[Boolean] =
      maybeFiber.flatMap(_.extractBool(field))
  }
}
