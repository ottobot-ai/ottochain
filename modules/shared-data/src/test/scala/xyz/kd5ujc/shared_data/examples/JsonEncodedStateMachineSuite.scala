package xyz.kd5ujc.shared_data.examples

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object JsonEncodedStateMachineSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("json-encoded: time lock contract") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Define time lock contract in JSON format
        // Uses standard JSON Logic format with operator tags
        timeLockJson =
          """{
          "states": {
            "locked": {
              "id": { "value": "locked" },
              "isFinal": false
            },
            "unlocked": {
              "id": { "value": "unlocked" },
              "isFinal": true
            }
          },
          "initialState": { "value": "locked" },
          "transitions": [
            {
              "from": { "value": "locked" },
              "to": { "value": "unlocked" },
              "eventType": { "value": "unlock" },
              "guard": {
                ">=": [
                  { "var": "event.currentTime" },
                  { "var": "state.unlockTime" }
                ]
              },
              "effect": [
                ["unlocked", true],
                ["unlockedAt", { "var": "event.currentTime" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Parse JSON into StateMachineDefinition
        parsedDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](timeLockJson).left.map(err =>
            new RuntimeException(s"Failed to decode JSON: $err")
          )
        )

        // Verify the parsed definition structure
        _ = expect.all(
          parsedDef.states.size == 2,
          parsedDef.initialState == StateMachine.StateId("locked"),
          parsedDef.transitions.size == 1,
          parsedDef.transitions.head.from == StateMachine.StateId("locked"),
          parsedDef.transitions.head.to == StateMachine.StateId("unlocked"),
          parsedDef.transitions.head.eventType == StateMachine.EventType("unlock")
        )

        // Create time lock fiber with unlock time set to timestamp 1000
        lockCid <- UUIDGen.randomUUID[IO]
        lockData = MapValue(
          Map(
            "unlockTime"  -> IntValue(1000),
            "amount"      -> IntValue(500),
            "beneficiary" -> StrValue("beneficiary-address")
          )
        )
        lockHash <- (lockData: JsonLogicValue).computeDigest

        lockFiber = Records.StateMachineFiberRecord(
          cid = lockCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parsedDef,
          currentState = StateMachine.StateId("locked"),
          stateData = lockData,
          stateDataHash = lockHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(lockCid -> lockHash)),
          CalculatedState(Map(lockCid -> lockFiber), Map.empty)
        )

        // Try to unlock BEFORE time (should fail)
        earlyUnlockEvent = StateMachine.Event(
          eventType = StateMachine.EventType("unlock"),
          payload = MapValue(Map("currentTime" -> IntValue(900)))
        )
        earlyUpdate = Updates.ProcessFiberEvent(lockCid, earlyUnlockEvent)
        earlyProof  <- registry.generateProofs(earlyUpdate, Set(Alice))
        earlyResult <- combiner.insert(inState, Signed(earlyUpdate, earlyProof)).attempt

        // Verify early unlock fails
        _ = expect(earlyResult.isLeft)

        // Try to unlock AFTER time (should succeed)
        validUnlockEvent = StateMachine.Event(
          eventType = StateMachine.EventType("unlock"),
          payload = MapValue(Map("currentTime" -> IntValue(1500)))
        )
        validUpdate = Updates.ProcessFiberEvent(lockCid, validUnlockEvent)
        validProof <- registry.generateProofs(validUpdate, Set(Alice))
        finalState <- combiner.insert(inState, Signed(validUpdate, validProof))

        unlockedFiber = finalState.calculated.records
          .get(lockCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        isUnlocked: Option[Boolean] = unlockedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("unlocked").collect { case BoolValue(u) => u }
            case _           => None
          }
        }
        unlockedAtTime: Option[BigInt] = unlockedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("unlockedAt").collect { case IntValue(t) => t }
            case _           => None
          }
        }

      } yield expect.all(
        unlockedFiber.isDefined,
        unlockedFiber.map(_.currentState).contains(StateMachine.StateId("unlocked")),
        unlockedFiber.map(_.sequenceNumber).contains(1L),
        isUnlocked.contains(true),
        unlockedAtTime.contains(BigInt(1500))
      )
    }
  }

  test("json-encoded: hash time-locked contract (HTLC) with Alice and Bob") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // HTLC: Alice locks funds for Bob with a secret hash
        // Bob can claim with correct preimage before timeout
        // Alice can refund after timeout
        htlcJson =
          """{
          "states": {
            "pending": {
              "id": { "value": "pending" },
              "isFinal": false
            },
            "claimed": {
              "id": { "value": "claimed" },
              "isFinal": true
            },
            "refunded": {
              "id": { "value": "refunded" },
              "isFinal": true
            }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "claimed" },
              "eventType": { "value": "claim" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "event.secretHash" },
                      { "var": "state.hashLock" }
                    ]
                  },
                  {
                    "<": [
                      { "var": "event.currentTime" },
                      { "var": "state.timeout" }
                    ]
                  }
                ]
              },
              "effect": [
                ["claimed", true],
                ["claimedBy", { "var": "event.claimant" }],
                ["claimedAt", { "var": "event.currentTime" }],
                ["secret", { "var": "event.secret" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "refunded" },
              "eventType": { "value": "refund" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "event.refunder" },
                      { "var": "state.sender" }
                    ]
                  },
                  {
                    ">=": [
                      { "var": "event.currentTime" },
                      { "var": "state.timeout" }
                    ]
                  }
                ]
              },
              "effect": [
                ["refunded", true],
                ["refundedAt", { "var": "event.currentTime" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        parsedDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](htlcJson).left.map(err =>
            new RuntimeException(s"Failed to decode HTLC JSON: $err")
          )
        )

        // Alice creates HTLC with secret hash
        // Secret: "opensesame", Hash: "secret123hash"
        htlcCid <- UUIDGen.randomUUID[IO]
        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        htlcData = MapValue(
          Map(
            "sender"    -> StrValue(aliceAddr.toString),
            "recipient" -> StrValue(bobAddr.toString),
            "amount"    -> IntValue(1000),
            "hashLock"  -> StrValue("secret123hash"), // Hash of "opensesame"
            "timeout"   -> IntValue(2000) // Expires at time 2000
          )
        )
        htlcHash <- (htlcData: JsonLogicValue).computeDigest

        htlcFiber = Records.StateMachineFiberRecord(
          cid = htlcCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parsedDef,
          currentState = StateMachine.StateId("pending"),
          stateData = htlcData,
          stateDataHash = htlcHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(htlcCid -> htlcHash)),
          CalculatedState(Map(htlcCid -> htlcFiber), Map.empty)
        )

        // Test 1: Bob tries to claim with WRONG secret (should fail)
        wrongClaimEvent = StateMachine.Event(
          eventType = StateMachine.EventType("claim"),
          payload = MapValue(
            Map(
              "secret"      -> StrValue("wrongsecret"),
              "secretHash"  -> StrValue("wronghash"),
              "claimant"    -> StrValue(bobAddr.toString),
              "currentTime" -> IntValue(1000)
            )
          )
        )
        wrongClaimUpdate = Updates.ProcessFiberEvent(htlcCid, wrongClaimEvent)
        wrongClaimProof  <- registry.generateProofs(wrongClaimUpdate, Set(Bob))
        wrongClaimResult <- combiner.insert(inState, Signed(wrongClaimUpdate, wrongClaimProof)).attempt

        _ = expect(wrongClaimResult.isLeft)

        // Test 2: Bob claims with CORRECT secret BEFORE timeout (should succeed)
        correctClaimEvent = StateMachine.Event(
          eventType = StateMachine.EventType("claim"),
          payload = MapValue(
            Map(
              "secret"      -> StrValue("opensesame"),
              "secretHash"  -> StrValue("secret123hash"),
              "claimant"    -> StrValue(bobAddr.toString),
              "currentTime" -> IntValue(1500)
            )
          )
        )
        correctClaimUpdate = Updates.ProcessFiberEvent(htlcCid, correctClaimEvent)
        correctClaimProof <- registry.generateProofs(correctClaimUpdate, Set(Bob))
        claimedState      <- combiner.insert(inState, Signed(correctClaimUpdate, correctClaimProof))

        claimedFiber = claimedState.calculated.records
          .get(htlcCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        wasClaimed: Option[Boolean] = claimedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("claimed").collect { case BoolValue(c) => c }
            case _           => None
          }
        }
        claimedBy: Option[String] = claimedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("claimedBy").collect { case StrValue(cb) => cb }
            case _           => None
          }
        }
        revealedSecret: Option[String] = claimedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("secret").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        // Verify Bob successfully claimed
        _ = expect.all(
          claimedFiber.isDefined,
          claimedFiber.map(_.currentState).contains(StateMachine.StateId("claimed")),
          wasClaimed.contains(true),
          claimedBy.contains(bobAddr.toString),
          revealedSecret.contains("opensesame")
        )

        // Test 3: Alice tries to refund AFTER timeout on a NEW HTLC (should succeed)
        htlcCid2 <- UUIDGen.randomUUID[IO]
        htlcData2 = MapValue(
          Map(
            "sender"    -> StrValue(aliceAddr.toString),
            "recipient" -> StrValue(bobAddr.toString),
            "amount"    -> IntValue(500),
            "hashLock"  -> StrValue("anotherhash"),
            "timeout"   -> IntValue(2000)
          )
        )
        htlcHash2 <- (htlcData2: JsonLogicValue).computeDigest

        htlcFiber2 = Records.StateMachineFiberRecord(
          cid = htlcCid2,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = parsedDef,
          currentState = StateMachine.StateId("pending"),
          stateData = htlcData2,
          stateDataHash = htlcHash2,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState2 = DataState(
          OnChain(Map(htlcCid2 -> htlcHash2)),
          CalculatedState(Map(htlcCid2 -> htlcFiber2), Map.empty)
        )

        // Alice tries to refund BEFORE timeout (should fail)
        earlyRefundEvent = StateMachine.Event(
          eventType = StateMachine.EventType("refund"),
          payload = MapValue(
            Map(
              "refunder"    -> StrValue(aliceAddr.toString),
              "currentTime" -> IntValue(1500)
            )
          )
        )
        earlyRefundUpdate = Updates.ProcessFiberEvent(htlcCid2, earlyRefundEvent)
        earlyRefundProof  <- registry.generateProofs(earlyRefundUpdate, Set(Alice))
        earlyRefundResult <- combiner.insert(inState2, Signed(earlyRefundUpdate, earlyRefundProof)).attempt

        _ = expect(earlyRefundResult.isLeft)

        // Alice refunds AFTER timeout (should succeed)
        refundEvent = StateMachine.Event(
          eventType = StateMachine.EventType("refund"),
          payload = MapValue(
            Map(
              "refunder"    -> StrValue(aliceAddr.toString),
              "currentTime" -> IntValue(2500)
            )
          )
        )
        refundUpdate = Updates.ProcessFiberEvent(htlcCid2, refundEvent)
        refundProof   <- registry.generateProofs(refundUpdate, Set(Alice))
        refundedState <- combiner.insert(inState2, Signed(refundUpdate, refundProof))

        refundedFiber = refundedState.calculated.records
          .get(htlcCid2)
          .collect { case r: Records.StateMachineFiberRecord => r }

        wasRefunded: Option[Boolean] = refundedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("refunded").collect { case BoolValue(r) => r }
            case _           => None
          }
        }
        refundedAt: Option[BigInt] = refundedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("refundedAt").collect { case IntValue(t) => t }
            case _           => None
          }
        }

      } yield expect.all(
        // Verify claim succeeded
        claimedFiber.isDefined,
        claimedFiber.map(_.currentState).contains(StateMachine.StateId("claimed")),
        claimedFiber.map(_.sequenceNumber).contains(1L),
        wasClaimed.contains(true),
        claimedBy.contains(bobAddr.toString),
        revealedSecret.contains("opensesame"),
        // Verify refund succeeded
        refundedFiber.isDefined,
        refundedFiber.map(_.currentState).contains(StateMachine.StateId("refunded")),
        refundedFiber.map(_.sequenceNumber).contains(1L),
        wasRefunded.contains(true),
        refundedAt.contains(BigInt(2500))
      )
    }
  }

  test("json-encoded: supply chain with escrow, inspection, and insurance") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        orderCid      <- UUIDGen.randomUUID[IO]
        escrowCid     <- UUIDGen.randomUUID[IO]
        shippingCid   <- UUIDGen.randomUUID[IO]
        inspectionCid <- UUIDGen.randomUUID[IO]
        insuranceCid  <- UUIDGen.randomUUID[IO]

        orderJson =
          s"""{
          "states": {
            "placed": { "id": { "value": "placed" }, "isFinal": false },
            "funded": { "id": { "value": "funded" }, "isFinal": false },
            "shipped": { "id": { "value": "shipped" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": false },
            "inspecting": { "id": { "value": "inspecting" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": true },
            "disputed": { "id": { "value": "disputed" }, "isFinal": true }
          },
          "initialState": { "value": "placed" },
          "transitions": [
            {
              "from": { "value": "placed" },
              "to": { "value": "funded" },
              "eventType": { "value": "confirm_funding" },
              "guard": {
                "===": [
                  { "var": "machines.${escrowCid}.state.status" },
                  "locked"
                ]
              },
              "effect": [
                ["status", "funded"],
                ["fundedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${escrowCid}"]
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "shipped" },
              "eventType": { "value": "ship" },
              "guard": true,
              "effect": [
                ["status", "shipped"],
                ["shippedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "shipped" },
              "to": { "value": "in_transit" },
              "eventType": { "value": "accept_shipment" },
              "guard": {
                "===": [
                  { "var": "machines.${shippingCid}.state.status" },
                  "picked_up"
                ]
              },
              "effect": [
                ["status", "in_transit"],
                ["transitStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingCid}"]
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "delivered" },
              "eventType": { "value": "confirm_delivery" },
              "guard": {
                "===": [
                  { "var": "machines.${shippingCid}.state.status" },
                  "delivered"
                ]
              },
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingCid}"]
            },
            {
              "from": { "value": "delivered" },
              "to": { "value": "inspecting" },
              "eventType": { "value": "start_inspection" },
              "guard": {
                "===": [
                  { "var": "machines.${inspectionCid}.state.status" },
                  "scheduled"
                ]
              },
              "effect": [
                ["status", "inspecting"],
                ["inspectionStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}"]
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "completed" },
              "eventType": { "value": "complete_order" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.result" },
                      "passed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${escrowCid}.state.status" },
                      "released"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}", "${escrowCid}"]
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "disputed" },
              "eventType": { "value": "dispute" },
              "guard": {
                "===": [
                  { "var": "machines.${inspectionCid}.state.result" },
                  "failed"
                ]
              },
              "effect": [
                ["status", "disputed"],
                ["disputedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}"]
            }
          ]
        }"""

        escrowJson =
          s"""{
          "states": {
            "empty": { "id": { "value": "empty" }, "isFinal": false },
            "locked": { "id": { "value": "locked" }, "isFinal": false },
            "held": { "id": { "value": "held" }, "isFinal": false },
            "releasing": { "id": { "value": "releasing" }, "isFinal": false },
            "released": { "id": { "value": "released" }, "isFinal": true },
            "refunding": { "id": { "value": "refunding" }, "isFinal": false },
            "refunded": { "id": { "value": "refunded" }, "isFinal": true }
          },
          "initialState": { "value": "empty" },
          "transitions": [
            {
              "from": { "value": "empty" },
              "to": { "value": "locked" },
              "eventType": { "value": "lock_funds" },
              "guard": {
                ">=": [
                  { "var": "event.amount" },
                  { "var": "state.requiredAmount" }
                ]
              },
              "effect": [
                ["status", "locked"],
                ["lockedAmount", { "var": "event.amount" }],
                ["lockedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "locked" },
              "to": { "value": "held" },
              "eventType": { "value": "hold" },
              "guard": {
                "===": [
                  { "var": "machines.${shippingCid}.state.status" },
                  "picked_up"
                ]
              },
              "effect": [
                ["status", "held"],
                ["holdUntil", { "+": [{ "var": "event.timestamp" }, 86400] }]
              ],
              "dependencies": ["${shippingCid}"]
            },
            {
              "from": { "value": "held" },
              "to": { "value": "releasing" },
              "eventType": { "value": "release" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${orderCid}.state.status" },
                      "inspecting"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.result" },
                      "passed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingCid}.state.condition" },
                      "good"
                    ]
                  },
                  {
                    ">": [
                      { "var": "event.timestamp" },
                      { "var": "state.holdUntil" }
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${insuranceCid}.state.hasClaim" },
                      false
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "releasing"],
                ["releaseInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${orderCid}", "${inspectionCid}", "${shippingCid}", "${insuranceCid}"]
            },
            {
              "from": { "value": "releasing" },
              "to": { "value": "released" },
              "eventType": { "value": "finalize_release" },
              "guard": true,
              "effect": [
                ["status", "released"],
                ["releasedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "held" },
              "to": { "value": "refunding" },
              "eventType": { "value": "refund" },
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.result" },
                      "failed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingCid}.state.status" },
                      "lost"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "refunding"],
                ["refundInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}", "${shippingCid}"]
            },
            {
              "from": { "value": "refunding" },
              "to": { "value": "refunded" },
              "eventType": { "value": "finalize_refund" },
              "guard": true,
              "effect": [
                ["status", "refunded"],
                ["refundedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        shippingJson =
          s"""{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "picked_up": { "id": { "value": "picked_up" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "customs": { "id": { "value": "customs" }, "isFinal": false },
            "out_for_delivery": { "id": { "value": "out_for_delivery" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": true },
            "lost": { "id": { "value": "lost" }, "isFinal": true },
            "damaged": { "id": { "value": "damaged" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "picked_up" },
              "eventType": { "value": "pickup" },
              "guard": true,
              "effect": [
                ["status", "picked_up"],
                ["pickedUpAt", { "var": "event.timestamp" }],
                ["condition", "good"],
                ["lastGPS", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "picked_up" },
              "to": { "value": "in_transit" },
              "eventType": { "value": "checkpoint" },
              "guard": true,
              "effect": [
                ["status", "in_transit"],
                ["lastGPS", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "customs" },
              "eventType": { "value": "enter_customs" },
              "guard": true,
              "effect": [
                ["status", "customs"],
                ["enteredCustomsAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "customs" },
              "to": { "value": "out_for_delivery" },
              "eventType": { "value": "clear_customs" },
              "guard": {
                "===": [
                  { "var": "machines.${insuranceCid}.state.status" },
                  "active"
                ]
              },
              "effect": [
                ["status", "out_for_delivery"],
                ["clearedCustomsAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${insuranceCid}"]
            },
            {
              "from": { "value": "out_for_delivery" },
              "to": { "value": "delivered" },
              "eventType": { "value": "deliver" },
              "guard": true,
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "lost" },
              "eventType": { "value": "report_lost" },
              "guard": {
                ">": [
                  { "-": [{ "var": "event.timestamp" }, { "var": "state.lastGPS" }] },
                  172800
                ]
              },
              "effect": [
                ["status", "lost"],
                ["lostAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "damaged" },
              "eventType": { "value": "report_damage" },
              "guard": {
                "===": [
                  { "var": "event.tampered" },
                  true
                ]
              },
              "effect": [
                ["status", "damaged"],
                ["damagedAt", { "var": "event.timestamp" }],
                ["tampered", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        inspectionJson =
          """{
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "scheduled": { "id": { "value": "scheduled" }, "isFinal": false },
            "in_progress": { "id": { "value": "in_progress" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": true },
            "failed": { "id": { "value": "failed" }, "isFinal": true }
          },
          "initialState": { "value": "inactive" },
          "transitions": [
            {
              "from": { "value": "inactive" },
              "to": { "value": "scheduled" },
              "eventType": { "value": "schedule" },
              "guard": true,
              "effect": [
                ["status", "scheduled"],
                ["scheduledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "scheduled" },
              "to": { "value": "in_progress" },
              "eventType": { "value": "begin_inspection" },
              "guard": true,
              "effect": [
                ["status", "in_progress"],
                ["beganAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_progress" },
              "to": { "value": "passed" },
              "eventType": { "value": "complete_inspection" },
              "guard": {
                "and": [
                  {
                    ">=": [
                      { "var": "event.qualityScore" },
                      7
                    ]
                  },
                  {
                    "===": [
                      { "var": "event.damaged" },
                      false
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "passed"],
                ["result", "passed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_progress" },
              "to": { "value": "failed" },
              "eventType": { "value": "complete_inspection" },
              "guard": {
                "or": [
                  {
                    "<": [
                      { "var": "event.qualityScore" },
                      7
                    ]
                  },
                  {
                    "===": [
                      { "var": "event.damaged" },
                      true
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "failed"],
                ["result", "failed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["damageScore", { "var": "event.damageScore" }],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        insuranceJson =
          s"""{
          "states": {
            "active": { "id": { "value": "active" }, "isFinal": false },
            "claim_filed": { "id": { "value": "claim_filed" }, "isFinal": false },
            "investigating": { "id": { "value": "investigating" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "denied": { "id": { "value": "denied" }, "isFinal": true },
            "settled": { "id": { "value": "settled" }, "isFinal": true }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "claim_filed" },
              "eventType": { "value": "file_claim" },
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${shippingCid}.state.status" },
                      "lost"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingCid}.state.status" },
                      "damaged"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.result" },
                      "failed"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "claim_filed"],
                ["hasClaim", true],
                ["claimFiledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingCid}", "${inspectionCid}"]
            },
            {
              "from": { "value": "claim_filed" },
              "to": { "value": "investigating" },
              "eventType": { "value": "investigate" },
              "guard": true,
              "effect": [
                ["status", "investigating"],
                ["investigatingAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "approved" },
              "eventType": { "value": "decide" },
              "guard": {
                "or": [
                  {
                    "and": [
                      {
                        "===": [
                          { "var": "machines.${shippingCid}.state.tampered" },
                          true
                        ]
                      },
                      {
                        "<": [
                          { "-": [{ "var": "event.timestamp" }, { "var": "machines.${shippingCid}.state.lastGPS" }] },
                          86400
                        ]
                      }
                    ]
                  },
                  {
                    "and": [
                      {
                        "===": [
                          { "var": "machines.${inspectionCid}.state.result" },
                          "failed"
                        ]
                      },
                      {
                        ">=": [
                          { "var": "machines.${inspectionCid}.state.damageScore" },
                          7
                        ]
                      }
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "approved"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingCid}", "${inspectionCid}"]
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "denied" },
              "eventType": { "value": "decide" },
              "guard": {
                "!": [
                  {
                    "or": [
                      {
                        "and": [
                          {
                            "===": [
                              { "var": "machines.${shippingCid}.state.tampered" },
                              true
                            ]
                          },
                          {
                            "<": [
                              { "-": [{ "var": "event.timestamp" }, { "var": "machines.${shippingCid}.state.lastGPS" }] },
                              86400
                            ]
                          }
                        ]
                      },
                      {
                        "and": [
                          {
                            "===": [
                              { "var": "machines.${inspectionCid}.state.result" },
                              "failed"
                            ]
                          },
                          {
                            ">=": [
                              { "var": "machines.${inspectionCid}.state.damageScore" },
                              7
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "denied"],
                ["deniedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingCid}", "${inspectionCid}"]
            },
            {
              "from": { "value": "approved" },
              "to": { "value": "settled" },
              "eventType": { "value": "settle" },
              "guard": true,
              "effect": [
                ["status", "settled"],
                ["settledAt", { "var": "event.timestamp" }],
                ["payoutAmount", { "var": "event.payoutAmount" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        orderDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](orderJson).left.map(err =>
            new RuntimeException(s"Failed to decode order JSON: $err")
          )
        )

        escrowDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](escrowJson).left.map(err =>
            new RuntimeException(s"Failed to decode escrow JSON: $err")
          )
        )

        shippingDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](shippingJson).left.map(err =>
            new RuntimeException(s"Failed to decode shipping JSON: $err")
          )
        )

        inspectionDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](inspectionJson).left.map(err =>
            new RuntimeException(s"Failed to decode inspection JSON: $err")
          )
        )

        insuranceDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](insuranceJson).left.map(err =>
            new RuntimeException(s"Failed to decode insurance JSON: $err")
          )
        )

        orderData = MapValue(
          Map(
            "orderId" -> StrValue("ORDER-001"),
            "buyer"   -> StrValue(registry.addresses(Bob).toString),
            "seller"  -> StrValue(registry.addresses(Alice).toString),
            "amount"  -> IntValue(5000),
            "status"  -> StrValue("placed")
          )
        )
        orderHash <- (orderData: JsonLogicValue).computeDigest

        escrowData = MapValue(
          Map(
            "requiredAmount" -> IntValue(5000),
            "status"         -> StrValue("empty")
          )
        )
        escrowHash <- (escrowData: JsonLogicValue).computeDigest

        shippingData = MapValue(
          Map(
            "trackingNumber" -> StrValue("TRACK-12345"),
            "carrier"        -> StrValue("FastShip"),
            "status"         -> StrValue("pending")
          )
        )
        shippingHash <- (shippingData: JsonLogicValue).computeDigest

        inspectionData = MapValue(
          Map(
            "inspector" -> StrValue(registry.addresses(Charlie).toString),
            "status"    -> StrValue("inactive")
          )
        )
        inspectionHash <- (inspectionData: JsonLogicValue).computeDigest

        insuranceData = MapValue(
          Map(
            "policyNumber"   -> StrValue("INS-999"),
            "coverageAmount" -> IntValue(5000),
            "status"         -> StrValue("active"),
            "hasClaim"       -> BoolValue(false)
          )
        )
        insuranceHash <- (insuranceData: JsonLogicValue).computeDigest

        orderFiber = Records.StateMachineFiberRecord(
          cid = orderCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = orderDef,
          currentState = StateMachine.StateId("placed"),
          stateData = orderData,
          stateDataHash = orderHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        escrowFiber = Records.StateMachineFiberRecord(
          cid = escrowCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = escrowDef,
          currentState = StateMachine.StateId("empty"),
          stateData = escrowData,
          stateDataHash = escrowHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        shippingFiber = Records.StateMachineFiberRecord(
          cid = shippingCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = shippingDef,
          currentState = StateMachine.StateId("pending"),
          stateData = shippingData,
          stateDataHash = shippingHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inspectionFiber = Records.StateMachineFiberRecord(
          cid = inspectionCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = inspectionDef,
          currentState = StateMachine.StateId("inactive"),
          stateData = inspectionData,
          stateDataHash = inspectionHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        insuranceFiber = Records.StateMachineFiberRecord(
          cid = insuranceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = insuranceDef,
          currentState = StateMachine.StateId("active"),
          stateData = insuranceData,
          stateDataHash = insuranceHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(
            Map(
              orderCid      -> orderHash,
              escrowCid     -> escrowHash,
              shippingCid   -> shippingHash,
              inspectionCid -> inspectionHash,
              insuranceCid  -> insuranceHash
            )
          ),
          CalculatedState(
            Map(
              orderCid      -> orderFiber,
              escrowCid     -> escrowFiber,
              shippingCid   -> shippingFiber,
              inspectionCid -> inspectionFiber,
              insuranceCid  -> insuranceFiber
            ),
            Map.empty
          )
        )

        lockFundsEvent = StateMachine.Event(
          eventType = StateMachine.EventType("lock_funds"),
          payload = MapValue(
            Map(
              "amount"    -> IntValue(5000),
              "timestamp" -> IntValue(1000)
            )
          )
        )
        lockFundsUpdate = Updates.ProcessFiberEvent(escrowCid, lockFundsEvent)
        lockFundsProof <- registry.generateProofs(lockFundsUpdate, Set(Bob))
        state1         <- combiner.insert(inState, Signed(lockFundsUpdate, lockFundsProof))

        confirmFundingEvent = StateMachine.Event(
          eventType = StateMachine.EventType("confirm_funding"),
          payload = MapValue(Map("timestamp" -> IntValue(1100)))
        )
        confirmFundingUpdate = Updates.ProcessFiberEvent(orderCid, confirmFundingEvent)
        confirmFundingProof <- registry.generateProofs(confirmFundingUpdate, Set(Alice))
        state2              <- combiner.insert(state1, Signed(confirmFundingUpdate, confirmFundingProof))

        shipEvent = StateMachine.Event(
          eventType = StateMachine.EventType("ship"),
          payload = MapValue(Map("timestamp" -> IntValue(1200)))
        )
        shipUpdate = Updates.ProcessFiberEvent(orderCid, shipEvent)
        shipProof <- registry.generateProofs(shipUpdate, Set(Alice))
        state3    <- combiner.insert(state2, Signed(shipUpdate, shipProof))

        pickupEvent = StateMachine.Event(
          eventType = StateMachine.EventType("pickup"),
          payload = MapValue(Map("timestamp" -> IntValue(1300)))
        )
        pickupUpdate = Updates.ProcessFiberEvent(shippingCid, pickupEvent)
        pickupProof <- registry.generateProofs(pickupUpdate, Set(Alice))
        state4      <- combiner.insert(state3, Signed(pickupUpdate, pickupProof))

        holdEvent = StateMachine.Event(
          eventType = StateMachine.EventType("hold"),
          payload = MapValue(Map("timestamp" -> IntValue(1400)))
        )
        holdUpdate = Updates.ProcessFiberEvent(escrowCid, holdEvent)
        holdProof <- registry.generateProofs(holdUpdate, Set(Alice))
        state5    <- combiner.insert(state4, Signed(holdUpdate, holdProof))

        acceptShipmentEvent = StateMachine.Event(
          eventType = StateMachine.EventType("accept_shipment"),
          payload = MapValue(Map("timestamp" -> IntValue(1500)))
        )
        acceptShipmentUpdate = Updates.ProcessFiberEvent(orderCid, acceptShipmentEvent)
        acceptShipmentProof <- registry.generateProofs(acceptShipmentUpdate, Set(Alice))
        state6              <- combiner.insert(state5, Signed(acceptShipmentUpdate, acceptShipmentProof))

        checkpointEvent = StateMachine.Event(
          eventType = StateMachine.EventType("checkpoint"),
          payload = MapValue(Map("timestamp" -> IntValue(1600)))
        )
        checkpointUpdate = Updates.ProcessFiberEvent(shippingCid, checkpointEvent)
        checkpointProof <- registry.generateProofs(checkpointUpdate, Set(Alice))
        state7          <- combiner.insert(state6, Signed(checkpointUpdate, checkpointProof))

        enterCustomsEvent = StateMachine.Event(
          eventType = StateMachine.EventType("enter_customs"),
          payload = MapValue(Map("timestamp" -> IntValue(2000)))
        )
        enterCustomsUpdate = Updates.ProcessFiberEvent(shippingCid, enterCustomsEvent)
        enterCustomsProof <- registry.generateProofs(enterCustomsUpdate, Set(Alice))
        state8            <- combiner.insert(state7, Signed(enterCustomsUpdate, enterCustomsProof))

        clearCustomsEvent = StateMachine.Event(
          eventType = StateMachine.EventType("clear_customs"),
          payload = MapValue(Map("timestamp" -> IntValue(3000)))
        )
        clearCustomsUpdate = Updates.ProcessFiberEvent(shippingCid, clearCustomsEvent)
        clearCustomsProof <- registry.generateProofs(clearCustomsUpdate, Set(Alice))
        state9            <- combiner.insert(state8, Signed(clearCustomsUpdate, clearCustomsProof))

        deliverEvent = StateMachine.Event(
          eventType = StateMachine.EventType("deliver"),
          payload = MapValue(Map("timestamp" -> IntValue(4000)))
        )
        deliverUpdate = Updates.ProcessFiberEvent(shippingCid, deliverEvent)
        deliverProof <- registry.generateProofs(deliverUpdate, Set(Alice))
        state10      <- combiner.insert(state9, Signed(deliverUpdate, deliverProof))

        confirmDeliveryEvent = StateMachine.Event(
          eventType = StateMachine.EventType("confirm_delivery"),
          payload = MapValue(Map("timestamp" -> IntValue(4100)))
        )
        confirmDeliveryUpdate = Updates.ProcessFiberEvent(orderCid, confirmDeliveryEvent)
        confirmDeliveryProof <- registry.generateProofs(confirmDeliveryUpdate, Set(Alice))
        state11              <- combiner.insert(state10, Signed(confirmDeliveryUpdate, confirmDeliveryProof))

        scheduleEvent = StateMachine.Event(
          eventType = StateMachine.EventType("schedule"),
          payload = MapValue(Map("timestamp" -> IntValue(4200)))
        )
        scheduleUpdate = Updates.ProcessFiberEvent(inspectionCid, scheduleEvent)
        scheduleProof <- registry.generateProofs(scheduleUpdate, Set(Charlie))
        state12       <- combiner.insert(state11, Signed(scheduleUpdate, scheduleProof))

        startInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_inspection"),
          payload = MapValue(Map("timestamp" -> IntValue(4300)))
        )
        startInspectionUpdate = Updates.ProcessFiberEvent(orderCid, startInspectionEvent)
        startInspectionProof <- registry.generateProofs(startInspectionUpdate, Set(Alice))
        state13              <- combiner.insert(state12, Signed(startInspectionUpdate, startInspectionProof))

        beginInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("begin_inspection"),
          payload = MapValue(Map("timestamp" -> IntValue(4400)))
        )
        beginInspectionUpdate = Updates.ProcessFiberEvent(inspectionCid, beginInspectionEvent)
        beginInspectionProof <- registry.generateProofs(beginInspectionUpdate, Set(Charlie))
        state14              <- combiner.insert(state13, Signed(beginInspectionUpdate, beginInspectionProof))

        completeInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("complete_inspection"),
          payload = MapValue(
            Map(
              "timestamp"    -> IntValue(5000),
              "qualityScore" -> IntValue(9),
              "damaged"      -> BoolValue(false)
            )
          )
        )
        completeInspectionUpdate = Updates.ProcessFiberEvent(inspectionCid, completeInspectionEvent)
        completeInspectionProof <- registry.generateProofs(completeInspectionUpdate, Set(Charlie))
        state15                 <- combiner.insert(state14, Signed(completeInspectionUpdate, completeInspectionProof))

        releaseEvent = StateMachine.Event(
          eventType = StateMachine.EventType("release"),
          payload = MapValue(Map("timestamp" -> IntValue(90000)))
        )
        releaseUpdate = Updates.ProcessFiberEvent(escrowCid, releaseEvent)
        releaseProof <- registry.generateProofs(releaseUpdate, Set(Alice))
        state16      <- combiner.insert(state15, Signed(releaseUpdate, releaseProof))

        finalizeReleaseEvent = StateMachine.Event(
          eventType = StateMachine.EventType("finalize_release"),
          payload = MapValue(Map("timestamp" -> IntValue(90100)))
        )
        finalizeReleaseUpdate = Updates.ProcessFiberEvent(escrowCid, finalizeReleaseEvent)
        finalizeReleaseProof <- registry.generateProofs(finalizeReleaseUpdate, Set(Alice))
        state17              <- combiner.insert(state16, Signed(finalizeReleaseUpdate, finalizeReleaseProof))

        completeOrderEvent = StateMachine.Event(
          eventType = StateMachine.EventType("complete_order"),
          payload = MapValue(Map("timestamp" -> IntValue(90200)))
        )
        completeOrderUpdate = Updates.ProcessFiberEvent(orderCid, completeOrderEvent)
        completeOrderProof <- registry.generateProofs(completeOrderUpdate, Set(Alice))
        finalState         <- combiner.insert(state17, Signed(completeOrderUpdate, completeOrderProof))

        finalOrder = finalState.calculated.records
          .get(orderCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalEscrow = finalState.calculated.records
          .get(escrowCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalShipping = finalState.calculated.records
          .get(shippingCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalInspection = finalState.calculated.records
          .get(inspectionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalInsurance = finalState.calculated.records
          .get(insuranceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        orderStatus: Option[String] = finalOrder.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        escrowStatus: Option[String] = finalEscrow.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        inspectionResult: Option[String] = finalInspection.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("result").collect { case StrValue(r) => r }
            case _           => None
          }
        }

      } yield expect.all(
        finalOrder.isDefined,
        finalOrder.map(_.currentState).contains(StateMachine.StateId("completed")),
        orderStatus.contains("completed"),
        finalEscrow.isDefined,
        finalEscrow.map(_.currentState).contains(StateMachine.StateId("released")),
        escrowStatus.contains("released"),
        finalShipping.isDefined,
        finalShipping.map(_.currentState).contains(StateMachine.StateId("delivered")),
        finalInspection.isDefined,
        finalInspection.map(_.currentState).contains(StateMachine.StateId("passed")),
        inspectionResult.contains("passed"),
        finalInsurance.isDefined,
        finalInsurance.map(_.currentState).contains(StateMachine.StateId("active"))
      )
    }
  }

  test("json-encoded: multi-stream governance with voting and actions") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Get proposal CID early so we can reference it in action stream
        proposalCid <- UUIDGen.randomUUID[IO]

        // Define Proposal state machine (the main management/governance stream)
        proposalJson =
          """{
          "states": {
            "proposed": {
              "id": { "value": "proposed" },
              "isFinal": false
            },
            "open": {
              "id": { "value": "open" },
              "isFinal": false
            },
            "closing": {
              "id": { "value": "closing" },
              "isFinal": false
            },
            "finalized": {
              "id": { "value": "finalized" },
              "isFinal": true
            },
            "aborted": {
              "id": { "value": "aborted" },
              "isFinal": true
            }
          },
          "initialState": { "value": "proposed" },
          "transitions": [
            {
              "from": { "value": "proposed" },
              "to": { "value": "open" },
              "eventType": { "value": "collect" },
              "guard": { "var": "state.hasQuorum" },
              "effect": [
                ["status", "open"],
                ["openedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "open" },
              "to": { "value": "closing" },
              "eventType": { "value": "close" },
              "guard": {
                ">=": [
                  { "var": "event.timestamp" },
                  { "var": "state.votingDeadline" }
                ]
              },
              "effect": [
                ["status", "closing"],
                ["closedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "closing" },
              "to": { "value": "finalized" },
              "eventType": { "value": "collect" },
              "guard": {
                ">=": [
                  { "var": "state.yesVotes" },
                  { "var": "state.requiredVotes" }
                ]
              },
              "effect": [
                ["status", "finalized"],
                ["finalizedAt", { "var": "event.timestamp" }],
                ["result", "passed"]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "proposed" },
              "to": { "value": "aborted" },
              "eventType": { "value": "abort" },
              "guard": true,
              "effect": [
                ["status", "aborted"],
                ["abortedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Define Action stream - can only accept submissions when proposal is "open"
        actionJson =
          s"""{
          "states": {
            "pending": {
              "id": { "value": "pending" },
              "isFinal": false
            },
            "submitted": {
              "id": { "value": "submitted" },
              "isFinal": false
            },
            "rejected": {
              "id": { "value": "rejected" },
              "isFinal": true
            }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "submitted" },
              "eventType": { "value": "submit" },
              "guard": {
                "===": [
                  { "var": "machines.${proposalCid}.state.status" },
                  "open"
                ]
              },
              "effect": [
                ["submitted", true],
                ["submittedAt", { "var": "event.timestamp" }],
                ["submitter", { "var": "event.submitter" }],
                ["actionData", { "var": "event.actionData" }]
              ],
              "dependencies": ["${proposalCid}"]
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "rejected" },
              "eventType": { "value": "submit" },
              "guard": {
                "!==": [
                  { "var": "machines.${proposalCid}.state.status" },
                  "open"
                ]
              },
              "effect": [
                ["rejected", true],
                ["reason", "proposal not open"]
              ],
              "dependencies": ["${proposalCid}"]
            }
          ]
        }"""

        // Define Voter state machine (individual vote streams)
        voterJson =
          """{
          "states": {
            "idle": {
              "id": { "value": "idle" },
              "isFinal": false
            },
            "voted": {
              "id": { "value": "voted" },
              "isFinal": false
            }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "voted" },
              "eventType": { "value": "vote" },
              "guard": true,
              "effect": [
                ["hasVoted", true],
                ["vote", { "var": "event.vote" }],
                ["votedAt", { "var": "event.timestamp" }],
                ["proposalId", { "var": "event.proposalId" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        proposalDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](proposalJson).left.map(err =>
            new RuntimeException(s"Failed to decode proposal JSON: $err")
          )
        )

        voterDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](voterJson).left.map(err =>
            new RuntimeException(s"Failed to decode voter JSON: $err")
          )
        )

        actionDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](actionJson).left.map(err =>
            new RuntimeException(s"Failed to decode action JSON: $err")
          )
        )
        proposalData = MapValue(
          Map(
            "title"          -> StrValue("Increase treasury allocation"),
            "hasQuorum"      -> BoolValue(true),
            "votingDeadline" -> IntValue(2000),
            "requiredVotes"  -> IntValue(2),
            "yesVotes"       -> IntValue(0),
            "noVotes"        -> IntValue(0),
            "status"         -> StrValue("proposed")
          )
        )
        proposalHash <- (proposalData: JsonLogicValue).computeDigest

        proposalFiber = Records.StateMachineFiberRecord(
          cid = proposalCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = proposalDef,
          currentState = StateMachine.StateId("proposed"),
          stateData = proposalData,
          stateDataHash = proposalHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        // Create voter fibers for Alice, Bob, and Charlie
        aliceCid <- UUIDGen.randomUUID[IO]
        aliceVoterData = MapValue(
          Map(
            "voter"       -> StrValue(registry.addresses(Alice).toString),
            "votingPower" -> IntValue(1)
          )
        )
        aliceVoterHash <- (aliceVoterData: JsonLogicValue).computeDigest

        aliceVoterFiber = Records.StateMachineFiberRecord(
          cid = aliceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = voterDef,
          currentState = StateMachine.StateId("idle"),
          stateData = aliceVoterData,
          stateDataHash = aliceVoterHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        bobCid <- UUIDGen.randomUUID[IO]
        bobVoterData = MapValue(
          Map(
            "voter"       -> StrValue(registry.addresses(Bob).toString),
            "votingPower" -> IntValue(1)
          )
        )
        bobVoterHash <- (bobVoterData: JsonLogicValue).computeDigest

        bobVoterFiber = Records.StateMachineFiberRecord(
          cid = bobCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = voterDef,
          currentState = StateMachine.StateId("idle"),
          stateData = bobVoterData,
          stateDataHash = bobVoterHash,
          sequenceNumber = 0,
          owners = Set(Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        charlieCid <- UUIDGen.randomUUID[IO]
        charlieVoterData = MapValue(
          Map(
            "voter"       -> StrValue(registry.addresses(Charlie).toString),
            "votingPower" -> IntValue(1)
          )
        )
        charlieVoterHash <- (charlieVoterData: JsonLogicValue).computeDigest

        charlieVoterFiber = Records.StateMachineFiberRecord(
          cid = charlieCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = voterDef,
          currentState = StateMachine.StateId("idle"),
          stateData = charlieVoterData,
          stateDataHash = charlieVoterHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        // Create first action fiber (for early submission test - will be rejected)
        earlyActionCid <- UUIDGen.randomUUID[IO]
        earlyActionData = MapValue(
          Map(
            "proposalId" -> StrValue(proposalCid.toString),
            "submitter"  -> StrValue(registry.addresses(Charlie).toString)
          )
        )
        earlyActionHash <- (earlyActionData: JsonLogicValue).computeDigest

        earlyActionFiber = Records.StateMachineFiberRecord(
          cid = earlyActionCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = actionDef,
          currentState = StateMachine.StateId("pending"),
          stateData = earlyActionData,
          stateDataHash = earlyActionHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        // Create second action fiber (for valid submission test)
        validActionCid <- UUIDGen.randomUUID[IO]
        validActionData = MapValue(
          Map(
            "proposalId" -> StrValue(proposalCid.toString),
            "submitter"  -> StrValue(registry.addresses(Charlie).toString)
          )
        )
        validActionHash <- (validActionData: JsonLogicValue).computeDigest

        validActionFiberInit = Records.StateMachineFiberRecord(
          cid = validActionCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = actionDef,
          currentState = StateMachine.StateId("pending"),
          stateData = validActionData,
          stateDataHash = validActionHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        // Initial state with all machines (proposal, voters, and both action fibers)
        inState = DataState(
          OnChain(
            Map(
              proposalCid    -> proposalHash,
              aliceCid       -> aliceVoterHash,
              bobCid         -> bobVoterHash,
              charlieCid     -> charlieVoterHash,
              earlyActionCid -> earlyActionHash,
              validActionCid -> validActionHash
            )
          ),
          CalculatedState(
            Map(
              proposalCid    -> proposalFiber,
              aliceCid       -> aliceVoterFiber,
              bobCid         -> bobVoterFiber,
              charlieCid     -> charlieVoterFiber,
              earlyActionCid -> earlyActionFiber,
              validActionCid -> validActionFiberInit
            ),
            Map.empty
          )
        )

        // Step 0: Try to submit action BEFORE proposal is open (should transition to rejected)
        earlySubmitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("submit"),
          payload = MapValue(
            Map(
              "timestamp"  -> IntValue(900),
              "submitter"  -> StrValue(registry.addresses(Charlie).toString),
              "actionData" -> StrValue("early submission data")
            )
          )
        )
        earlySubmitUpdate = Updates.ProcessFiberEvent(earlyActionCid, earlySubmitEvent)
        earlySubmitProof      <- registry.generateProofs(earlySubmitUpdate, Set(Charlie))
        stateAfterEarlySubmit <- combiner.insert(inState, Signed(earlySubmitUpdate, earlySubmitProof))

        earlyActionFiberResult = stateAfterEarlySubmit.calculated.records
          .get(earlyActionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        earlyRejected: Option[Boolean] = earlyActionFiberResult.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("rejected").collect { case BoolValue(r) => r }
            case _           => None
          }
        }
        earlyReason: Option[String] = earlyActionFiberResult.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("reason").collect { case StrValue(r) => r }
            case _           => None
          }
        }

        // Verify early submission was rejected
        _ = expect.all(
          earlyActionFiberResult.isDefined,
          earlyActionFiberResult.map(_.currentState).contains(StateMachine.StateId("rejected")),
          earlyRejected.contains(true),
          earlyReason.contains("proposal not open")
        )

        // Step 1: Open the proposal (proposed -> open)
        collectEvent1 = StateMachine.Event(
          eventType = StateMachine.EventType("collect"),
          payload = MapValue(Map("timestamp" -> IntValue(1000)))
        )
        collectUpdate1 = Updates.ProcessFiberEvent(proposalCid, collectEvent1)
        collectProof1 <- registry.generateProofs(collectUpdate1, Set(Alice))
        state1        <- combiner.insert(inState, Signed(collectUpdate1, collectProof1))

        proposalAfterOpen = state1.calculated.records
          .get(proposalCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Step 1.5: Submit action AFTER proposal is open (should succeed)
        validSubmitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("submit"),
          payload = MapValue(
            Map(
              "timestamp"  -> IntValue(1050),
              "submitter"  -> StrValue(registry.addresses(Charlie).toString),
              "actionData" -> StrValue("valid submission data")
            )
          )
        )
        validSubmitUpdate = Updates.ProcessFiberEvent(validActionCid, validSubmitEvent)
        validSubmitProof <- registry.generateProofs(validSubmitUpdate, Set(Charlie))
        state1_5         <- combiner.insert(state1, Signed(validSubmitUpdate, validSubmitProof))

        validActionFiberResult = state1_5.calculated.records
          .get(validActionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Step 2: Alice votes YES
        aliceVoteEvent = StateMachine.Event(
          eventType = StateMachine.EventType("vote"),
          payload = MapValue(
            Map(
              "vote"       -> StrValue("yes"),
              "timestamp"  -> IntValue(1100),
              "proposalId" -> StrValue(proposalCid.toString)
            )
          )
        )
        aliceVoteUpdate = Updates.ProcessFiberEvent(aliceCid, aliceVoteEvent)
        aliceVoteProof <- registry.generateProofs(aliceVoteUpdate, Set(Alice))
        state2         <- combiner.insert(state1_5, Signed(aliceVoteUpdate, aliceVoteProof))

        aliceAfterVote = state2.calculated.records
          .get(aliceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Step 3: Bob votes YES
        bobVoteEvent = StateMachine.Event(
          eventType = StateMachine.EventType("vote"),
          payload = MapValue(
            Map(
              "vote"       -> StrValue("yes"),
              "timestamp"  -> IntValue(1200),
              "proposalId" -> StrValue(proposalCid.toString)
            )
          )
        )
        bobVoteUpdate = Updates.ProcessFiberEvent(bobCid, bobVoteEvent)
        bobVoteProof <- registry.generateProofs(bobVoteUpdate, Set(Bob))
        state3       <- combiner.insert(state2, Signed(bobVoteUpdate, bobVoteProof))

        bobAfterVote = state3.calculated.records
          .get(bobCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Step 4: Manually update proposal with vote counts (simulating aggregation)
        // In a real system, this would happen via cross-machine dependencies
        proposalWithVotes = state3.calculated.records
          .get(proposalCid)
          .collect { case r: Records.StateMachineFiberRecord => r }
          .map { p =>
            val updatedData = MapValue(
              Map(
                "title"          -> StrValue("Increase treasury allocation"),
                "hasQuorum"      -> BoolValue(true),
                "votingDeadline" -> IntValue(2000),
                "requiredVotes"  -> IntValue(2),
                "yesVotes"       -> IntValue(2), // Updated: Alice + Bob
                "noVotes"        -> IntValue(0),
                "status"         -> StrValue("open")
              )
            )
            p.copy(stateData = updatedData)
          }

        updatedProposalHash <- proposalWithVotes.traverse[IO, Hash](p => p.stateData.computeDigest)

        state4 = proposalWithVotes
          .zip(updatedProposalHash)
          .map { case (p, h) =>
            state3.copy(
              onChain = state3.onChain.copy(
                latest = state3.onChain.latest.updated(proposalCid, h)
              ),
              calculated = state3.calculated.copy(
                stateMachines = state3.calculated.stateMachines.updated(
                  proposalCid,
                  p.copy(stateDataHash = h)
                )
              )
            )
          }
          .getOrElse(state3)

        // Step 5: Close voting (open -> closing)
        closeEvent = StateMachine.Event(
          eventType = StateMachine.EventType("close"),
          payload = MapValue(Map("timestamp" -> IntValue(2100)))
        )
        closeUpdate = Updates.ProcessFiberEvent(proposalCid, closeEvent)
        closeProof <- registry.generateProofs(closeUpdate, Set(Alice))
        state5     <- combiner.insert(state4, Signed(closeUpdate, closeProof))

        proposalAfterClose = state5.calculated.records
          .get(proposalCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Step 6: Finalize (closing -> finalized)
        finalCollectEvent = StateMachine.Event(
          eventType = StateMachine.EventType("collect"),
          payload = MapValue(Map("timestamp" -> IntValue(2200)))
        )
        finalCollectUpdate = Updates.ProcessFiberEvent(proposalCid, finalCollectEvent)
        finalCollectProof <- registry.generateProofs(finalCollectUpdate, Set(Alice))
        finalState        <- combiner.insert(state5, Signed(finalCollectUpdate, finalCollectProof))

        finalProposal = finalState.calculated.records
          .get(proposalCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        proposalResult: Option[String] = finalProposal.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("result").collect { case StrValue(r) => r }
            case _           => None
          }
        }

        aliceVote: Option[String] = aliceAfterVote.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("vote").collect { case StrValue(v) => v }
            case _           => None
          }
        }

        bobVote: Option[String] = bobAfterVote.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("vote").collect { case StrValue(v) => v }
            case _           => None
          }
        }

        actionSubmitted: Option[Boolean] = validActionFiberResult.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("submitted").collect { case BoolValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        // Verify valid action submission succeeded when proposal was open
        validActionFiberResult.isDefined,
        validActionFiberResult.map(_.currentState).contains(StateMachine.StateId("submitted")),
        actionSubmitted.contains(true),
        // Verify proposal opened
        proposalAfterOpen.isDefined,
        proposalAfterOpen.map(_.currentState).contains(StateMachine.StateId("open")),
        // Verify Alice voted
        aliceAfterVote.isDefined,
        aliceAfterVote.map(_.currentState).contains(StateMachine.StateId("voted")),
        aliceVote.contains("yes"),
        // Verify Bob voted
        bobAfterVote.isDefined,
        bobAfterVote.map(_.currentState).contains(StateMachine.StateId("voted")),
        bobVote.contains("yes"),
        // Verify proposal closed
        proposalAfterClose.isDefined,
        proposalAfterClose.map(_.currentState).contains(StateMachine.StateId("closing")),
        // Verify proposal finalized with passing result
        finalProposal.isDefined,
        finalProposal.map(_.currentState).contains(StateMachine.StateId("finalized")),
        proposalResult.contains("passed")
      )
    }
  }

}
