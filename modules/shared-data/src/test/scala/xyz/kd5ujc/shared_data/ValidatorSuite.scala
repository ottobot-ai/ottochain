package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext, L1NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object ValidatorSuite extends SimpleIOSuite {

  // ============== Test Fixtures ==============

  object Fixtures {

    def minimalDefinition(): StateMachine.StateMachineDefinition = {
      val initial = StateMachine.StateId("initial")
      StateMachine.StateMachineDefinition(
        states = Map(initial -> StateMachine.State(initial)),
        initialState = initial,
        transitions = List.empty
      )
    }

    def simpleDefinitionWithTransition(): StateMachine.StateMachineDefinition = {
      val stateA = StateMachine.StateId("stateA")
      val stateB = StateMachine.StateId("stateB")
      StateMachine.StateMachineDefinition(
        states = Map(
          stateA -> StateMachine.State(stateA),
          stateB -> StateMachine.State(stateB)
        ),
        initialState = stateA,
        transitions = List(
          StateMachine.Transition(
            from = stateA,
            to = stateB,
            eventType = StateMachine.EventType("advance"),
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map("moved" -> BoolValue(true))))
          )
        )
      )
    }

    def definitionWithStates(count: Int): StateMachine.StateMachineDefinition = {
      val states = (1 to count).map(i => StateMachine.StateId(s"state$i"))
      val stateMap = states.map(s => s -> StateMachine.State(s)).toMap
      StateMachine.StateMachineDefinition(
        states = stateMap,
        initialState = states.head,
        transitions = List.empty
      )
    }

    def definitionWithTransitions(count: Int): StateMachine.StateMachineDefinition = {
      // Distribute transitions across multiple states to stay within MaxTransitionsPerState limit (20)
      val maxPerState = 20
      val numStates = Math.max(2, (count / maxPerState) + 2)
      val states = (1 to numStates).map(i => StateMachine.StateId(s"state$i"))
      val stateMap = states.map(s => s -> StateMachine.State(s)).toMap

      val transitions = (1 to count).map { i =>
        val fromIdx = ((i - 1) / maxPerState) % (numStates - 1)
        val toIdx = (fromIdx + 1)             % numStates
        StateMachine.Transition(
          from = states(fromIdx),
          to = states(toIdx),
          eventType = StateMachine.EventType(s"event$i"),
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(MapValue(Map.empty))
        )
      }.toList

      StateMachine.StateMachineDefinition(
        states = stateMap,
        initialState = states.head,
        transitions = transitions
      )
    }

    def definitionWithTransitionsPerState(perState: Int): StateMachine.StateMachineDefinition = {
      val state1 = StateMachine.StateId("state1")
      val state2 = StateMachine.StateId("state2")
      val transitions = (1 to perState).map { i =>
        StateMachine.Transition(
          from = state1,
          to = state2,
          eventType = StateMachine.EventType(s"event$i"),
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(MapValue(Map.empty))
        )
      }.toList
      StateMachine.StateMachineDefinition(
        states = Map(state1 -> StateMachine.State(state1), state2 -> StateMachine.State(state2)),
        initialState = state1,
        transitions = transitions
      )
    }

    def emptyDefinition(): StateMachine.StateMachineDefinition =
      StateMachine.StateMachineDefinition(
        states = Map.empty,
        initialState = StateMachine.StateId("nonexistent"),
        transitions = List.empty
      )

    def invalidInitialStateDefinition(): StateMachine.StateMachineDefinition = {
      val existing = StateMachine.StateId("existing")
      StateMachine.StateMachineDefinition(
        states = Map(existing -> StateMachine.State(existing)),
        initialState = StateMachine.StateId("nonexistent"),
        transitions = List.empty
      )
    }

    def invalidTransitionFromDefinition(): StateMachine.StateMachineDefinition = {
      val stateA = StateMachine.StateId("stateA")
      StateMachine.StateMachineDefinition(
        states = Map(stateA -> StateMachine.State(stateA)),
        initialState = stateA,
        transitions = List(
          StateMachine.Transition(
            from = StateMachine.StateId("nonexistent"),
            to = stateA,
            eventType = StateMachine.EventType("test"),
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty))
          )
        )
      )
    }

    def invalidTransitionToDefinition(): StateMachine.StateMachineDefinition = {
      val stateA = StateMachine.StateId("stateA")
      StateMachine.StateMachineDefinition(
        states = Map(stateA -> StateMachine.State(stateA)),
        initialState = stateA,
        transitions = List(
          StateMachine.Transition(
            from = stateA,
            to = StateMachine.StateId("nonexistent"),
            eventType = StateMachine.EventType("test"),
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty))
          )
        )
      )
    }

    def duplicateTransitionsDefinition(): StateMachine.StateMachineDefinition = {
      val stateA = StateMachine.StateId("stateA")
      val stateB = StateMachine.StateId("stateB")
      val transition = StateMachine.Transition(
        from = stateA,
        to = stateB,
        eventType = StateMachine.EventType("test"),
        guard = ConstExpression(BoolValue(true)),
        effect = ConstExpression(MapValue(Map.empty))
      )
      StateMachine.StateMachineDefinition(
        states = Map(stateA -> StateMachine.State(stateA), stateB -> StateMachine.State(stateB)),
        initialState = stateA,
        transitions = List(transition, transition)
      )
    }

    def ambiguousTransitionsDefinition(): StateMachine.StateMachineDefinition = {
      val stateA = StateMachine.StateId("stateA")
      val stateB = StateMachine.StateId("stateB")
      val stateC = StateMachine.StateId("stateC")
      StateMachine.StateMachineDefinition(
        states = Map(
          stateA -> StateMachine.State(stateA),
          stateB -> StateMachine.State(stateB),
          stateC -> StateMachine.State(stateC)
        ),
        initialState = stateA,
        transitions = List(
          StateMachine.Transition(
            from = stateA,
            to = stateB,
            eventType = StateMachine.EventType("test"),
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty))
          ),
          StateMachine.Transition(
            from = stateA,
            to = stateC,
            eventType = StateMachine.EventType("test"),
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty))
          )
        )
      )
    }

    def simpleOracleScript(): JsonLogicExpression =
      ConstExpression(MapValue(Map("result" -> IntValue(42))))
  }

  // ============== CID Not Used Tests (L1) ==============

  test("cidNotUsed: new fiber ID accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.minimalDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("cidNotUsed: duplicate fiber ID rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates.CreateStateMachineFiber(cid, Fixtures.minimalDefinition(), MapValue(Map.empty))
        proof <- fixture.registry.generateProofs(createUpdate, Set(Alice))

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
        stateAfterCreate <- combiner.insert(inState, Signed(createUpdate, proof))

        // Now try to validate creating the same fiber again - L0 level with existing state
        result <- validator.validateSignedUpdate(stateAfterCreate, Signed(createUpdate, proof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("already exists"))))
    }
  }

  // ============== Valid State Machine Definition Tests (L1) ==============

  test("validStateMachineDefinition: empty definition rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.emptyDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("no states"))))
    }
  }

  test("validStateMachineDefinition: invalid initial state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.invalidInitialStateDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("initial state"))))
    }
  }

  test("validStateMachineDefinition: invalid transition 'from' state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.invalidTransitionFromDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("invalid from state"))))
    }
  }

  test("validStateMachineDefinition: invalid transition 'to' state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.invalidTransitionToDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("invalid to state"))))
    }
  }

  test("validStateMachineDefinition: duplicate transitions rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.duplicateTransitionsDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("duplicate"))))
    }
  }

  test("validStateMachineDefinition: ambiguous transitions rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.ambiguousTransitionsDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("ambiguous"))))
    }
  }

  // ============== Initial Data Is MapValue Tests (L1) ==============

  test("initialDataIsMapValue: MapValue accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachineFiber(cid, Fixtures.minimalDefinition(), MapValue(Map("key" -> StrValue("value"))))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialDataIsMapValue: non-MapValue rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.minimalDefinition(), ArrayValue(List(IntValue(1))))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("mapvalue"))))
    }
  }

  // ============== CID Is Found Tests (L1) ==============

  test("cidIsFound: existing fiber ID accepted for ProcessFiberEvent") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("cidIsFound: non-existent fiber ID rejected for ProcessFiberEvent") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result <- validator
          .validateSignedUpdate(
            DataState(OnChain.genesis, CalculatedState.genesis),
            Signed(processUpdate, processProof)
          )
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("not found"))))
    }
  }

  // ============== Event Payload Is Valid Tests (L1) ==============

  test("eventPayloadIsValid: non-null payload accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.ProcessFiberEvent(
          cid,
          StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map("data" -> IntValue(123))))
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("eventPayloadIsValid: null payload rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), NullValue))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("null"))))
    }
  }

  // ============== Fiber Is Active Tests (L0) ==============

  test("fiberIsActive: active fiber accepts events") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("fiberIsActive: archived fiber rejects events") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        archiveUpdate = Updates.ArchiveFiber(cid)
        archiveProof      <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterCreate, Signed(archiveUpdate, archiveProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterArchive, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("not active"))))
    }
  }

  // ============== Update Signed By Owners Tests (L0) ==============

  test("updateSignedByOwners: owner signature accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("updateSignedByOwners: non-owner signature rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Bob))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("owner"))))
    }
  }

  // ============== Transition Exists Tests (L0) ==============

  test("transitionExists: valid transition accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("advance"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("transitionExists: undefined transition rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachineFiber(cid, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .ProcessFiberEvent(cid, StateMachine.Event(StateMachine.EventType("nonexistent"), MapValue(Map.empty)))
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("transition"))))
    }
  }

  // ============== Oracle Initial State Tests (L1) ==============

  test("initialStateIsMapValueOrNull: None accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateScriptOracle(cid, Fixtures.simpleOracleScript(), None, Records.AccessControlPolicy.Public)
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialStateIsMapValueOrNull: MapValue accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateScriptOracle(
          cid,
          Fixtures.simpleOracleScript(),
          Some(MapValue(Map("counter" -> IntValue(0)))),
          Records.AccessControlPolicy.Public
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialStateIsMapValueOrNull: non-MapValue rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateScriptOracle(
          cid,
          Fixtures.simpleOracleScript(),
          Some(ArrayValue(List(IntValue(1)))),
          Records.AccessControlPolicy.Public
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("mapvalue"))))
    }
  }

  // ============== Parent Fiber Validation Tests ==============

  test("parentFiberExistsInOnChain: non-existent parent rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        parentId  <- UUIDGen.randomUUID[IO]

        update = Updates.CreateStateMachineFiber(cid, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.contains("Parent fiber"))))
    }
  }

  test("parentFiberActive: archived parent rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        parentId  <- UUIDGen.randomUUID[IO]
        childId   <- UUIDGen.randomUUID[IO]

        createParent = Updates.CreateStateMachineFiber(parentId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        createParentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createParent, createParentProof))

        archiveParent = Updates.ArchiveFiber(parentId)
        archiveProof      <- fixture.registry.generateProofs(archiveParent, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterCreate, Signed(archiveParent, archiveProof))

        createChild = Updates
          .CreateStateMachineFiber(childId, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        createChildProof <- fixture.registry.generateProofs(createChild, Set(Alice))
        result           <- validator.validateSignedUpdate(stateAfterArchive, Signed(createChild, createChildProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("not active"))))
    }
  }

  test("parentFiberActive: active parent accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        parentId  <- UUIDGen.randomUUID[IO]
        childId   <- UUIDGen.randomUUID[IO]

        createParent = Updates.CreateStateMachineFiber(parentId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        createParentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createParent, createParentProof))

        createChild = Updates
          .CreateStateMachineFiber(childId, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        createChildProof <- fixture.registry.generateProofs(createChild, Set(Alice))
        result           <- validator.validateSignedUpdate(stateAfterCreate, Signed(createChild, createChildProof))
      } yield expect(result.isValid)
    }
  }

  // ============== Oracle Access Control Tests (L0) ==============

  test("oracleAccessControlCheck: public policy allows any caller") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        oracleId  <- UUIDGen.randomUUID[IO]

        createOracle = Updates
          .CreateScriptOracle(oracleId, Fixtures.simpleOracleScript(), None, Records.AccessControlPolicy.Public)
        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        invokeOracle = Updates.InvokeScriptOracle(oracleId, "test", MapValue(Map.empty))
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Bob))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeOracle, invokeProof))
      } yield expect(result.isValid)
    }
  }

  test("oracleAccessControlCheck: whitelist allows authorized caller") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        oracleId  <- UUIDGen.randomUUID[IO]
        bobAddr   <- fixture.registry.addresses(Bob).pure[IO]

        createOracle = Updates.CreateScriptOracle(
          oracleId,
          Fixtures.simpleOracleScript(),
          None,
          Records.AccessControlPolicy.Whitelist(Set(bobAddr))
        )
        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        invokeOracle = Updates.InvokeScriptOracle(oracleId, "test", MapValue(Map.empty))
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Bob))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeOracle, invokeProof))
      } yield expect(result.isValid)
    }
  }

  test("oracleAccessControlCheck: whitelist denies unauthorized caller") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO].pure[IO]
        validator <- Validator.make[IO]
        oracleId  <- UUIDGen.randomUUID[IO]
        bobAddr   <- fixture.registry.addresses(Bob).pure[IO]

        createOracle = Updates.CreateScriptOracle(
          oracleId,
          Fixtures.simpleOracleScript(),
          None,
          Records.AccessControlPolicy.Whitelist(Set(bobAddr))
        )
        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        invokeOracle = Updates.InvokeScriptOracle(oracleId, "test", MapValue(Map.empty))
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Charlie))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeOracle, invokeProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("access denied"))))
    }
  }

  // ============== Definition Size Limit Tests (L1) ==============

  test("definitionWithinLimits: too many states rejected (>100)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.definitionWithStates(101), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many states"))))
    }
  }

  test("definitionWithinLimits: exactly 100 states accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.definitionWithStates(100), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("definitionWithinLimits: too many transitions rejected (>500)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.definitionWithTransitions(501), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many transitions"))))
    }
  }

  test("definitionWithinLimits: exactly 500 transitions accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachineFiber(cid, Fixtures.definitionWithTransitions(500), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("definitionWithinLimits: too many transitions per state rejected (>20)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachineFiber(cid, Fixtures.definitionWithTransitionsPerState(21), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many transitions per state"))))
    }
  }

  test("definitionWithinLimits: exactly 20 transitions per state accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        cid       <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachineFiber(cid, Fixtures.definitionWithTransitionsPerState(20), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }
}
