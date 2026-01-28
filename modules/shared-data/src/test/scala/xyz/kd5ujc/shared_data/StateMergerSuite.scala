package xyz.kd5ujc.shared_data

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.StateMerger

import weaver.SimpleIOSuite

/**
 * Tests for StateMerger state merging semantics.
 *
 * Verifies:
 * - MapValue shallow merge format
 * - ArrayValue [key, value] pair update format
 * - Reserved key filtering
 * - Error handling for invalid formats
 */
object StateMergerSuite extends SimpleIOSuite {

  private val merger = StateMerger.make[IO]

  test("MapValue effect merges keys into current state") {
    val currentState = MapValue(
      Map(
        "existing" -> IntValue(1),
        "counter"  -> IntValue(10)
      )
    )

    val effectResult = MapValue(
      Map(
        "counter" -> IntValue(20), // Override existing key
        "newKey"  -> StrValue("hello") // Add new key
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value.get("existing").contains(IntValue(1))) and // Preserved
        expect(merged.value.get("counter").contains(IntValue(20))) and // Overwritten
        expect(merged.value.get("newKey").contains(StrValue("hello"))) // Added
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }

  test("ArrayValue [key, value] pairs update state sequentially") {
    val currentState = MapValue(
      Map(
        "a" -> IntValue(1),
        "b" -> IntValue(2)
      )
    )

    // Array of [key, value] pairs
    val effectResult = ArrayValue(
      List(
        ArrayValue(List(StrValue("b"), IntValue(20))), // Update b
        ArrayValue(List(StrValue("c"), IntValue(30))), // Add c
        ArrayValue(List(StrValue("a"), IntValue(100))) // Update a (later in sequence)
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value.get("a").contains(IntValue(100))) and // Updated last
        expect(merged.value.get("b").contains(IntValue(20))) and // Updated
        expect(merged.value.get("c").contains(IntValue(30))) // Added
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }

  test("invalid ArrayValue format returns EvaluationError") {
    val currentState = MapValue(Map("x" -> IntValue(1)))

    // Invalid: array element is not [key, value] pair
    val effectResult = ArrayValue(
      List(
        IntValue(5) // Invalid: should be ArrayValue([StrValue, value])
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Left(err) =>
        err match {
          case FailureReason.EvaluationError(_, msg) =>
            expect(msg.contains("Invalid effect update format"))
          case other =>
            failure(s"Expected EvaluationError, got: $other")
        }
      case Right(_) =>
        failure("Expected error for invalid format, got success")
    }
  }

  test("ArrayValue with wrong key type returns error") {
    val currentState = MapValue(Map("x" -> IntValue(1)))

    // Invalid: key is IntValue instead of StrValue
    val effectResult = ArrayValue(
      List(
        ArrayValue(List(IntValue(1), StrValue("value")))
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Left(err) =>
        expect(err.isInstanceOf[FailureReason.EvaluationError])
      case Right(_) =>
        failure("Expected error for non-string key, got success")
    }
  }

  test("reserved keys are filtered from MapValue effect") {
    val currentState = MapValue(Map("data" -> IntValue(1)))

    val effectResult = MapValue(
      Map(
        "data"      -> IntValue(2), // Valid: should update
        "_triggers" -> ArrayValue(List.empty), // Reserved: should be filtered
        "_spawn"    -> MapValue(Map.empty), // Reserved: should be filtered
        "result"    -> StrValue("ok") // Valid: should be added
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value.get("data").contains(IntValue(2))) and
        expect(merged.value.get("result").contains(StrValue("ok"))) and
        expect(!merged.value.contains("_triggers")) and // Filtered out
        expect(!merged.value.contains("_spawn")) // Filtered out
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }

  test("reserved keys are filtered from ArrayValue pairs") {
    val currentState = MapValue(Map("data" -> IntValue(1)))

    val effectResult = ArrayValue(
      List(
        ArrayValue(List(StrValue("data"), IntValue(2))),
        ArrayValue(List(StrValue("_internal"), StrValue("secret"))), // Reserved
        ArrayValue(List(StrValue("value"), IntValue(42)))
      )
    )

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value.get("data").contains(IntValue(2))) and
        expect(merged.value.get("value").contains(IntValue(42))) and
        expect(!merged.value.contains("_internal")) // Filtered out
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }

  test("non-map/array effect result returns error") {
    val currentState = MapValue(Map("x" -> IntValue(1)))

    // Invalid: effect is neither MapValue nor ArrayValue
    val effectResult = IntValue(42)

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Left(err) =>
        err match {
          case FailureReason.EvaluationError(_, msg) =>
            expect(msg.contains("must return MapValue or ArrayValue"))
          case other =>
            failure(s"Expected EvaluationError, got: $other")
        }
      case Right(_) =>
        failure("Expected error for invalid effect type, got success")
    }
  }

  test("empty ArrayValue effect preserves current state") {
    val currentState = MapValue(
      Map(
        "a" -> IntValue(1),
        "b" -> IntValue(2)
      )
    )

    val effectResult = ArrayValue(List.empty)

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value == currentState.value)
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }

  test("empty MapValue effect preserves current state") {
    val currentState = MapValue(
      Map(
        "a" -> IntValue(1),
        "b" -> IntValue(2)
      )
    )

    val effectResult = MapValue(Map.empty)

    for {
      result <- merger.mergeEffectIntoState(currentState, effectResult)
    } yield result match {
      case Right(merged) =>
        expect(merged.value == currentState.value)
      case Left(err) =>
        failure(s"Expected success, got error: ${err.toMessage}")
    }
  }
}
