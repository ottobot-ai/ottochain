package xyz.kd5ujc.shared_data

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner

import io.circe.parser
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant
import zyx.kd5ujc.shared_test.Participant._

object StatefulOracleSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("oracle state initialization: null initial state") {
    val oracleScript = """{"method":{"var":"method"},"result":42}"""

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        oracle = state.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.stateData).contains(None),
        oracle.map(_.stateDataHash).contains(None),
        oracle.map(_.invocationCount).contains(0L)
      )
    }
  }

  test("oracle state transformation: explicit _state and _result") {
    val oracleScript = """|{
                          |  "_state": {"counter": 5},
                          |  "_result": {"success": true, "newValue": 5}
                          |}""".stripMargin

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("counter" -> IntValue(0)))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = Records.AccessControlPolicy.Public
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
        expectedState = MapValue(Map("counter" -> IntValue(5)))
        expectedResult = MapValue(Map("success" -> BoolValue(true), "newValue" -> IntValue(5)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L),
        oracle.flatMap(_.invocationLog.headOption.map(_.result)).contains(expectedResult)
      )
    }
  }

  test("oracle state transformation: simple return value becomes state") {
    val oracleScript = """{"counter": 1}"""

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

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
          method = "increment",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("counter" -> IntValue(1)))
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.map(_.invocationCount).contains(1L),
        oracle.flatMap(_.invocationLog.headOption.map(_.result)).contains(expectedState)
      )
    }
  }

  test("oracle state transformation: _state only (result is full response)") {
    val oracleScript = """|{
                          |  "_state": {"visits": 1},
                          |  "message": "Visit recorded"
                          |}""".stripMargin

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("visits" -> IntValue(0)))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = cid,
          method = "visit",
          args = MapValue(Map.empty)
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        expectedState = MapValue(Map("visits" -> IntValue(1)))
        expectedResult = MapValue(
          Map(
            "_state"  -> MapValue(Map("visits" -> IntValue(1))),
            "message" -> StrValue("Visit recorded")
          )
        )
      } yield expect.all(
        oracle.isDefined,
        oracle.flatMap(_.stateData).contains(expectedState),
        oracle.flatMap(_.invocationLog.headOption.map(_.result)).contains(expectedResult)
      )
    }
  }

  test("oracle state persistence: multiple invocations maintain state") {
    val oracleScript = """|{
                          |  "_state": {"callCount": 99},
                          |  "_result": "incremented"
                          |}""".stripMargin

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("callCount" -> IntValue(0)))

        createOracle = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First invocation
        invoke1 = Updates.InvokeScriptOracle(cid, "inc", MapValue(Map.empty))
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        oracle1 = state1.calculated.scriptOracles.get(cid)

        // Second invocation
        invoke2 = Updates.InvokeScriptOracle(cid, "inc", MapValue(Map.empty))
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        oracle2 = state2.calculated.scriptOracles.get(cid)

      } yield expect.all(
        oracle1.map(_.invocationCount).contains(1L),
        oracle1.flatMap(_.stateData).contains(MapValue(Map("callCount" -> IntValue(99)))),
        oracle2.map(_.invocationCount).contains(2L),
        oracle2.flatMap(_.stateData).contains(MapValue(Map("callCount" -> IntValue(99)))),
        oracle2.map(_.invocationLog.size).contains(2)
      )
    }
  }
}
