package xyz.kd5ujc.shared_data.examples

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Unit tests for the counter oracle (stateful with non-null initialState).
 *
 * NOTE: The e2e definition uses "count" as a field name, but "count" is a reserved
 * JLVM operation (see metakit JsonLogicOp.scala line 55). Single-key objects like
 * { "count": {...} } are interpreted as operation calls, not object literals.
 *
 * Solution: Use "value" instead of "count" to avoid the reserved operation name.
 */
object CounterOracleSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  private val counterScript =
    """|{
       |  "if": [
       |    { "==": [{ "var": "method" }, "increment"] },
       |    { "merge": [{ "var": "state" }, { "value": { "+": [{ "var": "state.value" }, 1] } }] },
       |    { "==": [{ "var": "method" }, "decrement"] },
       |    { "merge": [{ "var": "state" }, { "value": { "-": [{ "var": "state.value" }, 1] } }] },
       |    { "==": [{ "var": "method" }, "reset"] },
       |    { "value": 0 },
       |    { "var": "state" }
       |  ]
       |}""".stripMargin

  private val counterInitialState = MapValue(Map("value" -> IntValue(0)))

  test("creation with initialState") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        oracle = state.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(counterInitialState),
        oracle.flatMap(_.stateDataHash).isDefined,
        oracle.map(_.invocationCount).contains(0L)
      )
    }
  }

  test("increment operation (0 -> 1)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "increment",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("decrement operation (0 -> -1)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "decrement",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(-1)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("reset operation (any -> 0)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        // Start with non-zero initial state
        nonZeroInitial = MapValue(Map("value" -> IntValue(42)))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(nonZeroInitial),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "reset",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(0)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("multiple increments maintain state correctly") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First increment (0 -> 1)
        invoke1 = Updates.InvokeScriptOracle(cid, "increment", MapValue(Map.empty))
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        oracle1 = state1.calculated.scriptOracles.get(cid)

        // Second increment (1 -> 2)
        invoke2 = Updates.InvokeScriptOracle(cid, "increment", MapValue(Map.empty))
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        oracle2 = state2.calculated.scriptOracles.get(cid)

        // Third increment (2 -> 3)
        invoke3 = Updates.InvokeScriptOracle(cid, "increment", MapValue(Map.empty))
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        oracle3 = state3.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle1.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(1)))),
        oracle2.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(2)))),
        oracle3.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(3)))),
        oracle3.map(_.invocationCount).contains(3L),
        oracle3.map(_.invocationLog.size).contains(3)
      )
    }
  }

  test("mixed operations (increment, increment, decrement)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First increment (0 -> 1)
        invoke1 = Updates.InvokeScriptOracle(cid, "increment", MapValue(Map.empty))
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        // Second increment (1 -> 2)
        invoke2 = Updates.InvokeScriptOracle(cid, "increment", MapValue(Map.empty))
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        // Decrement (2 -> 1)
        invoke3 = Updates.InvokeScriptOracle(cid, "decrement", MapValue(Map.empty))
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        oracle = state3.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(3L)
      )
    }
  }

  test("works with non-zero initial value") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        // Start with non-zero value
        initialState = MapValue(Map("value" -> IntValue(5)))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(initialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "increment",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(6)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("state hash changes after invocation") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        oracleBefore = state1.calculated.scriptOracles.get(cid)
        hashBefore = oracleBefore.flatMap(_.stateDataHash)

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "increment",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracleAfter = state2.calculated.scriptOracles.get(cid)
        hashAfter = oracleAfter.flatMap(_.stateDataHash)
      } yield expect.all(
        hashBefore.isDefined,
        hashAfter.isDefined,
        hashBefore != hashAfter,
        oracleBefore.flatMap(_.stateData) != oracleAfter.flatMap(_.stateData)
      )
    }
  }

  test("signature verification with multiple signers") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        // Create with both Alice and Bob signing
        createProof <- registry.generateProofs(createOracle, Set(Alice, Bob))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        oracle = state1.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(counterInitialState),
        oracle.map(_.owners.size).contains(2)
      )
    }
  }

  test("invocation by different signer than creator") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        // Alice creates the oracle
        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "increment",
          args = MapValue(Map.empty)
        )

        // Bob invokes the oracle (should work for Public access control)
        invokeProof <- registry.generateProofs(invokeOracle, Set(Bob))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("verifies onChain hash is updated correctly") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // Check onChain has hash for this oracle
        initialOnChainHash = state1.onChain.latest.get(cid)
        initialStateHash = state1.calculated.scriptOracles.get(cid).flatMap(_.stateDataHash)

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "increment",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        // Check onChain hash was updated
        updatedOnChainHash = state2.onChain.latest.get(cid)
        updatedStateHash = state2.calculated.scriptOracles.get(cid).flatMap(_.stateDataHash)
      } yield expect.all(
        initialOnChainHash.isDefined,
        initialStateHash.isDefined,
        initialOnChainHash == initialStateHash,
        updatedOnChainHash.isDefined,
        updatedStateHash.isDefined,
        updatedOnChainHash == updatedStateHash,
        initialOnChainHash != updatedOnChainHash
      )
    }
  }
}
