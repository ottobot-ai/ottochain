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

object ParentChildStateMachineSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("structured outputs: webhook and notification emissions") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "confirm" },
              "guard": true,
              "effect": {
                "_outputs": [
                  {
                    "outputType": "email",
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
              "eventType": { "value": "ship" },
              "guard": true,
              "effect": [
                ["_outputs", [
                  {
                    "outputType": "webhook",
                    "data": {
                      "event": "order.shipped",
                      "orderId": { "var": "state.orderId" },
                      "trackingNumber": { "var": "event.trackingNumber" }
                    },
                    "destination": "https://api.partner.com/webhooks"
                  },
                  {
                    "outputType": "sms",
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

        orderDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](orderJson))

        initialData = MapValue(
          Map(
            "orderId"       -> StrValue("ORDER-123"),
            "customerEmail" -> StrValue("customer@example.com"),
            "customerPhone" -> StrValue("+1234567890"),
            "status"        -> StrValue("pending")
          )
        )

        createOrder = Updates.CreateStateMachineFiber(orderCid, orderDef, initialData)
        createProof <- registry.generateProofs(createOrder, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOrder, createProof)
        )

        confirmEvent = Updates.ProcessFiberEvent(
          orderCid,
          StateMachine.Event(
            StateMachine.EventType("confirm"),
            MapValue(Map("timestamp" -> IntValue(1000)))
          )
        )
        confirmProof      <- registry.generateProofs(confirmEvent, Set(Alice))
        stateAfterConfirm <- combiner.insert(stateAfterCreate, Signed(confirmEvent, confirmProof))

        shipEvent = Updates.ProcessFiberEvent(
          orderCid,
          StateMachine.Event(
            StateMachine.EventType("ship"),
            MapValue(Map("trackingNumber" -> StrValue("TRACK-456")))
          )
        )
        shipProof      <- registry.generateProofs(shipEvent, Set(Alice))
        stateAfterShip <- combiner.insert(stateAfterConfirm, Signed(shipEvent, shipProof))

        finalOrder = stateAfterShip.calculated.records
          .get(orderCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        orderState = finalOrder.flatMap(_.stateData match {
          case m: MapValue => Some(m)
          case _           => None
        })

        status = orderState.flatMap(_.value.get("status")).collect { case StrValue(s) => s }
        tracking = orderState.flatMap(_.value.get("trackingNumber")).collect { case StrValue(s) => s }

      } yield expect.all(
        finalOrder.isDefined,
        finalOrder.map(_.currentState).contains(StateMachine.StateId("shipped")),
        status.contains("shipped"),
        tracking.contains("TRACK-456")
      )
    }
  }

  test("parent-child context: child can access parent state") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

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
              "eventType": { "value": "suspend" },
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
              "eventType": { "value": "check_parent" },
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

        parentDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](parentJson))
        childDef  <- IO.fromEither(decode[StateMachine.StateMachineDefinition](childJson))

        parentInitialData = MapValue(Map("status" -> StrValue("active")))
        childInitialData = MapValue(Map("status" -> StrValue("running")))

        createParent = Updates.CreateStateMachineFiber(parentCid, parentDef, parentInitialData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        createChild = Updates.CreateStateMachineFiber(childCid, childDef, childInitialData, Some(parentCid))
        childProof      <- registry.generateProofs(createChild, Set(Bob))
        stateAfterChild <- combiner.insert(stateAfterParent, Signed(createChild, childProof))

        suspendEvent = StateMachine.Event(
          StateMachine.EventType("suspend"),
          MapValue(Map.empty)
        )
        suspendUpdate = Updates.ProcessFiberEvent(parentCid, suspendEvent)
        suspendProof      <- registry.generateProofs(suspendUpdate, Set(Alice))
        stateAfterSuspend <- combiner.insert(stateAfterChild, Signed(suspendUpdate, suspendProof))

        checkEvent = StateMachine.Event(
          StateMachine.EventType("check_parent"),
          MapValue(Map.empty)
        )
        checkUpdate = Updates.ProcessFiberEvent(childCid, checkEvent)
        checkProof <- registry.generateProofs(checkUpdate, Set(Bob))
        finalState <- combiner.insert(stateAfterSuspend, Signed(checkUpdate, checkProof))

        finalParent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalChild = finalState.calculated.records
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

      } yield expect.all(
        finalParent.isDefined,
        finalParent.map(_.currentState).contains(StateMachine.StateId("suspended")),
        parentStatus.contains("suspended"),
        finalChild.isDefined,
        finalChild.map(_.currentState).contains(StateMachine.StateId("paused")),
        childStatus.contains("paused"),
        childReason.contains("parent_suspended"),
        childParentId.contains(parentCid)
      )
    }
  }

  test("parent-child relationship: parent tracks children") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        parentCid <- UUIDGen.randomUUID[IO]
        child1Cid <- UUIDGen.randomUUID[IO]
        child2Cid <- UUIDGen.randomUUID[IO]

        simpleDef = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("active") -> StateMachine.State(StateMachine.StateId("active"), isFinal = false)
          ),
          initialState = StateMachine.StateId("active"),
          transitions = List.empty
        )

        initialData = MapValue(Map("status" -> StrValue("active")))

        createParent = Updates.CreateStateMachineFiber(parentCid, simpleDef, initialData)
        parentProof <- registry.generateProofs(createParent, Set(Alice))
        stateAfterParent <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createParent, parentProof)
        )

        createChild1 = Updates.CreateStateMachineFiber(child1Cid, simpleDef, initialData, Some(parentCid))
        child1Proof      <- registry.generateProofs(createChild1, Set(Alice))
        stateAfterChild1 <- combiner.insert(stateAfterParent, Signed(createChild1, child1Proof))

        createChild2 = Updates.CreateStateMachineFiber(child2Cid, simpleDef, initialData, Some(parentCid))
        child2Proof <- registry.generateProofs(createChild2, Set(Alice))
        finalState  <- combiner.insert(stateAfterChild1, Signed(createChild2, child2Proof))

        parent = finalState.calculated.records
          .get(parentCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child1 = finalState.calculated.records
          .get(child1Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        child2 = finalState.calculated.records
          .get(child2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        parent.isDefined,
        child1.isDefined,
        child2.isDefined,
        child1.flatMap(_.parentFiberId).contains(parentCid),
        child2.flatMap(_.parentFiberId).contains(parentCid)
      )
    }
  }
}
