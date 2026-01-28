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

object OracleValidationSuite extends SimpleIOSuite {

  test("create script oracle with public access") {
    val oracleScript =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        oracle = state.calculated.scriptOracles.get(cid)
      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.status).contains(FiberStatus.Active)) and
      expect(oracle.map(_.owners).contains(Set(fixture.registry.addresses(Alice))))
    }
  }

  test("invoke oracle with validation method") {
    val oracleScript =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScriptOracle(
          cid = cid,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(15)))
        )

        invokeProof <- fixture.registry.generateProofs(invokeUpdate, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeUpdate, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        lastInvocation = oracle.flatMap(_.invocationLog.headOption)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(1L)) and
      expect(lastInvocation.isDefined) and
      expect(lastInvocation.map(_.method).contains("validate")) and
      expect(
        lastInvocation
          .flatMap(inv =>
            inv.result match {
              case BoolValue(v) => Some(v)
              case _            => None
            }
          )
          .contains(true)
      )
    }
  }

  test("invoke oracle validation fails when value too low") {
    val oracleScript =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScriptOracle(
          cid = cid,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(5)))
        )

        invokeProof <- fixture.registry.generateProofs(invokeUpdate, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeUpdate, invokeProof))

        lastInvocation = state2.calculated.scriptOracles.get(cid).flatMap(_.invocationLog.headOption)

      } yield expect(
        lastInvocation
          .flatMap(inv =>
            inv.result match {
              case BoolValue(v) => Some(v)
              case _            => None
            }
          )
          .contains(false)
      )
    }
  }
}
