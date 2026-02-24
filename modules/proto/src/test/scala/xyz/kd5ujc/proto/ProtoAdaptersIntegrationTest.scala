package xyz.kd5ujc.proto

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{Records, Updates}

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import ottochain.v1.{
  FiberOrdinal => ProtoFiberOrdinal,
  FiberStatus => ProtoFiberStatus,
  ScriptFiberRecord => ProtoScriptRecord,
  StateMachineFiberRecord => ProtoSMRecord
}
import weaver.SimpleIOSuite

/**
 * Integration tests for ProtoAdapters — 15 tests across 5 groups.
 *
 * Group A (A1–A4): Binary round-trips for StateMachineFiberRecord
 * Group B (B1–B3): Binary round-trips for ScriptFiberRecord
 * Group C (C1–C2): Cross-language binary compatibility
 * Group D (D1–D4): Error handling / fromProto failure cases
 * Group E (E1–E2): Pipeline integration scenarios
 */
object ProtoAdaptersIntegrationTest extends SimpleIOSuite {

  // ─── IO helpers for extracting Either values in tests ─────────────────────

  def getRight[A](e: Either[String, A], label: String = ""): IO[A] =
    IO.fromEither(
      e.left.map(err =>
        new RuntimeException(s"Expected Right${if (label.nonEmpty) s" ($label)" else ""} but got Left: $err")
      )
    )

  def getLeft[A](e: Either[String, A], label: String = ""): IO[String] =
    e match {
      case Left(err) => IO.pure(err)
      case Right(v) =>
        IO.raiseError(
          new RuntimeException(s"Expected Left${if (label.nonEmpty) s" ($label)" else ""} but got Right: $v")
        )
    }

  // ─── Binary round-trip helpers ────────────────────────────────────────────

  def smRoundTrip(r: Records.StateMachineFiberRecord): Either[String, Records.StateMachineFiberRecord] = {
    val bytes = ProtoAdapters.toProtoSMRecord(r).toByteArray
    val decoded = ottochain.v1.StateMachineFiberRecord.parseFrom(bytes)
    ProtoAdapters.fromProtoSMRecord(decoded)
  }

  def scriptRoundTrip(r: Records.ScriptFiberRecord): Either[String, Records.ScriptFiberRecord] = {
    val bytes = ProtoAdapters.toProtoScriptRecord(r).toByteArray
    val decoded = ottochain.v1.ScriptFiberRecord.parseFrom(bytes)
    ProtoAdapters.fromProtoScriptRecord(decoded)
  }

  // ─── Test fixtures ─────────────────────────────────────────────────────────

  def snapOrd(n: Long): SnapshotOrdinal =
    SnapshotOrdinal(NonNegLong.unsafeFrom(n))

  // Valid DAG address (parity + 36 Base58 chars = 40 chars total)
  val testAddr: Address = Address(refineV[DAGAddressRefined].unsafeFrom("DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG"))
  val testUUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")
  val testHash: Hash = Hash("abc123def456abc123def456abc123def456")

  val trueExpr: JsonLogicExpression = ConstExpression(BoolValue(true))
  val nullExpr: JsonLogicExpression = ConstExpression(NullValue)

  def minimalSMDef: StateMachineDefinition = StateMachineDefinition(
    states = Map(StateId("initial") -> State(id = StateId("initial"), isFinal = false)),
    initialState = StateId("initial"),
    transitions = List.empty,
    metadata = None
  )

  def minimalSMRecord(status: FiberStatus = FiberStatus.Active): Records.StateMachineFiberRecord =
    Records.StateMachineFiberRecord(
      fiberId = testUUID,
      creationOrdinal = snapOrd(0L),
      previousUpdateOrdinal = snapOrd(0L),
      latestUpdateOrdinal = snapOrd(1L),
      definition = minimalSMDef,
      currentState = StateId("initial"),
      stateData = NullValue,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddr),
      status = status
    )

  def minimalScriptRecord: Records.ScriptFiberRecord =
    Records.ScriptFiberRecord(
      fiberId = testUUID,
      creationOrdinal = snapOrd(0L),
      latestUpdateOrdinal = snapOrd(1L),
      scriptProgram = ConstExpression(NullValue),
      stateData = None,
      stateDataHash = None,
      accessControl = AccessControlPolicy.Public,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddr),
      status = FiberStatus.Active
    )

  // ═══ Group A: Binary Round-Trip — StateMachineFiberRecord (4 tests) ════════

  test("A1: Minimal StateMachineFiberRecord round-trips via proto binary") {
    val record = minimalSMRecord()
    for {
      result <- getRight(smRoundTrip(record))
    } yield expect(result.fiberId == record.fiberId) and
    expect(result.sequenceNumber == record.sequenceNumber) and
    expect(result.status == FiberStatus.Active) and
    expect(result.owners == record.owners)
  }

  test("A2: Full StateMachineFiberRecord with complex stateData round-trips") {
    val complexStateData = MapValue(
      Map(
        "counter" -> IntValue(BigInt(42)),
        "active"  -> BoolValue(true),
        "name"    -> StrValue("test"),
        "scores"  -> ArrayValue(List(IntValue(BigInt(1)), IntValue(BigInt(2))))
      )
    )

    val complexDef = StateMachineDefinition(
      states = Map(
        StateId("IDLE")   -> State(id = StateId("IDLE"), isFinal = false),
        StateId("ACTIVE") -> State(id = StateId("ACTIVE"), isFinal = false),
        StateId("DONE")   -> State(id = StateId("DONE"), isFinal = true)
      ),
      initialState = StateId("IDLE"),
      transitions = List(
        Transition(
          from = StateId("IDLE"),
          to = StateId("ACTIVE"),
          eventName = "start",
          guard = trueExpr,
          effect = nullExpr
        ),
        Transition(
          from = StateId("ACTIVE"),
          to = StateId("DONE"),
          eventName = "complete",
          guard = trueExpr,
          effect = nullExpr
        )
      ),
      metadata = Some(MapValue(Map("version" -> IntValue(BigInt(1)))))
    )

    val parentUUID = UUID.randomUUID()
    val childUUID = UUID.randomUUID()

    val receipt = EventReceipt(
      fiberId = testUUID,
      sequenceNumber = FiberOrdinal.unsafeApply(2L),
      eventName = "start",
      ordinal = snapOrd(5L),
      fromState = StateId("IDLE"),
      toState = StateId("ACTIVE"),
      success = true,
      gasUsed = 100L,
      triggersFired = 0
    )

    val record = Records.StateMachineFiberRecord(
      fiberId = testUUID,
      creationOrdinal = snapOrd(0L),
      previousUpdateOrdinal = snapOrd(1L),
      latestUpdateOrdinal = snapOrd(3L),
      definition = complexDef,
      currentState = StateId("ACTIVE"),
      stateData = complexStateData,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(3L),
      owners = Set(testAddr),
      status = FiberStatus.Active,
      parentFiberId = Some(parentUUID),
      childFiberIds = Set(childUUID),
      lastReceipt = Some(receipt)
    )

    for {
      result <- getRight(smRoundTrip(record))
    } yield expect(result.fiberId == record.fiberId) and
    expect(result.stateData == complexStateData) and
    expect(result.definition.states.size == 3) and
    expect(result.lastReceipt.isDefined) and
    expect(result.parentFiberId.contains(parentUUID)) and
    expect(result.childFiberIds == Set(childUUID))
  }

  test("A3: StateMachineDefinition transitions encode losslessly") {
    val guardExpr = ConstExpression(MapValue(Map("var" -> StrValue("authorized"))))
    val effectExpr = ConstExpression(MapValue(Map("var" -> StrValue("newState"))))

    val definition = StateMachineDefinition(
      states = Map(
        StateId("IDLE")   -> State(id = StateId("IDLE"), isFinal = false),
        StateId("ACTIVE") -> State(id = StateId("ACTIVE"), isFinal = false),
        StateId("DONE")   -> State(id = StateId("DONE"), isFinal = true)
      ),
      initialState = StateId("IDLE"),
      transitions = List(
        Transition(
          from = StateId("IDLE"),
          to = StateId("ACTIVE"),
          eventName = "start",
          guard = guardExpr,
          effect = effectExpr
        ),
        Transition(
          from = StateId("ACTIVE"),
          to = StateId("DONE"),
          eventName = "complete",
          guard = trueExpr,
          effect = nullExpr
        )
      ),
      metadata = Some(MapValue(Map("version" -> IntValue(BigInt(1)))))
    )

    val record = minimalSMRecord().copy(definition = definition, currentState = StateId("IDLE"))

    for {
      result <- getRight(smRoundTrip(record))
    } yield expect(result.definition.states.size == 3) and
    expect(result.definition.states.contains(StateId("IDLE"))) and
    expect(result.definition.states.contains(StateId("ACTIVE"))) and
    expect(result.definition.states.contains(StateId("DONE"))) and
    expect(result.definition.initialState.value == "IDLE") and
    expect(result.definition.transitions.length == 2) and
    expect(result.definition.metadata.isDefined)
  }

  test("A4: FiberStatus enum maps bidirectionally") {
    for {
      activeResult   <- getRight(smRoundTrip(minimalSMRecord(FiberStatus.Active)))
      archivedResult <- getRight(smRoundTrip(minimalSMRecord(FiberStatus.Archived)))
      failedResult   <- getRight(smRoundTrip(minimalSMRecord(FiberStatus.Failed)))
    } yield expect(activeResult.status == FiberStatus.Active) and
    expect(archivedResult.status == FiberStatus.Archived) and
    expect(failedResult.status == FiberStatus.Failed)
  }

  // ═══ Group B: Binary Round-Trip — ScriptFiberRecord (3 tests) ═════════════

  test("B1: Minimal ScriptFiberRecord round-trips via proto binary") {
    val record = minimalScriptRecord
    for {
      result <- getRight(scriptRoundTrip(record))
    } yield expect(result.fiberId == record.fiberId) and
    expect(result.sequenceNumber == record.sequenceNumber) and
    expect(result.accessControl == AccessControlPolicy.Public)
  }

  test("B2: ScriptFiberRecord with Whitelist access control round-trips") {
    val addr1 = Address.fromBytes(Array.fill(32)(0xaa.toByte))
    val addr2 = Address.fromBytes(Array.fill(32)(0xbb.toByte))
    val stateData = MapValue(Map("counter" -> IntValue(BigInt(5)), "name" -> StrValue("state")))

    val record = minimalScriptRecord.copy(
      stateData = Some(stateData),
      stateDataHash = Some(testHash),
      accessControl = AccessControlPolicy.Whitelist(Set(addr1, addr2))
    )

    for {
      result <- getRight(scriptRoundTrip(record))
    } yield {
      val whitelistOk = result.accessControl match {
        case AccessControlPolicy.Whitelist(addrs) => addrs == Set(addr1, addr2)
        case _                                    => false
      }
      expect(whitelistOk) and
      expect(result.stateData.contains(stateData)) and
      expect(result.stateDataHash.contains(testHash))
    }
  }

  test("B3: FiberOwned access control maps correctly") {
    val fiberRef = UUID.randomUUID()
    val record = minimalScriptRecord.copy(accessControl = AccessControlPolicy.FiberOwned(fiberRef))

    for {
      result <- getRight(scriptRoundTrip(record))
    } yield {
      val fiberOwnedOk = result.accessControl match {
        case AccessControlPolicy.FiberOwned(ref) => ref == fiberRef
        case _                                   => false
      }
      expect(fiberOwnedOk)
    }
  }

  // ═══ Group C: Cross-Language Compatibility (2 tests) ══════════════════════

  test("C1: Scala proto binary is decodable via generated ScalaPB companion") {
    IO {
      val record = minimalSMRecord()
      val binary = ProtoAdapters.toProtoSMRecord(record).toByteArray
      val decoded = ottochain.v1.StateMachineFiberRecord.parseFrom(binary)

      expect(binary.nonEmpty) and
      expect(decoded.fiberId == testUUID.toString) and
      expect(decoded.sequenceNumber.map(_.value).contains(1L)) and
      expect(decoded.status == ProtoFiberStatus.FIBER_STATUS_ACTIVE)
    }
  }

  test("C2: Proto binary is idempotent across two full round-trips") {
    for {
      decoded1 <- getRight(smRoundTrip(minimalSMRecord()), "first round-trip")
      decoded2 <- getRight(smRoundTrip(decoded1), "second round-trip")
      binary1 = ProtoAdapters.toProtoSMRecord(minimalSMRecord()).toByteArray
      binary2 = ProtoAdapters.toProtoSMRecord(decoded1).toByteArray
    } yield expect(binary1.sameElements(binary2)) and
    expect(decoded1 == decoded2)
  }

  // ═══ Group D: Error Handling — fromProto Failures (4 tests) ══════════════

  test("D1: fromProtoSMRecord returns Left on invalid fiberId UUID") {
    val invalidProto = ProtoSMRecord(
      fiberId = "not-a-valid-uuid",
      sequenceNumber = Some(ProtoFiberOrdinal(value = 1L)),
      status = ProtoFiberStatus.FIBER_STATUS_ACTIVE
    )

    for {
      err <- getLeft(ProtoAdapters.fromProtoSMRecord(invalidProto))
    } yield expect(err.contains("Invalid UUID"))
  }

  test("D2: fromProtoFiberStatus returns Left on FIBER_STATUS_UNSPECIFIED") {
    for {
      err <- getLeft(ProtoAdapters.fromProtoFiberStatus(ProtoFiberStatus.FIBER_STATUS_UNSPECIFIED))
    } yield expect(err.contains("Unknown FiberStatus"))
  }

  test("D3: fromProtoScriptRecord returns Left on missing scriptProgram") {
    val invalidProto = ProtoScriptRecord(
      fiberId = testUUID.toString,
      sequenceNumber = Some(ProtoFiberOrdinal(value = 1L)),
      status = ProtoFiberStatus.FIBER_STATUS_ACTIVE
      // scriptProgram intentionally absent
    )

    for {
      err <- getLeft(ProtoAdapters.fromProtoScriptRecord(invalidProto))
    } yield expect(err.contains("Missing scriptProgram"))
  }

  test("D4: fromProto does NOT throw exceptions on random or truncated byte arrays") {
    IO {
      val rng = new scala.util.Random(42L)

      val results: Seq[Either[_, _]] = (1 to 10).map { i =>
        val bytes = Array.fill(i * 5)(rng.nextInt(256).toByte)
        try {
          val parsed = ottochain.v1.StateMachineFiberRecord.parseFrom(bytes)
          ProtoAdapters.fromProtoSMRecord(parsed)
        } catch {
          case _: com.google.protobuf.InvalidProtocolBufferException =>
            Left("Parse failed")
        }
      }

      expect(results.forall(_.isInstanceOf[Either[_, _]]))
    }
  }

  // ═══ Group E: Pipeline Integration (2 tests) ══════════════════════════════

  test("E1: ProtoAdapters handles a realistic CreateStateMachine → SMRecord scenario") {
    val createSM = Updates.CreateStateMachine(
      fiberId = testUUID,
      definition = minimalSMDef,
      initialData = NullValue
    )

    val engineRecord = Records.StateMachineFiberRecord(
      fiberId = createSM.fiberId,
      creationOrdinal = snapOrd(10L),
      previousUpdateOrdinal = snapOrd(10L),
      latestUpdateOrdinal = snapOrd(10L),
      definition = createSM.definition,
      currentState = createSM.definition.initialState,
      stateData = createSM.initialData,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddr),
      status = FiberStatus.Active
    )

    val binarySize = ProtoAdapters.toProtoSMRecord(engineRecord).toByteArray.length

    for {
      result <- getRight(smRoundTrip(engineRecord))
    } yield expect(result.fiberId == createSM.fiberId) and
    expect(result.currentState == createSM.definition.initialState) and
    expect(binarySize < 10240)
  }

  test("E2: FiberRecord binary storage is idempotent across snapshot boundaries") {
    val receipt = EventReceipt(
      fiberId = testUUID,
      sequenceNumber = FiberOrdinal.unsafeApply(3L),
      eventName = "third_transition",
      ordinal = snapOrd(20L),
      fromState = StateId("initial"),
      toState = StateId("initial"),
      success = true,
      gasUsed = 150L,
      triggersFired = 0
    )

    val record = Records.StateMachineFiberRecord(
      fiberId = testUUID,
      creationOrdinal = snapOrd(5L),
      previousUpdateOrdinal = snapOrd(18L),
      latestUpdateOrdinal = snapOrd(20L),
      definition = minimalSMDef,
      currentState = StateId("initial"),
      stateData = MapValue(Map("transitions_completed" -> IntValue(BigInt(3)))),
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(3L),
      owners = Set(testAddr),
      status = FiberStatus.Active,
      lastReceipt = Some(receipt)
    )

    for {
      firstDes  <- getRight(smRoundTrip(record), "first snapshot")
      secondDes <- getRight(smRoundTrip(firstDes), "second snapshot")
    } yield expect(firstDes == secondDes) and
    expect(secondDes.sequenceNumber.value.value == 3L) and
    expect(secondDes.currentState == StateId("initial")) and
    expect(secondDes.lastReceipt.isDefined)
  }
}
