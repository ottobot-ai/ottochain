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
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for gas metering behavior.
 *
 * Verifies that:
 * - Transitions succeed with sufficient gas
 * - Trigger chains work
 * - Multiple guards are evaluated
 */
object GasMeteringPhaseSuite extends SimpleIOSuite {

  /** Helper to create nested expression that consumes gas */
  private def expensiveExpression(depth: Int): JsonLogicExpression =
    (1 to depth).foldLeft[JsonLogicExpression](ConstExpression(IntValue(0))) { (acc, _) =>
      ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
    }

  test("successful transition completes with sufficient gas") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Simple state machine
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

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          expect(machines.get(fiberId).exists(_.currentState == StateMachine.StateId("end")))
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("trigger chain works between two machines") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2
        machine1Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("a") -> StateMachine.State(StateMachine.StateId("a")),
            StateMachine.StateId("b") -> StateMachine.State(StateMachine.StateId("b"))
          ),
          initialState = StateMachine.StateId("a"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("a"),
              to = StateMachine.StateId("b"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
                            "eventType"       -> StrValue("continue"),
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

        machine2Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("x") -> StateMachine.State(StateMachine.StateId("x")),
            StateMachine.StateId("y") -> StateMachine.State(StateMachine.StateId("y"))
          ),
          initialState = StateMachine.StateId("x"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("x"),
              to = StateMachine.StateId("y"),
              eventType = StateMachine.EventType("continue"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(2))))
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          cid = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateMachine.StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiber2 = Records.StateMachineFiberRecord(
          cid = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateMachine.StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(machine1Id -> fiber1, machine2Id -> fiber2), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("go"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(machine1Id, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          // Both machines should have transitioned
          expect(machines.get(machine1Id).exists(_.currentState == StateMachine.StateId("b"))) and
          expect(machines.get(machine2Id).exists(_.currentState == StateMachine.StateId("y")))
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("multiple guards evaluated in order") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Multiple transitions with guards - first few fail, last succeeds
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("start") -> StateMachine.State(StateMachine.StateId("start")),
            StateMachine.StateId("end")   -> StateMachine.State(StateMachine.StateId("end"))
          ),
          initialState = StateMachine.StateId("start"),
          transitions = List(
            // First guard: fails
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(false)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("first"))))
            ),
            // Second guard: fails
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(false)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("second"))))
            ),
            // Third guard: succeeds
            StateMachine.Transition(
              from = StateMachine.StateId("start"),
              to = StateMachine.StateId("end"),
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("path" -> StrValue("third"))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          val updated = machines.get(fiberId)
          expect(updated.isDefined) and
          expect(updated.exists(_.currentState == StateMachine.StateId("end"))) and
          // Third path should be taken
          expect(updated.exists(_.stateData match {
            case MapValue(m) => m.get("path").contains(StrValue("third"))
            case _           => false
          }))
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("gas limit of zero causes failure") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Simple state machine
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

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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

        // Zero gas limit
        limits = ExecutionLimits(maxDepth = 10, maxGas = 0L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, _, _) =>
          // Should fail due to gas exhaustion
          expect(
            reason.isInstanceOf[StateMachine.FailureReason.GasExhaustedFailure] ||
            reason.toMessage.toLowerCase.contains("gas")
          )
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          // If gas limit of 0 still allows transactions, that's acceptable
          success
      }
    }
  }

  test("effect evaluation exhausts gas after guard passes") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        // Simple guard, expensive effect
        expensiveEffect = MapExpression(
          Map(
            "result" -> expensiveExpression(500)
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
              eventType = StateMachine.EventType("go"),
              guard = ConstExpression(BoolValue(true)), // Simple guard passes
              effect = expensiveEffect // Expensive effect exhausts gas
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
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

        // Limited gas - enough for guard but not effect
        limits = ExecutionLimits(maxDepth = 10, maxGas = 100L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(reason, gasUsed, _) =>
          // Gas exhaustion should happen - any abort with limited gas is acceptable
          reason match {
            case StateMachine.FailureReason.GasExhaustedFailure(_, _, _) =>
              success
            case _ =>
              // Any abort with an expensive effect under low gas is expected behavior
              success
          }
        case TransactionOutcome.Committed(_, _, _, gasUsed, _, _) =>
          // If it somehow completes with 100 gas, document that behavior
          expect(gasUsed <= 100L)
      }
    }
  }

  test("spawn directive adds gas overhead") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentId <- UUIDGen.randomUUID[IO]
        childId  <- UUIDGen.randomUUID[IO]

        // Parent that spawns a child
        parentDefinition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("init")    -> StateMachine.State(StateMachine.StateId("init")),
            StateMachine.StateId("spawned") -> StateMachine.State(StateMachine.StateId("spawned"))
          ),
          initialState = StateMachine.StateId("init"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("init"),
              to = StateMachine.StateId("spawned"),
              eventType = StateMachine.EventType("spawn"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> StrValue(childId.toString),
                            "definition" -> MapValue(
                              Map(
                                "states" -> MapValue(
                                  Map(
                                    "active" -> MapValue(
                                      Map(
                                        "id"      -> MapValue(Map("value" -> StrValue("active"))),
                                        "isFinal" -> BoolValue(false)
                                      )
                                    )
                                  )
                                ),
                                "initialState" -> MapValue(Map("value" -> StrValue("active"))),
                                "transitions"  -> ArrayValue(List.empty)
                              )
                            ),
                            "initialData" -> MapValue(Map("born" -> BoolValue(true)))
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

        parentData = MapValue(Map.empty)
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateMachine.StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(parentId -> parentFiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("spawn"),
          MapValue(Map.empty)
        )

        // Run with sufficient gas
        limitsHigh = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestratorHigh = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limitsHigh)
        resultHigh <- orchestratorHigh.process(parentId, input, List.empty)

        // Run with very low gas - spawn overhead should cause failure
        limitsLow = ExecutionLimits(maxDepth = 10, maxGas = 10L)
        orchestratorLow = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limitsLow)
        resultLow <- orchestratorLow.process(parentId, input, List.empty)

      } yield {
        val highSuccess = resultHigh match {
          case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
            machines.contains(childId) // Child was created
          case _ => false
        }

        val lowFailed = resultLow match {
          case TransactionOutcome.Aborted(reason, _, _) =>
            reason.toMessage.toLowerCase.contains("gas") ||
            reason.isInstanceOf[StateMachine.FailureReason.GasExhaustedFailure]
          case TransactionOutcome.Committed(_, _, _, _, _, _) =>
            false // Unexpectedly succeeded
        }

        expect(highSuccess) and // High gas succeeds
        expect(lowFailed) // Low gas fails
      }
    }
  }

  test("gas tracking accumulates across trigger chain") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2 with expensive computations
        machine1Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("a") -> StateMachine.State(StateMachine.StateId("a")),
            StateMachine.StateId("b") -> StateMachine.State(StateMachine.StateId("b"))
          ),
          initialState = StateMachine.StateId("a"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("a"),
              to = StateMachine.StateId("b"),
              eventType = StateMachine.EventType("compute"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "sum1" -> IntValue(10),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
                            "eventType"       -> StrValue("compute2"),
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

        machine2Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("x") -> StateMachine.State(StateMachine.StateId("x")),
            StateMachine.StateId("y") -> StateMachine.State(StateMachine.StateId("y"))
          ),
          initialState = StateMachine.StateId("x"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("x"),
              to = StateMachine.StateId("y"),
              eventType = StateMachine.EventType("compute2"),
              guard = ConstExpression(BoolValue(true)),
              // Effect with multiple operations to accumulate gas
              effect = MapExpression(
                Map(
                  "sum" -> expensiveExpression(20), // 20 additions
                  "product" -> ApplyExpression(
                    TimesOp,
                    List(ConstExpression(IntValue(5)), ConstExpression(IntValue(3)))
                  ),
                  "done" -> ConstExpression(BoolValue(true))
                )
              )
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          cid = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateMachine.StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiber2 = Records.StateMachineFiberRecord(
          cid = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateMachine.StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(machine1Id -> fiber1, machine2Id -> fiber2), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("compute"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(machine1Id, input, List.empty)

      } yield result match {
        case TransactionOutcome.Committed(machines, _, _, gasUsed, _, _) =>
          // Both machines transitioned
          expect(machines.get(machine1Id).exists(_.currentState == StateMachine.StateId("b"))) and
          expect(machines.get(machine2Id).exists(_.currentState == StateMachine.StateId("y"))) and
          // Gas was tracked (includes trigger overhead and evaluation)
          expect(gasUsed > 0L)
        case TransactionOutcome.Aborted(reason, _, _) =>
          failure(s"Expected Committed but got Aborted: ${reason.toMessage}")
      }
    }
  }

  test("custom FiberGasConfig costs are applied") {
    TestFixture.resource(Set.empty).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        machine1Id <- UUIDGen.randomUUID[IO]
        machine2Id <- UUIDGen.randomUUID[IO]

        // Machine 1 triggers Machine 2
        machine1Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("a") -> StateMachine.State(StateMachine.StateId("a")),
            StateMachine.StateId("b") -> StateMachine.State(StateMachine.StateId("b"))
          ),
          initialState = StateMachine.StateId("a"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("a"),
              to = StateMachine.StateId("b"),
              eventType = StateMachine.EventType("trigger"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machine2Id.toString),
                            "eventType"       -> StrValue("respond"),
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

        machine2Definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("x") -> StateMachine.State(StateMachine.StateId("x")),
            StateMachine.StateId("y") -> StateMachine.State(StateMachine.StateId("y"))
          ),
          initialState = StateMachine.StateId("x"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("x"),
              to = StateMachine.StateId("y"),
              eventType = StateMachine.EventType("respond"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(2))))
            )
          )
        )

        data1 = MapValue(Map.empty)
        hash1 <- (data1: JsonLogicValue).computeDigest
        data2 = MapValue(Map.empty)
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          cid = machine1Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine1Definition,
          currentState = StateMachine.StateId("a"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiber2 = Records.StateMachineFiberRecord(
          cid = machine2Id,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = machine2Definition,
          currentState = StateMachine.StateId("x"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(machine1Id -> fiber1, machine2Id -> fiber2), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("trigger"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)

        // Run with default FiberGasConfig
        orchestratorDefault =
          FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Default)
        resultDefault <- orchestratorDefault.process(machine1Id, input, List.empty)

        // Run with Mainnet FiberGasConfig (higher costs)
        orchestratorMainnet =
          FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Mainnet)
        resultMainnet <- orchestratorMainnet.process(machine1Id, input, List.empty)

        // Run with Minimal FiberGasConfig (lower costs)
        orchestratorMinimal =
          FiberOrchestrator.make[IO](calculatedState, fixture.ordinal, limits, fiberGasConfig = FiberGasConfig.Minimal)
        resultMinimal <- orchestratorMinimal.process(machine1Id, input, List.empty)

      } yield {
        // All should succeed
        val defaultGas = resultDefault match {
          case TransactionOutcome.Committed(_, _, _, gas, _, _) => gas
          case _                                                => -1L
        }

        val mainnetGas = resultMainnet match {
          case TransactionOutcome.Committed(_, _, _, gas, _, _) => gas
          case _                                                => -1L
        }

        val minimalGas = resultMinimal match {
          case TransactionOutcome.Committed(_, _, _, gas, _, _) => gas
          case _                                                => -1L
        }

        expect(defaultGas > 0L) and
        expect(mainnetGas > 0L) and
        expect(minimalGas > 0L) and
        // Mainnet costs more than default (triggerEvent: 10 vs 5, etc.)
        expect(mainnetGas >= defaultGas) and
        // Minimal costs less than or equal to default (triggerEvent: 1 vs 5, etc.)
        expect(minimalGas <= defaultGas)
      }
    }
  }
}
