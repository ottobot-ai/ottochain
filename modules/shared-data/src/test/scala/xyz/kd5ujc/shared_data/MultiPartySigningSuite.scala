package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.lifecycle.validate.FiberValidator
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object MultiPartySigningSuite extends SimpleIOSuite {

  private val simpleDefinitionJson: String = """
    {
      "states": {
        "idle": { "id": "idle", "isFinal": false },
        "done": { "id": "done", "isFinal": true }
      },
      "initialState": "idle",
      "transitions": [
        {
          "from": "idle",
          "to": "done",
          "eventName": "advance",
          "guard": true,
          "effect": { "advanced": true },
          "dependencies": []
        }
      ]
    }
  """

  private val contractDefinitionJson: String = """
    {
      "states": {
        "pending": { "id": "pending", "isFinal": false },
        "accepted": { "id": "accepted", "isFinal": false },
        "released": { "id": "released", "isFinal": true }
      },
      "initialState": "pending",
      "transitions": [
        {
          "from": "pending",
          "to": "accepted",
          "eventName": "accept",
          "guard": true,
          "effect": { "status": "accepted" },
          "dependencies": []
        },
        {
          "from": "accepted",
          "to": "released",
          "eventName": "release",
          "guard": true,
          "effect": { "status": "released" },
          "dependencies": []
        }
      ]
    }
  """

  // ============================================================================
  // Group 1: Backward Compatibility
  // ============================================================================

  test("T1.1 - single-owner fiber: owner can transition (no participants)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        create = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        transProof      <- fixture.registry.generateProofs(transition, Set(Alice))
        stateAfterTrans <- combiner.insert(stateAfterCreate, Signed(transition, transProof))

        record = stateAfterTrans.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.currentState).contains(StateId("done")))
    }
  }

  test("T1.2 - single-owner fiber: non-owner rejected (no participants)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        create = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        bobProof <- fixture.registry.generateProofs(transition, Set(Bob))

        validator = new FiberValidator.L0Validator[IO](stateAfterCreate, bobProof)
        result <- validator.processEvent(transition)
      } yield expect(result.isInvalid)
    }
  }

  test("T1.3 - archive requires owner even with participants") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        bobAddress = fixture.registry.addresses(Bob)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        archive = Updates.ArchiveStateMachine(fiberId, FiberOrdinal.MinValue)
        bobProof <- fixture.registry.generateProofs(archive, Set(Bob))

        validator = new FiberValidator.L0Validator[IO](stateAfterCreate, bobProof)
        result <- validator.archiveFiber(archive)
      } yield expect(result.isInvalid)
    }
  }

  // ============================================================================
  // Group 2: Multi-Party Transition Signing
  // ============================================================================

  test("T2.1 - authorized participant can trigger transition") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        bobAddress = fixture.registry.addresses(Bob)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        bobProof        <- fixture.registry.generateProofs(transition, Set(Bob))
        stateAfterTrans <- combiner.insert(stateAfterCreate, Signed(transition, bobProof))

        record = stateAfterTrans.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.currentState).contains(StateId("done")))
    }
  }

  test("T2.2 - multiple participants: any one can transition") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        participants = Set(
          fixture.registry.addresses(Bob),
          fixture.registry.addresses(Charlie),
          fixture.registry.addresses(Dave)
        )
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(participants))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        charlieProof    <- fixture.registry.generateProofs(transition, Set(Charlie))
        stateAfterTrans <- combiner.insert(stateAfterCreate, Signed(transition, charlieProof))

        record = stateAfterTrans.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.currentState).contains(StateId("done")))
    }
  }

  test("T2.3 - non-participant rejected even with participants present") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        bobAddress = fixture.registry.addresses(Bob)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        charlieProof <- fixture.registry.generateProofs(transition, Set(Charlie))

        validator = new FiberValidator.L0Validator[IO](stateAfterCreate, charlieProof)
        result <- validator.processEvent(transition)
      } yield expect(result.isInvalid)
    }
  }

  test("T2.4 - owner can still transition when participants are set") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        bobAddress = fixture.registry.addresses(Bob)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        aliceProof      <- fixture.registry.generateProofs(transition, Set(Alice))
        stateAfterTrans <- combiner.insert(stateAfterCreate, Signed(transition, aliceProof))

        record = stateAfterTrans.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.currentState).contains(StateId("done")))
    }
  }

  // ============================================================================
  // Group 3: FiberRecord field verification
  // ============================================================================

  test("T3.1 - authorizedSigners populated on created fiber record") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        bobAddr = fixture.registry.addresses(Bob)
        charlieAddr = fixture.registry.addresses(Charlie)
        create = Updates
          .CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddr, charlieAddr)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        record = stateAfterCreate.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.authorizedSigners).contains(Set(bobAddr, charlieAddr)))
    }
  }

  test("T3.2 - no participants means empty authorizedSigners") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        create = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        record = stateAfterCreate.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.authorizedSigners).contains(Set.empty[io.constellationnetwork.schema.address.Address]))
    }
  }

  // ============================================================================
  // Group 4: Full Round-Trip Integration
  // ============================================================================

  test("T4.1 - two-party contract lifecycle") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](contractDefinitionJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        bobAddress = fixture.registry.addresses(Bob)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(bobAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        // Bob accepts
        accept = Updates.TransitionStateMachine(fiberId, "accept", MapValue(Map.empty), FiberOrdinal.MinValue)
        bobProof <- fixture.registry.generateProofs(accept, Set(Bob))
        state1   <- combiner.insert(state0, Signed(accept, bobProof))

        acceptedRecord = state1.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Alice releases (seq number incremented after Bob's accept)
        release = Updates.TransitionStateMachine(
          fiberId,
          "release",
          MapValue(Map.empty),
          acceptedRecord.map(_.sequenceNumber).getOrElse(FiberOrdinal.MinValue)
        )
        aliceProof <- fixture.registry.generateProofs(release, Set(Alice))
        state2     <- combiner.insert(state1, Signed(release, aliceProof))

        releasedRecord = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Charlie (unauthorized) tries — validate only
        charlieTransition = Updates
          .TransitionStateMachine(fiberId, "accept", MapValue(Map.empty), FiberOrdinal.MinValue)
        charlieProof <- fixture.registry.generateProofs(charlieTransition, Set(Charlie))
        charlieValidator = new FiberValidator.L0Validator[IO](state0, charlieProof)
        charlieResult <- charlieValidator.processEvent(charlieTransition)

        // Bob tries to archive — should fail
        archive = Updates.ArchiveStateMachine(fiberId, FiberOrdinal.MinValue)
        bobArchiveProof <- fixture.registry.generateProofs(archive, Set(Bob))
        archiveValidator = new FiberValidator.L0Validator[IO](state0, bobArchiveProof)
        archiveResult <- archiveValidator.archiveFiber(archive)

      } yield expect(acceptedRecord.map(_.currentState).contains(StateId("accepted"))) and
      expect(releasedRecord.map(_.currentState).contains(StateId("released"))) and
      expect(charlieResult.isInvalid) and
      expect(archiveResult.isInvalid)
    }
  }

  test("T4.2 - owner-also-participant: no double-counting issues") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](simpleDefinitionJson))
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        aliceAddress = fixture.registry.addresses(Alice)
        create = Updates.CreateStateMachine(fiberId, machineDef, initialData, participants = Some(Set(aliceAddress)))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        transition = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        aliceProof      <- fixture.registry.generateProofs(transition, Set(Alice))
        stateAfterTrans <- combiner.insert(stateAfterCreate, Signed(transition, aliceProof))

        record = stateAfterTrans.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }
      } yield expect(record.isDefined) and
      expect(record.map(_.currentState).contains(StateId("done")))
    }
  }
}
