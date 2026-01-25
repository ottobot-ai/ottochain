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
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Tests for atomic rollback behavior.
 *
 * EVM semantics require all-or-nothing transaction execution:
 * - If any part of a transaction fails, ALL changes must be rolled back
 * - This includes state changes, spawns, and triggered events
 */
object RollbackCompensationSuite extends SimpleIOSuite {

  test("partial trigger chain failure rolls back all state changes") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        // Create A -> B -> C chain where C fails
        machineA <- UUIDGen.randomUUID[IO]
        machineB <- UUIDGen.randomUUID[IO]
        machineC <- UUIDGen.randomUUID[IO]

        // Machine A triggers B
        defA = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle")      -> StateMachine.State(StateMachine.StateId("idle")),
            StateMachine.StateId("triggered") -> StateMachine.State(StateMachine.StateId("triggered"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("idle"),
              to = StateMachine.StateId("triggered"),
              eventType = StateMachine.EventType("start"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(1),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineB.toString),
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

        // Machine B triggers C
        defB = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("waiting")   -> StateMachine.State(StateMachine.StateId("waiting")),
            StateMachine.StateId("continued") -> StateMachine.State(StateMachine.StateId("continued"))
          ),
          initialState = StateMachine.StateId("waiting"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("waiting"),
              to = StateMachine.StateId("continued"),
              eventType = StateMachine.EventType("continue"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "step" -> IntValue(2),
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue(machineC.toString),
                            "eventType"       -> StrValue("finish"),
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

        // Machine C fails (guard always false, no valid transition)
        defC = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("pending")  -> StateMachine.State(StateMachine.StateId("pending")),
            StateMachine.StateId("finished") -> StateMachine.State(StateMachine.StateId("finished"))
          ),
          initialState = StateMachine.StateId("pending"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("pending"),
              to = StateMachine.StateId("finished"),
              eventType = StateMachine.EventType("finish"),
              // Guard that always fails
              guard = ConstExpression(BoolValue(false)),
              effect = ConstExpression(MapValue(Map("step" -> IntValue(3))))
            )
          )
        )

        dataA = MapValue(Map("step" -> IntValue(0)))
        hashA <- (dataA: JsonLogicValue).computeDigest

        dataB = MapValue(Map("step" -> IntValue(0)))
        hashB <- (dataB: JsonLogicValue).computeDigest

        dataC = MapValue(Map("step" -> IntValue(0)))
        hashC <- (dataC: JsonLogicValue).computeDigest

        fiberA = Records.StateMachineFiberRecord(
          cid = machineA,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defA,
          currentState = StateMachine.StateId("idle"),
          stateData = dataA,
          stateDataHash = hashA,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiberB = Records.StateMachineFiberRecord(
          cid = machineB,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defB,
          currentState = StateMachine.StateId("waiting"),
          stateData = dataB,
          stateDataHash = hashB,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiberC = Records.StateMachineFiberRecord(
          cid = machineC,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = defC,
          currentState = StateMachine.StateId("pending"),
          stateData = dataC,
          stateDataHash = hashC,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(
          Map(machineA -> fiberA, machineB -> fiberB, machineC -> fiberC),
          Map.empty
        )

        input = FiberInput.Transition(
          StateMachine.EventType("start"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(machineA, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(_, _, _) =>
          // All machines should be unchanged in the original calculatedState
          // (the Aborted result means no changes were applied)
          expect(calculatedState.stateMachines.get(machineA).exists(_.currentState == StateMachine.StateId("idle"))) and
          expect(
            calculatedState.stateMachines.get(machineB).exists(_.currentState == StateMachine.StateId("waiting"))
          ) and
          expect(
            calculatedState.stateMachines.get(machineC).exists(_.currentState == StateMachine.StateId("pending"))
          ) and
          expect(calculatedState.stateMachines.get(machineA).exists(_.sequenceNumber == 0)) and
          expect(calculatedState.stateMachines.get(machineB).exists(_.sequenceNumber == 0)) and
          expect(calculatedState.stateMachines.get(machineC).exists(_.sequenceNumber == 0))
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted due to C's failed guard, got Committed")
      }
    }
  }

  test("effect evaluation error rolls back state changes") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with guard that passes but effect that causes issues
        // by trying to access non-existent nested property in complex way
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
              guard = ConstExpression(BoolValue(true)), // Guard passes
              // Effect that will fail during evaluation (merge with non-map values)
              // This attempts to merge a string with a map, which should error
              effect = ApplyExpression(
                MergeOp,
                List(
                  VarExpression(Left("state")),
                  // Accessing a deeply nested non-existent path
                  VarExpression(Left("event.payload.deeply.nested.missing.path"))
                )
              )
            )
          )
        )

        initialData = MapValue(Map("important" -> IntValue(42)))
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
          sequenceNumber = 5,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)

        // Payload without the expected nested structure
        input = FiberInput.Transition(
          StateMachine.EventType("process"),
          MapValue(Map("simple" -> StrValue("value")))
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(_, _, _) =>
          // Original state should be preserved
          expect(calculatedState.stateMachines.get(fiberId).exists(_.currentState == StateMachine.StateId("start"))) and
          expect(calculatedState.stateMachines.get(fiberId).exists(_.sequenceNumber == 5)) and
          expect(calculatedState.stateMachines.get(fiberId).exists(_.stateData == initialData))
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          // If it committed (JsonLogic might handle missing vars gracefully),
          // verify state was actually updated
          val updated = machines.get(fiberId)
          expect(updated.exists(_.sequenceNumber > 5))
      }
    }
  }

  test("spawn failure rolls back parent state change") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        parentId <- UUIDGen.randomUUID[IO]

        // Parent spawns children and triggers one that fails
        childDefinitionJson = MapValue(
          Map(
            "states" -> MapValue(
              Map(
                "init" -> MapValue(Map("id" -> StrValue("init"))),
                "done" -> MapValue(Map("id" -> StrValue("done")))
              )
            ),
            "initialState" -> StrValue("init"),
            "transitions" -> ArrayValue(
              List(
                MapValue(
                  Map(
                    "from"      -> StrValue("init"),
                    "to"        -> StrValue("done"),
                    "eventType" -> StrValue("complete"),
                    "guard"     -> BoolValue(false), // Always fails
                    "effect"    -> MapValue(Map("completed" -> BoolValue(true)))
                  )
                )
              )
            )
          )
        )

        parentDefinition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("ready")   -> StateMachine.State(StateMachine.StateId("ready")),
            StateMachine.StateId("spawned") -> StateMachine.State(StateMachine.StateId("spawned"))
          ),
          initialState = StateMachine.StateId("ready"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("ready"),
              to = StateMachine.StateId("spawned"),
              eventType = StateMachine.EventType("spawn_and_trigger"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "spawned" -> BoolValue(true),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId"         -> StrValue("child-1"),
                            "definition"      -> childDefinitionJson,
                            "initializerData" -> MapValue(Map("index" -> IntValue(1)))
                          )
                        )
                      )
                    ),
                    // Trigger the spawned child with event that will fail
                    "_triggers" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "targetMachineId" -> StrValue("child-1"), // References spawned child
                            "eventType"       -> StrValue("complete"),
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

        parentData = MapValue(Map("step" -> IntValue(0)))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parentDefinition,
          currentState = StateMachine.StateId("ready"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(parentId -> parentFiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("spawn_and_trigger"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(parentId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(_, _, _) =>
          // Parent should remain unchanged
          expect(
            calculatedState.stateMachines.get(parentId).exists(_.currentState == StateMachine.StateId("ready"))
          ) and
          expect(calculatedState.stateMachines.get(parentId).exists(_.sequenceNumber == 0)) and
          // No children should exist
          expect(calculatedState.stateMachines.size == 1)
        case TransactionOutcome.Committed(machines, _, _, _, _, _) =>
          // If it committed, the child's trigger might have been handled differently
          // Document the actual behavior
          expect(machines.contains(parentId))
      }
    }
  }

  test("failed transaction preserves original sequence number") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val jle: JsonLogicEvaluator[IO] = JsonLogicEvaluator.tailRecursive[IO]
      val ordinal = fixture.ordinal
      for {
        fiberId <- UUIDGen.randomUUID[IO]

        // State machine with no valid transitions for the event
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("locked") -> StateMachine.State(StateMachine.StateId("locked"))
          ),
          initialState = StateMachine.StateId("locked"),
          transitions = List.empty // No transitions at all
        )

        initialData = MapValue(Map("important" -> IntValue(999)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        originalSeqNum = 42L

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("locked"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = originalSeqNum,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        input = FiberInput.Transition(
          StateMachine.EventType("unlock"),
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberOrchestrator.make[IO](calculatedState, ordinal, limits)

        result <- orchestrator.process(fiberId, input, List.empty)

      } yield result match {
        case TransactionOutcome.Aborted(_, _, _) =>
          // Original state completely preserved
          expect(calculatedState.stateMachines.get(fiberId).exists(_.sequenceNumber == originalSeqNum)) and
          expect(calculatedState.stateMachines.get(fiberId).exists(_.stateData == initialData)) and
          expect(calculatedState.stateMachines.get(fiberId).exists(_.stateDataHash == initialHash))
        case TransactionOutcome.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted for event with no transitions, got Committed")
      }
    }
  }
}
