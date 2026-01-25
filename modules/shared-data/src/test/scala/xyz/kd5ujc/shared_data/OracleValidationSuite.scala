package xyz.kd5ujc.shared_data

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
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

object OracleValidationSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("create script oracle with public access") {
    val oracleScript =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createUpdate, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        oracle = state.calculated.scriptOracles.get(cid)
      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.status).contains(Records.FiberStatus.Active),
        oracle.map(_.owners).contains(Set(registry.addresses(Alice)))
      )
    }
  }

  test("invoke oracle with validation method") {
    val oracleScript =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScriptOracle(
          cid = cid,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(15)))
        )

        invokeProof <- registry.generateProofs(invokeUpdate, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeUpdate, invokeProof))

        oracle = state2.calculated.scriptOracles.get(cid)
        lastInvocation = oracle.flatMap(_.invocationLog.headOption)

      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(1L),
        lastInvocation.isDefined,
        lastInvocation.map(_.method).contains("validate"),
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

    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- Participant.ParticipantRegistry.create[IO](Set(Alice))
        combiner                            <- Combiner.make[IO].pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScriptOracle(
          cid = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScriptOracle(
          cid = cid,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(5)))
        )

        invokeProof <- registry.generateProofs(invokeUpdate, Set(Alice))
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
