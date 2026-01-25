package xyz.kd5ujc.shared_data.examples

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Unit tests for the calculator oracle (stateless - initialState: null).
 * Based on e2e-test/examples/calculator-oracle/definition.json
 */
object CalculatorOracleSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  // Calculator Oracle definition (stateless) - matches e2e definition
  private val calculatorScript =
    """|{
       |  "if": [
       |    { "==": [{ "var": "method" }, "add"] },
       |    { "+": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "subtract"] },
       |    { "-": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "multiply"] },
       |    { "*": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "divide"] },
       |    { "/": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    null
       |  ]
       |}""".stripMargin

  test("add operation (10 + 5 = 15)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "add",
          args = MapValue(Map("a" -> IntValue(10), "b" -> IntValue(5)))
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(15)),
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("subtract operation (20 - 8 = 12)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "subtract",
          args = MapValue(Map("a" -> IntValue(20), "b" -> IntValue(8)))
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(12))
      )
    }
  }

  test("multiply operation (7 * 6 = 42)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "multiply",
          args = MapValue(Map("a" -> IntValue(7), "b" -> IntValue(6)))
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(42))
      )
    }
  }

  test("divide operation (100 / 4 = 25)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "divide",
          args = MapValue(Map("a" -> IntValue(100), "b" -> IntValue(4)))
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(25))
      )
    }
  }

  test("unknown method returns null") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "unknownMethod",
          args = MapValue(Map("a" -> IntValue(1), "b" -> IntValue(2)))
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(NullValue)
      )
    }
  }

  test("multiple invocations are logged") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First invocation: add
        invoke1 = Updates.InvokeScriptOracle(cid, "add", MapValue(Map("a" -> IntValue(1), "b" -> IntValue(2))))
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        // Second invocation: multiply
        invoke2 = Updates.InvokeScriptOracle(cid, "multiply", MapValue(Map("a" -> IntValue(3), "b" -> IntValue(4))))
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        // Third invocation: subtract
        invoke3 = Updates.InvokeScriptOracle(cid, "subtract", MapValue(Map("a" -> IntValue(10), "b" -> IntValue(5))))
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        oracle = state3.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle.map(_.invocationCount).contains(3L),
        oracle.map(_.invocationLog.size).contains(3)
      )
    }
  }

  test("invocation by different signer (Public access)") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        // Alice creates
        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "add",
          args = MapValue(Map("a" -> IntValue(5), "b" -> IntValue(3)))
        )

        // Bob invokes
        invokeProof <- registry.generateProofs(invokeOracle, Set(Bob))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        result = oracle.flatMap(_.invocationLog.headOption.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(8))
      )
    }
  }
}
