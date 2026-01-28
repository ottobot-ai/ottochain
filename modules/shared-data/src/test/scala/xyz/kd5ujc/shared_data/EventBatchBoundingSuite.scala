package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Tests for eventBatch behavior and maxEventBatchSize field.
 *
 * The eventBatch tracks the event processing status from the most recent transaction.
 * It is replaced (not accumulated) with each new transaction.
 */
object EventBatchBoundingSuite extends SimpleIOSuite {

  /** Simple state machine definition that allows repeated events */
  private val counterDefinitionJson =
    """|{
       |  "states": {
       |    "counting": { "id": { "value": "counting" }, "isFinal": false }
       |  },
       |  "initialState": { "value": "counting" },
       |  "transitions": [
       |    {
       |      "from": { "value": "counting" },
       |      "to": { "value": "counting" },
       |      "eventType": { "value": "increment" },
       |      "guard": true,
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "count": { "+": [{ "var": "state.count" }, 1] } }
       |        ]
       |      },
       |      "dependencies": []
       |    }
       |  ]
       |}""".stripMargin

  test("eventBatch contains status from most recent transaction") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        def_ <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(cid, def_, MapValue(Map("count" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        // Process first event
        event1 = Updates.TransitionStateMachine(cid, EventType("increment"), MapValue(Map.empty))
        proof1 <- fixture.registry.generateProofs(event1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(event1, proof1))

        machine1 = state1.calculated.stateMachines.get(cid)

        // Process second event
        event2 = Updates.TransitionStateMachine(cid, EventType("increment"), MapValue(Map.empty))
        proof2 <- fixture.registry.generateProofs(event2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(event2, proof2))

        machine2 = state2.calculated.stateMachines.get(cid)
      } yield expect(machine1.isDefined) and
      expect(machine2.isDefined) and
      // After first event, batch contains status for that event
      expect(machine1.exists(_.eventBatch.nonEmpty)) and
      // After second event, batch is replaced with new status
      expect(machine2.exists(_.eventBatch.nonEmpty))
    }
  }

  test("maxEventBatchSize field has sensible default") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        def_ <- IO.fromEither(parser.parse(counterDefinitionJson).flatMap(_.as[StateMachineDefinition]))

        createSM = Updates.CreateStateMachine(cid, def_, MapValue(Map("count" -> IntValue(0))))

        createProof <- fixture.registry.generateProofs(createSM, Set(Alice))
        state0 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createSM, createProof))

        machine = state0.calculated.stateMachines.get(cid)
      } yield expect(machine.isDefined) and
      // Default maxEventBatchSize should be 100
      expect(machine.exists(_.maxEventBatchSize == 100))
    }
  }
}
