package xyz.kd5ujc.shared_data

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, DeterministicEventProcessor}

import io.circe.parser._
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object SpawnMachinesSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("basic spawn: parent spawns single child machine") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_child" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_child"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.records
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

      } yield expect.all(
        parent.isDefined,
        parent.map(_.currentState).contains(StateMachine.StateId("spawned")),
        parent.exists(_.childFiberIds.contains(childCid)),
        child.isDefined,
        child.map(_.currentState).contains(StateMachine.StateId("active")),
        child.map(_.parentFiberId).contains(Some(parentCid)),
        child.map(_.status).contains(Records.FiberStatus.Active),
        childParentId.contains(parentCid.toString),
        childCreatedAt.contains(BigInt(1))
      )
    }
  }

  test("multiple spawns: parent spawns multiple children in single event") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_multiple" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_multiple"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.records
          .get(child1Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child2 = finalState.calculated.records
          .get(child2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child3 = finalState.calculated.records
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

      } yield expect.all(
        parent.isDefined,
        parent.map(_.currentState).contains(StateMachine.StateId("spawned")),
        parent.map(_.childFiberIds.size).contains(3),
        parent.map(_.childFiberIds.contains(child1Cid)).contains(true),
        parent.map(_.childFiberIds.contains(child2Cid)).contains(true),
        parent.map(_.childFiberIds.contains(child3Cid)).contains(true),
        child1.isDefined,
        child2.isDefined,
        child3.isDefined,
        child1Index.contains(BigInt(0)),
        child2Index.contains(BigInt(1)),
        child3Index.contains(BigInt(2))
      )
    }
  }

  test("spawn with triggers: spawned child can be triggered immediately") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_and_trigger" },
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
                          "eventType": { "value": "activate" },
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
                    "eventType": "activate",
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_and_trigger"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.records
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

      } yield expect.all(
        parent.isDefined,
        parent.map(_.currentState).contains(StateMachine.StateId("spawned")),
        child.isDefined,
        child.map(_.currentState).contains(StateMachine.StateId("activated")),
        childStatus.contains("activated"),
        childMessage.contains("Hello from parent")
      )
    }
  }

  test("spawn with custom owners: child inherits parent owners by default") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_child" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice, Bob))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_child"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child = finalState.calculated.records
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        aliceAddress = registry.addresses(Alice)
        bobAddress = registry.addresses(Bob)

      } yield expect.all(
        parent.isDefined,
        child.isDefined,
        child.map(_.owners.size).contains(2),
        child.map(_.owners.contains(aliceAddress)).contains(true),
        child.map(_.owners.contains(bobAddress)).contains(true)
      )
    }
  }

  test("complete lifecycle: parent spawns child, child processes events, child archived") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "create_child" },
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
                          "eventType": { "value": "start_work" },
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
                          "eventType": { "value": "finish_work" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("childStatus" -> StrValue("none")))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("create_child"),
            MapValue(Map.empty)
          )
        )
        spawnProof      <- registry.generateProofs(spawnEvent, Set(Alice))
        stateAfterSpawn <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        childAfterSpawn = stateAfterSpawn.calculated.records
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        startWorkEvent = Updates.ProcessFiberEvent(
          childCid,
          StateMachine.Event(
            StateMachine.EventType("start_work"),
            MapValue(Map.empty)
          )
        )
        startProof      <- registry.generateProofs(startWorkEvent, Set(Alice))
        stateAfterStart <- combiner.insert(stateAfterSpawn, Signed(startWorkEvent, startProof))

        childAfterStart = stateAfterStart.calculated.records
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finishWorkEvent = Updates.ProcessFiberEvent(
          childCid,
          StateMachine.Event(
            StateMachine.EventType("finish_work"),
            MapValue(Map.empty)
          )
        )
        finishProof      <- registry.generateProofs(finishWorkEvent, Set(Alice))
        stateAfterFinish <- combiner.insert(stateAfterStart, Signed(finishWorkEvent, finishProof))

        childAfterFinish = stateAfterFinish.calculated.records
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        progressAfterFinish = childAfterFinish.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("progress").collect { case IntValue(p) => p }
            case _           => None
          }
        }

        archiveChild = Updates.ArchiveFiber(childCid)
        archiveProof      <- registry.generateProofs(archiveChild, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterFinish, Signed(archiveChild, archiveProof))

        childAfterArchive = stateAfterArchive.calculated.records
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        childAfterSpawn.isDefined,
        childAfterSpawn.map(_.currentState).contains(StateMachine.StateId("idle")),
        childAfterSpawn.map(_.status).contains(Records.FiberStatus.Active),
        childAfterSpawn.map(_.parentFiberId).contains(Some(parentCid)),
        childAfterStart.isDefined,
        childAfterStart.map(_.currentState).contains(StateMachine.StateId("working")),
        childAfterStart.map(_.sequenceNumber).contains(1L),
        childAfterFinish.isDefined,
        childAfterFinish.map(_.currentState).contains(StateMachine.StateId("done")),
        childAfterFinish.map(_.definition.states(StateMachine.StateId("done")).isFinal).contains(true),
        childAfterFinish.map(_.sequenceNumber).contains(2L),
        progressAfterFinish.contains(BigInt(100)),
        childAfterArchive.isDefined,
        childAfterArchive.map(_.status).contains(Records.FiberStatus.Archived)
      )
    }
  }

  test("child uses multiple var expressions in effect") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_and_trigger" },
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
                          "eventType": { "value": "activate" },
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
                    "eventType": "activate",
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("level" -> IntValue(1)))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_and_trigger"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        child = finalState.calculated.records
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

      } yield expect.all(
        child.isDefined,
        child.map(_.currentState).contains(StateMachine.StateId("active")),
        childEventMessage.contains("Hello"),
        childEventAmount.contains(BigInt(42)),
        childParentState.contains(BigInt(1)),
        childMyId.contains(childCid.toString)
      )
    }
  }

  test("nested spawn: child can spawn grandchild with var expressions") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_parent" },
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
                          "eventType": { "value": "spawn_grandchild" },
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
                                      "eventType": { "value": "activate" },
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
                                "eventType": "activate",
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
                          "eventType": { "value": "activate" },
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

        grandparentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](grandparentJson))
        grandparentData = MapValue(Map("level" -> IntValue(0)))

        createGrandparent = Updates.CreateStateMachineFiber(grandparentCid, grandparentDef, grandparentData)
        grandparentProof <- registry.generateProofs(createGrandparent, Set(Alice))
        stateAfterGrandparent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createGrandparent, grandparentProof)
        )

        spawnParentEvent = Updates.ProcessFiberEvent(
          grandparentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_parent"),
            MapValue(Map.empty)
          )
        )
        spawnParentProof <- registry.generateProofs(spawnParentEvent, Set(Alice))
        stateAfterParent <- combiner.insert(stateAfterGrandparent, Signed(spawnParentEvent, spawnParentProof))

        spawnGrandchildEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_grandchild"),
            MapValue(Map.empty)
          )
        )
        spawnGrandchildProof <- registry.generateProofs(spawnGrandchildEvent, Set(Alice))
        finalState           <- combiner.insert(stateAfterParent, Signed(spawnGrandchildEvent, spawnGrandchildProof))

        grandparent = finalState.calculated.records
          .get(grandparentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        grandchild = finalState.calculated.records
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

      } yield expect.all(
        grandparent.isDefined,
        grandparent.map(_.childFiberIds.contains(parentCid)).contains(true),
        parent.isDefined,
        parent.map(_.parentFiberId).contains(Some(grandparentCid)),
        parent.map(_.childFiberIds.contains(childCid)).contains(true),
        grandchild.isDefined,
        grandchild.map(_.currentState).contains(StateMachine.StateId("active")),
        grandchild.map(_.parentFiberId).contains(Some(parentCid)),
        grandchildGrandparentId.contains(grandparentCid.toString),
        grandchildActivatedBy.contains(parentCid.toString)
      )
    }
  }

  test("rollback on nested spawn failure: failed trigger rolls back all spawns") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "spawn_with_failing_trigger" },
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
                          "eventType": { "value": "activate" },
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
                    "eventType": "activate",
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("spawnCount" -> IntValue(0)))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        spawnEvent = Updates.ProcessFiberEvent(
          parentCid,
          StateMachine.Event(
            StateMachine.EventType("spawn_with_failing_trigger"),
            MapValue(Map.empty)
          )
        )
        spawnProof <- registry.generateProofs(spawnEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterParent, Signed(spawnEvent, spawnProof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.records.get(child1Cid)
        child2 = finalState.calculated.records.get(child2Cid)
        child3 = finalState.calculated.records.get(child3Cid)

      } yield expect.all(
        parent.isDefined,
        parent.map(_.currentState).contains(StateMachine.StateId("init")),
        parent.map(_.lastEventStatus).exists {
          case Records.EventProcessingStatus.ExecutionFailed(_, _, _, _, _) => true
          case _                                                            => false
        },
        child1.isEmpty,
        child2.isEmpty,
        child3.isEmpty
      )
    }
  }

  test("gas limiting: excessive spawns exhaust gas") {
    // Test that spawn overhead (50 gas each) can exhaust a low gas limit
    // Using DeterministicEventProcessor directly to control gas limit
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        ordinal   <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)
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
              "eventType": { "value": "spawn_many" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        parentData = MapValue(Map("status" -> StrValue("init")))
        parentHash <- (parentData: JsonLogicValue).computeDigest

        parentFiber = Records.StateMachineFiberRecord(
          cid = parentCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parentDef,
          currentState = StateMachine.StateId("init"),
          stateData = parentData,
          stateDataHash = parentHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(parentCid -> parentFiber), Map.empty)

        event = StateMachine.Event(
          StateMachine.EventType("spawn_many"),
          MapValue(Map.empty)
        )

        // Use a gas limit that will be exceeded by spawn overhead
        // 25 spawns * 50 gas = 1250 spawn gas, plus guard + effect evaluation
        executionContext = StateMachine.ExecutionContext(
          depth = 0,
          maxDepth = 10,
          gasUsed = 0L,
          maxGas = 1000L,
          processedEvents = Set.empty
        )

        result <- DeterministicEventProcessor.processEvent(
          parentFiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L // Low limit that spawn overhead will exceed
        )

      } yield expect(
        result.isInstanceOf[StateMachine.GasExhausted]
      )
    }
  }
}
