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
import io.constellationnetwork.security.signature.Signed
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object FuelLogisticsStateMachineSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("fuel logistics: complete contract lifecycle with GPS tracking") {
    import io.circe.parser._

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie, Dave))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        contractCid   <- UUIDGen.randomUUID[IO]
        gpsTrackerCid <- UUIDGen.randomUUID[IO]
        supplierCid   <- UUIDGen.randomUUID[IO]
        inspectionCid <- UUIDGen.randomUUID[IO]

        // FuelContract: Main contract state machine
        // States: draft -> supplier_review -> supplier_approved -> gps_ready ->
        //         in_transit -> delivered -> quality_check -> inspected -> settling -> settled
        contractJson =
          s"""{
          "states": {
            "draft": { "id": { "value": "draft" }, "isFinal": false },
            "supplier_review": { "id": { "value": "supplier_review" }, "isFinal": false },
            "supplier_approved": { "id": { "value": "supplier_approved" }, "isFinal": false },
            "gps_ready": { "id": { "value": "gps_ready" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": false },
            "quality_check": { "id": { "value": "quality_check" }, "isFinal": false },
            "inspected": { "id": { "value": "inspected" }, "isFinal": false },
            "settling": { "id": { "value": "settling" }, "isFinal": false },
            "settled": { "id": { "value": "settled" }, "isFinal": true },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "draft" },
          "transitions": [
            {
              "from": { "value": "draft" },
              "to": { "value": "supplier_review" },
              "eventType": { "value": "submit_for_approval" },
              "guard": {
                "and": [
                  { ">=": [ { "var": "state.fuelQuantity" }, 1000 ] },
                  { ">=": [ { "var": "state.pricePerLiter" }, 0 ] }
                ]
              },
              "effect": [
                ["status", "supplier_review"],
                ["submittedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "supplier_review" },
              "to": { "value": "supplier_approved" },
              "eventType": { "value": "supplier_approved" },
              "guard": {
                "===": [
                  { "var": "machines.${supplierCid}.state.status" },
                  "approved"
                ]
              },
              "effect": [
                ["status", "supplier_approved"],
                ["supplierApprovedAt", { "var": "event.timestamp" }],
                ["approvedSupplier", { "var": "machines.${supplierCid}.state.supplierName" }]
              ],
              "dependencies": ["${supplierCid}"]
            },
            {
              "from": { "value": "supplier_approved" },
              "to": { "value": "gps_ready" },
              "eventType": { "value": "prepare_shipment" },
              "guard": {
                "===": [
                  { "var": "machines.${gpsTrackerCid}.state.status" },
                  "active"
                ]
              },
              "effect": [
                ["status", "gps_ready"],
                ["shipmentPreparedAt", { "var": "event.timestamp" }],
                ["vehicleId", { "var": "event.vehicleId" }],
                ["driverId", { "var": "event.driverId" }]
              ],
              "dependencies": ["${gpsTrackerCid}"]
            },
            {
              "from": { "value": "gps_ready" },
              "to": { "value": "in_transit" },
              "eventType": { "value": "begin_transit" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${gpsTrackerCid}.state.status" },
                      "tracking"
                    ]
                  },
                  {
                    ">": [
                      { "var": "machines.${gpsTrackerCid}.state.dataPointCount" },
                      0
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "in_transit"],
                ["transitStartedAt", { "var": "event.timestamp" }],
                ["estimatedArrival", { "var": "event.estimatedArrival" }]
              ],
              "dependencies": ["${gpsTrackerCid}"]
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "delivered" },
              "eventType": { "value": "confirm_delivery" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${gpsTrackerCid}.state.status" },
                      "stopped"
                    ]
                  },
                  {
                    ">=": [
                      { "var": "machines.${gpsTrackerCid}.state.dataPointCount" },
                      3
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }],
                ["totalDistance", { "var": "machines.${gpsTrackerCid}.state.totalDistance" }],
                ["gpsDataPoints", { "var": "machines.${gpsTrackerCid}.state.dataPointCount" }]
              ],
              "dependencies": ["${gpsTrackerCid}"]
            },
            {
              "from": { "value": "delivered" },
              "to": { "value": "quality_check" },
              "eventType": { "value": "initiate_inspection" },
              "guard": {
                "===": [
                  { "var": "machines.${inspectionCid}.state.status" },
                  "scheduled"
                ]
              },
              "effect": [
                ["status", "quality_check"],
                ["inspectionInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}"]
            },
            {
              "from": { "value": "quality_check" },
              "to": { "value": "inspected" },
              "eventType": { "value": "inspection_complete" },
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.status" },
                      "passed"
                    ]
                  },
                  {
                    ">=": [
                      { "var": "machines.${inspectionCid}.state.qualityScore" },
                      85
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "inspected"],
                ["inspectedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "machines.${inspectionCid}.state.qualityScore" }],
                ["qualityReport", { "var": "machines.${inspectionCid}.state.reportId" }]
              ],
              "dependencies": ["${inspectionCid}"]
            },
            {
              "from": { "value": "quality_check" },
              "to": { "value": "rejected" },
              "eventType": { "value": "inspection_complete" },
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${inspectionCid}.state.status" },
                      "failed"
                    ]
                  },
                  {
                    "<": [
                      { "var": "machines.${inspectionCid}.state.qualityScore" },
                      85
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "rejected"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["rejectionReason", "quality inspection failed"],
                ["qualityScore", { "var": "machines.${inspectionCid}.state.qualityScore" }]
              ],
              "dependencies": ["${inspectionCid}"]
            },
            {
              "from": { "value": "inspected" },
              "to": { "value": "settling" },
              "eventType": { "value": "initiate_settlement" },
              "guard": true,
              "effect": [
                ["status", "settling"],
                ["settlementInitiatedAt", { "var": "event.timestamp" }],
                ["totalAmount", { "*": [ { "var": "state.fuelQuantity" }, { "var": "state.pricePerLiter" } ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "settling" },
              "to": { "value": "settled" },
              "eventType": { "value": "finalize_settlement" },
              "guard": {
                ">=": [
                  { "var": "event.paymentConfirmation" },
                  { "*": [ { "var": "state.fuelQuantity" }, { "var": "state.pricePerLiter" } ] }
                ]
              },
              "effect": [
                ["status", "settled"],
                ["settledAt", { "var": "event.timestamp" }],
                ["paymentConfirmation", { "var": "event.paymentConfirmation" }],
                ["contractCompleted", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        // GPSTracker: Tracks vehicle location during transit
        // States: inactive -> active -> tracking -> stopped -> archived
        gpsTrackerJson =
          """{
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "active": { "id": { "value": "active" }, "isFinal": false },
            "tracking": { "id": { "value": "tracking" }, "isFinal": false },
            "stopped": { "id": { "value": "stopped" }, "isFinal": false },
            "archived": { "id": { "value": "archived" }, "isFinal": true }
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
                ["activatedAt", { "var": "event.timestamp" }],
                ["vehicleId", { "var": "event.vehicleId" }],
                ["dataPointCount", 0],
                ["totalDistance", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "active" },
              "to": { "value": "tracking" },
              "eventType": { "value": "start_tracking" },
              "guard": true,
              "effect": [
                ["status", "tracking"],
                ["trackingStartedAt", { "var": "event.timestamp" }],
                ["originLat", { "var": "event.latitude" }],
                ["originLon", { "var": "event.longitude" }],
                ["lastLat", { "var": "event.latitude" }],
                ["lastLon", { "var": "event.longitude" }],
                ["dataPointCount", 1]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "tracking" },
              "to": { "value": "tracking" },
              "eventType": { "value": "log_position" },
              "guard": {
                "<": [
                  { "var": "state.dataPointCount" },
                  100
                ]
              },
              "effect": [
                ["lastLat", { "var": "event.latitude" }],
                ["lastLon", { "var": "event.longitude" }],
                ["lastUpdate", { "var": "event.timestamp" }],
                ["dataPointCount", { "+": [ { "var": "state.dataPointCount" }, 1 ] }],
                ["totalDistance", { "+": [ { "var": "state.totalDistance" }, { "var": "event.distanceDelta" } ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "tracking" },
              "to": { "value": "stopped" },
              "eventType": { "value": "stop_tracking" },
              "guard": {
                ">=": [
                  { "var": "state.dataPointCount" },
                  1
                ]
              },
              "effect": [
                ["status", "stopped"],
                ["stoppedAt", { "var": "event.timestamp" }],
                ["finalLat", { "var": "state.lastLat" }],
                ["finalLon", { "var": "state.lastLon" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "stopped" },
              "to": { "value": "archived" },
              "eventType": { "value": "archive" },
              "guard": true,
              "effect": [
                ["status", "archived"],
                ["archivedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // SupplierApproval: Supplier vetting and approval
        // States: pending -> reviewing -> approved / rejected
        supplierApprovalJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "reviewing": { "id": { "value": "reviewing" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "reviewing" },
              "eventType": { "value": "begin_review" },
              "guard": true,
              "effect": [
                ["status", "reviewing"],
                ["reviewStartedAt", { "var": "event.timestamp" }],
                ["reviewer", { "var": "event.reviewer" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "approved" },
              "eventType": { "value": "approve" },
              "guard": {
                "and": [
                  { ">=": [ { "var": "event.complianceScore" }, 75 ] },
                  { "===": [ { "var": "event.licenseValid" }, true ] }
                ]
              },
              "effect": [
                ["status", "approved"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }],
                ["approvedBy", { "var": "event.approver" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "rejected" },
              "eventType": { "value": "approve" },
              "guard": {
                "or": [
                  { "<": [ { "var": "event.complianceScore" }, 75 ] },
                  { "===": [ { "var": "event.licenseValid" }, false ] }
                ]
              },
              "effect": [
                ["status", "rejected"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["rejectionReason", "compliance or licensing issues"]
              ],
              "dependencies": []
            }
          ]
        }"""

        // QualityInspection: Post-delivery quality verification
        // States: pending -> scheduled -> inspecting -> passed / failed
        qualityInspectionJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "scheduled": { "id": { "value": "scheduled" }, "isFinal": false },
            "inspecting": { "id": { "value": "inspecting" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": true },
            "failed": { "id": { "value": "failed" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "scheduled" },
              "eventType": { "value": "schedule" },
              "guard": true,
              "effect": [
                ["status", "scheduled"],
                ["scheduledAt", { "var": "event.timestamp" }],
                ["inspector", { "var": "event.inspector" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "scheduled" },
              "to": { "value": "inspecting" },
              "eventType": { "value": "begin_inspection" },
              "guard": true,
              "effect": [
                ["status", "inspecting"],
                ["inspectionStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "passed" },
              "eventType": { "value": "complete" },
              "guard": {
                "and": [
                  { ">=": [ { "var": "event.qualityScore" }, 85 ] },
                  { "===": [ { "var": "event.contaminationDetected" }, false ] }
                ]
              },
              "effect": [
                ["status", "passed"],
                ["completedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["reportId", { "var": "event.reportId" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "failed" },
              "eventType": { "value": "complete" },
              "guard": {
                "or": [
                  { "<": [ { "var": "event.qualityScore" }, 85 ] },
                  { "===": [ { "var": "event.contaminationDetected" }, true ] }
                ]
              },
              "effect": [
                ["status", "failed"],
                ["completedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["failureReason", { "var": "event.failureReason" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        contractDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](contractJson).left.map(err =>
            new RuntimeException(s"Failed to decode contract JSON: $err")
          )
        )

        gpsTrackerDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](gpsTrackerJson).left.map(err =>
            new RuntimeException(s"Failed to decode GPS tracker JSON: $err")
          )
        )

        supplierDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](supplierApprovalJson).left.map(err =>
            new RuntimeException(s"Failed to decode supplier JSON: $err")
          )
        )

        inspectionDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](qualityInspectionJson).left.map(err =>
            new RuntimeException(s"Failed to decode inspection JSON: $err")
          )
        )

        contractData = MapValue(
          Map(
            "contractId"    -> StrValue("FC-2025-001"),
            "buyer"         -> StrValue(registry.addresses(Alice).toString),
            "fuelType"      -> StrValue("Diesel"),
            "fuelQuantity"  -> IntValue(5000),
            "pricePerLiter" -> IntValue(2),
            "status"        -> StrValue("draft")
          )
        )
        contractHash <- (contractData: JsonLogicValue).computeDigest

        gpsTrackerData = MapValue(
          Map(
            "trackerId" -> StrValue("GPS-TRACKER-001"),
            "status"    -> StrValue("inactive")
          )
        )
        gpsTrackerHash <- (gpsTrackerData: JsonLogicValue).computeDigest

        supplierData = MapValue(
          Map(
            "supplierName" -> StrValue("Global Fuel Co"),
            "supplierId"   -> StrValue("SUP-001"),
            "status"       -> StrValue("pending")
          )
        )
        supplierHash <- (supplierData: JsonLogicValue).computeDigest

        inspectionData = MapValue(
          Map(
            "contractRef" -> StrValue("FC-2025-001"),
            "status"      -> StrValue("pending")
          )
        )
        inspectionHash <- (inspectionData: JsonLogicValue).computeDigest

        contractFiber = Records.StateMachineFiberRecord(
          cid = contractCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = contractDef,
          currentState = StateMachine.StateId("draft"),
          stateData = contractData,
          stateDataHash = contractHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        gpsTrackerFiber = Records.StateMachineFiberRecord(
          cid = gpsTrackerCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = gpsTrackerDef,
          currentState = StateMachine.StateId("inactive"),
          stateData = gpsTrackerData,
          stateDataHash = gpsTrackerHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        supplierFiber = Records.StateMachineFiberRecord(
          cid = supplierCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = supplierDef,
          currentState = StateMachine.StateId("pending"),
          stateData = supplierData,
          stateDataHash = supplierHash,
          sequenceNumber = 0,
          owners = Set(Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inspectionFiber = Records.StateMachineFiberRecord(
          cid = inspectionCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = inspectionDef,
          currentState = StateMachine.StateId("pending"),
          stateData = inspectionData,
          stateDataHash = inspectionHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(
            Map(
              contractCid   -> contractHash,
              gpsTrackerCid -> gpsTrackerHash,
              supplierCid   -> supplierHash,
              inspectionCid -> inspectionHash
            )
          ),
          CalculatedState(
            Map(
              contractCid   -> contractFiber,
              gpsTrackerCid -> gpsTrackerFiber,
              supplierCid   -> supplierFiber,
              inspectionCid -> inspectionFiber
            ),
            Map.empty
          )
        )

        // STEP 1: Submit contract for approval
        submitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("submit_for_approval"),
          payload = MapValue(Map("timestamp" -> IntValue(1000)))
        )
        submitUpdate = Updates.ProcessFiberEvent(contractCid, submitEvent)
        submitProof <- registry.generateProofs(submitUpdate, Set(Alice))
        state1      <- combiner.insert(inState, Signed(submitUpdate, submitProof))

        // STEP 2: Begin supplier review
        beginReviewEvent = StateMachine.Event(
          eventType = StateMachine.EventType("begin_review"),
          payload = MapValue(
            Map(
              "timestamp" -> IntValue(1100),
              "reviewer"  -> StrValue(registry.addresses(Bob).toString)
            )
          )
        )
        beginReviewUpdate = Updates.ProcessFiberEvent(supplierCid, beginReviewEvent)
        beginReviewProof <- registry.generateProofs(beginReviewUpdate, Set(Bob))
        state2           <- combiner.insert(state1, Signed(beginReviewUpdate, beginReviewProof))

        // STEP 3: Approve supplier
        approveSupplierEvent = StateMachine.Event(
          eventType = StateMachine.EventType("approve"),
          payload = MapValue(
            Map(
              "timestamp"       -> IntValue(1200),
              "complianceScore" -> IntValue(90),
              "licenseValid"    -> BoolValue(true),
              "approver"        -> StrValue(registry.addresses(Bob).toString)
            )
          )
        )
        approveSupplierUpdate = Updates.ProcessFiberEvent(supplierCid, approveSupplierEvent)
        approveSupplierProof <- registry.generateProofs(approveSupplierUpdate, Set(Bob))
        state3               <- combiner.insert(state2, Signed(approveSupplierUpdate, approveSupplierProof))

        // STEP 4: Contract receives supplier approval
        supplierApprovedEvent = StateMachine.Event(
          eventType = StateMachine.EventType("supplier_approved"),
          payload = MapValue(Map("timestamp" -> IntValue(1300)))
        )
        supplierApprovedUpdate = Updates.ProcessFiberEvent(contractCid, supplierApprovedEvent)
        supplierApprovedProof <- registry.generateProofs(supplierApprovedUpdate, Set(Alice))
        state4                <- combiner.insert(state3, Signed(supplierApprovedUpdate, supplierApprovedProof))

        // STEP 5: Activate GPS tracker
        activateGpsEvent = StateMachine.Event(
          eventType = StateMachine.EventType("activate"),
          payload = MapValue(
            Map(
              "timestamp" -> IntValue(1400),
              "vehicleId" -> StrValue("TRUCK-42")
            )
          )
        )
        activateGpsUpdate = Updates.ProcessFiberEvent(gpsTrackerCid, activateGpsEvent)
        activateGpsProof <- registry.generateProofs(activateGpsUpdate, Set(Alice))
        state5           <- combiner.insert(state4, Signed(activateGpsUpdate, activateGpsProof))

        // STEP 6: Prepare shipment (contract checks GPS is active)
        prepareShipmentEvent = StateMachine.Event(
          eventType = StateMachine.EventType("prepare_shipment"),
          payload = MapValue(
            Map(
              "timestamp" -> IntValue(1500),
              "vehicleId" -> StrValue("TRUCK-42"),
              "driverId"  -> StrValue(registry.addresses(Dave).toString)
            )
          )
        )
        prepareShipmentUpdate = Updates.ProcessFiberEvent(contractCid, prepareShipmentEvent)
        prepareShipmentProof <- registry.generateProofs(prepareShipmentUpdate, Set(Alice))
        state6               <- combiner.insert(state5, Signed(prepareShipmentUpdate, prepareShipmentProof))

        // STEP 7: Start GPS tracking
        startTrackingEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_tracking"),
          payload = MapValue(
            Map(
              "timestamp" -> IntValue(1600),
              "latitude"  -> IntValue(40_7128),
              "longitude" -> IntValue(-74_0060)
            )
          )
        )
        startTrackingUpdate = Updates.ProcessFiberEvent(gpsTrackerCid, startTrackingEvent)
        startTrackingProof <- registry.generateProofs(startTrackingUpdate, Set(Alice))
        state7             <- combiner.insert(state6, Signed(startTrackingUpdate, startTrackingProof))

        // STEP 8: Begin transit (contract checks GPS is tracking)
        beginTransitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("begin_transit"),
          payload = MapValue(
            Map(
              "timestamp"        -> IntValue(1700),
              "estimatedArrival" -> IntValue(3000)
            )
          )
        )
        beginTransitUpdate = Updates.ProcessFiberEvent(contractCid, beginTransitEvent)
        beginTransitProof <- registry.generateProofs(beginTransitUpdate, Set(Alice))
        state8            <- combiner.insert(state7, Signed(beginTransitUpdate, beginTransitProof))

        // STEP 9-11: Log GPS positions during transit
        logPosition1Event = StateMachine.Event(
          eventType = StateMachine.EventType("log_position"),
          payload = MapValue(
            Map(
              "timestamp"     -> IntValue(1800),
              "latitude"      -> IntValue(40_7580),
              "longitude"     -> IntValue(-73_9855),
              "distanceDelta" -> IntValue(5)
            )
          )
        )
        logPosition1Update = Updates.ProcessFiberEvent(gpsTrackerCid, logPosition1Event)
        logPosition1Proof <- registry.generateProofs(logPosition1Update, Set(Alice))
        state9            <- combiner.insert(state8, Signed(logPosition1Update, logPosition1Proof))

        logPosition2Event = StateMachine.Event(
          eventType = StateMachine.EventType("log_position"),
          payload = MapValue(
            Map(
              "timestamp"     -> IntValue(2000),
              "latitude"      -> IntValue(40_8500),
              "longitude"     -> IntValue(-73_8700),
              "distanceDelta" -> IntValue(8)
            )
          )
        )
        logPosition2Update = Updates.ProcessFiberEvent(gpsTrackerCid, logPosition2Event)
        logPosition2Proof <- registry.generateProofs(logPosition2Update, Set(Alice))
        state10           <- combiner.insert(state9, Signed(logPosition2Update, logPosition2Proof))

        logPosition3Event = StateMachine.Event(
          eventType = StateMachine.EventType("log_position"),
          payload = MapValue(
            Map(
              "timestamp"     -> IntValue(2200),
              "latitude"      -> IntValue(41_0000),
              "longitude"     -> IntValue(-73_7500),
              "distanceDelta" -> IntValue(12)
            )
          )
        )
        logPosition3Update = Updates.ProcessFiberEvent(gpsTrackerCid, logPosition3Event)
        logPosition3Proof <- registry.generateProofs(logPosition3Update, Set(Alice))
        state11           <- combiner.insert(state10, Signed(logPosition3Update, logPosition3Proof))

        // STEP 12: Stop GPS tracking
        stopTrackingEvent = StateMachine.Event(
          eventType = StateMachine.EventType("stop_tracking"),
          payload = MapValue(Map("timestamp" -> IntValue(2500)))
        )
        stopTrackingUpdate = Updates.ProcessFiberEvent(gpsTrackerCid, stopTrackingEvent)
        stopTrackingProof <- registry.generateProofs(stopTrackingUpdate, Set(Alice))
        state12           <- combiner.insert(state11, Signed(stopTrackingUpdate, stopTrackingProof))

        // STEP 13: Confirm delivery (contract checks GPS stopped and has data)
        confirmDeliveryEvent = StateMachine.Event(
          eventType = StateMachine.EventType("confirm_delivery"),
          payload = MapValue(Map("timestamp" -> IntValue(2600)))
        )
        confirmDeliveryUpdate = Updates.ProcessFiberEvent(contractCid, confirmDeliveryEvent)
        confirmDeliveryProof <- registry.generateProofs(confirmDeliveryUpdate, Set(Alice))
        state13              <- combiner.insert(state12, Signed(confirmDeliveryUpdate, confirmDeliveryProof))

        // STEP 14: Schedule quality inspection
        scheduleInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("schedule"),
          payload = MapValue(
            Map(
              "timestamp" -> IntValue(2700),
              "inspector" -> StrValue(registry.addresses(Charlie).toString)
            )
          )
        )
        scheduleInspectionUpdate = Updates.ProcessFiberEvent(inspectionCid, scheduleInspectionEvent)
        scheduleInspectionProof <- registry.generateProofs(scheduleInspectionUpdate, Set(Charlie))
        state14                 <- combiner.insert(state13, Signed(scheduleInspectionUpdate, scheduleInspectionProof))

        // STEP 15: Contract initiates inspection
        initiateInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("initiate_inspection"),
          payload = MapValue(Map("timestamp" -> IntValue(2800)))
        )
        initiateInspectionUpdate = Updates.ProcessFiberEvent(contractCid, initiateInspectionEvent)
        initiateInspectionProof <- registry.generateProofs(initiateInspectionUpdate, Set(Alice))
        state15                 <- combiner.insert(state14, Signed(initiateInspectionUpdate, initiateInspectionProof))

        // STEP 16: Begin inspection
        beginInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("begin_inspection"),
          payload = MapValue(Map("timestamp" -> IntValue(2900)))
        )
        beginInspectionUpdate = Updates.ProcessFiberEvent(inspectionCid, beginInspectionEvent)
        beginInspectionProof <- registry.generateProofs(beginInspectionUpdate, Set(Charlie))
        state16              <- combiner.insert(state15, Signed(beginInspectionUpdate, beginInspectionProof))

        // STEP 17: Complete inspection (passed)
        completeInspectionEvent = StateMachine.Event(
          eventType = StateMachine.EventType("complete"),
          payload = MapValue(
            Map(
              "timestamp"             -> IntValue(3000),
              "qualityScore"          -> IntValue(95),
              "contaminationDetected" -> BoolValue(false),
              "reportId"              -> StrValue("QC-REPORT-001")
            )
          )
        )
        completeInspectionUpdate = Updates.ProcessFiberEvent(inspectionCid, completeInspectionEvent)
        completeInspectionProof <- registry.generateProofs(completeInspectionUpdate, Set(Charlie))
        state17                 <- combiner.insert(state16, Signed(completeInspectionUpdate, completeInspectionProof))

        // STEP 18: Contract receives inspection completion
        inspectionCompleteEvent = StateMachine.Event(
          eventType = StateMachine.EventType("inspection_complete"),
          payload = MapValue(Map("timestamp" -> IntValue(3100)))
        )
        inspectionCompleteUpdate = Updates.ProcessFiberEvent(contractCid, inspectionCompleteEvent)
        inspectionCompleteProof <- registry.generateProofs(inspectionCompleteUpdate, Set(Alice))
        state18                 <- combiner.insert(state17, Signed(inspectionCompleteUpdate, inspectionCompleteProof))

        // STEP 19: Initiate settlement
        initiateSettlementEvent = StateMachine.Event(
          eventType = StateMachine.EventType("initiate_settlement"),
          payload = MapValue(Map("timestamp" -> IntValue(3200)))
        )
        initiateSettlementUpdate = Updates.ProcessFiberEvent(contractCid, initiateSettlementEvent)
        initiateSettlementProof <- registry.generateProofs(initiateSettlementUpdate, Set(Alice))
        state19                 <- combiner.insert(state18, Signed(initiateSettlementUpdate, initiateSettlementProof))

        // STEP 20: Finalize settlement
        finalizeSettlementEvent = StateMachine.Event(
          eventType = StateMachine.EventType("finalize_settlement"),
          payload = MapValue(
            Map(
              "timestamp"           -> IntValue(3300),
              "paymentConfirmation" -> IntValue(10000)
            )
          )
        )
        finalizeSettlementUpdate = Updates.ProcessFiberEvent(contractCid, finalizeSettlementEvent)
        finalizeSettlementProof <- registry.generateProofs(finalizeSettlementUpdate, Set(Alice))
        finalState              <- combiner.insert(state19, Signed(finalizeSettlementUpdate, finalizeSettlementProof))

        finalContract = finalState.calculated.records
          .get(contractCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalGpsTracker = finalState.calculated.records
          .get(gpsTrackerCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalSupplier = finalState.calculated.records
          .get(supplierCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalInspection = finalState.calculated.records
          .get(inspectionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        contractStatus: Option[String] = finalContract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        gpsDataPointCount: Option[BigInt] = finalGpsTracker.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("dataPointCount").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        totalDistance: Option[BigInt] = finalGpsTracker.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("totalDistance").collect { case IntValue(d) => d }
            case _           => None
          }
        }

        qualityScore: Option[BigInt] = finalInspection.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("qualityScore").collect { case IntValue(q) => q }
            case _           => None
          }
        }

        contractCompleted: Option[Boolean] = finalContract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("contractCompleted").collect { case BoolValue(c) => c }
            case _           => None
          }
        }

      } yield expect.all(
        finalContract.isDefined,
        finalContract.map(_.currentState).contains(StateMachine.StateId("settled")),
        contractStatus.contains("settled"),
        contractCompleted.contains(true),
        finalGpsTracker.isDefined,
        finalGpsTracker.map(_.currentState).contains(StateMachine.StateId("stopped")),
        gpsDataPointCount.contains(BigInt(4)),
        totalDistance.contains(BigInt(25)),
        finalSupplier.isDefined,
        finalSupplier.map(_.currentState).contains(StateMachine.StateId("approved")),
        finalInspection.isDefined,
        finalInspection.map(_.currentState).contains(StateMachine.StateId("passed")),
        qualityScore.contains(BigInt(95))
      )
    }
  }
}
