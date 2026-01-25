package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationException
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.OracleRules
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object OracleAccessControlSuite extends SimpleIOSuite {

  test("whitelist allows authorized user to invoke oracle directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        finalState  <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof))

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(1L)) and
      expect(oracle.flatMap(_.invocationLog.headOption.map(_.invokedBy)).contains(aliceAddress))
    }
  }

  test("whitelist denies unauthorized user from invoking oracle directly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Bob))

        result <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof)).attempt

        oracle = stateAfterOracle.calculated.scriptOracles.get(oracleCid)

      } yield expect(result.isLeft) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(0L))
    }
  }

  test("whitelist allows multiple authorized users") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)
        bobAddress = fixture.registry.addresses(Bob)

        oracleScript = """{"result": "success"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress, bobAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        invokeOracle1 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof1    <- fixture.registry.generateProofs(invokeOracle1, Set(Alice))
        stateAfterAlice <- combiner.insert(stateAfterOracle, Signed(invokeOracle1, invokeProof1))

        invokeOracle2 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof2  <- fixture.registry.generateProofs(invokeOracle2, Set(Bob))
        stateAfterBob <- combiner.insert(stateAfterAlice, Signed(invokeOracle2, invokeProof2))

        invokeOracle3 = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "test",
          args = MapValue(Map.empty)
        )
        invokeProof3  <- fixture.registry.generateProofs(invokeOracle3, Set(Charlie))
        charlieResult <- combiner.insert(stateAfterBob, Signed(invokeOracle3, invokeProof3)).attempt

        oracle = stateAfterBob.calculated.scriptOracles.get(oracleCid)

      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(2L)) and
      expect(charlieResult.isLeft)
    }
  }

  test("state machine _oracleCall respects whitelist - owner is whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

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

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
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
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Alice))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validateProof <- fixture.registry.generateProofs(validateEvent, Set(Alice))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateMachine.StateId("validated"))) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(1L))
    }
  }

  test("state machine _oracleCall respects whitelist - owner is NOT whitelisted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

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

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
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
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        validateEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validateProof <- fixture.registry.generateProofs(validateEvent, Set(Bob))
        finalState    <- combiner.insert(stateAfterMachine, Signed(validateEvent, validateProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect(machine.isDefined) and
      expect(machine.map(_.currentState).contains(StateMachine.StateId("idle"))) and
      expect(machine.map(_.lastEventStatus).exists {
        case Records.EventProcessingStatus.ExecutionFailed(reason, _, _, _, _) =>
          reason.contains("Access denied") || reason.contains("not in whitelist")
        case _ => false
      }) and
      expect(oracle.isDefined) and
      expect(oracle.map(_.invocationCount).contains(0L))
    }
  }

  test("trigger directive to oracle respects whitelist - unauthorized owner blocked") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddress = fixture.registry.addresses(Alice)

        // Oracle with whitelist - only Alice allowed
        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"process"]},
             |  {"result": "processed"},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Whitelist(Set(aliceAddress))
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        // State machine that uses _triggers to invoke the oracle (NOT _oracleCall)
        // Owner is Bob, who is NOT in the whitelist
        machineJson = s"""
        {
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "triggered": { "id": { "value": "triggered" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "triggered" },
              "eventType": { "value": "trigger" },
              "guard": true,
              "effect": {
                "status": "triggered",
                "_triggers": [
                  {
                    "targetMachineId": "$oracleCid",
                    "eventType": "process",
                    "payload": {}
                  }
                ]
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        // Create state machine owned by Bob (not in whitelist)
        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- fixture.registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        triggerEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("trigger"),
            MapValue(Map.empty)
          )
        )
        triggerProof <- fixture.registry.generateProofs(triggerEvent, Set(Bob))
        finalState   <- combiner.insert(stateAfterMachine, Signed(triggerEvent, triggerProof))

        machine = finalState.calculated.stateMachines
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect(machine.isDefined) and
      // The SM's transition should have been aborted due to oracle access denial
      expect(machine.map(_.currentState).contains(StateMachine.StateId("idle"))) and
      expect(machine.map(_.lastEventStatus).exists {
        case Records.EventProcessingStatus.ExecutionFailed(reason, _, _, _, _) =>
          reason.toLowerCase.contains("access") || reason.toLowerCase.contains("denied")
        case _ => false
      }) and
      expect(oracle.isDefined) and
      // Oracle should NOT have been invoked
      expect(oracle.map(_.invocationCount).contains(0L))
    }
  }

  test("FiberOwned access control policy returns 'not yet implemented'") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO].pure[IO]

        oracleCid    <- IO.randomUUID
        ownerFiberId <- IO.randomUUID // The fiber that "owns" this oracle

        oracleScript =
          """|{
             |  "if": [
             |    { "==": [{ "var": "method" }, "process"] },
             |    "processed",
             |    "unknown"
             |  ]
             |}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        // Create oracle with FiberOwned access control
        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.FiberOwned(ownerFiberId)
        )

        oracleProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        // Attempt to invoke the oracle
        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "process",
          args = MapValue(Map.empty)
        )

        invokeProof  <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        invokeResult <- combiner.insert(stateAfterOracle, Signed(invokeOracle, invokeProof)).attempt

        oracle = invokeResult.toOption.flatMap(_.calculated.scriptOracles.get(oracleCid))

      } yield invokeResult match {
        case Left(ValidationException(err: OracleRules.Errors.OracleAccessDenied)) =>
          // FiberOwned access control denies access (not yet implemented)
          expect(
            err.policy.isInstanceOf[Records.AccessControlPolicy.FiberOwned],
            s"Expected FiberOwned policy, got ${err.policy}"
          )
        case Left(other) =>
          failure(
            s"Expected OracleAccessDenied validation error, got: ${other.getClass.getSimpleName}: ${other.getMessage}"
          )
        case Right(_) =>
          failure("Expected FiberOwned access control to fail (not yet implemented)")
      }
    }
  }
}
