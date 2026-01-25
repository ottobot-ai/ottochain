package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain._
import xyz.kd5ujc.shared_data.fiber.engine._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for comprehensive FailureReason coverage.
 *
 * Verifies that all FailureReason ADT variants are properly returned
 * for their corresponding error conditions.
 */
object FailureReasonSuite extends SimpleIOSuite {

  test("NoTransitionFound when event type not in transitionMap") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with only one event type defined
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle")   -> StateMachine.State(StateMachine.StateId("idle")),
            StateMachine.StateId("active") -> StateMachine.State(StateMachine.StateId("active"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("idle"),
              to = StateMachine.StateId("active"),
              eventType = StateMachine.EventType("activate"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("activated" -> BoolValue(true))))
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
          currentState = StateMachine.StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)

        // Send an event type that doesn't exist in the transitionMap
        input = FiberInput.Transition(
          StateMachine.EventType("unknown_event"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          reason match {
            case StateMachine.FailureReason.NoTransitionFound(state, eventType) =>
              expect(state == StateMachine.StateId("idle"), s"Expected state 'idle', got $state") and
              expect(
                eventType == StateMachine.EventType("unknown_event"),
                s"Expected event 'unknown_event', got $eventType"
              )
            case other =>
              failure(s"Expected NoTransitionFound but got: ${other.getClass.getSimpleName}")
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with NoTransitionFound, but transaction was committed")
      }
    }
  }

  test("NoGuardMatched when all guards return false") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // Multiple transitions for same event, all guards return false
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
              guard = ConstExpression(BoolValue(false)), // Always false
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(false)), // Always false
              effect = ConstExpression(MapValue(Map("path" -> StrValue("second"))))
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
          reason match {
            case StateMachine.FailureReason.NoGuardMatched(_, _, attemptedCount) =>
              expect(attemptedCount == 2, s"Expected 2 guard attempts, got $attemptedCount")
            case other =>
              failure(s"Expected NoGuardMatched but got: ${other.getClass.getSimpleName}")
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with NoGuardMatched, but transaction was committed")
      }
    }
  }

  test("TriggerTargetNotFound includes fiberId in message") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId       <- UUIDGen.randomUUID[IO]
        nonExistentId <- UUIDGen.randomUUID[IO]

        // State machine that triggers a non-existent target
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle")      -> StateMachine.State(StateMachine.StateId("idle")),
            StateMachine.StateId("triggered") -> StateMachine.State(StateMachine.StateId("triggered"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("idle"),
              to = StateMachine.StateId("triggered"),
              eventType = StateMachine.EventType("fire"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "fired" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(nonExistentId.toString),
                            "eventType"       -> StrValue("receive"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
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
          currentState = StateMachine.StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        // Only the source fiber exists, target doesn't
        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("fire"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          reason match {
            case StateMachine.FailureReason.TriggerTargetNotFound(targetId, _) =>
              expect(targetId == nonExistentId, s"Expected target $nonExistentId, got $targetId")
            case other =>
              failure(s"Expected TriggerTargetNotFound but got: ${other.getClass.getSimpleName}")
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with TriggerTargetNotFound, but transaction was committed")
      }
    }
  }

  test("CycleDetected for self-referential trigger") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine triggers itself with same event type
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("a") -> StateMachine.State(StateMachine.StateId("a")),
            StateMachine.StateId("b") -> StateMachine.State(StateMachine.StateId("b"))
          ),
          initialState = StateMachine.StateId("a"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("a"),
              to = StateMachine.StateId("b"),
              eventType = StateMachine.EventType("loop"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "looped" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(fiberId.toString),
                            "eventType"       -> StrValue("loop"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
            ),
            StateMachine.Transition(
              from = StateMachine.StateId("b"),
              to = StateMachine.StateId("a"),
              eventType = StateMachine.EventType("loop"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "back" -> BoolValue(true),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(fiberId.toString),
                            "eventType"       -> StrValue("loop"),
                            "payload"         -> MapValue(Map.empty)
                          )
                        )
                      )
                    )
                  )
                )
              )
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
          currentState = StateMachine.StateId("a"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("loop"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          expect(reason.isInstanceOf[StateMachine.FailureReason.CycleDetected])
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with CycleDetected, got Committed")
      }
    }
  }

  test("GasExhaustedFailure with phase information") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // Expensive guard that will exhaust gas
        expensiveGuard = (1 to 100).foldLeft[JsonLogicExpression](
          ConstExpression(IntValue(0))
        ) { (acc, _) =>
          ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
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
              eventType = StateMachine.EventType("go"),
              guard = ApplyExpression(EqOp, List(expensiveGuard, ConstExpression(IntValue(100)))),
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

        // Gas limit set to exhaust during guard evaluation (100 additions need ~100+ gas)
        limits = ExecutionLimits(maxDepth = 10, maxGas = 50L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          reason match {
            case StateMachine.FailureReason.GasExhaustedFailure(gasUsed, gasLimit, phase) =>
              // Gas should exhaust during guard evaluation (100 additions with 50L limit)
              expect(gasUsed <= gasLimit, s"Gas used ($gasUsed) should not exceed limit ($gasLimit)") and
              expect(gasLimit == 50L, s"Expected gas limit 50L, got $gasLimit") and
              expect(
                phase == StateMachine.GasExhaustionPhase.Guard,
                s"Expected Guard phase (guard has 100 additions), got $phase"
              )
            case other =>
              failure(
                s"Expected GasExhaustedFailure in Guard phase but got: ${other.getClass.getSimpleName}: ${other.toMessage}"
              )
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure, but transaction was committed")
      }
    }
  }

  // Note: Payload validation with control characters is now handled at L1 (validateUpdate)
  // not at runtime in FiberOrchestrator. See ValidatorSuite and DeterministicExecutionSuite
  // for tests covering L1 payload validation (CommonRules.payloadStructureValid).
  // This test now verifies that payloads reaching the orchestrator are assumed to be valid.
  test("Valid payload with proper characters succeeds") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

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
              guard = ConstExpression(BoolValue(true)),
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

        // Valid payload (control character validation happens at L1)
        validPayload = MapValue(
          Map(
            "message" -> StrValue("Hello World")
          )
        )

        input = FiberInput.Transition(
          StateMachine.EventType("process"),
          validPayload
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          success
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason}")
      }
    }
  }

  test("type mismatch: MethodCall input with StateMachineFiberRecord") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      val registry = fixture.registry
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle") -> StateMachine.State(StateMachine.StateId("idle"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List.empty
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        // Create a state machine fiber
        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)

        // Use MethodCall input (oracle-style) with state machine - type mismatch
        input = FiberInput.MethodCall(
          method = "someMethod",
          args = MapValue(Map.empty),
          caller = registry.addresses(Alice),
          idempotencyKey = None
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          reason match {
            case StateMachine.FailureReason.FiberInputMismatch(fid, fiberType, inputType) =>
              expect(fid == fiberId, s"Expected fiber $fiberId, got $fid") and
              expect(
                fiberType == "StateMachineFiberRecord",
                s"Expected fiberType 'StateMachineFiberRecord', got $fiberType"
              ) and
              expect(inputType == "MethodCall", s"Expected inputType 'MethodCall', got $inputType")
            case other =>
              failure(s"Expected FiberInputMismatch but got: ${other.getClass.getSimpleName}")
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with FiberInputMismatch, but transaction was committed")
      }
    }
  }

  test("type mismatch: Transition input with ScriptOracleFiberRecord") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        oracleId <- UUIDGen.randomUUID[IO]

        // Simple oracle script that just returns state
        oracleScript = VarExpression(Left("_state"))

        oracleData = MapValue(Map("value" -> IntValue(0)))
        oracleHash <- (oracleData: JsonLogicValue).computeDigest

        // Create a script oracle fiber
        oracle = Records.ScriptOracleFiberRecord(
          cid = oracleId,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          scriptProgram = oracleScript,
          stateData = Some(oracleData),
          stateDataHash = Some(oracleHash),
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          invocationCount = 0,
          invocationLog = List.empty,
          accessControl = Records.AccessControlPolicy.Public
        )

        calculatedState = CalculatedState(Map.empty, Map(oracleId -> oracle))

        // Use Transition input (state machine-style) with oracle - type mismatch
        input = FiberInput.Transition(
          StateMachine.EventType("someEvent"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(oracleId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          reason match {
            case StateMachine.FailureReason.FiberInputMismatch(oid, fiberType, inputType) =>
              expect(oid == oracleId, s"Expected oracle $oracleId, got $oid") and
              expect(
                fiberType == "ScriptOracleFiberRecord",
                s"Expected fiberType 'ScriptOracleFiberRecord', got $fiberType"
              ) and
              expect(inputType == "Transition", s"Expected inputType 'Transition', got $inputType")
            case other =>
              failure(s"Expected FiberInputMismatch but got: ${other.getClass.getSimpleName}")
          }
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with FiberInputMismatch, but transaction was committed")
      }
    }
  }
}
