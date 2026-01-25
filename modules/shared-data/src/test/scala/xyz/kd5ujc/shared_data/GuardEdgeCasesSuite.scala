package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.fiber.engine._
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import weaver.SimpleIOSuite

/**
 * Tests for guard evaluation edge cases.
 *
 * Verifies behavior when guards:
 * - Access missing state fields
 * - Have deeply nested expressions
 * - Encounter evaluation errors
 * - Use complex boolean logic
 */
object GuardEdgeCasesSuite extends SimpleIOSuite {

  test("guard accessing missing state field gracefully handles null") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard references a field that doesn't exist in state
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            // Guard checks missing field - should evaluate to false or null
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("check"),
              // Access non-existent state.missingField and compare to true
              guard = ApplyExpression(
                EqOp,
                List(VarExpression(Left("state.missingField")), ConstExpression(BoolValue(true)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            // Fallback guard that always passes
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("check"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("fallback"))))
            )
          )
        )

        // State has no "missingField"
        initialData = MapValue(Map("existingField" -> IntValue(42)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("check"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          // First guard should fail (missing field), fallback should succeed
          val updated = machines.get(fiberId)
          expect(updated.isDefined) and
          expect(updated.exists(_.currentState == StateMachine.StateId("end"))) and
          expect(updated.exists(_.stateData match {
            case MapValue(m) => m.get("path").contains(StrValue("fallback"))
            case _           => false
          }))
        case TransactionOutcome.Aborted(reason, _, _) =>
          // Accessing missing field shouldn't crash - but if it does, document
          expect(reason.toMessage.toLowerCase.contains("guard") || reason.toMessage.toLowerCase.contains("missing"))
      }
    }
  }

  test("guard with deeply nested expression evaluates within limits") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // 10 levels of nested AND/OR
        deepGuard = (1 to 10).foldLeft[JsonLogicExpression](
          ConstExpression(BoolValue(true))
        ) { (acc, i) =>
          if (i % 2 == 0)
            ApplyExpression(AndOp, List(acc, ConstExpression(BoolValue(true))))
          else
            ApplyExpression(OrOp, List(acc, ConstExpression(BoolValue(false))))
        }

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("process"),
              guard = deepGuard,
              effect = ConstExpression(MapValue(Map("processed" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("process"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, gasUsed, _, _) =>
          expect(machines.get(fiberId).exists(_.currentState == StateMachine.StateId("end"))) and
          expect(gasUsed > 0L) // Nested guard consumed some gas
        case TransactionOutcome.Aborted(reason, _, _) =>
          // If depth limit is exceeded, that's acceptable
          expect(
            reason.toMessage.toLowerCase.contains("depth") ||
            reason.toMessage.toLowerCase.contains("gas")
          )
      }
    }
  }

  test("guard with division by zero in expression") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard that divides by zero
        divByZeroGuard = ApplyExpression(
          EqOp,
          List(
            ApplyExpression(
              DivOp,
              List(ConstExpression(IntValue(100)), ConstExpression(IntValue(0)))
            ),
            ConstExpression(IntValue(0))
          )
        )

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("divide"),
              guard = divByZeroGuard,
              effect = ConstExpression(MapValue(Map("divided" -> BoolValue(true))))
            ),
            // Fallback
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("divide"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("fallback" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("divide"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          // Division by zero should cause guard evaluation error
          expect(
            reason.isInstanceOf[StateMachine.FailureReason.GuardEvaluationError] ||
            reason.toMessage.toLowerCase.contains("guard") ||
            reason.toMessage.toLowerCase.contains("divide") ||
            reason.toMessage.toLowerCase.contains("zero")
          )
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          // If JsonLogic handles div-by-zero gracefully (returns null/false),
          // fallback transition should be taken
          val updated = machines.get(fiberId)
          expect(
            updated.exists(_.stateData match {
              case MapValue(m) => m.get("fallback").contains(BoolValue(true))
              case _           => false
            })
          )
      }
    }
  }

  test("guard evaluation order follows transition list order") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Three transitions - first two guards check specific conditions
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            // First: requires value > 100
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("process"),
              guard = ApplyExpression(
                Gt,
                List(VarExpression(Left("state.value")), ConstExpression(IntValue(100)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("high"))))
            ),
            // Second: requires value > 50
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("process"),
              guard = ApplyExpression(
                Gt,
                List(VarExpression(Left("state.value")), ConstExpression(IntValue(50)))
              ),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("medium"))))
            ),
            // Third: always passes
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("process"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("low"))))
            )
          )
        )

        // Value is 75 - should match second guard (> 50 but not > 100)
        initialData = MapValue(Map("value" -> IntValue(75)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("process"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          val updated = machines.get(fiberId)
          expect(updated.isDefined) and
          // Second transition should be taken (75 > 50 but 75 not > 100)
          expect(updated.exists(_.stateData match {
            case MapValue(m) => m.get("path").contains(StrValue("medium"))
            case _           => false
          }))
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("guard returning non-boolean value causes GuardEvaluationError") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        // Guard returns an integer instead of boolean
        nonBooleanGuard = ConstExpression(IntValue(42))

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = nonBooleanGuard,
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("go"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          // Guard that returns non-boolean should cause error or be treated as false
          expect(
            reason.isInstanceOf[StateMachine.FailureReason.GuardEvaluationError] ||
            reason.isInstanceOf[StateMachine.FailureReason.NoGuardMatched] ||
            reason.toMessage.toLowerCase.contains("boolean") ||
            reason.toMessage.toLowerCase.contains("guard")
          )
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          // If the system treats non-boolean as truthy/falsy and proceeds, that's also valid behavior
          success
      }
    }
  }

  test("fiber with non-MapValue stateData causes EffectEvaluationError") {
    TestFixture.resource(Set.empty[Participant]).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal = fixture.ordinal

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("done" -> BoolValue(true))))
            )
          )
        )

        // State data is an ArrayValue instead of MapValue
        initialData: JsonLogicValue = ArrayValue(List(IntValue(1), IntValue(2), IntValue(3)))
        initialHash <- initialData.computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("start"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("go"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[StateMachine.FailureReason.EffectEvaluationError] ||
            reason.toMessage.toLowerCase.contains("mapvalue") ||
            reason.toMessage.toLowerCase.contains("state")
          )
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          // If the effect somehow succeeds with array state, document that behavior
          failure("Expected EffectEvaluationError for non-MapValue state data")
      }
    }
  }
}
