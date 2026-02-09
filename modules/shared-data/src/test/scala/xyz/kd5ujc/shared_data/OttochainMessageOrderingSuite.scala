package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object OttochainMessageOrderingSuite extends SimpleIOSuite {

  // Helper to create a minimal state machine definition
  private def minimalDefinition: StateMachineDefinition = {
    val initial = StateId("initial")
    StateMachineDefinition(
      states = Map(initial -> State(initial, isFinal = false)),
      initialState = initial,
      transitions = Nil
    )
  }

  // Helper for empty initial data
  private val emptyData: JsonLogicValue = MapValue(Map.empty[String, JsonLogicValue])

  test("Sequenced trait is mixed into TransitionStateMachine") {
    val tsm: OttochainMessage = TransitionStateMachine(
      fiberId = UUID.randomUUID(),
      eventName = "test",
      payload = emptyData,
      targetSequenceNumber = FiberOrdinal.MinValue.next
    )

    IO.pure(
      expect(tsm.isInstanceOf[Sequenced]) and
      expect(tsm.asInstanceOf[Sequenced].targetSequenceNumber == FiberOrdinal.MinValue.next)
    )
  }

  test("Sequenced trait is mixed into ArchiveStateMachine") {
    val asm: OttochainMessage = ArchiveStateMachine(
      fiberId = UUID.randomUUID(),
      targetSequenceNumber = FiberOrdinal.MinValue.next
    )

    IO.pure(expect(asm.isInstanceOf[Sequenced]))
  }

  test("Sequenced trait is mixed into InvokeScript") {
    val inv: OttochainMessage = InvokeScript(
      fiberId = UUID.randomUUID(),
      method = "test",
      args = emptyData,
      targetSequenceNumber = FiberOrdinal.MinValue.next
    )

    IO.pure(expect(inv.isInstanceOf[Sequenced]))
  }

  test("CreateStateMachine is NOT Sequenced") {
    val csm: OttochainMessage = CreateStateMachine(
      fiberId = UUID.randomUUID(),
      definition = minimalDefinition,
      initialData = emptyData
    )

    IO.pure(expect(!csm.isInstanceOf[Sequenced]))
  }

  test("CreateScript is NOT Sequenced") {
    val cs: OttochainMessage = CreateScript(
      fiberId = UUID.randomUUID(),
      scriptProgram = ConstExpression(emptyData),
      initialState = None,
      accessControl = AccessControlPolicy.Public
    )

    IO.pure(expect(!cs.isInstanceOf[Sequenced]))
  }

  test("OttochainMessage.ordering puts creates before transitions") {
    val fiberId = UUID.randomUUID()

    val create: OttochainMessage = CreateStateMachine(
      fiberId = fiberId,
      definition = minimalDefinition,
      initialData = emptyData
    )

    val transition: OttochainMessage = TransitionStateMachine(
      fiberId = fiberId,
      eventName = "start",
      payload = emptyData,
      targetSequenceNumber = FiberOrdinal.MinValue.next
    )

    // Even if transition comes first in the list, sorting should put create first
    val unsorted: List[OttochainMessage] = List(transition, create)
    val sorted = unsorted.sorted(OttochainMessage.ordering)

    IO.pure(
      expect(sorted.head == create) and
      expect(sorted(1) == transition)
    )
  }

  test("OttochainMessage.ordering sorts transitions by sequence number within same fiber") {
    val fiberId = UUID.randomUUID()
    val seq1 = FiberOrdinal.MinValue.next
    val seq2 = seq1.next
    val seq3 = seq2.next

    val t1: OttochainMessage = TransitionStateMachine(fiberId, "event1", emptyData, seq1)
    val t2: OttochainMessage = TransitionStateMachine(fiberId, "event2", emptyData, seq2)
    val t3: OttochainMessage = TransitionStateMachine(fiberId, "event3", emptyData, seq3)

    // Shuffle them
    val unsorted: List[OttochainMessage] = List(t3, t1, t2)
    val sorted = unsorted.sorted(OttochainMessage.ordering)

    IO.pure(expect(sorted == List(t1, t2, t3)))
  }

  test("OttochainMessage.ordering handles mixed message types correctly") {
    val fiber1 = UUID.randomUUID()
    val fiber2 = UUID.randomUUID()

    val create1: OttochainMessage = CreateStateMachine(fiber1, minimalDefinition, emptyData)
    val create2: OttochainMessage = CreateStateMachine(fiber2, minimalDefinition, emptyData)

    val t1_seq1: OttochainMessage = TransitionStateMachine(fiber1, "e1", emptyData, FiberOrdinal.MinValue.next)
    val t1_seq2: OttochainMessage = TransitionStateMachine(fiber1, "e2", emptyData, FiberOrdinal.MinValue.next.next)
    val t2_seq1: OttochainMessage = TransitionStateMachine(fiber2, "e1", emptyData, FiberOrdinal.MinValue.next)

    // Completely shuffled
    val unsorted: List[OttochainMessage] = List(t1_seq2, t2_seq1, create2, t1_seq1, create1)
    val sorted = unsorted.sorted(OttochainMessage.ordering)

    // Creates should come first (in fiberId order since both have priority 0)
    // Then transitions sorted by (fiberId, sequenceNumber)
    val creates = sorted.take(2)
    val transitions = sorted.drop(2)

    IO.pure(
      // All creates come before all transitions
      expect(creates.forall(!_.isInstanceOf[Sequenced])) and
      expect(transitions.forall(_.isInstanceOf[Sequenced])) and
      // Transitions for same fiber are in sequence order
      expect(
        transitions
          .collect { case t: TransitionStateMachine if t.fiberId == fiber1 => t.targetSequenceNumber.value.value }
          == List(1L, 2L)
      )
    )
  }

  test("signedOrdering delegates to message ordering") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      val fiberId = UUID.randomUUID()
      val seq1 = FiberOrdinal.MinValue.next
      val seq2 = seq1.next

      val trans1 = TransitionStateMachine(fiberId, "e1", emptyData, seq1)
      val trans2 = TransitionStateMachine(fiberId, "e2", emptyData, seq2)

      for {
        proofs1 <- fixture.registry.generateProofs(trans1, Set(Alice))
        proofs2 <- fixture.registry.generateProofs(trans2, Set(Alice))

        signedT1 = Signed(trans1, proofs1)
        signedT2 = Signed(trans2, proofs2)

        // Reverse order
        unsorted = List(signedT2, signedT1)
        sorted = unsorted.sorted(OttochainMessage.signedOrdering)

      } yield expect(sorted.map(_.value) == List(trans1, trans2))
    }
  }

  test("ordering is deterministic for same input") {
    val fiberId = UUID.randomUUID()

    val create: OttochainMessage = CreateStateMachine(fiberId, minimalDefinition, emptyData)
    val transition: OttochainMessage = TransitionStateMachine(
      fiberId,
      "start",
      emptyData,
      FiberOrdinal.MinValue.next
    )

    // Same input should always produce same output
    val input = List(transition, create)
    val sorted1 = input.sorted(OttochainMessage.ordering)
    val sorted2 = input.sorted(OttochainMessage.ordering)
    val sorted3 = input.sorted(OttochainMessage.ordering)

    // Sorting is deterministic - same input gives same output every time
    IO.pure(
      expect(sorted1 == sorted2) and
      expect(sorted2 == sorted3) and
      expect(sorted1.head == create) // Create always comes first
    )
  }
}
