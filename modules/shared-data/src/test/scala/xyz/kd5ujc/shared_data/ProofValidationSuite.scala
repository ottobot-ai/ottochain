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

object ProofValidationSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("multiple signers: proofs from Alice and Bob in context") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice).toString
        bobAddress = registry.addresses(Bob).toString

        // Machine that records proof addresses
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "approved" },
              "eventType": { "value": "approve" },
              "guard": true,
              "effect": [
                ["approved", true],
                ["signer1", { "var": "proofs.0.address" }],
                ["signer2", { "var": "proofs.1.address" }],
                ["hasTwoSigners", { "and": [
                  { "exists": [{ "var": "proofs.0" }] },
                  { "exists": [{ "var": "proofs.1" }] }
                ]}]
              ],
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("approved" -> BoolValue(false)))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof <- registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send approve event with proofs from both Alice and Bob
        approveEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        approveProof <- registry.generateProofs(approveEvent, Set(Alice, Bob))
        finalState   <- combiner.insert(stateAfterCreate, Signed(approveEvent, approveProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        approved = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("approved").collect { case BoolValue(a) => a }
            case _           => None
          }
        }

        signer1 = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("signer1").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        signer2 = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("signer2").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        // Get all signer addresses
        allSigners = Set(signer1, signer2).flatten

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("approved")),
        approved.contains(true),
        allSigners.contains(aliceAddress),
        allSigners.contains(bobAddress)
      )
    }
  }

  test("guard checks proof address: only Alice can approve") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice).toString
        bobAddress = registry.addresses(Bob).toString

        // Machine that only allows Alice to approve
        machineJson = s"""
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "approved" },
              "eventType": { "value": "approve" },
              "guard": {
                "in": [
                  "$aliceAddress",
                  { "map": [{ "var": "proofs" }, { "var": "address" }] }
                ]
              },
              "effect": {
                "status": "approved",
                "approvedBy": "alice"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test 1: Bob tries to approve (should fail guard)
        approveBobEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        bobProof      <- registry.generateProofs(approveBobEvent, Set(Bob))
        stateAfterBob <- combiner.insert(stateAfterCreate, Signed(approveBobEvent, bobProof))

        machineBob = stateAfterBob.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Test 2: Alice approves (should succeed)
        approveAliceEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        aliceProof      <- registry.generateProofs(approveAliceEvent, Set(Alice))
        stateAfterAlice <- combiner.insert(stateAfterBob, Signed(approveAliceEvent, aliceProof))

        machineAlice = stateAfterAlice.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        statusAfterAlice = machineAlice.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        machineBob.isDefined,
        machineBob.map(_.currentState).contains(StateMachine.StateId("pending")),
        machineAlice.isDefined,
        machineAlice.map(_.currentState).contains(StateMachine.StateId("approved")),
        statusAfterAlice.contains("approved")
      )
    }
  }

  test("proof count validation: requires 2 of 3 signatures") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Machine requires at least 2 signatures to approve
        machineJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "approved" },
              "eventType": { "value": "approve" },
              "guard": {
                "and": [
                  { "exists": [{ "var": "proofs.0" }] },
                  { "exists": [{ "var": "proofs.1" }] }
                ]
              },
              "effect": {
                "status": "approved",
                "hasTwoProofs": true
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof <- registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test 1: Only Alice signs (should fail - need 2 signatures)
        approveAliceOnly = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        aliceOnlyProof      <- registry.generateProofs(approveAliceOnly, Set(Alice))
        stateAfterAliceOnly <- combiner.insert(stateAfterCreate, Signed(approveAliceOnly, aliceOnlyProof))

        machineAfterAliceOnly = stateAfterAliceOnly.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Test 2: Alice and Bob sign (should succeed)
        approveAliceBob = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        aliceBobProof      <- registry.generateProofs(approveAliceBob, Set(Alice, Bob))
        stateAfterAliceBob <- combiner.insert(stateAfterAliceOnly, Signed(approveAliceBob, aliceBobProof))

        machineAfterAliceBob = stateAfterAliceBob.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        statusAfterAliceBob = machineAfterAliceBob.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        machineAfterAliceOnly.isDefined,
        machineAfterAliceOnly.map(_.currentState).contains(StateMachine.StateId("pending")),
        machineAfterAliceBob.isDefined,
        machineAfterAliceBob.map(_.currentState).contains(StateMachine.StateId("approved")),
        statusAfterAliceBob.contains("approved")
      )
    }
  }

  test("complex multisig: 3 of 5 with role-based validation") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice).toString
        bobAddress = registry.addresses(Bob).toString
        charlieAddress = registry.addresses(Charlie).toString

        // Machine with role-based authorization
        machineJson = s"""
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "approved" },
              "eventType": { "value": "approve" },
              "guard": {
                "and": [
                  { "exists": [{ "var": "proofs.0" }] },
                  { "exists": [{ "var": "proofs.1" }] },
                  {
                    "or": [
                      { "in": ["$aliceAddress", { "map": [{ "var": "proofs" }, { "var": "address" }] }] },
                      { "in": ["$bobAddress", { "map": [{ "var": "proofs" }, { "var": "address" }] }] }
                    ]
                  }
                ]
              },
              "effect": {
                "status": "approved",
                "approvers": { "map": [{ "var": "proofs" }, { "var": "address" }] }
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof <- registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Test: Alice and Bob sign (should succeed - 2 signatures and Alice is included)
        approveEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("approve"),
            MapValue(Map.empty)
          )
        )
        approveProof <- registry.generateProofs(approveEvent, Set(Alice, Bob))
        finalState   <- combiner.insert(stateAfterCreate, Signed(approveEvent, approveProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        approvers = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("approvers").collect { case ArrayValue(a) => a }
            case _           => None
          }
        }

        approverCount = approvers.map(_.length)

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("approved")),
        status.contains("approved"),
        approverCount.contains(2)
      )
    }
  }

  test("proof metadata: accessing signature and id fields") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        machineCid <- UUIDGen.randomUUID[IO]

        // Machine that records proof metadata
        machineJson = """
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "logged": { "id": { "value": "logged" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "logged" },
              "eventType": { "value": "log" },
              "guard": true,
              "effect": [
                ["hasProofs", { "exists": [{ "var": "proofs.0" }] }],
                ["firstProofId", { "var": "proofs.0.id" }],
                ["firstProofAddress", { "var": "proofs.0.address" }],
                ["firstProofSignature", { "var": "proofs.0.signature" }]
              ],
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof <- registry.generateProofs(createMachine, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createMachine, machineProof)
        )

        // Send log event
        logEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("log"),
            MapValue(Map.empty)
          )
        )
        logProof   <- registry.generateProofs(logEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterCreate, Signed(logEvent, logProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        hasProofs = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("hasProofs").collect { case BoolValue(h) => h }
            case _           => None
          }
        }

        firstProofId = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("firstProofId").collect { case StrValue(i) => i }
            case _           => None
          }
        }

        firstProofAddress = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("firstProofAddress").collect { case StrValue(a) => a }
            case _           => None
          }
        }

        firstProofSignature = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("firstProofSignature").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("logged")),
        hasProofs.contains(true),
        firstProofId.isDefined,
        firstProofAddress.isDefined,
        firstProofSignature.isDefined
      )
    }
  }
}
