package xyz.kd5ujc.shared_data

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._
import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.lifecycle.{DeterministicEventProcessor, InputValidation}
import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import zyx.kd5ujc.shared_test.Mock.MockL0NodeContext

object DeterministicExecutionSuite extends SimpleIOSuite with Checkers {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("deterministic execution: same input always produces same output") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Create a simple state machine
        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle")   -> StateMachine.State(StateMachine.StateId("idle")),
            StateMachine.StateId("active") -> StateMachine.State(StateMachine.StateId("active"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("idle"),
              to = StateMachine.StateId("active"),
              eventType = StateMachine.EventType("activate"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("activated" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map("counter" -> IntValue(0)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        event = StateMachine.Event(
          StateMachine.EventType("activate"),
          MapValue(Map.empty)
        )

        executionContext = StateMachine.ExecutionContext(
          depth = 0,
          maxDepth = 10,
          gasUsed = 0,
          maxGas = 1000,
          processedEvents = Set.empty
        )

        // Execute the same event multiple times
        result1 <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

        result2 <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

        result3 <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

      } yield expect.all(
        // All results should be identical
        result1 == result2,
        result2 == result3,
        result1.isInstanceOf[StateMachine.StateMachineSuccess],
        result1.asInstanceOf[StateMachine.StateMachineSuccess].newState == StateMachine.StateId("active")
      )
    }
  }

  test("gas limit enforcement: transaction fails when gas exhausted") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("idle") -> StateMachine.State(StateMachine.StateId("idle"))
          ),
          initialState = StateMachine.StateId("idle"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("idle"),
              to = StateMachine.StateId("idle"),
              eventType = StateMachine.EventType("process"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("processed" -> BoolValue(true))))
            )
          )
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("idle"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        event = StateMachine.Event(
          StateMachine.EventType("process"),
          MapValue(Map.empty)
        )

        // Start with high gas usage
        executionContext = StateMachine.ExecutionContext(
          depth = 0,
          maxDepth = 10,
          gasUsed = 25, // Already near limit
          maxGas = 50,
          processedEvents = Set.empty
        )

        // Execute with low gas limit
        result <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          30L
        )

      } yield expect(
        result.isInstanceOf[StateMachine.GasExhausted]
      )
    }
  }

  test("input validation: rejects deeply nested expressions") {
    IO {
      val deepExpression = (1 to 150).foldLeft[JsonLogicExpression](
        ConstExpression(IntValue(1))
      ) { (acc, _) =>
        ApplyExpression(AddOp, List(acc, ConstExpression(IntValue(1))))
      }

      val result = InputValidation.validateExpression(deepExpression, maxDepth = 100)

      expect.all(
        result.isValid == false,
        result.errors.nonEmpty,
        result.errors.head.message.contains("exceeds maximum")
      )
    }
  }

  test("input validation: rejects oversized state") {
    IO {
      val largeString = "x" * 1000000
      val largeState = MapValue(
        Map(
          "data" -> StrValue(largeString)
        )
      )

      val result = InputValidation.validateStateSize(largeState, maxSizeBytes = 500000)

      expect.all(
        result.isValid == false,
        result.errors.nonEmpty,
        result.errors.head.message.contains("exceeds maximum")
      )
    }
  }

  test("input validation: rejects control characters in event payload") {
    IO {
      val dirtyPayload = MapValue(
        Map(
          "message" -> StrValue("Hello\u0000\u0001World")
        )
      )

      val result = InputValidation.validateEventPayload(dirtyPayload)

      expect.all(
        result.isValid == false,
        result.errors.nonEmpty,
        result.errors.head.message.contains("control characters")
      )
    }
  }

  test("cycle detection: prevents infinite loops") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("loop") -> StateMachine.State(StateMachine.StateId("loop"))
          ),
          initialState = StateMachine.StateId("loop"),
          transitions = List.empty
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("loop"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        event = StateMachine.Event(
          StateMachine.EventType("loop"),
          MapValue(Map.empty)
        )

        // Already processed this event type (simulating a cycle)
        executionContext = StateMachine.ExecutionContext(
          depth = 0,
          maxDepth = 10,
          gasUsed = 0,
          maxGas = 1000,
          processedEvents = Set((fiberId, StateMachine.EventType("loop")))
        )

        result <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

      } yield expect(
        result match {
          case StateMachine.Failure(reason) =>
            reason.isInstanceOf[StateMachine.FailureReason.CycleDetected]
          case _ => false
        }
      )
    }
  }

  test("depth limit enforcement: prevents stack overflow") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("deep") -> StateMachine.State(StateMachine.StateId("deep"))
          ),
          initialState = StateMachine.StateId("deep"),
          transitions = List.empty
        )

        initialData = MapValue(Map.empty)
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("deep"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        event = StateMachine.Event(
          StateMachine.EventType("dive"),
          MapValue(Map.empty)
        )

        // Already at max depth
        executionContext = StateMachine.ExecutionContext(
          depth = 10,
          maxDepth = 10,
          gasUsed = 0,
          maxGas = 1000,
          processedEvents = Set.empty
        )

        result <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

      } yield expect(
        result.isInstanceOf[StateMachine.DepthExceeded]
      )
    }
  }

  test("atomic triggers: all succeed or all fail") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiber1Id <- UUIDGen.randomUUID[IO]
        fiber2Id <- UUIDGen.randomUUID[IO]
        ordinal  <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        simpleDef = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("state1") -> StateMachine.State(StateMachine.StateId("state1")),
            StateMachine.StateId("state2") -> StateMachine.State(StateMachine.StateId("state2"))
          ),
          initialState = StateMachine.StateId("state1"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("state1"),
              to = StateMachine.StateId("state2"),
              eventType = StateMachine.EventType("advance"),
              guard = ConstExpression(BoolValue(true)),
              effect = ConstExpression(MapValue(Map("advanced" -> BoolValue(true))))
            )
          )
        )

        data1 = MapValue(Map("id" -> IntValue(1)))
        hash1 <- (data1: JsonLogicValue).computeDigest

        data2 = MapValue(Map("id" -> IntValue(2)))
        hash2 <- (data2: JsonLogicValue).computeDigest

        fiber1 = Records.StateMachineFiberRecord(
          cid = fiber1Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = simpleDef,
          currentState = StateMachine.StateId("state1"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        fiber2 = Records.StateMachineFiberRecord(
          cid = fiber2Id,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = simpleDef,
          currentState = StateMachine.StateId("state1"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = 0,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(
          Map(
            fiber1Id -> fiber1,
            fiber2Id -> fiber2
          ),
          Map.empty
        )

        triggers = List(
          StateMachine.TriggerEvent(
            targetMachineId = fiber1Id,
            eventType = StateMachine.EventType("advance"),
            payloadExpr = ConstExpression(MapValue(Map.empty))
          ),
          StateMachine.TriggerEvent(
            targetMachineId = fiber2Id,
            eventType = StateMachine.EventType("advance"),
            payloadExpr = ConstExpression(MapValue(Map.empty))
          )
        )

        executionContext = StateMachine.ExecutionContext()
        contextData = MapValue(Map.empty)

        result <- DeterministicEventProcessor.processTriggerEventsAtomic(
          triggers,
          ordinal,
          calculatedState,
          executionContext,
          contextData,
          1000L
        )

      } yield expect(
        result match {
          case StateMachine.TransactionResult.Committed(updatedFibers, _, _, _) =>
            // Both fibers should be updated
            updatedFibers.size == 2 &&
            updatedFibers.get(fiber1Id).exists(_.currentState == StateMachine.StateId("state2")) &&
            updatedFibers.get(fiber2Id).exists(_.currentState == StateMachine.StateId("state2"))
          case _ => false
        }
      )
    }
  }

  test("failed transactions don't change state") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO])    <- MockL0NodeContext.make[IO]
        implicit0(jle: JsonLogicEvaluator[IO]) <- JsonLogicEvaluator.tailRecursive[IO].pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        ordinal <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        definition = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("initial") -> StateMachine.State(StateMachine.StateId("initial"))
          ),
          initialState = StateMachine.StateId("initial"),
          transitions = List.empty // No valid transitions
        )

        initialData = MapValue(Map("important" -> IntValue(42)))
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          cid = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateMachine.StateId("initial"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = 5,
          owners = Set.empty,
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        calculatedState = CalculatedState(Map(fiberId -> fiber), Map.empty)
        event = StateMachine.Event(
          StateMachine.EventType("invalid"),
          MapValue(Map.empty)
        )

        executionContext = StateMachine.ExecutionContext()

        result <- DeterministicEventProcessor.processEvent(
          fiber,
          event,
          List.empty,
          ordinal,
          calculatedState,
          executionContext,
          1000L
        )

        // State should remain unchanged
        finalFiber = calculatedState.records.get(fiberId)

      } yield expect.all(
        result.isInstanceOf[StateMachine.Failure],
        // Fiber state should be unchanged
        finalFiber.isDefined,
        finalFiber.exists(_.asInstanceOf[Records.StateMachineFiberRecord].sequenceNumber == 5),
        finalFiber.exists(_.asInstanceOf[Records.StateMachineFiberRecord].stateData == initialData)
      )
    }
  }
}
