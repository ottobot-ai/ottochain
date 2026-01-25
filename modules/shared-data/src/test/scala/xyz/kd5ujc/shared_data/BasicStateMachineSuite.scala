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

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BasicStateMachineSuite extends SimpleIOSuite with Checkers {

  def createCounterStateMachine(): StateMachine.StateMachineDefinition = {
    val waitingState = StateMachine.StateId("waiting")
    val countingState = StateMachine.StateId("counting")
    val doneState = StateMachine.StateId("done")

    StateMachine.StateMachineDefinition(
      states = Map(
        waitingState  -> StateMachine.State(waitingState, isFinal = false),
        countingState -> StateMachine.State(countingState, isFinal = false),
        doneState     -> StateMachine.State(doneState, isFinal = true)
      ),
      initialState = waitingState,
      transitions = List(
        StateMachine.Transition(
          from = waitingState,
          to = countingState,
          eventType = StateMachine.EventType("start"),
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
        StateMachine.Transition(
          from = countingState,
          to = countingState,
          eventType = StateMachine.EventType("increment"),
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
        StateMachine.Transition(
          from = countingState,
          to = doneState,
          eventType = StateMachine.EventType("finish"),
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

        update = Updates.CreateStateMachineFiber(cid, definition, initialData)
        updateProof <- fixture.registry.generateProofs(update, Set(Alice, Bob))

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
        outState <- combiner.insert(inState, Signed(update, updateProof))

        fiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(fiber.isDefined) and
      expect(fiber.map(_.currentState).contains(StateMachine.StateId("waiting"))) and
      expect(fiber.map(_.sequenceNumber).contains(0L)) and
      expect(fiber.map(_.status).contains(Records.FiberStatus.Active))
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

        createUpdate = Updates.CreateStateMachineFiber(cid, definition, initialData)
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice, Bob))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        startEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        processUpdate = Updates.ProcessFiberEvent(cid, startEvent)
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
      expect(fiber.map(_.currentState).contains(StateMachine.StateId("counting"))) and
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
          currentState = StateMachine.StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        incrementEvent = StateMachine.Event(
          eventType = StateMachine.EventType("increment"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        processUpdate = Updates.ProcessFiberEvent(cid, incrementEvent)
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
      expect(updatedFiber.map(_.currentState).contains(StateMachine.StateId("counting"))) and
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
          currentState = StateMachine.StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        finishEvent = StateMachine.Event(
          eventType = StateMachine.EventType("finish"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        processUpdate = Updates.ProcessFiberEvent(cid, finishEvent)
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
      expect(updatedFiber.map(_.currentState).contains(StateMachine.StateId("done"))) and
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
          currentState = StateMachine.StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        incrementEvent = StateMachine.Event(
          eventType = StateMachine.EventType("increment"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        processUpdate = Updates.ProcessFiberEvent(cid, incrementEvent)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))

        result <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateMachine.StateId("counting"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(0L)) and // Sequence not incremented
      expect(updatedFiber.map(_.lastEventStatus).exists {
        case Records.EventProcessingStatus.GuardFailed(_, _, _) => true
        case _                                                  => false
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
          currentState = StateMachine.StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        archiveUpdate = Updates.ArchiveFiber(cid)
        archiveProof <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        archivedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(archivedFiber.isDefined) and
      expect(archivedFiber.map(_.status).contains(Records.FiberStatus.Archived))
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
          currentState = StateMachine.StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        // Archive the fiber first
        archiveUpdate = Updates.ArchiveFiber(cid)
        archiveProof  <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        archivedState <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        // Attempt to process an event on the archived fiber
        startEvent = Updates.ProcessFiberEvent(
          cid,
          StateMachine.Event(StateMachine.EventType("start"), MapValue(Map.empty))
        )
        eventProof <- fixture.registry.generateProofs(startEvent, Set(Alice))

        // The event should fail because fiber is archived
        eventResult <- combiner.insert(archivedState, Signed(startEvent, eventProof)).attempt

      } yield eventResult match {
        case Left(err) =>
          // Should fail with an error about fiber not being active
          expect(
            err.getMessage.toLowerCase.contains("archived") ||
            err.getMessage.toLowerCase.contains("not active") ||
            err.getMessage.toLowerCase.contains("inactive")
          )
        case Right(state) =>
          // If it doesn't throw, check that the fiber wasn't modified
          val fiberAfter = state.calculated.stateMachines.get(cid)
          expect(fiberAfter.isDefined) and
          expect(fiberAfter.map(_.status).contains(Records.FiberStatus.Archived)) and
          // Sequence number should not have changed
          expect(
            fiberAfter
              .flatMap {
                case r: Records.StateMachineFiberRecord => Some(r.sequenceNumber)
                case _                                  => None
              }
              .contains(0L)
          )
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
          currentState = StateMachine.StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        incrementEvent = StateMachine.Event(
          eventType = StateMachine.EventType("increment"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )

        processUpdate = Updates.ProcessFiberEvent(cid, incrementEvent)
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

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("open")   -> StateMachine.State(StateMachine.StateId("open")),
            StateMachine.StateId("locked") -> StateMachine.State(StateMachine.StateId("locked"))
          ),
          initialState = StateMachine.StateId("open"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("open"),
              to = StateMachine.StateId("locked"),
              eventType = StateMachine.EventType("lock"),
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
          currentState = StateMachine.StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        lockEvent = StateMachine.Event(
          eventType = StateMachine.EventType("lock"),
          payload = MapValue(Map("authorized" -> BoolValue(true)))
        )
        processUpdate = Updates.ProcessFiberEvent(cid, lockEvent)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateMachine.StateId("locked")))
    }
  }

  test("unauthorized event payload fails guard condition") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid <- UUIDGen.randomUUID[IO]

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("open")   -> StateMachine.State(StateMachine.StateId("open")),
            StateMachine.StateId("locked") -> StateMachine.State(StateMachine.StateId("locked"))
          ),
          initialState = StateMachine.StateId("open"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("open"),
              to = StateMachine.StateId("locked"),
              eventType = StateMachine.EventType("lock"),
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
          currentState = StateMachine.StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(cid -> initialHash)),
          CalculatedState(Map(cid -> fiber), Map.empty)
        )

        lockEvent = StateMachine.Event(
          eventType = StateMachine.EventType("lock"),
          payload = MapValue(Map("authorized" -> BoolValue(false)))
        )
        processUpdate = Updates.ProcessFiberEvent(cid, lockEvent)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateMachine.StateId("open"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(0L)) and // Sequence not incremented
      expect(updatedFiber.map(_.lastEventStatus).exists {
        case Records.EventProcessingStatus.GuardFailed(_, _, _) => true
        case _                                                  => false
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
            currentState = StateMachine.StateId("counting"),
            stateData = initialData,
            stateDataHash = initialHash,
            sequenceNumber = 0,
            owners = Set(Alice, Bob).map(fixture.registry.addresses),
            status = Records.FiberStatus.Active,
            lastEventStatus = Records.EventProcessingStatus.Initialized
          )

          inState = DataState(
            OnChain(Map(cid -> initialHash)),
            CalculatedState(Map(cid -> fiber), Map.empty)
          )

          incrementEvent = StateMachine.Event(
            eventType = StateMachine.EventType("increment"),
            payload = MapValue(Map.empty[String, JsonLogicValue])
          )

          finalState <- (1 to increments).toList.foldLeftM(inState) { (state, _) =>
            val processUpdate = Updates.ProcessFiberEvent(cid, incrementEvent)
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
