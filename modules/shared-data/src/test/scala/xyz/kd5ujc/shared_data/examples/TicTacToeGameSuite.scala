package xyz.kd5ujc.shared_data.examples

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner

import io.circe.parser.{decode, parse}
import weaver.SimpleIOSuite
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext
import zyx.kd5ujc.shared_test.Participant._

object TicTacToeGameSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("tic-tac-toe: complete game flow - X wins") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        oracleCid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        oracleDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/oracle-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        oracleDefParsed <- IO.fromEither(parse(oracleDefJson))

        oracleScript <- IO.fromEither(
          oracleDefParsed.hcursor.downField("scriptProgram").as[JsonLogicExpression]
        )

        oracleInitialState <- IO.fromEither(
          oracleDefParsed.hcursor.downField("initialState").as[Option[JsonLogicValue]]
        )

        // Create oracle
        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleScript,
          initialState = oracleInitialState,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/state-machine-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineDefJson))

        // Create state machine with oracle CID
        initialData = MapValue(
          Map(
            "oracleCid"  -> StrValue(oracleCid.toString),
            "status"     -> StrValue("waiting"),
            "roundCount" -> IntValue(0)
          )
        )
        initialDataHash <- (initialData: JsonLogicValue).computeDigest

        machineFiber = Records.StateMachineFiberRecord(
          cid = machineCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = machineDef,
          currentState = StateMachine.StateId("setup"),
          stateData = initialData,
          stateDataHash = initialDataHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        stateAfterMachine = DataState(
          OnChain(Map(machineCid -> initialDataHash)),
          CalculatedState(Map(machineCid -> machineFiber), stateAfterOracle.calculated.scriptOracles)
        )

        // Step 1: Start game
        startGameEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_game"),
          payload = MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-001")
            )
          )
        )
        startGameUpdate = Updates.ProcessFiberEvent(machineCid, startGameEvent)
        startGameProof <- registry.generateProofs(startGameUpdate, Set(Alice))
        state1         <- combiner.insert(stateAfterMachine, Signed(startGameUpdate, startGameProof))

        machineAfterStart = state1.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        _ = expect.all(
          machineAfterStart.isDefined,
          machineAfterStart.map(_.currentState).contains(StateMachine.StateId("playing"))
        )

        // Step 2-6: Play game - X wins with top row (0, 1, 2)
        // Move 1: X plays top left (0)
        move1 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(0)))
        )
        move1Update = Updates.ProcessFiberEvent(machineCid, move1)
        move1Proof <- registry.generateProofs(move1Update, Set(Alice))
        state2     <- combiner.insert(state1, Signed(move1Update, move1Proof))

        // Move 2: O plays middle left (3)
        move2 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(3)))
        )
        move2Update = Updates.ProcessFiberEvent(machineCid, move2)
        move2Proof <- registry.generateProofs(move2Update, Set(Bob))
        state3     <- combiner.insert(state2, Signed(move2Update, move2Proof))

        // Move 3: X plays top middle (1)
        move3 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(1)))
        )
        move3Update = Updates.ProcessFiberEvent(machineCid, move3)
        move3Proof <- registry.generateProofs(move3Update, Set(Alice))
        state4     <- combiner.insert(state3, Signed(move3Update, move3Proof))

        // Move 4: O plays center (4)
        move4 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(4)))
        )
        move4Update = Updates.ProcessFiberEvent(machineCid, move4)
        move4Proof <- registry.generateProofs(move4Update, Set(Bob))
        state5     <- combiner.insert(state4, Signed(move4Update, move4Proof))

        // Move 5: X plays top right (2) - should trigger win
        move5 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(2)))
        )
        move5Update = Updates.ProcessFiberEvent(machineCid, move5)
        move5Proof <- registry.generateProofs(move5Update, Set(Alice))
        finalState <- combiner.insert(state5, Signed(move5Update, move5Proof))

        // Verify final state
        finalMachine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalOracle = finalState.calculated.scriptOracles.get(oracleCid)

        // Check oracle state
        oracleStatus = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }

        oracleWinner = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("winner").collect { case StrValue(w) => w }
            case _                 => None
          }
        }

      } yield expect.all(
        // State machine processed all moves
        finalMachine.isDefined,
        finalMachine
          .map(_.currentState)
          .contains(StateMachine.StateId("playing")), // Still playing - win detected next event
        finalMachine
          .flatMap(m =>
            m.stateData match {
              case MapValue(map) =>
                map.get("lastMove").collect { case MapValue(m) => m.get("cell").contains(IntValue(2)) }
              case _ => None
            }
          )
          .getOrElse(false), // Last move was cell 2
        // Oracle correctly detected the win
        finalOracle.isDefined,
        finalOracle.map(_.invocationCount).contains(6L), // 1 initialize + 5 moves
        oracleStatus.contains("Won"),
        oracleWinner.contains("X"),
        // Oracle board shows winning pattern
        finalOracle.flatMap(_.stateData).exists {
          case MapValue(m) =>
            m.get("board") match {
              case Some(ArrayValue(List(StrValue("X"), StrValue("X"), StrValue("X"), _, _, _, _, _, _))) => true
              case _                                                                                     => false
            }
          case _ => false
        }
      )
    }
  }

  test("tic-tac-toe: draw scenario") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        oracleCid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        // Load and create oracle from test resources
        oracleDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/oracle-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        oracleDefParsed <- IO.fromEither(parse(oracleDefJson))

        oracleScript <- IO.fromEither(
          oracleDefParsed.hcursor
            .downField("scriptProgram")
            .as[JsonLogicExpression]
        )

        oracleInitialState <- IO.fromEither(
          oracleDefParsed.hcursor
            .downField("initialState")
            .as[Option[JsonLogicValue]]
        )

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleScript,
          initialState = oracleInitialState,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        // Load state machine from test resources
        machineDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/state-machine-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineDefJson))

        initialData = MapValue(
          Map(
            "oracleCid"  -> StrValue(oracleCid.toString),
            "status"     -> StrValue("waiting"),
            "roundCount" -> IntValue(0)
          )
        )
        initialDataHash <- (initialData: JsonLogicValue).computeDigest

        machineFiber = Records.StateMachineFiberRecord(
          cid = machineCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = machineDef,
          currentState = StateMachine.StateId("setup"),
          stateData = initialData,
          stateDataHash = initialDataHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        stateAfterMachine = DataState(
          OnChain(Map(machineCid -> initialDataHash)),
          CalculatedState(Map(machineCid -> machineFiber), stateAfterOracle.calculated.scriptOracles)
        )

        // Start game
        startGameEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_game"),
          payload = MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-draw")
            )
          )
        )
        startGameUpdate = Updates.ProcessFiberEvent(machineCid, startGameEvent)
        startGameProof <- registry.generateProofs(startGameUpdate, Set(Alice))
        state1         <- combiner.insert(stateAfterMachine, Signed(startGameUpdate, startGameProof))

        // Play draw sequence: X=0, O=1, X=2, O=4, X=3, O=5, X=7, O=6, X=8
        drawMoves = List(
          ("X", 0, Alice),
          ("O", 1, Bob),
          ("X", 2, Alice),
          ("O", 4, Bob),
          ("X", 3, Alice),
          ("O", 5, Bob),
          ("X", 7, Alice),
          ("O", 6, Bob),
          ("X", 8, Alice)
        )

        finalState <- drawMoves.foldLeftM(state1) { case (currentState, (player, cell, signer)) =>
          val moveEvent = StateMachine.Event(
            eventType = StateMachine.EventType("make_move"),
            payload = MapValue(Map("player" -> StrValue(player), "cell" -> IntValue(cell)))
          )
          val moveUpdate = Updates.ProcessFiberEvent(machineCid, moveEvent)
          for {
            moveProof <- registry.generateProofs(moveUpdate, Set(signer))
            nextState <- combiner.insert(currentState, Signed(moveUpdate, moveProof))
          } yield nextState
        }

        finalMachine = finalState.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalOracle = finalState.calculated.scriptOracles.get(oracleCid)

        finalStatus = finalMachine.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("finalStatus").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        oracleStatus = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }

      } yield expect.all(
        finalMachine.isDefined,
        finalMachine.map(_.currentState).contains(StateMachine.StateId("playing")),
        finalOracle.isDefined,
        finalOracle.map(_.invocationCount).contains(10L),
        oracleStatus.contains("Draw")
      )
    }
  }

  test("tic-tac-toe: invalid move rejected") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        oracleCid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        // Setup (same as above - could be refactored to shared setup)
        oracleDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/oracle-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        oracleDefParsed <- IO.fromEither(parse(oracleDefJson))

        oracleScript <- IO.fromEither(
          oracleDefParsed.hcursor.downField("scriptProgram").as[JsonLogicExpression]
        )

        oracleInitialState <- IO.fromEither(
          oracleDefParsed.hcursor.downField("initialState").as[Option[JsonLogicValue]]
        )

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleScript,
          initialState = oracleInitialState,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/state-machine-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineDefJson))

        initialData = MapValue(
          Map(
            "oracleCid"  -> StrValue(oracleCid.toString),
            "status"     -> StrValue("waiting"),
            "roundCount" -> IntValue(0)
          )
        )
        initialDataHash <- (initialData: JsonLogicValue).computeDigest

        machineFiber = Records.StateMachineFiberRecord(
          cid = machineCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = machineDef,
          currentState = StateMachine.StateId("setup"),
          stateData = initialData,
          stateDataHash = initialDataHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        stateAfterMachine = DataState(
          OnChain(Map(machineCid -> initialDataHash)),
          CalculatedState(Map(machineCid -> machineFiber), stateAfterOracle.calculated.scriptOracles)
        )

        // Start game
        startGameEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_game"),
          payload = MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-invalid")
            )
          )
        )
        startGameUpdate = Updates.ProcessFiberEvent(machineCid, startGameEvent)
        startGameProof <- registry.generateProofs(startGameUpdate, Set(Alice))
        state1         <- combiner.insert(stateAfterMachine, Signed(startGameUpdate, startGameProof))

        // Move 1: X plays cell 0
        move1 = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(0)))
        )
        move1Update = Updates.ProcessFiberEvent(machineCid, move1)
        move1Proof <- registry.generateProofs(move1Update, Set(Alice))
        state2     <- combiner.insert(state1, Signed(move1Update, move1Proof))

        // Invalid move: O tries to play same cell again (should be recorded as failed)
        invalidMove = StateMachine.Event(
          eventType = StateMachine.EventType("make_move"),
          payload = MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(0)))
        )
        invalidMoveUpdate = Updates.ProcessFiberEvent(machineCid, invalidMove)
        invalidMoveProof <- registry.generateProofs(invalidMoveUpdate, Set(Bob))
        state3           <- combiner.insert(state2, Signed(invalidMoveUpdate, invalidMoveProof))

        machineAfterInvalid = state3.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oracleAfterInvalid = state3.calculated.scriptOracles.get(oracleCid)

      } yield expect.all(
        machineAfterInvalid.isDefined,
        // Event processing should have failed
        machineAfterInvalid.exists(m => m.lastEventStatus.isInstanceOf[Records.EventProcessingStatus.ExecutionFailed]),
        // Oracle invocation count stays at 2 (init + first move) - invalid move failed before updating oracle
        oracleAfterInvalid.map(_.invocationCount).contains(2L),
        // Oracle state should remain unchanged from after first move
        oracleAfterInvalid.flatMap(_.stateData).exists {
          case MapValue(m) => m.get("moveCount").contains(IntValue(1))
          case _           => false
        }
      )
    }
  }

  test("tic-tac-toe: reset and play another round") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob))
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        oracleCid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        machineCid <- UUIDGen.randomUUID[IO]

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        // Setup oracle and state machine (abbreviated for brevity)
        oracleDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/oracle-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        oracleDefParsed <- IO.fromEither(parse(oracleDefJson))

        oracleScript <- IO.fromEither(
          oracleDefParsed.hcursor.downField("scriptProgram").as[JsonLogicExpression]
        )

        oracleInitialState <- IO.fromEither(
          oracleDefParsed.hcursor.downField("initialState").as[Option[JsonLogicValue]]
        )

        createOracle = Updates.CreateScriptOracle(
          cid = oracleCid,
          scriptProgram = oracleScript,
          initialState = oracleInitialState,
          accessControl = Records.AccessControlPolicy.Public
        )

        oracleProof <- registry.generateProofs(createOracle, Set(Alice))
        stateAfterOracle <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, oracleProof)
        )

        machineDefJson <- IO {
          val stream = getClass.getResourceAsStream("/tictactoe/state-machine-definition.json")
          scala.io.Source.fromInputStream(stream).mkString
        }
        machineDef <- IO.fromEither(decode[StateMachine.StateMachineDefinition](machineDefJson))

        initialData = MapValue(
          Map(
            "oracleCid"  -> StrValue(oracleCid.toString),
            "status"     -> StrValue("waiting"),
            "roundCount" -> IntValue(0)
          )
        )
        initialDataHash <- (initialData: JsonLogicValue).computeDigest

        machineFiber = Records.StateMachineFiberRecord(
          cid = machineCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = machineDef,
          currentState = StateMachine.StateId("setup"),
          stateData = initialData,
          stateDataHash = initialDataHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        stateAfterMachine = DataState(
          OnChain(Map(machineCid -> initialDataHash)),
          CalculatedState(Map(machineCid -> machineFiber), stateAfterOracle.calculated.scriptOracles)
        )

        // Start and play first game to completion
        startGameEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_game"),
          payload = MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-reset")
            )
          )
        )
        startGameUpdate = Updates.ProcessFiberEvent(machineCid, startGameEvent)
        startGameProof <- registry.generateProofs(startGameUpdate, Set(Alice))
        state1         <- combiner.insert(stateAfterMachine, Signed(startGameUpdate, startGameProof))

        // Quick win for X: cells 0,1,2
        quickWin = List(("X", 0, Alice), ("O", 3, Bob), ("X", 1, Alice), ("O", 4, Bob), ("X", 2, Alice))

        stateAfterWin <- quickWin.foldLeftM(state1) { case (currentState, (player, cell, signer)) =>
          val moveEvent = StateMachine.Event(
            eventType = StateMachine.EventType("make_move"),
            payload = MapValue(Map("player" -> StrValue(player), "cell" -> IntValue(cell)))
          )
          val moveUpdate = Updates.ProcessFiberEvent(machineCid, moveEvent)
          for {
            moveProof <- registry.generateProofs(moveUpdate, Set(signer))
            nextState <- combiner.insert(currentState, Signed(moveUpdate, moveProof))
          } yield nextState
        }

        machineAfterWin = stateAfterWin.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        _ = expect.all(
          machineAfterWin.isDefined,
          machineAfterWin.map(_.currentState).contains(StateMachine.StateId("finished"))
        )

        // Reset for round 2
        resetEvent = StateMachine.Event(
          eventType = StateMachine.EventType("reset_board"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        resetUpdate = Updates.ProcessFiberEvent(machineCid, resetEvent)
        resetProof      <- registry.generateProofs(resetUpdate, Set(Alice))
        stateAfterReset <- combiner.insert(stateAfterWin, Signed(resetUpdate, resetProof))

        machineAfterReset = stateAfterReset.calculated.records
          .get(machineCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        roundCount = machineAfterReset.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("roundCount").collect { case IntValue(rc) => rc }
            case _           => None
          }
        }

        oracleAfterReset = stateAfterReset.calculated.scriptOracles.get(oracleCid)

        oracleStatusAfterReset = oracleAfterReset.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }

      } yield expect.all(
        // State machine stayed in playing state
        machineAfterReset.isDefined,
        machineAfterReset.map(_.currentState).contains(StateMachine.StateId("playing")),
        // Round counter incremented
        roundCount.contains(BigInt(1)),
        // Oracle reset to InProgress
        oracleAfterReset.isDefined,
        oracleStatusAfterReset.contains("InProgress")
      )
    }
  }
}
