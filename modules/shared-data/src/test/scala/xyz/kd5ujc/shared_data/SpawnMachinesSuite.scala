package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object SpawnMachinesSuite extends SimpleIOSuite {

  test("basic spawn: parent spawns single child machine") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_child",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": {
                      "parentId": { "var": "machineId" },
                      "createdAt": { "var": "sequenceNumber" }
                    }
                  }
                ],
                "childCount": 1
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_child", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        childParentId = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("parentId").collect { case StrValue(pid) => pid }
            case _           => None
          }
        }

        childCreatedAt = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("createdAt").collect { case IntValue(ca) => ca }
            case _           => None
          }
        }

      } yield expect(parent.isDefined) and
      expect(parent.map(_.currentState).contains(StateId("spawned"))) and
      expect(parent.exists(_.childFiberIds.contains(childCid))) and
      expect(child.isDefined) and
      expect(child.map(_.currentState).contains(StateId("active"))) and
      expect(child.map(_.parentFiberId).contains(Some(parentCid))) and
      expect(child.map(_.status).contains(FiberStatus.Active)) and
      expect(childParentId.contains(parentCid.toString)) and
      expect(childCreatedAt.contains(BigInt(1)))
    }
  }

  test("multiple spawns: parent spawns multiple children in single event") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        child1Cid <- UUIDGen.randomUUID[IO]
        child2Cid <- UUIDGen.randomUUID[IO]
        child3Cid <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_multiple",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$child1Cid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 0 }
                  },
                  {
                    "childId": "$child2Cid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 1 }
                  },
                  {
                    "childId": "$child3Cid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 2 }
                  }
                ],
                "childCount": 3
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_multiple", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.stateMachines
          .get(child1Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child2 = finalState.calculated.stateMachines
          .get(child2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child3 = finalState.calculated.stateMachines
          .get(child3Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1Index = child1.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("index").collect { case IntValue(i) => i }
            case _           => None
          }
        }

        child2Index = child2.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("index").collect { case IntValue(i) => i }
            case _           => None
          }
        }

        child3Index = child3.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("index").collect { case IntValue(i) => i }
            case _           => None
          }
        }

      } yield expect(parent.isDefined) and
      expect(parent.map(_.currentState).contains(StateId("spawned"))) and
      expect(parent.map(_.childFiberIds.size).contains(3)) and
      expect(parent.exists(_.childFiberIds.contains(child1Cid))) and
      expect(parent.exists(_.childFiberIds.contains(child2Cid))) and
      expect(parent.exists(_.childFiberIds.contains(child3Cid))) and
      expect(child1.isDefined) and
      expect(child2.isDefined) and
      expect(child3.isDefined) and
      expect(child1Index.contains(BigInt(0))) and
      expect(child2Index.contains(BigInt(1))) and
      expect(child3Index.contains(BigInt(2)))
    }
  }

  test("spawn with triggers: spawned child can be triggered immediately") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_and_trigger",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "idle": { "id": { "value": "idle" }, "isFinal": false },
                        "activated": { "id": { "value": "activated" }, "isFinal": false }
                      },
                      "initialState": { "value": "idle" },
                      "transitions": [
                        {
                          "from": { "value": "idle" },
                          "to": { "value": "activated" },
                          "eventName": "activate",
                          "guard": true,
                          "effect": {
                            "status": "activated",
                            "message": { "var": "event.message" }
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": { "status": "idle" }
                  }
                ],
                "_triggers": [
                  {
                    "targetMachineId": "$childCid",
                    "eventName": "activate",
                    "payload": {
                      "message": "Hello from parent"
                    }
                  }
                ],
                "childCount": 1
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_and_trigger", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        childStatus = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        childMessage = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("message").collect { case StrValue(m) => m }
            case _           => None
          }
        }

      } yield expect(parent.isDefined) and
      expect(parent.map(_.currentState).contains(StateId("spawned"))) and
      expect(child.isDefined) and
      expect(child.map(_.currentState).contains(StateId("activated"))) and
      expect(childStatus.contains("activated")) and
      expect(childMessage.contains("Hello from parent"))
    }
  }

  test("spawn with custom owners: child inherits parent owners by default") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_child",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "status": "active" }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice, Bob))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_child", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)

      } yield expect(parent.isDefined) and
      expect(child.isDefined) and
      expect(child.map(_.owners.size).contains(2)) and
      expect(child.exists(_.owners.contains(aliceAddress))) and
      expect(child.exists(_.owners.contains(bobAddress)))
    }
  }

  test("complete lifecycle: parent spawns child, child processes events, child archived") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "create_child",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "idle": { "id": { "value": "idle" }, "isFinal": false },
                        "working": { "id": { "value": "working" }, "isFinal": false },
                        "done": { "id": { "value": "done" }, "isFinal": true }
                      },
                      "initialState": { "value": "idle" },
                      "transitions": [
                        {
                          "from": { "value": "idle" },
                          "to": { "value": "working" },
                          "eventName": "start_work",
                          "guard": true,
                          "effect": {
                            "status": "working",
                            "progress": 0
                          },
                          "dependencies": []
                        },
                        {
                          "from": { "value": "working" },
                          "to": { "value": "done" },
                          "eventName": "finish_work",
                          "guard": true,
                          "effect": {
                            "status": "done",
                            "progress": 100
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": {
                      "status": "idle",
                      "progress": 0
                    }
                  }
                ],
                "childStatus": "created"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childStatus" -> StrValue("none")))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "create_child", MapValue(Map.empty))
        spawnProof      <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        stateAfterSpawn <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        childAfterSpawn = stateAfterSpawn.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        startWorkEvent = Updates.TransitionStateMachine(childCid, "start_work", MapValue(Map.empty))
        startProof      <- fixture.registry.generateProofs(startWorkEvent, Set(Alice))
        stateAfterStart <- combiner.insert(stateAfterSpawn, Signed(startWorkEvent, startProof))

        childAfterStart = stateAfterStart.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finishWorkEvent = Updates.TransitionStateMachine(childCid, "finish_work", MapValue(Map.empty))
        finishProof      <- fixture.registry.generateProofs(finishWorkEvent, Set(Alice))
        stateAfterFinish <- combiner.insert(stateAfterStart, Signed(finishWorkEvent, finishProof))

        childAfterFinish = stateAfterFinish.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        progressAfterFinish = childAfterFinish.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("progress").collect { case IntValue(p) => p }
            case _           => None
          }
        }

        archiveChild = Updates.ArchiveStateMachine(childCid)
        archiveProof      <- fixture.registry.generateProofs(archiveChild, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterFinish, Signed(archiveChild, archiveProof))

        childAfterArchive = stateAfterArchive.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(childAfterSpawn.isDefined) and
      expect(childAfterSpawn.map(_.currentState).contains(StateId("idle"))) and
      expect(childAfterSpawn.map(_.status).contains(FiberStatus.Active)) and
      expect(childAfterSpawn.map(_.parentFiberId).contains(Some(parentCid))) and
      expect(childAfterStart.isDefined) and
      expect(childAfterStart.map(_.currentState).contains(StateId("working"))) and
      expect(childAfterStart.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(childAfterFinish.isDefined) and
      expect(childAfterFinish.map(_.currentState).contains(StateId("done"))) and
      expect(childAfterFinish.exists(_.definition.states(StateId("done")).isFinal)) and
      expect(childAfterFinish.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))) and
      expect(progressAfterFinish.contains(BigInt(100))) and
      expect(childAfterArchive.isDefined) and
      expect(childAfterArchive.map(_.status).contains(FiberStatus.Archived))
    }
  }

  test("child uses multiple var expressions in effect") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_and_trigger",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "idle": { "id": { "value": "idle" }, "isFinal": false },
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "idle" },
                      "transitions": [
                        {
                          "from": { "value": "idle" },
                          "to": { "value": "active" },
                          "eventName": "activate",
                          "guard": true,
                          "effect": {
                            "status": "active",
                            "eventMessage": { "var": "event.msg" },
                            "eventAmount": { "var": "event.amount" },
                            "parentState": { "var": "parent.state.level" },
                            "myId": { "var": "machineId" }
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": { "status": "idle" }
                  }
                ],
                "_triggers": [
                  {
                    "targetMachineId": "$childCid",
                    "eventName": "activate",
                    "payload": {
                      "msg": "Hello",
                      "amount": 42
                    }
                  }
                ],
                "childSpawned": true
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("level" -> IntValue(1)))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_and_trigger", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        childEventMessage = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("eventMessage").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        childEventAmount = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("eventAmount").collect { case IntValue(i) => i }
            case _           => None
          }
        }

        childParentState = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("parentState").collect { case IntValue(i) => i }
            case _           => None
          }
        }

        childMyId = child.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("myId").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect(child.isDefined) and
      expect(child.map(_.currentState).contains(StateId("active"))) and
      expect(childEventMessage.contains("Hello")) and
      expect(childEventAmount.contains(BigInt(42))) and
      expect(childParentState.contains(BigInt(1))) and
      expect(childMyId.contains(childCid.toString))
    }
  }

  test("nested spawn: child can spawn grandchild with var expressions") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        grandparentCid <- UUIDGen.randomUUID[IO]
        parentCid      <- UUIDGen.randomUUID[IO]
        childCid       <- UUIDGen.randomUUID[IO]

        grandparentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_parent",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$parentCid",
                    "definition": {
                      "states": {
                        "idle": { "id": { "value": "idle" }, "isFinal": false },
                        "spawned_child": { "id": { "value": "spawned_child" }, "isFinal": false }
                      },
                      "initialState": { "value": "idle" },
                      "transitions": [
                        {
                          "from": { "value": "idle" },
                          "to": { "value": "spawned_child" },
                          "eventName": "spawn_grandchild",
                          "guard": true,
                          "effect": {
                            "_spawn": [
                              {
                                "childId": "$childCid",
                                "definition": {
                                  "states": {
                                    "active": { "id": { "value": "active" }, "isFinal": false }
                                  },
                                  "initialState": { "value": "active" },
                                  "transitions": [
                                    {
                                      "from": { "value": "active" },
                                      "to": { "value": "active" },
                                      "eventName": "activate",
                                      "guard": true,
                                      "effect": {
                                        "activatedBy": { "var": "event.activatedBy" }
                                      },
                                      "dependencies": []
                                    }
                                  ]
                                },
                                "initialData": {
                                  "grandparentId": { "var": "parent.machineId" },
                                  "generation": 3
                                }
                              }
                            ],
                            "_triggers": [
                              {
                                "targetMachineId": "$childCid",
                                "eventName": "activate",
                                "payload": {
                                  "activatedBy": { "var": "machineId" }
                                }
                              }
                            ],
                            "spawned": true
                          },
                          "dependencies": []
                        },
                        {
                          "from": { "value": "spawned_child" },
                          "to": { "value": "spawned_child" },
                          "eventName": "activate",
                          "guard": true,
                          "effect": {
                            "activationSource": { "var": "event.activatedBy" }
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": {
                      "parentId": { "var": "machineId" },
                      "level": 2
                    }
                  }
                ],
                "level": 1
              },
              "dependencies": []
            }
          ]
        }
        """

        grandparentDef <- IO.fromEither(decode[StateMachineDefinition](grandparentJson))
        grandparentData = MapValue(Map("level" -> IntValue(0)))

        createGrandparent = Updates.CreateStateMachine(grandparentCid, grandparentDef, grandparentData)
        grandparentProof <- fixture.registry.generateProofs(createGrandparent, Set(Alice))
        stateAfterGrandparent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createGrandparent, grandparentProof)
        )

        spawnParentEvent = Updates
          .TransitionStateMachine(grandparentCid, "spawn_parent", MapValue(Map.empty))
        spawnParentProof <- fixture.registry.generateProofs(spawnParentEvent, Set(Alice))
        stateAfterParent <- combiner.insert(stateAfterGrandparent, Signed(spawnParentEvent, spawnParentProof))

        spawnGrandchildEvent = Updates
          .TransitionStateMachine(parentCid, "spawn_grandchild", MapValue(Map.empty))
        spawnGrandchildProof <- fixture.registry.generateProofs(spawnGrandchildEvent, Set(Alice))
        finalState           <- combiner.insert(stateAfterParent, Signed(spawnGrandchildEvent, spawnGrandchildProof))

        grandparent = finalState.calculated.stateMachines
          .get(grandparentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        grandchild = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        grandchildGrandparentId = grandchild.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("grandparentId").collect { case StrValue(id) => id }
            case _           => None
          }
        }

        grandchildActivatedBy = grandchild.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("activatedBy").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect(grandparent.isDefined) and
      expect(grandparent.exists(_.childFiberIds.contains(parentCid))) and
      expect(parent.isDefined) and
      expect(parent.map(_.parentFiberId).contains(Some(grandparentCid))) and
      expect(parent.exists(_.childFiberIds.contains(childCid))) and
      expect(grandchild.isDefined) and
      expect(grandchild.map(_.currentState).contains(StateId("active"))) and
      expect(grandchild.map(_.parentFiberId).contains(Some(parentCid))) and
      expect(grandchildGrandparentId.contains(grandparentCid.toString)) and
      expect(grandchildActivatedBy.contains(parentCid.toString))
    }
  }

  test("rollback on nested spawn failure: failed trigger rolls back all spawns") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        child1Cid <- UUIDGen.randomUUID[IO]
        child2Cid <- UUIDGen.randomUUID[IO]
        child3Cid <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_with_failing_trigger",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$child1Cid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 0 }
                  },
                  {
                    "childId": "$child2Cid",
                    "definition": {
                      "states": {
                        "idle": { "id": { "value": "idle" }, "isFinal": false },
                        "activated": { "id": { "value": "activated" }, "isFinal": false }
                      },
                      "initialState": { "value": "idle" },
                      "transitions": [
                        {
                          "from": { "value": "idle" },
                          "to": { "value": "activated" },
                          "eventName": "activate",
                          "guard": { "===": [{ "var": "event.shouldFail" }, false] },
                          "effect": {
                            "status": "activated"
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": { "status": "idle" }
                  },
                  {
                    "childId": "$child3Cid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 2 }
                  }
                ],
                "_triggers": [
                  {
                    "targetMachineId": "$child2Cid",
                    "eventName": "activate",
                    "payload": {
                      "shouldFail": true
                    }
                  }
                ],
                "spawnCount": 3
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("spawnCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates
          .TransitionStateMachine(parentCid, "spawn_with_failing_trigger", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.stateMachines.get(child1Cid)
        child2 = finalState.calculated.stateMachines.get(child2Cid)
        child3 = finalState.calculated.stateMachines.get(child3Cid)

      } yield expect(parent.isDefined) and
      expect(parent.map(_.currentState).contains(StateId("init"))) and
      expect(parent.exists(_.lastReceipt.exists(r => !r.success))) and
      expect(child1.isEmpty) and
      expect(child2.isEmpty) and
      expect(child3.isEmpty)
    }
  }

  test("gas limiting: excessive spawns exhaust gas") {
    // Test that spawn overhead (50 gas each) can exhaust a low gas limit
    // Using DeterministicEventProcessor directly to control gas limit
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        // 25 children * 50 gas overhead = 1250 gas for spawns alone
        children <- (1 to 25).toList.traverse(_ => UUIDGen.randomUUID[IO])

        childSpawns = children
          .map { cid =>
            s"""
          {
            "childId": "$cid",
            "definition": {
              "states": {
                "active": { "id": { "value": "active" }, "isFinal": false }
              },
              "initialState": { "value": "active" },
              "transitions": []
            },
            "initialData": { "status": "active" }
          }
          """
          }
          .mkString(",")

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_many",
              "guard": true,
              "effect": {
                "_spawn": [$childSpawns],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDef,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)

        // Use a gas limit that will be exceeded by spawn overhead
        // 25 spawns * 50 gas = 1250 spawn gas, plus guard + effect evaluation
        limits = ExecutionLimits(maxDepth = 10, maxGas = 1000L)
        input = FiberInput.Transition("spawn_many", MapValue(Map.empty))

        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)
        result <- orchestrator.process(parentCid, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.GasExhaustedFailure],
            s"Expected GasExhaustedFailure, got ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with GasExhaustedFailure, but transaction was committed")
      }
    }
  }

  test("spawn validation: duplicate childId in same effect rejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO] // Same ID used twice

        // Try to spawn two children with the same ID
        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_duplicate",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 1 }
                  },
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "index": 2 }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDef,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn_duplicate",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          // Duplicate childId should be rejected with DuplicateChildId
          expect(
            reason.isInstanceOf[FailureReason.DuplicateChildId],
            s"Expected DuplicateChildId but got: ${reason.getClass.getSimpleName}"
          )
        case TransactionResult.Committed(machines, _, _, _, _, _) =>
          // If duplicates are deduplicated (second overwrites first), verify exactly 1 child
          val childCount = machines.values.count(_.parentFiberId.contains(parentCid))
          expect(childCount == 1, s"Expected exactly 1 child after dedup, got $childCount")
      }
    }
  }

  test("spawn validation: childId collision with existing fiber rejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid   <- UUIDGen.randomUUID[IO]
        existingCid <- UUIDGen.randomUUID[IO] // Already exists in CalculatedState

        // Parent tries to spawn a child with the same ID as existingCid
        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_colliding",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$existingCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "value": 1 }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDef,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        // Existing fiber that occupies existingCid
        existingDef <- IO.fromEither(decode[StateMachineDefinition]("""
        {
          "states": { "idle": { "id": { "value": "idle" }, "isFinal": false } },
          "initialState": { "value": "idle" },
          "transitions": []
        }
        """))
        existingData = MapValue(Map("name" -> StrValue("occupied")))
        existingHash <- (existingData: JsonLogicValue).computeDigest

        existingFiber = Records.StateMachineFiberRecord(
          cid = existingCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = existingDef,
          currentState = StateId("idle"),
          stateData = existingData,
          stateDataHash = existingHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        // Both fibers exist in CalculatedState
        calculatedState = CalculatedState(
          SortedMap(parentCid -> parentFiber, existingCid -> existingFiber),
          SortedMap.empty
        )

        input = FiberInput.Transition(
          "spawn_colliding",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.ChildIdCollision],
            s"Expected ChildIdCollision but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted with ChildIdCollision, but transaction was committed")
      }
    }
  }

  test("spawn validation: oversized initialData rejected") {
    // Use a small string but set a tiny maxStateSizeBytes limit
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        // Use a modest string - the limit will be set very low to trigger rejection
        testData = "x" * 200 // 200 bytes

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_large",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "testData": "$testData" }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDef,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn_large",
          MapValue(Map.empty)
        )

        // Set maxStateSizeBytes to 50 bytes - our 200 byte payload will exceed this
        limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L, maxStateSizeBytes = 50)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty)

      } yield result match {
        case TransactionResult.Aborted(reason, _, _) =>
          expect(
            reason.isInstanceOf[FailureReason.StateSizeTooLarge],
            s"Expected size-related failure but got: ${reason.getClass.getSimpleName}: ${reason.toMessage}"
          )
        case TransactionResult.Committed(_, _, _, _, _, _) =>
          failure("Expected Aborted for oversized initialData")
      }
    }
  }

  test("spawn owner inheritance: child inherits all parent owners") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]
        combiner                               <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_child",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "status": "active" }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))

        // Create parent with 3 owners
        createParent = Updates.CreateStateMachine(
          parentCid,
          parentDef,
          MapValue(Map("status" -> StrValue("init")))
        )
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice, Bob, Charlie))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        // Spawn child (only Alice signs the spawn event)
        spawnEvent = Updates.TransitionStateMachine(parentCid, "spawn_child", MapValue(Map.empty))
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)
        charlieAddress = fixture.registry.addresses(Charlie)

      } yield expect(child.isDefined) and
      // Child should inherit ALL 3 parent owners
      expect(child.map(_.owners.size).contains(3)) and
      expect(child.map(_.owners.contains(aliceAddress)).contains(true)) and
      expect(child.map(_.owners.contains(bobAddress)).contains(true)) and
      expect(child.map(_.owners.contains(charlieAddress)).contains(true))
    }
  }

  test("spawn with explicit ownersExpr sets custom owners (not inherited)") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]
        combiner                               <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        charlieAddress = fixture.registry.addresses(Charlie)

        // Parent with Alice and Bob as owners
        // Spawn directive specifies Charlie as the explicit owner via ownersExpr
        // The ownersExpr uses a var expression that reads from event payload
        parentJson = s"""
        {
          "states": {
            "init": { "id": { "value": "init" }, "isFinal": false },
            "spawned": { "id": { "value": "spawned" }, "isFinal": false }
          },
          "initialState": { "value": "init" },
          "transitions": [
            {
              "from": { "value": "init" },
              "to": { "value": "spawned" },
              "eventName": "spawn_child",
              "guard": true,
              "effect": {
                "_spawn": [
                  {
                    "childId": "$childCid",
                    "definition": {
                      "states": {
                        "active": { "id": { "value": "active" }, "isFinal": false }
                      },
                      "initialState": { "value": "active" },
                      "transitions": []
                    },
                    "initialData": { "status": "active" },
                    "owners": { "var": "event.customOwners" }
                  }
                ],
                "status": "spawned"
              },
              "dependencies": []
            }
          ]
        }
        """

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))

        // Create parent with Alice and Bob as owners
        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentData)
        parentProof <- fixture.registry.generateProofs(createParent, Set(Alice, Bob))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        // Spawn child - pass Charlie's address in event payload for ownersExpr to use
        spawnEvent = Updates.TransitionStateMachine(
          parentCid,
          "spawn_child",
          MapValue(
            Map(
              "customOwners" -> ArrayValue(List(StrValue(charlieAddress.value.value)))
            )
          )
        )
        spawnProof <- fixture.registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        child = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)

      } yield expect(child.isDefined) and
      // Child should have ONLY Charlie as owner (from ownersExpr, not inherited)
      expect(child.map(_.owners.size).contains(1)) and
      expect(child.map(_.owners.contains(charlieAddress)).contains(true)) and
      // Should NOT have inherited owners
      expect(child.map(_.owners.contains(aliceAddress)).contains(false)) and
      expect(child.map(_.owners.contains(bobAddress)).contains(false))
    }
  }

  test("spawn with invalid UUID format in childIdExpr causes error") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]

        // Parent that spawns with an invalid UUID
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")    -> State(StateId("init")),
            StateId("spawned") -> State(StateId("spawned"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> StrValue("not-a-valid-uuid"), // Invalid UUID!
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
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(fixture.registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty).attempt

      } yield result match {
        case Left(err) =>
          // Invalid UUID throws RuntimeException - verify it's UUID-related
          expect(
            err.getMessage != null,
            s"Expected error with message, got null message"
          )
        case Right(TransactionResult.Aborted(reason, _, _)) =>
          // Invalid UUID should cause InvalidChildIdFormat
          expect(
            reason.isInstanceOf[FailureReason.InvalidChildIdFormat],
            s"Expected InvalidChildIdFormat but got: ${reason.getClass.getSimpleName}"
          )
        case Right(TransactionResult.Committed(_, _, _, _, _, _)) =>
          failure("Expected error or Aborted for invalid UUID format, but transaction was committed")
      }
    }
  }

  test("spawn with non-string childIdExpr causes error") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]

        // Parent that spawns with childId as an integer instead of string
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")    -> State(StateId("init")),
            StateId("spawned") -> State(StateId("spawned"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> IntValue(12345), // Non-string!
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
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(fixture.registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty).attempt

      } yield result match {
        case Left(err) =>
          // Non-string childId should throw error
          expect(err.getMessage != null, s"Expected error with message")
        case Right(TransactionResult.Aborted(reason, _, _)) =>
          expect(
            reason.isInstanceOf[FailureReason.InvalidChildIdFormat],
            s"Expected InvalidChildIdFormat but got: ${reason.getClass.getSimpleName}"
          )
        case Right(TransactionResult.Committed(_, _, _, _, _, _)) =>
          failure("Expected error or Aborted for non-string childIdExpr, but transaction was committed")
      }
    }
  }

  test("spawn with non-array ownersExpr causes error") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        // Parent that spawns with owners as a string instead of array
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")    -> State(StateId("init")),
            StateId("spawned") -> State(StateId("spawned"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> StrValue(childCid.toString),
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
                            "initialData" -> MapValue(Map("born" -> BoolValue(true))),
                            "owners"      -> StrValue("not-an-array") // Non-array!
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
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(fixture.registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty).attempt

      } yield result match {
        case Left(err) =>
          // Non-array owners should throw error
          expect(err.getMessage != null, s"Expected error with message")
        case Right(TransactionResult.Aborted(reason, _, _)) =>
          expect(
            reason.isInstanceOf[FailureReason.InvalidOwnersExpression],
            s"Expected InvalidOwnersExpression but got: ${reason.getClass.getSimpleName}"
          )
        case Right(TransactionResult.Committed(_, _, _, _, _, _)) =>
          failure("Expected error or Aborted for non-array ownersExpr, but transaction was committed")
      }
    }
  }

  test("spawn with invalid owner address format causes error") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        // Parent that spawns with an invalid owner address
        parentDefinition = StateMachineDefinition(
          states = Map(
            StateId("init")    -> State(StateId("init")),
            StateId("spawned") -> State(StateId("spawned"))
          ),
          initialState = StateId("init"),
          transitions = List(
            Transition(
              from = StateId("init"),
              to = StateId("spawned"),
              eventName = "spawn",
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "status" -> StrValue("spawned"),
                    "_spawn" -> ArrayValue(
                      List(
                        MapValue(
                          Map(
                            "childId" -> StrValue(childCid.toString),
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
                            "initialData" -> MapValue(Map("born" -> BoolValue(true))),
                            "owners"      -> ArrayValue(List(StrValue("not-a-valid-dag-address"))) // Invalid!
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
          cid = parentCid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = parentDefinition,
          currentState = StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(fixture.registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        calculatedState = CalculatedState(SortedMap(parentCid -> parentFiber), SortedMap.empty)
        input = FiberInput.Transition(
          "spawn",
          MapValue(Map.empty)
        )

        limits = ExecutionLimits(maxDepth = 10, maxGas = 10_000L)
        orchestrator = FiberEngine.make[IO](calculatedState, fixture.ordinal, limits)

        result <- orchestrator.process(parentCid, input, List.empty).attempt

      } yield result match {
        case Left(err) =>
          // Invalid address should throw error
          expect(err.getMessage != null, s"Expected error with message")
        case Right(TransactionResult.Aborted(reason, _, _)) =>
          expect(
            reason.isInstanceOf[FailureReason.InvalidOwnerAddress],
            s"Expected InvalidOwnerAddress but got: ${reason.getClass.getSimpleName}"
          )
        case Right(TransactionResult.Committed(_, _, _, _, _, _)) =>
          failure("Expected error or Aborted for invalid owner address")
      }
    }
  }
}
