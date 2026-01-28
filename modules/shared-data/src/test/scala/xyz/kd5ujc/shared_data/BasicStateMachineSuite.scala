package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BasicStateMachineSuite extends SimpleIOSuite with Checkers {

  def createCounterStateMachine(): StateMachineDefinition = {
    val waitingState = StateId("waiting")
    val countingState = StateId("counting")
    val doneState = StateId("done")

    StateMachineDefinition(
      states = Map(
        waitingState  -> State(waitingState, isFinal = false),
        countingState -> State(countingState, isFinal = false),
        doneState     -> State(doneState, isFinal = true)
      ),
      initialState = waitingState,
      transitions = List(
        Transition(
          from = waitingState,
          to = countingState,
          eventType = EventType("start"),
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(
            MapValue(
              Map(
                "counter" -> IntValue(0),
                "active"  -> BoolValue(true)
              )
            )
          )
        ),
        Transition(
          from = countingState,
          to = countingState,
          eventType = EventType("increment"),
          guard = ApplyExpression(
            Lt,
            List(
              VarExpression(Left("state.counter")),
              ConstExpression(IntValue(10))
            )
          ),
          effect = ArrayExpression(
            List(
              ArrayExpression(
                List(
                  ConstExpression(StrValue("counter")),
                  ApplyExpression(
                    AddOp,
                    List(
                      VarExpression(Left("state.counter")),
                      ConstExpression(IntValue(1))
                    )
                  )
                )
              ),
              ArrayExpression(
                List(
                  ConstExpression(StrValue("active")),
                  ConstExpression(BoolValue(true))
                )
              )
            )
          )
        ),
        Transition(
          from = countingState,
          to = doneState,
          eventType = EventType("finish"),
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(
            ArrayValue(
              List(
                ArrayValue(
                  List(
                    StrValue("counter"),
                    IntValue(99)
                  )
                ),
                ArrayValue(
                  List(
                    StrValue("active"),
                    BoolValue(false)
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  test("create state machine fiber with initial state") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        update = Updates.CreateStateMachine(cid, definition, initialData)
        updateProof <- fixture.registry.generateProofs(update, Set(Alice, Bob))

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
        outState <- combiner.insert(inState, Signed(update, updateProof))

        fiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(fiber.isDefined) and
      expect(fiber.map(_.currentState).contains(StateId("waiting"))) and
      expect(fiber.map(_.sequenceNumber).contains(0L)) and
      expect(fiber.map(_.status).contains(FiberStatus.Active))
    }
  }

  test("process 'start' event transitions from waiting to counting") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createUpdate = Updates.CreateStateMachine(cid, definition, initialData)
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice, Bob))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("start"),
          MapValue(Map.empty[String, JsonLogicValue])
        )
        processProof    <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        stateAfterStart <- combiner.insert(stateAfterCreate, Signed(processUpdate, processProof))

        fiber = stateAfterStart.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = fiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(fiber.isDefined) and
      expect(fiber.map(_.currentState).contains(StateId("counting"))) and
      expect(fiber.map(_.sequenceNumber).contains(1L)) and
      expect(counterValue.contains(BigInt(0)))
    }
  }

  test("process 'increment' event increases counter") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(5),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("increment"),
          MapValue(Map.empty[String, JsonLogicValue])
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
        activeValue: Option[Boolean] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("active").collect { case BoolValue(a) => a }
            case _           => None
          }
        }
      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("counting"))) and
      expect(updatedFiber.map(_.sequenceNumber).contains(1L)) and
      expect(counterValue.contains(BigInt(6))) and
      expect(activeValue.contains(true))
    }
  }

  test("process 'finish' event transitions to done state") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(7),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("finish"),
          MapValue(Map.empty[String, JsonLogicValue])
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        activeValue: Option[Boolean] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("active").collect { case BoolValue(a) => a }
            case _           => None
          }
        }
        counterValue: Option[BigInt] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("done"))) and
      expect(updatedFiber.map(_.sequenceNumber).contains(1L)) and
      expect(activeValue.contains(false)) and
      expect(counterValue.contains(BigInt(99)))
    }
  }

  test("guard condition prevents transition when counter >= 10") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(10),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("increment"),
          MapValue(Map.empty[String, JsonLogicValue])
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))

        result <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("counting"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(0L)) and // Sequence not incremented
      expect(updatedFiber.map(_.lastEventStatus).exists {
        case EventProcessingStatus.GuardFailed(_, _, _) => true
        case _                                          => false
      })
    }
  }

  test("archive fiber changes status to archived") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        archiveUpdate = Updates.ArchiveStateMachine(cid)
        archiveProof <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        archivedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(archivedFiber.isDefined) and
      expect(archivedFiber.map(_.status).contains(FiberStatus.Archived))
    }
  }

  test("archived fiber rejects events") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        // Archive the fiber first
        archiveUpdate = Updates.ArchiveStateMachine(cid)
        archiveProof  <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        archivedState <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        // Attempt to process an event on the archived fiber
        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("start"),
          MapValue(Map.empty)
        )
        eventProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))

        // The event should record failure because fiber is archived
        eventResult <- combiner.insert(archivedState, Signed(processUpdate, eventProof))

        // Get the updated fiber to check its status
        updatedFiber = eventResult.calculated.stateMachines.get(cid)

      } yield updatedFiber match {
        case Some(fiber) =>
          // Archived fiber should have ExecutionFailed status recorded
          fiber.lastEventStatus match {
            case EventProcessingStatus.ExecutionFailed(reason, _, _, _, _) =>
              expect(reason.toLowerCase.contains("not active"), s"Expected 'not active' in reason, got: $reason")
            case other =>
              failure(s"Expected ExecutionFailed status, got: $other")
          }
        case None =>
          failure(s"Fiber $cid not found in result state")
      }
    }
  }

  test("sequence number increments correctly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(0),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("increment"),
          MapValue(Map.empty[String, JsonLogicValue])
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        finalState   <- combiner.insert(inState, Signed(processUpdate, processProof))

        finalFiber = finalState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = finalFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(finalFiber.isDefined) and
      expect(finalFiber.map(_.sequenceNumber).contains(1L)) and
      expect(counterValue.contains(BigInt(1)))
    }
  }

  test("event payload is accessible in guard condition") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("open")   -> State(StateId("open")),
            StateId("locked") -> State(StateId("locked"))
          ),
          initialState = StateId("open"),
          transitions = List(
            Transition(
              from = StateId("open"),
              to = StateId("locked"),
              eventType = EventType("lock"),
              guard = ApplyExpression(
                EqStrictOp,
                List(
                  VarExpression(Left("event.authorized")),
                  ConstExpression(BoolValue(true))
                )
              ),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "locked" -> BoolValue(true)
                  )
                )
              )
            )
          )
        )

        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("lock"),
          MapValue(Map("authorized" -> BoolValue(true)))
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("locked")))
    }
  }

  test("unauthorized event payload fails guard condition") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("open")   -> State(StateId("open")),
            StateId("locked") -> State(StateId("locked"))
          ),
          initialState = StateId("open"),
          transitions = List(
            Transition(
              from = StateId("open"),
              to = StateId("locked"),
              eventType = EventType("lock"),
              guard = ApplyExpression(
                EqStrictOp,
                List(
                  VarExpression(Left("event.authorized")),
                  ConstExpression(BoolValue(true))
                )
              ),
              effect = ConstExpression(MapValue(Map("locked" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = cid,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = FiberStatus.Active,
          lastEventStatus = EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        processUpdate = Updates.TransitionStateMachine(
          cid,
          EventType("lock"),
          MapValue(Map("authorized" -> BoolValue(false)))
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("open"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(0L)) and // Sequence not incremented
      expect(updatedFiber.map(_.lastEventStatus).exists {
        case EventProcessingStatus.GuardFailed(_, _, _) => true
        case _                                          => false
      })
    }
  }

  test("multiple sequential increments") {
    forall(Gen.choose(1, 9)) { increments =>
      TestFixture.resource().use { fixture =>
        implicit val s: SecurityProvider[IO] = fixture.securityProvider
        implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
        for {
          combiner <- Combiner.make[IO].pure[IO]

          cid <- UUIDGen.randomUUID[IO]
          definition = createCounterStateMachine()

          initialData = MapValue(
            Map(
              "counter" -> IntValue(0),
              "active"  -> BoolValue(true)
            )
          )
          initialHash <- (initialData: JsonLogicValue).computeDigest

          fiber = Records.StateMachineFiberRecord(
            cid = cid,
            creationOrdinal = fixture.ordinal,
            previousUpdateOrdinal = fixture.ordinal,
            latestUpdateOrdinal = fixture.ordinal,
            definition = definition,
            currentState = StateId("counting"),
            stateData = initialData,
            stateDataHash = initialHash,
            sequenceNumber = 0,
            owners = Set(Alice, Bob).map(fixture.registry.addresses),
            status = FiberStatus.Active,
            lastEventStatus = EventProcessingStatus.Initialized
          )

          inState = DataState(
            OnChain(Map(cid -> initialHash)),
            CalculatedState(Map(cid -> fiber), Map.empty)
          )

          finalState <- (1 to increments).toList.foldLeftM(inState) { (state, _) =>
            val processUpdate = Updates.TransitionStateMachine(
              cid,
              EventType("increment"),
              MapValue(Map.empty[String, JsonLogicValue])
            )
            for {
              processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
              newState     <- combiner.insert(state, Signed(processUpdate, processProof))
            } yield newState
          }

          finalFiber = finalState.calculated.stateMachines
            .get(cid)
            .collect { case r: Records.StateMachineFiberRecord => r }

          counterValue: Option[BigInt] = finalFiber.flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
              case _           => None
            }
          }
        } yield expect(finalFiber.isDefined) and
        expect(finalFiber.map(_.sequenceNumber).contains(increments.toLong)) and
        expect(counterValue.contains(BigInt(increments)))
      }
    }
  }
}
