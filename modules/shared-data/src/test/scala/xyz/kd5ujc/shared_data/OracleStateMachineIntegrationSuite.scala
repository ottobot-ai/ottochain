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

object OracleStateMachineIntegrationSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("state machine effect invokes oracle during transition") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validateAmount"]},
             |  {">=":[{"var":"args.amount"},100]},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "validated" },
              "eventType": { "value": "submit" },
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "cid": "$oracleCid",
                  "method": "validateAmount",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "submittedAmount": { "var": "event.amount" },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        submitEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("submit"),
            MapValue(Map("amount" -> IntValue(150)))
          )
        )
        submitProof <- registry.generateProofs(submitEvent, Set(Bob))
        finalState  <- combiner.insert(stateAfterMachine, Signed(submitEvent, submitProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

        machineStatus = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        submittedAmount = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("submittedAmount").collect { case IntValue(a) => a }
            case _           => None
          }
        }

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("validated")),
        machineStatus.contains("validated"),
        submittedAmount.contains(BigInt(150)),
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(1L),
        oracle.flatMap(_.invocationLog.headOption.map(_.method)).contains("validateAmount"),
        oracle.flatMap(_.invocationLog.headOption.map(_.result)).exists {
          case BoolValue(true) => true
          case _               => false
        }
      )
    }
  }

  test("state machine effect invokes oracle and oracle call fails - transition should fail") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"validateAmount"]},
             |  {">=":[{"var":"args.amount"},100]},
             |  false
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "validated": { "id": { "value": "validated" }, "isFinal": false }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "validated" },
              "eventType": { "value": "submit" },
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "cid": "$oracleCid",
                  "method": "validateAmount",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "submittedAmount": { "var": "event.amount" },
                "status": "validated"
              },
              "dependencies": []
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("pending")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        submitEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("submit"),
            MapValue(Map("amount" -> IntValue(50)))
          )
        )
        submitProof <- registry.generateProofs(submitEvent, Set(Bob))
        finalState  <- combiner.insert(stateAfterMachine, Signed(submitEvent, submitProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracle = finalState.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("pending")),
        machine.map(_.lastEventStatus).exists {
          case Records.EventProcessingStatus.ExecutionFailed(_, _, _, _, _) => true
          case _                                                            => false
        },
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(0L),
        oracle.map(_.invocationLog.isEmpty).contains(true)
      )
    }
  }

  test("state machine guard reads oracle state before transition") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        oracleScript = """{"counter": 5}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))
        initialOracleState = MapValue(Map("counter" -> IntValue(3)))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = Some(initialOracleState),
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineJson = s"""
        {
          "states": {
            "locked": { "id": { "value": "locked" }, "isFinal": false },
            "unlocked": { "id": { "value": "unlocked" }, "isFinal": false }
          },
          "initialState": { "value": "locked" },
          "transitions": [
            {
              "from": { "value": "locked" },
              "to": { "value": "unlocked" },
              "eventType": { "value": "unlock" },
              "guard": {
                ">=": [
                  { "var": "scriptOracles.$oracleCid.state.counter" },
                  5
                ]
              },
              "effect": {
                "status": "unlocked"
              },
              "dependencies": ["$oracleCid"]
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("locked")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        unlockEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("unlock"),
            MapValue(Map.empty)
          )
        )
        unlockProof           <- registry.generateProofs(unlockEvent, Set(Bob))
        stateAfterFirstUnlock <- combiner.insert(stateAfterMachine, Signed(unlockEvent, unlockProof))

        machineAfterFirstUnlock = stateAfterFirstUnlock.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        invokeOracle = Updates.InvokeScriptOracle(
          cid = oracleCid,
          method = "increment",
          args = MapValue(Map.empty)
        )
        invokeProof      <- registry.generateProofs(invokeOracle, Set(Alice))
        stateAfterInvoke <- combiner.insert(stateAfterFirstUnlock, Signed(invokeOracle, invokeProof))

        secondUnlockProof <- registry.generateProofs(unlockEvent, Set(Bob))
        finalState        <- combiner.insert(stateAfterInvoke, Signed(unlockEvent, secondUnlockProof))

        machineAfterSecondUnlock = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        machineAfterFirstUnlock.isDefined,
        machineAfterFirstUnlock.map(_.currentState).contains(StateMachine.StateId("locked")),
        machineAfterFirstUnlock.map(_.lastEventStatus).exists {
          case Records.EventProcessingStatus.GuardFailed(_, _, _) => true
          case _                                                  => false
        },
        machineAfterSecondUnlock.isDefined,
        machineAfterSecondUnlock.map(_.currentState).contains(StateMachine.StateId("unlocked"))
      )
    }
  }

  test("state machine uses oracle invocation result in subsequent state") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid  <- UUIDGen.randomUUID[IO]
        machineCid <- UUIDGen.randomUUID[IO]

        oracleScript =
          """|{"if":[
             |  {"==":[{"var":"method"},"calculateFee"]},
             |  {"*":[{"var":"args.amount"},0.05]},
             |  0
             |]}""".stripMargin

        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
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
            "processing": { "id": { "value": "processing" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "processing" },
              "eventType": { "value": "initiate" },
              "guard": true,
              "effect": {
                "_oracleCall": {
                  "cid": "$oracleCid",
                  "method": "calculateFee",
                  "args": {
                    "amount": { "var": "event.amount" }
                  }
                },
                "amount": { "var": "event.amount" }
              },
              "dependencies": ["$oracleCid"]
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "completed" },
              "eventType": { "value": "finalize" },
              "guard": true,
              "effect": [
                ["totalAmount", { "+": [
                  { "var": "state.amount" },
                  { "var": "scriptOracles.$oracleCid.invocationLog.0.result" }
                ]}],
                ["feeCalculated", { "var": "scriptOracles.$oracleCid.invocationLog.0.result" }],
                ["status", "completed"]
              ],
              "dependencies": ["$oracleCid"]
            }
          ]
        }
        """

        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineJson))
        initialData = MapValue(Map("status" -> StrValue("idle")))

        createMachine = Updates.CreateStateMachineFiber(machineCid, machineDef, initialData)
        machineProof      <- registry.generateProofs(createMachine, Set(Bob))
        stateAfterMachine <- combiner.insert(stateAfterOracle, Signed(createMachine, machineProof))

        initiateEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("initiate"),
            MapValue(Map("amount" -> IntValue(1000)))
          )
        )
        initiateProof      <- registry.generateProofs(initiateEvent, Set(Bob))
        stateAfterInitiate <- combiner.insert(stateAfterMachine, Signed(initiateEvent, initiateProof))

        finalizeEvent = Updates.ProcessFiberEvent(
          machineCid,
          StateMachine.Event(
            StateMachine.EventType("finalize"),
            MapValue(Map.empty)
          )
        )
        finalizeProof <- registry.generateProofs(finalizeEvent, Set(Bob))
        finalState    <- combiner.insert(stateAfterInitiate, Signed(finalizeEvent, finalizeProof))

        machine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        totalAmount = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) =>
              m.get("totalAmount").collect {
                case IntValue(a)   => a
                case FloatValue(a) => BigInt(a.toLong)
              }
            case _ => None
          }
        }

        feeCalculated = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) =>
              m.get("feeCalculated").collect {
                case IntValue(f)   => f
                case FloatValue(f) => BigInt(f.toLong)
              }
            case _ => None
          }
        }

        status = machine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        machine.isDefined,
        machine.map(_.currentState).contains(StateMachine.StateId("completed")),
        feeCalculated.contains(BigInt(50)),
        totalAmount.contains(BigInt(1050)),
        status.contains("completed")
      )
    }
  }

  test("multiple state machines invoking same oracle maintains invocation count") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie))
        combiner                            <- Combiner.make[IO].pure[IO]

        oracleCid   <- UUIDGen.randomUUID[IO]
        machine1Cid <- UUIDGen.randomUUID[IO]
        machine2Cid <- UUIDGen.randomUUID[IO]

        oracleScript = """{"result": "validated"}"""
        oracleProg <- IO.fromEither(parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleProg,
          initialState = None,
          accessControl = Records.AccessControlPolicy.Public
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
                  "method": "check",
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

        createMachine1 = Updates.CreateStateMachineFiber(machine1Cid, machineDef, initialData)
        machine1Proof      <- registry.generateProofs(createMachine1, Set(Bob))
        stateAfterMachine1 <- combiner.insert(stateAfterOracle, Signed(createMachine1, machine1Proof))

        createMachine2 = Updates.CreateStateMachineFiber(machine2Cid, machineDef, initialData)
        machine2Proof      <- registry.generateProofs(createMachine2, Set(Charlie))
        stateAfterMachine2 <- combiner.insert(stateAfterMachine1, Signed(createMachine2, machine2Proof))

        validateEvent1 = Updates.ProcessFiberEvent(
          machine1Cid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validate1Proof      <- registry.generateProofs(validateEvent1, Set(Bob))
        stateAfterValidate1 <- combiner.insert(stateAfterMachine2, Signed(validateEvent1, validate1Proof))

        validateEvent2 = Updates.ProcessFiberEvent(
          machine2Cid,
          StateMachine.Event(
            StateMachine.EventType("validate"),
            MapValue(Map.empty)
          )
        )
        validate2Proof <- registry.generateProofs(validateEvent2, Set(Charlie))
        finalState     <- combiner.insert(stateAfterValidate1, Signed(validateEvent2, validate2Proof))

        oracle = finalState.calculated.scriptOracles.get(oracleCid)
        machine1 = finalState.calculated.records.get(machine1Cid)
        machine2 = finalState.calculated.records.get(machine2Cid)

      } yield expect.all(
        oracle.isDefined,
        oracle.map(_.invocationCount).contains(2L),
        oracle.map(_.invocationLog.size).contains(2),
        machine1.isDefined,
        machine2.isDefined,
        machine1
          .collect { case r: Records.StateMachineFiberRecord => r.currentState }
          .contains(StateMachine.StateId("validated")),
        machine2
          .collect { case r: Records.StateMachineFiberRecord => r.currentState }
          .contains(StateMachine.StateId("validated"))
      )
    }
  }
}
