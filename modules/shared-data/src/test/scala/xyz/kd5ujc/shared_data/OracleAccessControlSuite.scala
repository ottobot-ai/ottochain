package xyz.kd5ujc.shared_data

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner

import io.circe.parser._
import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object OracleAccessControlSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("whitelist allows authorized user to invoke oracle directly") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        finalState  <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof))

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(1L),
        oracle.flatMap(_.invocationLog.headOption.map(_.invokedBy)).contains(aliceAddress)
      )
    }
  }

  test("whitelist denies unauthorized user from invoking oracle directly") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof <- registry.generateProofs(invokeOracle, Set(Bob))

        result <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof)).attempt

        oracle = stateAfterOracle.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        result.isLeft,
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(0L)
      )
    }
  }

  test("whitelist allows multiple authorized users") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice)
        bobAddress = registry.addresses(Bob)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress, bobAddress))
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle1 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof1    <- registry.generateProofs(invokeOracle1, Set(Alice))
        stateAfterAlice <- combiner.insert(stateAfterOracle, Signed(invokeOracle1, invokeProof1))

        invokeOracle2 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof2  <- registry.generateProofs(invokeOracle2, Set(Bob))
        stateAfterBob <- combiner.insert(stateAfterAlice, Signed(invokeOracle2, invokeProof2))

        invokeOracle3 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof3  <- registry.generateProofs(invokeOracle3, Set(Charlie))
        charlieResult <- combiner.insert(stateAfterBob, Signed(invokeOracle3, invokeProof3)).attempt

        oracle = stateAfterBob.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(2L),
        charlieResult.isLeft
      )
    }
  }

  test("state machine _oracleCall respects whitelist - owner is whitelisted") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice)

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "validated" },
              "eventType": { "value": "validate" },
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "cid": "$oracleCid",
                  "method": "validate",
                  "args": {}
                },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Alice))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validateProof <- registry.generateProofs(validateEvent, Set(Alice))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("validated")),
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(1L)
      )
    }
  }

  test("state machine _oracleCall respects whitelist - owner is NOT whitelisted") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = registry.addresses(Alice)

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validate"]},
             |  {"result": "validated"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "validated" },
              "eventType": { "value": "validate" },
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "cid": "$oracleCid",
                  "method": "validate",
                  "args": {}
                },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validateProof <- registry.generateProofs(validateEvent, Set(Bob))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("idle")),
        machine.map(_.lastEventStatus).exists {
          case Records.EventProcessingStatus.ExecutionFailed(reason, _, _, _, _) =>
            reason.contains("Access denied") || reason.contains("not in whitelist")
          case _ => false
        },
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(0L)
      )
    }
  }
}
