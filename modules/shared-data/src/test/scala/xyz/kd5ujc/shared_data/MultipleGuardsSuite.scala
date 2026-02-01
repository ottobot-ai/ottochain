package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object MultipleGuardsSuite extends SimpleIOSuite {

  test("first matching guard wins: multiple transitions same event") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Multiple transitions for "process" event with different guards
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "low_priority": { "id": { "value": "low_priority" }, "isFinal": false },
            "medium_priority": { "id": { "value": "medium_priority" }, "isFinal": false },
            "high_priority": { "id": { "value": "high_priority" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "high_priority" },
              "eventName": "process",
              "guard": {
                ">=": [{ "var": "event.priority" }, 80]
              },
              "effect": {
                "level": "high"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "medium_priority" },
              "eventName": "process",
              "guard": {
                ">=": [{ "var": "event.priority" }, 50]
              },
              "effect": {
                "level": "medium"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "low_priority" },
              "eventName": "process",
              "guard": true,
              "effect": {
                "level": "low"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test 1: High priority (>= 80) - should match first guard
        highPriorityEvent = Updates.TransitionStateMachine(
          machineCid,
          "process",
          MapValue(Map("priority" -> IntValue(90))),
          FiberOrdinal.MinValue
        )
        highProof      <- fixture.registry.generateProofs(highPriorityEvent, Set(Alice))
        stateAfterHigh <- combiner.insert(stateAfterCreate, Signed(highPriorityEvent, highProof))

        highMachine = stateAfterHigh.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        highLevel = highMachine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("level").collect { case StrValue(l) => l }
            case _           => None
          }
        }

      } yield expect(highMachine.isDefined) and
      expect(highMachine.map(_.currentState).contains(StateId("high_priority"))) and
      expect(highLevel.contains("high"))
    }
  }

  test("guard evaluation order: earlier guards checked first") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Guards that overlap - should use first matching one
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "result_a": { "id": { "value": "result_a" }, "isFinal": false },
            "result_b": { "id": { "value": "result_b" }, "isFinal": false },
            "result_c": { "id": { "value": "result_c" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "result_a" },
              "eventName": "check",
              "guard": {
                ">=": [{ "var": "event.value" }, 10]
              },
              "effect": {
                "result": "a",
                "message": "matched first guard (>= 10)"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "result_b" },
              "eventName": "check",
              "guard": {
                ">=": [{ "var": "event.value" }, 5]
              },
              "effect": {
                "result": "b",
                "message": "matched second guard (>= 5)"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "result_c" },
              "eventName": "check",
              "guard": true,
              "effect": {
                "result": "c",
                "message": "matched fallback guard"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test with value 15 - matches both first and second guards, should use first
        checkEvent = Updates.TransitionStateMachine(
          machineCid,
          "check",
          MapValue(Map("value" -> IntValue(15))),
          FiberOrdinal.MinValue
        )
        checkProof <- fixture.registry.generateProofs(checkEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterCreate, Signed(checkEvent, checkProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        result = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("result").collect { case StrValue(r) => r }
            case _           => None
          }
        }

        message = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("message").collect { case StrValue(m) => m }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("result_a"))) and
      expect(result.contains("a")) and
      expect(message.contains("matched first guard (>= 10)"))
    }
  }

  test("no guard matches: all transitions evaluated but none match") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // All guards have specific conditions - none will match if value is too low
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "tier1": { "id": { "value": "tier1" }, "isFinal": false },
            "tier2": { "id": { "value": "tier2" }, "isFinal": false },
            "tier3": { "id": { "value": "tier3" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "tier1" },
              "eventName": "upgrade",
              "guard": {
                ">=": [{ "var": "event.amount" }, 1000]
              },
              "effect": {
                "tier": 1
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "tier2" },
              "eventName": "upgrade",
              "guard": {
                ">=": [{ "var": "event.amount" }, 500]
              },
              "effect": {
                "tier": 2
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "tier3" },
              "eventName": "upgrade",
              "guard": {
                ">=": [{ "var": "event.amount" }, 100]
              },
              "effect": {
                "tier": 3
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map("tier" -> IntValue(0)))

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send upgrade with insufficient amount - no guard should match
        upgradeEvent = Updates.TransitionStateMachine(
          machineCid,
          "upgrade",
          MapValue(Map("amount" -> IntValue(50))),
          FiberOrdinal.MinValue
        )
        upgradeProof <- fixture.registry.generateProofs(upgradeEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterCreate, Signed(upgradeEvent, upgradeProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        tier = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("tier").collect { case IntValue(t) => t }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      // Should remain in idle state since no guard matched
      expect(machine.map(_.currentState).contains(StateId("idle"))) and
      // Tier should remain 0 (no effect applied)
      expect(tier.contains(BigInt(0)))
    }
  }

  test("multiple guards with complex conditions") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Guards with AND/OR conditions
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "premium": { "id": { "value": "premium" }, "isFinal": false },
            "standard": { "id": { "value": "standard" }, "isFinal": false },
            "basic": { "id": { "value": "basic" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "premium" },
              "eventName": "qualify",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.age" }, 25] },
                  { ">=": [{ "var": "event.income" }, 100000] },
                  { "===": [{ "var": "event.verified" }, true] }
                ]
              },
              "effect": {
                "level": "premium"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "standard" },
              "eventName": "qualify",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.age" }, 18] },
                  { ">=": [{ "var": "event.income" }, 50000] }
                ]
              },
              "effect": {
                "level": "standard"
              },
              "dependencies": []
            },
            {
              "from": { "value": "idle" },
              "to": { "value": "basic" },
              "eventName": "qualify",
              "guard": {
                ">=": [{ "var": "event.age" }, 18]
              },
              "effect": {
                "level": "basic"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test 1: Qualifies for premium (all conditions met)
        premiumEvent = Updates.TransitionStateMachine(
          machineCid,
          "qualify",
          MapValue(
            Map(
              "age"      -> IntValue(30),
              "income"   -> IntValue(150000),
              "verified" -> BoolValue(true)
            )
          ),
          FiberOrdinal.MinValue
        )
        premiumProof <- fixture.registry.generateProofs(premiumEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterCreate, Signed(premiumEvent, premiumProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        level = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("level").collect { case StrValue(l) => l }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("premium"))) and
      expect(level.contains("premium"))
    }
  }

  test("guard with state and event conditions") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Guards that check both state and event
        machineJson = """
        {
          "states": {
            "locked": { "id": { "value": "locked" }, "isFinal": false },
            "unlocked": { "id": { "value": "unlocked" }, "isFinal": false },
            "admin_unlocked": { "id": { "value": "admin_unlocked" }, "isFinal": false }
          },
          "initialState": { "value": "locked" },
          "transitions": [
            {
              "from": { "value": "locked" },
              "to": { "value": "admin_unlocked" },
              "eventName": "unlock",
              "guard": {
                "===": [{ "var": "event.role" }, "admin"]
              },
              "effect": {
                "unlockedBy": "admin",
                "attempts": { "var": "state.attempts" }
              },
              "dependencies": []
            },
            {
              "from": { "value": "locked" },
              "to": { "value": "unlocked" },
              "eventName": "unlock",
              "guard": {
                "and": [
                  { "===": [{ "var": "event.code" }, { "var": "state.secretCode" }] },
                  { "<": [{ "var": "state.attempts" }, 3] }
                ]
              },
              "effect": {
                "unlockedBy": "code",
                "attempts": { "+": [{ "var": "state.attempts" }, 1] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachineDefinition](machineJson))
        initialData = MapValue(
          Map(
            "secretCode" -> IntValue(1234),
            "attempts"   -> IntValue(0)
          )
        )

        createMachine = Updates.CreateStateMachine(machineCid, machineDef, initialData)
        machineProof <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test: Admin unlock (first guard should match)
        unlockEvent = Updates.TransitionStateMachine(
          machineCid,
          "unlock",
          MapValue(
            Map(
              "role" -> StrValue("admin"),
              "code" -> IntValue(0)
            )
          ),
          FiberOrdinal.MinValue
        )
        unlockProof <- fixture.registry.generateProofs(unlockEvent, Set(Alice))
        finalState  <- combiner.insert(stateAfterCreate, Signed(unlockEvent, unlockProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        unlockedBy = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("unlockedBy").collect { case StrValue(u) => u }
            case _           => None
          }
        }

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateId("admin_unlocked"))) and
      expect(unlockedBy.contains("admin"))
    }
  }
}
