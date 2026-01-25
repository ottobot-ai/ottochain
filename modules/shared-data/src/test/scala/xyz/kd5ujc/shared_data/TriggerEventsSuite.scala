package xyz.kd5ujc.shared_data

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner

import io.circe.parser._
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object TriggerEventsSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("basic trigger: effect triggers event on another machine") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        initiatorCid <- UUIDGen.randomUUID[IO]
        targetCid    <- UUIDGen.randomUUID[IO]

        // Initiator: sends trigger to target on "start"
        initiatorJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "triggered": { "id": { "value": "triggered" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "triggered" },
              "eventType": { "value": "start" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$targetCid",
                    "eventType": "activate",
                    "payload": {
                      "initiator": ["var", "machineId"],
                      "timestamp": 12345
                    }
                  }
                ],
                "status": "triggered"
              },
              "dependencies": []
            }
          ]
        }
        """

        // Target: receives "activate" event
        targetJson = """
        {
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "active": { "id": { "value": "active" }, "isFinal": false }
          },
          "initialState": { "value": "inactive" },
          "transitions": [
            {
              "from": { "value": "inactive" },
              "to": { "value": "active" },
              "eventType": { "value": "activate" },
              "guard": true,
              "effect": [
                ["status", "active"],
                ["activatedBy", { "var": "event.initiator" }],
                ["activatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }
        """

        initiatorDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](initiatorJson))
        targetDef    <- IO.fromEither(decode[StateMachine.StateMachineDefinition](targetJson))

        initiatorData = MapValue(Map("status" -> StrValue("idle")))
        targetData = MapValue(Map("status" -> StrValue("inactive")))

        createInitiator = Updates.CreateStateMachineFiber(initiatorCid, initiatorDef, initiatorData)
        initiatorProof <- registry.generateProofs(createInitiator, Set(Alice))
        stateAfterInitiator <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createInitiator, initiatorProof)
        )

        createTarget = Updates.CreateStateMachineFiber(targetCid, targetDef, targetData)
        targetProof      <- registry.generateProofs(createTarget, Set(Bob))
        stateAfterTarget <- combiner.insert(stateAfterInitiator, Signed(createTarget, targetProof))

        // Send start event to initiator - should trigger target
        startEvent = Updates.ProcessFiberEvent(
          initiatorCid,
          StateMachine.Event(
            StateMachine.EventType("start"),
            MapValue(Map.empty)
          )
        )
        startProof <- registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterTarget, Signed(startEvent, startProof))

        initiator = finalState.calculated.records
          .get(initiatorCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        target = finalState.calculated.records
          .get(targetCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        targetStatus = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetActivatedBy = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("activatedBy").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetActivatedAt = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("activatedAt").collect { case IntValue(t) => t }
            case _           => None
          }
        }

      } yield expect.all(
        initiator.isDefined,
        initiator.map(_.currentState).contains(StateMachine.StateId("triggered")),
        target.isDefined,
        target.map(_.currentState).contains(StateMachine.StateId("active")),
        targetStatus.contains("active"),
        targetActivatedBy.contains(initiatorCid.toString),
        targetActivatedAt.contains(BigInt(12345))
      )
    }
  }

  test("cascading triggers: A triggers B triggers C") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid  <- UUIDGen.randomUUID[IO]
        machine2Cid <- UUIDGen.randomUUID[IO]
        machine3Cid <- UUIDGen.randomUUID[IO]

        // Machine A: triggers B
        machineAJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventType": { "value": "start" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine2Cid",
                    "eventType": "continue",
                    "payload": {
                      "step": 1
                    }
                  }
                ],
                "step": 1
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine B: triggers C
        machineBJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventType": { "value": "continue" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machine3Cid",
                    "eventType": "finish",
                    "payload": {
                      "step": 2
                    }
                  }
                ],
                "step": 2
              },
              "dependencies": []
            }
          ]
        }
        """

        // Machine C: final machine
        machineCJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": true }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "done" },
              "eventType": { "value": "finish" },
              "guard": true,
              "effect": {
                "step": 3
              },
              "dependencies": []
            }
          ]
        }
        """

        machineADef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineAJson))
        machineBDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineBJson))
        machineCDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineCJson))

        initialData = MapValue(Map("step" -> IntValue(0)))

        createA = Updates.CreateStateMachineFiber(machineCid, machineADef, initialData)
        aProof <- registry.generateProofs(createA, Set(Alice))
        stateAfterA <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createA, aProof)
        )

        createB = Updates.CreateStateMachineFiber(machine2Cid, machineBDef, initialData)
        bProof      <- registry.generateProofs(createB, Set(Alice))
        stateAfterB <- combiner.insert(stateAfterA, Signed(createB, bProof))

        createC = Updates.CreateStateMachineFiber(machine3Cid, machineCDef, initialData)
        cProof      <- registry.generateProofs(createC, Set(Alice))
        stateAfterC <- combiner.insert(stateAfterB, Signed(createC, cProof))

        // Send start to A - should cascade to B then C
        startEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("start"),
            MapValue(Map.empty)
          )
        )
        startProof <- registry.generateProofs(startEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterC, Signed(startEvent, startProof))

        machineA = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineB = finalState.calculated.records
          .get(machine2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineC = finalState.calculated.records
          .get(machine3Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        stepA = machineA.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

        stepB = machineB.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

        stepC = machineC.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("step").collect { case IntValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        machineA.isDefined,
        machineA.map(_.currentState).contains(StateMachine.StateId("done")),
        stepA.contains(BigInt(1)),
        machineB.isDefined,
        machineB.map(_.currentState).contains(StateMachine.StateId("done")),
        stepB.contains(BigInt(2)),
        machineC.isDefined,
        machineC.map(_.currentState).contains(StateMachine.StateId("done")),
        stepC.contains(BigInt(3))
      )
    }
  }

  test("trigger with payload: payload expression evaluated with context") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        sourceCid <- UUIDGen.randomUUID[IO]
        targetCid <- UUIDGen.randomUUID[IO]

        // Source: sends computed payload to target
        sourceJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "sent": { "id": { "value": "sent" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "sent" },
              "eventType": { "value": "send" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$targetCid",
                    "eventType": "receive",
                    "payload": {
                      "amount": ["+", ["var", "state.balance"], ["var", "event.amount"]],
                      "sender": ["var", "machineId"],
                      "message": ["var", "event.message"]
                    }
                  }
                ],
                "balance": ["-", ["var", "state.balance"], ["var", "event.amount"]]
              },
              "dependencies": []
            }
          ]
        }
        """

        // Target: receives payload
        targetJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "received": { "id": { "value": "received" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "received" },
              "eventType": { "value": "receive" },
              "guard": true,
              "effect": [
                ["amount", { "var": "event.amount" }],
                ["sender", { "var": "event.sender" }],
                ["message", { "var": "event.message" }]
              ],
              "dependencies": []
            }
          ]
        }
        """

        sourceDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](sourceJson))
        targetDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](targetJson))

        sourceData = MapValue(Map("balance" -> IntValue(1000)))
        targetData = MapValue(Map.empty[String, JsonLogicValue])

        createSource = Updates.CreateStateMachineFiber(sourceCid, sourceDef, sourceData)
        sourceProof <- registry.generateProofs(createSource, Set(Alice))
        stateAfterSource <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createSource, sourceProof)
        )

        createTarget = Updates.CreateStateMachineFiber(targetCid, targetDef, targetData)
        targetProof      <- registry.generateProofs(createTarget, Set(Alice))
        stateAfterTarget <- combiner.insert(stateAfterSource, Signed(createTarget, targetProof))

        // Send event with payload - should compute and pass to target
        sendEvent = Updates.ProcessFiberEvent(
          sourceCid,
          StateMachine.Event(
            StateMachine.EventType("send"),
            MapValue(
              Map(
                "amount"  -> IntValue(250),
                "message" -> StrValue("Hello from source")
              )
            )
          )
        )
        sendProof  <- registry.generateProofs(sendEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterTarget, Signed(sendEvent, sendProof))

        source = finalState.calculated.records
          .get(sourceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        target = finalState.calculated.records
          .get(targetCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        sourceBalance = source.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
            case _           => None
          }
        }

        targetAmount = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("amount").collect { case IntValue(a) => a }
            case _           => None
          }
        }

        targetSender = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("sender").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        targetMessage = target.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("message").collect { case StrValue(m) => m }
            case _           => None
          }
        }

      } yield expect.all(
        source.isDefined,
        source.map(_.currentState).contains(StateMachine.StateId("sent")),
        sourceBalance.contains(BigInt(750)), // 1000 - 250
        target.isDefined,
        target.map(_.currentState).contains(StateMachine.StateId("received")),
        targetAmount.contains(BigInt(1250)), // 1000 + 250 (computed in payload)
        targetSender.contains(sourceCid.toString),
        targetMessage.contains("Hello from source")
      )
    }
  }

  test("failed trigger: target machine not found continues execution") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        sourceCid      <- UUIDGen.randomUUID[IO]
        nonExistentCid <- UUIDGen.randomUUID[IO]

        // Source: triggers non-existent machine but should still complete
        sourceJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "attempted": { "id": { "value": "attempted" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "attempted" },
              "eventType": { "value": "try_trigger" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$nonExistentCid",
                    "eventType": "activate",
                    "payload": {}
                  }
                ],
                "status": "attempted"
              },
              "dependencies": []
            }
          ]
        }
        """

        sourceDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](sourceJson))
        sourceData = MapValue(Map("status" -> StrValue("idle")))

        createSource = Updates.CreateStateMachineFiber(sourceCid, sourceDef, sourceData)
        sourceProof <- registry.generateProofs(createSource, Set(Alice))
        stateAfterSource <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createSource, sourceProof)
        )

        // Send event - should complete even though trigger target doesn't exist
        triggerEvent = Updates.ProcessFiberEvent(
          sourceCid,
          StateMachine.Event(
            StateMachine.EventType("try_trigger"),
            MapValue(Map.empty)
          )
        )
        triggerProof <- registry.generateProofs(triggerEvent, Set(Alice))
        finalState   <- combiner.insert(stateAfterSource, Signed(triggerEvent, triggerProof))

        source = finalState.calculated.records
          .get(sourceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = source.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        source.isDefined,
        source.map(_.currentState).contains(StateMachine.StateId("attempted")),
        status.contains("attempted")
      )
    }
  }

  test("trigger cycle detection: A triggers B triggers A fails") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineACid <- UUIDGen.randomUUID[IO]
        machineBCid <- UUIDGen.randomUUID[IO]

        // Machine A: triggers B with "ping"
        machineAJson = s"""
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
              "eventType": { "value": "ping" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineBCid",
                    "eventType": "pong",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "pinged" },
              "to": { "value": "idle" },
              "eventType": { "value": "ping" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineBCid",
                    "eventType": "pong",
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

        // Machine B: triggers A with "ping" (creates cycle)
        machineBJson = s"""
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
              "eventType": { "value": "pong" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineACid",
                    "eventType": "ping",
                    "payload": {}
                  }
                ],
                "count": { "+": [{ "var": "state.count" }, 1] }
              },
              "dependencies": []
            },
            {
              "from": { "value": "ponged" },
              "to": { "value": "idle" },
              "eventType": { "value": "pong" },
              "guard": true,
              "effect": {
                "_triggers": [
                  {
                    "targetMachineId": "$machineACid",
                    "eventType": "ping",
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

        machineADef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineAJson))
        machineBDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineBJson))

        initialData = MapValue(Map("count" -> IntValue(0)))

        createA = Updates.CreateStateMachineFiber(machineACid, machineADef, initialData)
        aProof <- registry.generateProofs(createA, Set(Alice))
        stateAfterA <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createA, aProof)
        )

        createB = Updates.CreateStateMachineFiber(machineBCid, machineBDef, initialData)
        bProof      <- registry.generateProofs(createB, Set(Alice))
        stateAfterB <- combiner.insert(stateAfterA, Signed(createB, bProof))

        // Send ping to A - should trigger B which tries to trigger A again (cycle)
        pingEvent = Updates.ProcessFiberEvent(
          machineACid,
          StateMachine.Event(
            StateMachine.EventType("ping"),
            MapValue(Map.empty)
          )
        )
        pingProof  <- registry.generateProofs(pingEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterB, Signed(pingEvent, pingProof))

        machineA = finalState.calculated.records
          .get(machineACid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        machineB = finalState.calculated.records
          .get(machineBCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        countA = machineA.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        countB = machineB.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("count").collect { case IntValue(c) => c }
            case _           => None
          }
        }

      } yield expect.all(
        machineA.isDefined,
        machineB.isDefined,
        // Atomic rollback - cycle detected, transaction aborted
        countA.contains(BigInt(0)), // No state changes
        countB.contains(BigInt(0)),
        machineA.map(_.currentState).contains(StateMachine.StateId("idle")), // Original states
        machineB.map(_.currentState).contains(StateMachine.StateId("idle")),
        // Parent fiber should have ExecutionFailed status
        machineA.map(_.lastEventStatus).exists {
          case Records.EventProcessingStatus.ExecutionFailed(_, _, _, _, _) => true
          case _                                                            => false
        }
      )
    }
  }
}
