package xyz.kd5ujc.shared_data.examples

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant._

import weaver.SimpleIOSuite

object NftMarketplaceSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("nft-marketplace: spawned listing triggers royalty oracle on sale") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Get royalty oracle ID early to reference in NFT listing definition
        royaltyOracleId <- UUIDGen.randomUUID[IO]

        // JSON-encoded oracle program for calculating and recording royalties
        royaltyOracleJson =
          """{
            "if": [
              { "===": [{ "var": "method" }, "recordRoyalty" ] },
              {
                "_state": {
                  "totalRoyalties": {
                    "+": [
                      {
                        "if": [
                          { "var": "state.totalRoyalties" },
                          { "var": "state.totalRoyalties" },
                          0
                        ]
                      },
                      { "var": "args.royaltyAmount" }
                    ]
                  },
                  "lastRecorded": { "var": "args" }
                },
                "_result": {
                  "success": true,
                  "totalRoyalties": {
                    "+": [
                      {
                        "if": [
                          { "var": "state.totalRoyalties" },
                          { "var": "state.totalRoyalties" },
                          0
                        ]
                      },
                      { "var": "args.royaltyAmount" }
                    ]
                  }
                }
              },
              {
                "_state": { "var": "state" },
                "_result": { "error": "Unknown method" }
              }
            ]
          }"""

        royaltyProgramExpr <- IO.fromEither(
          decode[JsonLogicExpression](royaltyOracleJson).left.map(err =>
            new RuntimeException(s"Failed to decode oracle JSON: $err")
          )
        )

        // Create royalty oracle
        royaltyOracleData = MapValue(Map("totalRoyalties" -> IntValue(0)))
        royaltyHash <- (royaltyOracleData: JsonLogicValue).computeDigest

        royaltyOracle = Records.ScriptOracleFiberRecord(
          cid = royaltyOracleId,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          scriptProgram = royaltyProgramExpr,
          stateData = Some(royaltyOracleData),
          stateDataHash = Some(royaltyHash),
          accessControl = AccessControlPolicy.Public,
          owners = Set(registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        // JSON-encoded NFT listing state machine with oracle trigger on sale
        nftListingJson =
          s"""{
          "states": {
            "listed": {
              "id": { "value": "listed" },
              "isFinal": false,
              "metadata": "NFT is listed for sale"
            },
            "sold": {
              "id": { "value": "sold" },
              "isFinal": true,
              "metadata": "NFT has been sold"
            },
            "cancelled": {
              "id": { "value": "cancelled" },
              "isFinal": true,
              "metadata": "Listing was cancelled"
            }
          },
          "initialState": { "value": "listed" },
          "transitions": [
            {
              "from": { "value": "listed" },
              "to": { "value": "sold" },
              "eventType": { "value": "purchase" },
              "guard": {
                ">=": [
                  { "var": "event.offerPrice" },
                  { "var": "state.price" }
                ]
              },
              "effect": {
                "buyer": { "var": "event.buyer" },
                "salePrice": { "var": "event.offerPrice" },
                "soldAt": { "var": "event.timestamp" },
                "royaltyAmount": {
                  "*": [
                    { "var": "event.offerPrice" },
                    5
                  ]
                },
                "_triggers": [
                  {
                    "targetMachineId": "${royaltyOracleId}",
                    "eventType": "recordRoyalty",
                    "payload": {
                      "nftId": { "var": "state.nftId" },
                      "creator": { "var": "state.creator" },
                      "royaltyAmount": {
                        "*": [
                          { "var": "event.offerPrice" },
                          5
                        ]
                      },
                      "salePrice": { "var": "event.offerPrice" }
                    }
                  }
                ]
              },
              "dependencies": ["${royaltyOracleId}"]
            },
            {
              "from": { "value": "listed" },
              "to": { "value": "cancelled" },
              "eventType": { "value": "cancel" },
              "guard": {
                "===": [
                  { "var": "event.cancelledBy" },
                  { "var": "state.seller" }
                ]
              },
              "effect": {
                "cancelledAt": { "var": "event.timestamp" }
              },
              "dependencies": []
            }
          ]
        }"""

        nftListingDef <- IO.fromEither(
          decode[StateMachineDefinition](nftListingJson).left.map(err =>
            new RuntimeException(s"Failed to decode NFT listing JSON: $err")
          )
        )

        // JSON-encoded marketplace state machine that spawns NFT listings
        marketplaceJson =
          s"""{
          "states": {
            "active": {
              "id": { "value": "active" },
              "isFinal": false,
              "metadata": "Marketplace is active"
            }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "active" },
              "eventType": { "value": "createListing" },
              "guard": true,
              "effect": {
                "listingCount": {
                  "+": [
                    {
                      "if": [
                        { "var": "state.listingCount" },
                        { "var": "state.listingCount" },
                        0
                      ]
                    },
                    1
                  ]
                },
                "_spawn": [
                  {
                    "childId": { "var": "event.listingId" },
                    "definition": {
                      "states": {
                        "listed": {
                          "id": { "value": "listed" },
                          "isFinal": false,
                          "metadata": "NFT is listed for sale"
                        },
                        "sold": {
                          "id": { "value": "sold" },
                          "isFinal": true,
                          "metadata": "NFT has been sold"
                        },
                        "cancelled": {
                          "id": { "value": "cancelled" },
                          "isFinal": true,
                          "metadata": "Listing was cancelled"
                        }
                      },
                      "initialState": { "value": "listed" },
                      "transitions": [
                        {
                          "from": { "value": "listed" },
                          "to": { "value": "sold" },
                          "eventType": { "value": "purchase" },
                          "guard": {
                            ">=": [
                              { "var": "event.offerPrice" },
                              { "var": "state.price" }
                            ]
                          },
                          "effect": {
                            "buyer": { "var": "event.buyer" },
                            "salePrice": { "var": "event.offerPrice" },
                            "soldAt": { "var": "event.timestamp" },
                            "royaltyAmount": {
                              "*": [
                                { "var": "event.offerPrice" },
                                5
                              ]
                            },
                            "_triggers": [
                              {
                                "targetMachineId": "${royaltyOracleId}",
                                "eventType": "recordRoyalty",
                                "payload": {
                                  "nftId": { "var": "state.nftId" },
                                  "creator": { "var": "state.creator" },
                                  "royaltyAmount": {
                                    "*": [
                                      { "var": "event.offerPrice" },
                                      5
                                    ]
                                  },
                                  "salePrice": { "var": "event.offerPrice" }
                                }
                              }
                            ]
                          },
                          "dependencies": ["${royaltyOracleId}"]
                        },
                        {
                          "from": { "value": "listed" },
                          "to": { "value": "cancelled" },
                          "eventType": { "value": "cancel" },
                          "guard": {
                            "===": [
                              { "var": "event.cancelledBy" },
                              { "var": "state.seller" }
                            ]
                          },
                          "effect": {
                            "cancelledAt": { "var": "event.timestamp" }
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": {
                      "nftId": { "var": "event.nftId" },
                      "creator": { "var": "event.creator" },
                      "seller": { "var": "event.seller" },
                      "price": { "var": "event.price" },
                      "metadata": { "var": "event.metadata" },
                      "listedAt": { "var": "event.timestamp" }
                    }
                  }
                ]
              },
              "dependencies": []
            }
          ]
        }"""

        marketplaceDef <- IO.fromEither(
          decode[StateMachineDefinition](marketplaceJson).left.map(err =>
            new RuntimeException(s"Failed to decode marketplace JSON: $err")
          )
        )

        // Create marketplace fiber
        marketplaceCid <- UUIDGen.randomUUID[IO]
        marketplaceData = MapValue(
          Map(
            "name"         -> StrValue("Constellation NFT Marketplace"),
            "listingCount" -> IntValue(0)
          )
        )
        marketplaceHash <- (marketplaceData: JsonLogicValue).computeDigest

        marketplaceFiber = Records.StateMachineFiberRecord(
          cid = marketplaceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = marketplaceDef,
          currentState = StateId("active"),
          stateData = marketplaceData,
          stateDataHash = marketplaceHash,
          sequenceNumber = 0,
          owners = Set(registry.addresses(Alice)),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecord[IO](marketplaceCid, marketplaceFiber)
          .flatMap(_.withRecord[IO](royaltyOracleId, royaltyOracle))

        // Alice creates an NFT listing (spawns child)
        nftListingId1 <- UUIDGen.randomUUID[IO]
        createListing1Update = Updates.TransitionStateMachine(
          marketplaceCid,
          EventType("createListing"),
          MapValue(
            Map(
              "listingId" -> StrValue(nftListingId1.toString),
              "nftId"     -> StrValue("nft-001"),
              "creator"   -> StrValue(registry.addresses(Alice).toString),
              "seller"    -> StrValue(registry.addresses(Alice).toString),
              "price"     -> IntValue(1000),
              "metadata" -> MapValue(
                Map(
                  "name"        -> StrValue("Cosmic Dragon"),
                  "description" -> StrValue("A majestic dragon soaring through the cosmos")
                )
              ),
              "timestamp" -> IntValue(1000)
            )
          )
        )
        createListing1Proof <- registry.generateProofs(createListing1Update, Set(Alice))
        state1              <- combiner.insert(inState, Signed(createListing1Update, createListing1Proof))

        // Verify marketplace state updated
        marketplaceAfterListing = state1.calculated.stateMachines.get(marketplaceCid)
        listingCount1: Option[BigInt] = marketplaceAfterListing.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("listingCount").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        // Verify NFT listing was spawned
        nftListing1 = state1.calculated.stateMachines.get(nftListingId1)
        nftPrice1: Option[BigInt] = nftListing1.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("price").collect { case IntValue(p) => p }
            case _           => None
          }
        }

        // Charlie creates another NFT listing
        nftListingId2 <- UUIDGen.randomUUID[IO]
        createListing2Update = Updates.TransitionStateMachine(
          marketplaceCid,
          EventType("createListing"),
          MapValue(
            Map(
              "listingId" -> StrValue(nftListingId2.toString),
              "nftId"     -> StrValue("nft-002"),
              "creator"   -> StrValue(registry.addresses(Charlie).toString),
              "seller"    -> StrValue(registry.addresses(Charlie).toString),
              "price"     -> IntValue(500),
              "metadata" -> MapValue(
                Map(
                  "name"        -> StrValue("Stellar Phoenix"),
                  "description" -> StrValue("A phoenix rising from stellar flames")
                )
              ),
              "timestamp" -> IntValue(2000)
            )
          )
        )
        createListing2Proof <- registry.generateProofs(createListing2Update, Set(Charlie))
        state2              <- combiner.insert(state1, Signed(createListing2Update, createListing2Proof))

        nftListing2 = state2.calculated.stateMachines.get(nftListingId2)

        // Bob purchases Alice's NFT (should trigger royalty oracle)
        purchaseNft1Update = Updates.TransitionStateMachine(
          nftListingId1,
          EventType("purchase"),
          MapValue(
            Map(
              "buyer"      -> StrValue(registry.addresses(Bob).toString),
              "offerPrice" -> IntValue(1200),
              "timestamp"  -> IntValue(3000)
            )
          )
        )
        purchaseNft1Proof <- registry.generateProofs(purchaseNft1Update, Set(Bob))
        state3            <- combiner.insert(state2, Signed(purchaseNft1Update, purchaseNft1Proof))

        // Verify NFT listing transitioned to sold
        nftListing1AfterSale = state3.calculated.stateMachines.get(nftListingId1)
        buyer1: Option[String] = nftListing1AfterSale.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("buyer").collect { case StrValue(b) => b }
            case _           => None
          }
        }
        salePrice1: Option[BigInt] = nftListing1AfterSale.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("salePrice").collect { case IntValue(p) => p }
            case _           => None
          }
        }
        royaltyAmount1: Option[BigInt] = nftListing1AfterSale.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("royaltyAmount").collect { case IntValue(r) => r }
            case _           => None
          }
        }

        // Verify royalty oracle was triggered and state updated
        oracleAfterSale1 = state3.calculated.scriptOracles.get(royaltyOracleId)
        totalRoyalties1: Option[BigInt] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) => m.get("totalRoyalties").collect { case IntValue(t) => t }
            case _           => None
          }
        }
        lastRecordedNft1: Option[String] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("nftId").collect { case StrValue(nft) => nft }
                case _            => None
              }
            case _ => None
          }
        }
        lastRecordedCreator1: Option[String] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("creator").collect { case StrValue(c) => c }
                case _            => None
              }
            case _ => None
          }
        }

        // Bob purchases Charlie's NFT at a lower price
        purchaseNft2Update = Updates.TransitionStateMachine(
          nftListingId2,
          EventType("purchase"),
          MapValue(
            Map(
              "buyer"      -> StrValue(registry.addresses(Bob).toString),
              "offerPrice" -> IntValue(600),
              "timestamp"  -> IntValue(4000)
            )
          )
        )
        purchaseNft2Proof <- registry.generateProofs(purchaseNft2Update, Set(Bob))
        finalState        <- combiner.insert(state3, Signed(purchaseNft2Update, purchaseNft2Proof))

        // Verify second sale triggered oracle again
        oracleFinal = finalState.calculated.scriptOracles.get(royaltyOracleId)
        totalRoyaltiesFinal: Option[BigInt] = oracleFinal.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) => m.get("totalRoyalties").collect { case IntValue(t) => t }
            case _           => None
          }
        }
        lastRecordedNft2: Option[String] = oracleFinal.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("nftId").collect { case StrValue(nft) => nft }
                case _            => None
              }
            case _ => None
          }
        }
        oracleInvocationCount: Option[Long] = oracleFinal.map(_.invocationCount)

        // Verify marketplace still active with 2 listings
        marketplaceFinal = finalState.calculated.stateMachines.get(marketplaceCid)
        listingCountFinal: Option[BigInt] = marketplaceFinal.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("listingCount").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        // Verify both NFT listings are in sold state
        nftListing1Final = finalState.calculated.stateMachines.get(nftListingId1)
        nftListing2Final = finalState.calculated.stateMachines.get(nftListingId2)

      } yield expect.all(
        // Verify marketplace created first listing
        listingCount1.contains(BigInt(1)),
        // Verify first NFT listing was spawned correctly
        nftListing1.isDefined,
        nftListing1.map(_.currentState).contains(StateId("listed")),
        nftPrice1.contains(BigInt(1000)),
        // Verify second listing was spawned
        nftListing2.isDefined,
        nftListing2.map(_.currentState).contains(StateId("listed")),
        // Verify first NFT sale
        nftListing1AfterSale.isDefined,
        nftListing1AfterSale.map(_.currentState).contains(StateId("sold")),
        buyer1.contains(registry.addresses(Bob).toString),
        salePrice1.contains(BigInt(1200)),
        royaltyAmount1.contains(BigInt(6000)), // 1200 * 5
        // Verify royalty oracle was triggered after first sale
        oracleAfterSale1.isDefined,
        oracleAfterSale1.map(_.invocationCount).contains(1L),
        totalRoyalties1.contains(BigInt(6000)),
        lastRecordedNft1.contains("nft-001"),
        lastRecordedCreator1.contains(registry.addresses(Alice).toString),
        // Verify oracle was triggered again after second sale
        oracleFinal.isDefined,
        oracleInvocationCount.contains(2L),
        totalRoyaltiesFinal.contains(BigInt(9000)), // 6000 + 3000
        lastRecordedNft2.contains("nft-002"),
        // Verify marketplace final state
        listingCountFinal.contains(BigInt(2)),
        // Verify both listings are sold
        nftListing1Final.map(_.currentState).contains(StateId("sold")),
        nftListing2Final.map(_.currentState).contains(StateId("sold"))
      )
    }
  }
}
