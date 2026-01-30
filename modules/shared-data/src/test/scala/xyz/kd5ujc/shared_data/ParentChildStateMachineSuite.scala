package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object ParentChildStateMachineSuite extends SimpleIOSuite {

  test("structured outputs: webhook and notification emissions") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]
        orderCid <- UUIDGen.randomUUID[IO]

        orderJson = s"""{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "confirmed": { "id": { "value": "confirmed" }, "isFinal": false },
            "shipped": { "id": { "value": "shipped" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "confirmed" },
              "eventName": "confirm",
              "guard": true,
              "effect": {
                "_emit": [
                  {
                    "name": "email",
                    "data": {
                      "to": { "var": "state.customerEmail" },
                      "subject": "Order Confirmed",
                      "body": "Your order has been confirmed"
                    },
                    "destination": "email-service"
                  }
                ],
                "status": "confirmed",
                "confirmedAt": { "var": "event.timestamp" }
              },
              "dependencies": []
            },
            {
              "from": { "value": "confirmed" },
              "to": { "value": "shipped" },
              "eventName": "ship",
              "guard": true,
              "effect": [
                ["_emit", [
                  {
                    "name": "webhook",
                    "data": {
                      "event": "order.shipped",
                      "orderId": { "var": "state.orderId" },
                      "trackingNumber": { "var": "event.trackingNumber" }
                    },
                    "destination": "https://api.partner.com/webhooks"
                  },
                  {
                    "name": "sms",
                    "data": {
                      "to": { "var": "state.customerPhone" },
                      "message": "Your order has shipped!"
                    }
                  }
                ]],
                ["status", "shipped"],
                ["trackingNumber", { "var": "event.trackingNumber" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        orderDef <- IO.fromEither(decode[StateMachineDefinition](orderJson))

        initialData = MapValue(
          Map(
            "orderId"       -> StrValue("ORDER-123"),
            "customerEmail" -> StrValue("customer@example.com"),
            "customerPhone" -> StrValue("+1234567890"),
            "status"        -> StrValue("pending")
          )
        )

        createOrder = Updates.CreateStateMachine(orderCid, orderDef, initialData)
        createProof <- registry.generateProofs(createOrder, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOrder, createProof)
        )

        confirmEvent = Updates.TransitionStateMachine(
          orderCid,
          "confirm",
          MapValue(Map("timestamp" -> IntValue(1000)))
        )
        confirmProof      <- registry.generateProofs(confirmEvent, Set(Alice))
        stateAfterConfirm <- combiner.insert(stateAfterCreate, Signed(confirmEvent, confirmProof))

        shipEvent = Updates.TransitionStateMachine(
          orderCid,
          "ship",
          MapValue(Map("trackingNumber" -> StrValue("TRACK-456")))
        )
        shipProof      <- registry.generateProofs(shipEvent, Set(Alice))
        stateAfterShip <- combiner.insert(stateAfterConfirm, Signed(shipEvent, shipProof))

        finalOrder = stateAfterShip.calculated.stateMachines
          .get(orderCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        orderState = finalOrder.flatMap(_.stateData match {
          case m: MapValue => Some(m)
          case _           => None
        })

        status = orderState.flatMap(_.value.get("status")).collect { case StrValue(s) => s }
        tracking = orderState.flatMap(_.value.get("trackingNumber")).collect { case StrValue(s) => s }

        // Verify emittedEvents on lastReceipt (ship transition emits webhook + sms)
        lastReceipt = finalOrder.flatMap(_.lastReceipt)
        emittedEvents = lastReceipt.map(_.emittedEvents).getOrElse(List.empty)

      } yield expect(finalOrder.isDefined) and
      expect(finalOrder.map(_.currentState).contains(StateId("shipped"))) and
      expect(status.contains("shipped")) and
      expect(tracking.contains("TRACK-456")) and
      expect.eql(2, emittedEvents.size) and
      expect.eql("webhook", emittedEvents.head.name) and
      expect.eql(Some("https://api.partner.com/webhooks"), emittedEvents.head.destination) and
      expect.eql("sms", emittedEvents(1).name) and
      expect.eql(None, emittedEvents(1).destination)
    }
  }

  test("parent-child context: child can access parent state") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        childCid  <- UUIDGen.randomUUID[IO]

        parentJson = s"""{
          "states": {
            "active": { "id": { "value": "active" }, "isFinal": false },
            "suspended": { "id": { "value": "suspended" }, "isFinal": false }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "suspended" },
              "eventName": "suspend",
              "guard": true,
              "effect": {
                "status": "suspended"
              },
              "dependencies": []
            }
          ]
        }"""

        childJson = s"""{
          "states": {
            "running": { "id": { "value": "running" }, "isFinal": false },
            "paused": { "id": { "value": "paused" }, "isFinal": false }
          },
          "initialState": { "value": "running" },
          "transitions": [
            {
              "from": { "value": "running" },
              "to": { "value": "paused" },
              "eventName": "check_parent",
              "guard": {
                "===": [
                  { "var": "parent.state.status" },
                  "suspended"
                ]
              },
              "effect": {
                "status": "paused",
                "reason": "parent_suspended"
              },
              "dependencies": []
            }
          ]
        }"""

        parentDef <- IO.fromEither(decode[StateMachineDefinition](parentJson))
        childDef  <- IO.fromEither(decode[StateMachineDefinition](childJson))

        parentInitialData = MapValue(Map("status" -> StrValue("active")))
        childInitialData = MapValue(Map("status" -> StrValue("running")))

        createParent = Updates.CreateStateMachine(parentCid, parentDef, parentInitialData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        createChild = Updates.CreateStateMachine(childCid, childDef, childInitialData, Some(parentCid))
        childProof      <- registry.generateProofs(createChild, Set(Bob))
        stateAfterChild <- combiner.insert(stateAfterParent, Signed(createChild, childProof))

        suspendUpdate = Updates.TransitionStateMachine(parentCid, "suspend", MapValue(Map.empty))
        suspendProof      <- registry.generateProofs(suspendUpdate, Set(Alice))
        stateAfterSuspend <- combiner.insert(stateAfterChild, Signed(suspendUpdate, suspendProof))

        checkUpdate = Updates.TransitionStateMachine(childCid, "check_parent", MapValue(Map.empty))
        checkProof <- registry.generateProofs(checkUpdate, Set(Bob))
        finalState <- combiner.insert(stateAfterSuspend, Signed(checkUpdate, checkProof))

        finalParent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalChild = finalState.calculated.stateMachines
          .get(childCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        parentStatus = finalParent.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        childStatus = finalChild.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        childReason = finalChild.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("reason").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        childParentId = finalChild.flatMap(_.parentFiberId)

      } yield expect(finalParent.isDefined) and
      expect(finalParent.map(_.currentState).contains(StateId("suspended"))) and
      expect(parentStatus.contains("suspended")) and
      expect(finalChild.isDefined) and
      expect(finalChild.map(_.currentState).contains(StateId("paused"))) and
      expect(childStatus.contains("paused")) and
      expect(childReason.contains("parent_suspended")) and
      expect(childParentId.contains(parentCid))
    }
  }

  test("parent-child relationship: parent tracks children") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        child1Cid <- UUIDGen.randomUUID[IO]
        child2Cid <- UUIDGen.randomUUID[IO]

        simpleDef = StateMachineDefinition(
          states = Map(
            StateId("active") -> State(StateId("active"), isFinal = false)
          ),
          initialState = StateId("active"),
          transitions = List.empty
        )

        initialData = MapValue(Map("status" -> StrValue("active")))

        createParent = Updates.CreateStateMachine(parentCid, simpleDef, initialData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        createChild1 = Updates.CreateStateMachine(child1Cid, simpleDef, initialData, Some(parentCid))
        child1Proof      <- registry.generateProofs(createChild1, Set(Alice))
        stateAfterChild1 <- combiner.insert(stateAfterParent, Signed(createChild1, child1Proof))

        createChild2 = Updates.CreateStateMachine(child2Cid, simpleDef, initialData, Some(parentCid))
        child2Proof <- registry.generateProofs(createChild2, Set(Alice))
        finalState  <- combiner.insert(stateAfterChild1, Signed(createChild2, child2Proof))

        parent = finalState.calculated.stateMachines
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.stateMachines
          .get(child1Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child2 = finalState.calculated.stateMachines
          .get(child2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(parent.isDefined) and
      expect(child1.isDefined) and
      expect(child2.isDefined) and
      expect(child1.flatMap(_.parentFiberId).contains(parentCid)) and
      expect(child2.flatMap(_.parentFiberId).contains(parentCid))
    }
  }

  test("emitted events: e2e via FiberEngine with MapValue effect format") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      val registry = fixture.registry
      for {
        cid <- UUIDGen.randomUUID[IO]

        json = s"""{
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "notified": { "id": { "value": "notified" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "notified" },
              "eventName": "alert",
              "guard": true,
              "effect": {
                "_emit": [
                  {
                    "name": "alarm",
                    "data": { "severity": { "var": "event.severity" } },
                    "destination": "monitoring-service"
                  }
                ],
                "status": "notified"
              },
              "dependencies": []
            }
          ]
        }"""

        def_ <- IO.fromEither(decode[StateMachineDefinition](json))

        initialData = MapValue(Map("status" -> StrValue("idle")))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        owners = Set(Alice).map(registry.addresses)

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = def_,
          currentState = StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = owners,
          status = FiberStatus.Active,
          lastReceipt = None
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](cid, fiber)

        engine = FiberEngine.make[IO](baseState.calculated, fixture.ordinal)

        dummyUpdate = Updates.TransitionStateMachine(cid, "alert", MapValue(Map("severity" -> StrValue("critical"))))
        proofs <- registry.generateProofs(dummyUpdate, Set(Alice)).map(_.toList)
        input = FiberInput.Transition("alert", MapValue(Map("severity" -> StrValue("critical"))))

        result <- engine.process(cid, input, proofs)

        // Extract logEntries from the committed result
        logEntries = result match {
          case TransactionResult.Committed(_, _, entries, _, _, _) => entries
          case _                                                   => List.empty
        }

        receipts = logEntries.collect { case r: EventReceipt => r }
        emittedEvents = receipts.flatMap(_.emittedEvents)

      } yield expect(receipts.size == 1, s"Expected 1 receipt, got ${receipts.size}") and
      expect(emittedEvents.size == 1, s"Expected 1 emitted event, got ${emittedEvents.size}") and
      expect.eql("alarm", emittedEvents.head.name) and
      expect.eql(Some("monitoring-service"), emittedEvents.head.destination) and
      expect(emittedEvents.head.data == MapValue(Map("severity" -> StrValue("critical"))))
    }
  }

  test("emitted events: e2e via FiberEngine with ArrayValue effect format") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      val registry = fixture.registry
      for {
        cid <- UUIDGen.randomUUID[IO]

        json = s"""{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "done": { "id": { "value": "done" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "done" },
              "eventName": "complete",
              "guard": true,
              "effect": [
                ["_emit", [
                  {
                    "name": "invoice",
                    "data": { "total": 100 },
                    "destination": "billing-api"
                  },
                  {
                    "name": "notification",
                    "data": { "message": "Order complete" }
                  }
                ]],
                ["status", "done"]
              ],
              "dependencies": []
            }
          ]
        }"""

        def_ <- IO.fromEither(decode[StateMachineDefinition](json))

        initialData = MapValue(Map("status" -> StrValue("pending")))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        owners = Set(Alice).map(registry.addresses)

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = def_,
          currentState = StateId("pending"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = owners,
          status = FiberStatus.Active,
          lastReceipt = None
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](cid, fiber)

        engine = FiberEngine.make[IO](baseState.calculated, fixture.ordinal)

        dummyUpdate = Updates.TransitionStateMachine(cid, "complete", MapValue(Map.empty))
        proofs <- registry.generateProofs(dummyUpdate, Set(Alice)).map(_.toList)
        input = FiberInput.Transition("complete", MapValue(Map.empty))

        result <- engine.process(cid, input, proofs)

        logEntries = result match {
          case TransactionResult.Committed(_, _, entries, _, _, _) => entries
          case _                                                   => List.empty
        }

        receipts = logEntries.collect { case r: EventReceipt => r }
        emittedEvents = receipts.flatMap(_.emittedEvents)

      } yield expect(receipts.size == 1, s"Expected 1 receipt, got ${receipts.size}") and
      expect(emittedEvents.size == 2, s"Expected 2 emitted events, got ${emittedEvents.size}") and
      expect.eql("invoice", emittedEvents.head.name) and
      expect.eql(Some("billing-api"), emittedEvents.head.destination) and
      expect.eql("notification", emittedEvents(1).name) and
      expect.eql(None, emittedEvents(1).destination)
    }
  }

  test("emitted events: no emission when _emit key absent") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      val registry = fixture.registry
      for {
        cid <- UUIDGen.randomUUID[IO]

        json = s"""{
          "states": {
            "a": { "id": { "value": "a" }, "isFinal": false },
            "b": { "id": { "value": "b" }, "isFinal": false }
          },
          "initialState": { "value": "a" },
          "transitions": [
            {
              "from": { "value": "a" },
              "to": { "value": "b" },
              "eventName": "go",
              "guard": true,
              "effect": { "status": "b" },
              "dependencies": []
            }
          ]
        }"""

        def_ <- IO.fromEither(decode[StateMachineDefinition](json))

        initialData = MapValue(Map("status" -> StrValue("a")))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        owners = Set(Alice).map(registry.addresses)

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = def_,
          currentState = StateId("a"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = owners,
          status = FiberStatus.Active,
          lastReceipt = None
        )

        baseState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](cid, fiber)

        engine = FiberEngine.make[IO](baseState.calculated, fixture.ordinal)

        dummyUpdate = Updates.TransitionStateMachine(cid, "go", MapValue(Map.empty))
        proofs <- registry.generateProofs(dummyUpdate, Set(Alice)).map(_.toList)
        input = FiberInput.Transition("go", MapValue(Map.empty))

        result <- engine.process(cid, input, proofs)

        logEntries = result match {
          case TransactionResult.Committed(_, _, entries, _, _, _) => entries
          case _                                                   => List.empty
        }

        receipts = logEntries.collect { case r: EventReceipt => r }
        emittedEvents = receipts.flatMap(_.emittedEvents)

      } yield expect(receipts.size == 1, "Expected 1 receipt") and
      expect(emittedEvents.isEmpty, "Expected no emitted events when _emit is absent")
    }
  }
}
