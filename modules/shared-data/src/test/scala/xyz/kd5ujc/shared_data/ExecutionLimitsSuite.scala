package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object ExecutionLimitsSuite extends SimpleIOSuite {

  test("depth exceeded: nested triggers hit maxDepth") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machine1Cid <- UUIDGen.randomUUID[IO]
        machine2Cid <- UUIDGen.randomUUID[IO]

        // Machine 1: triggers machine 2 on "ping"
        machine1Json = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "pinged": { "id": { "value": "pinged" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "pinged" },
              "eventName": "ping",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine2Cid",
                    "eventName": "pong",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine 2: triggers machine 1 on "pong"
        machine2Json = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "ponged": { "id": { "value": "ponged" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "ponged" },
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine1Cid",
                    "eventName": "ping",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "ponged" },
              "to": { "value": "idle" },
              "eventName": "pong",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine1Cid",
                    "eventName": "ping",
                    "payload": { "var": "state" }
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machine1Def <- IO.fromEither(decode[StateMachineDefinition](machine1Json))
        machine2Def <- IO.fromEither(decode[StateMachineDefinition](machine2Json))

        initialData = MapValue(Map("count" -> IntValue(0)))

        createMachine1 = Updates.CreateStateMachine(machine1Cid, machine1Def, initialData)
        machine1Proof <- fixture.registry.generateProofs(createMachine1, Set(Alice))
        stateAfterMachine1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine1, machine1Proof)
        )

        createMachine2 = Updates.CreateStateMachine(machine2Cid, machine2Def, initialData)
        machine2Proof      <- fixture.registry.generateProofs(createMachine2, Set(Alice))
        stateAfterMachine2 <- combiner.insert(stateAfterMachine1, Signed(createMachine2, machine2Proof))

        // Send initial ping - should hit depth limit due to ping-pong loop
        pingEvent = Updates.TransitionStateMachine(
          machine1Cid,
          "ping",
          MapValue(Map.empty)
        )
        pingProof  <- fixture.registry.generateProofs(pingEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterMachine2, Signed(pingEvent, pingProof))

        machine1 = finalState.calculated.stateMachines
          .get(machine1Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machine2 = finalState.calculated.stateMachines
          .get(machine2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Atomic rollback: transaction should have aborted, no state changes
        machine1Count = machine1.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        machine2Count = machine2.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      expect(machine1Count.contains(BigInt(0))) and
      expect(machine2Count.contains(BigInt(0))) and
      expect(machine1.map(_.currentState).contains(StateId("idle"))) and
      expect(machine2.map(_.currentState).contains(StateId("idle"))) and
      expect(machine1.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }

  test("gas exhausted: expensive computation hits maxGas") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Machine with many self-triggering transitions to exceed gas limit
        // Each transition costs ~35 gas (10 guard + 20 effect + 5 trigger)
        // With 30+ transitions, we'll exceed maxGas=1000
        machineJson = s"""
        {
          "states": {
            "s0": { "id": { "value": "s0" }, "isFinal": false },
            "s1": { "id": { "value": "s1" }, "isFinal": false },
            "s2": { "id": { "value": "s2" }, "isFinal": false },
            "s3": { "id": { "value": "s3" }, "isFinal": false },
            "s4": { "id": { "value": "s4" }, "isFinal": false },
            "s5": { "id": { "value": "s5" }, "isFinal": false },
            "s6": { "id": { "value": "s6" }, "isFinal": false },
            "s7": { "id": { "value": "s7" }, "isFinal": false },
            "s8": { "id": { "value": "s8" }, "isFinal": false },
            "s9": { "id": { "value": "s9" }, "isFinal": false },
            "s10": { "id": { "value": "s10" }, "isFinal": false },
            "s11": { "id": { "value": "s11" }, "isFinal": false },
            "s12": { "id": { "value": "s12" }, "isFinal": false },
            "s13": { "id": { "value": "s13" }, "isFinal": false },
            "s14": { "id": { "value": "s14" }, "isFinal": false },
            "s15": { "id": { "value": "s15" }, "isFinal": false },
            "s16": { "id": { "value": "s16" }, "isFinal": false },
            "s17": { "id": { "value": "s17" }, "isFinal": false },
            "s18": { "id": { "value": "s18" }, "isFinal": false },
            "s19": { "id": { "value": "s19" }, "isFinal": false },
            "s20": { "id": { "value": "s20" }, "isFinal": false },
            "s21": { "id": { "value": "s21" }, "isFinal": false },
            "s22": { "id": { "value": "s22" }, "isFinal": false },
            "s23": { "id": { "value": "s23" }, "isFinal": false },
            "s24": { "id": { "value": "s24" }, "isFinal": false },
            "s25": { "id": { "value": "s25" }, "isFinal": false },
            "s26": { "id": { "value": "s26" }, "isFinal": false },
            "s27": { "id": { "value": "s27" }, "isFinal": false },
            "s28": { "id": { "value": "s28" }, "isFinal": false },
            "s29": { "id": { "value": "s29" }, "isFinal": false },
            "s30": { "id": { "value": "s30" }, "isFinal": true }
          },
          "initialState": { "value": "s0" },
          "transitions": [
            { "from": { "value": "s0" }, "to": { "value": "s1" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 1 }, "dependencies": [] },
            { "from": { "value": "s1" }, "to": { "value": "s2" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 2 }, "dependencies": [] },
            { "from": { "value": "s2" }, "to": { "value": "s3" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 3 }, "dependencies": [] },
            { "from": { "value": "s3" }, "to": { "value": "s4" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 4 }, "dependencies": [] },
            { "from": { "value": "s4" }, "to": { "value": "s5" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 5 }, "dependencies": [] },
            { "from": { "value": "s5" }, "to": { "value": "s6" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 6 }, "dependencies": [] },
            { "from": { "value": "s6" }, "to": { "value": "s7" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 7 }, "dependencies": [] },
            { "from": { "value": "s7" }, "to": { "value": "s8" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 8 }, "dependencies": [] },
            { "from": { "value": "s8" }, "to": { "value": "s9" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 9 }, "dependencies": [] },
            { "from": { "value": "s9" }, "to": { "value": "s10" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 10 }, "dependencies": [] },
            { "from": { "value": "s10" }, "to": { "value": "s11" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 11 }, "dependencies": [] },
            { "from": { "value": "s11" }, "to": { "value": "s12" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 12 }, "dependencies": [] },
            { "from": { "value": "s12" }, "to": { "value": "s13" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 13 }, "dependencies": [] },
            { "from": { "value": "s13" }, "to": { "value": "s14" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 14 }, "dependencies": [] },
            { "from": { "value": "s14" }, "to": { "value": "s15" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 15 }, "dependencies": [] },
            { "from": { "value": "s15" }, "to": { "value": "s16" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 16 }, "dependencies": [] },
            { "from": { "value": "s16" }, "to": { "value": "s17" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 17 }, "dependencies": [] },
            { "from": { "value": "s17" }, "to": { "value": "s18" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 18 }, "dependencies": [] },
            { "from": { "value": "s18" }, "to": { "value": "s19" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 19 }, "dependencies": [] },
            { "from": { "value": "s19" }, "to": { "value": "s20" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 20 }, "dependencies": [] },
            { "from": { "value": "s20" }, "to": { "value": "s21" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 21 }, "dependencies": [] },
            { "from": { "value": "s21" }, "to": { "value": "s22" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 22 }, "dependencies": [] },
            { "from": { "value": "s22" }, "to": { "value": "s23" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 23 }, "dependencies": [] },
            { "from": { "value": "s23" }, "to": { "value": "s24" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 24 }, "dependencies": [] },
            { "from": { "value": "s24" }, "to": { "value": "s25" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 25 }, "dependencies": [] },
            { "from": { "value": "s25" }, "to": { "value": "s26" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 26 }, "dependencies": [] },
            { "from": { "value": "s26" }, "to": { "value": "s27" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 27 }, "dependencies": [] },
            { "from": { "value": "s27" }, "to": { "value": "s28" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 28 }, "dependencies": [] },
            { "from": { "value": "s28" }, "to": { "value": "s29" }, "eventName": "advance", "guard": true, "effect": { "_triggers": [{ "targetMachineId": "$machineCid", "eventName": "advance", "payload": {} }], "step": 29 }, "dependencies": [] },
            { "from": { "value": "s29" }, "to": { "value": "s30" }, "eventName": "advance", "guard": true, "effect": { "step": 30 }, "dependencies": [] }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("step" -> IntValue(0)))

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send advance event - should trigger chain but stop when gas exhausted
        advanceEvent = Updates.TransitionStateMachine(
          machineCid,
          "advance",
          MapValue(Map.empty)
        )
        advanceProof <- fixture.registry.generateProofs(advanceEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterCreate, Signed(advanceEvent, advanceProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        step = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      // Atomic rollback - transaction aborted due to gas exhaustion
      expect(step.contains(BigInt(0))) and // No state changes
      expect(machine.map(_.currentState).contains(StateId("s0"))) and // Original state
      expect(machine.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(machine.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }

  test("cycle detection: same event processed twice on same fiber") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Machine that triggers itself with the same event (should be detected as cycle)
        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "processing": { "id": { "value": "processing" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "processing" },
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineCid",
                    "eventName": "start",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "processing" },
              "eventName": "start",
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineCid",
                    "eventName": "start",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("count" -> IntValue(0)))

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send start event - should detect cycle when trigger tries to send "start" again
        startEvent = Updates.TransitionStateMachine(
          machineCid,
          "start",
          MapValue(Map.empty)
        )
        startProof <- fixture.registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterCreate, Signed(startEvent, startProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        count = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      // Atomic rollback - cycle detected, transaction aborted
      expect(count.contains(BigInt(0))) and // No state changes
      expect(machine.map(_.currentState).contains(StateId("idle"))) and // Original state
      expect(machine.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(machine.exists(_.lastReceipt.exists(r => !r.success)))
    }
  }
}
