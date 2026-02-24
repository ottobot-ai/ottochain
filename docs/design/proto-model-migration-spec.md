# Proto Model Migration Spec

> **Status**: Spec Writing → Test Definition  
> **Card**: [🗑️ Migrate: Remove hand-written models module](https://trello.com/c/699621e1d2651cedf586849f)  
> **Author**: @think  
> **Date**: 2026-02-24

## Problem Statement

OttoChain maintains two parallel type definitions:

1. **Hand-written models** in `modules/models/src/main/scala/xyz/kd5ujc/schema/` — Scala case classes with Circe codecs (derevo)
2. **Proto-generated types** in `modules/proto/` — ScalaPB-generated from `.proto` definitions

This duplication creates:
- **Maintenance burden**: Changes require updates in two places
- **Serialization inconsistency**: Circe JSON vs protobuf binary wire formats
- **Type mapping overhead**: Manual `ProtoAdapters` conversion code

**Goal**: Migrate wire-format types to proto definitions and remove the models module, establishing protobuf as the single source of truth for serialized types.

## Scope

### What Migrates (Wire-Format Types)

These types are serialized to disk/network and MUST have proto equivalents:

| Hand-written (models/) | Proto (records.proto) | Status |
|------------------------|----------------------|--------|
| `OnChain` | `OnChainState` | ✅ Proto exists |
| `CalculatedState` | `CalculatedState` | ✅ Proto exists |
| `FiberCommit` | `FiberCommit` | ✅ Proto exists |
| `Records.StateMachineFiberRecord` | `StateMachineFiberRecord` | ✅ Proto exists |
| `Records.ScriptFiberRecord` | `ScriptFiberRecord` | ✅ Proto exists |

| Hand-written (fiber/) | Proto (fiber.proto) | Status |
|----------------------|---------------------|--------|
| `FiberStatus` | `FiberStatus` | ✅ Proto exists |
| `FiberOrdinal` | `FiberOrdinal` | ✅ Proto exists |
| `StateId` | `StateId` | ✅ Proto exists |
| `AccessControlPolicy` | `AccessControlPolicy` | ✅ Proto exists |
| `FiberLogEntry` | `FiberLogEntry` | ✅ Proto exists |

| Hand-written (Updates.scala) | Proto (messages.proto) | Status |
|------------------------------|------------------------|--------|
| `OttochainMessage` | `OttochainMessage` | ✅ Proto exists |
| All 5 message types | All 5 message types | ✅ Proto exists |

### What Stays (Runtime-Only Types)

These types are never serialized — they exist only in the runtime execution context:

| Type | Location | Reason to Keep |
|------|----------|----------------|
| `FiberGasConfig` | `fiber/` | Runtime gas accounting |
| `ExecutionLimits` | `fiber/` | Execution parameters |
| `FiberContext` | `fiber/` | In-memory orchestrator context |
| `FiberInput` | `fiber/` | Internal event routing |
| `FiberResult` | `fiber/` | Engine return type |
| `FiberTrigger` | `fiber/` | Internal trigger dispatch |
| `FiberKind` | `fiber/` | Type discrimination |
| `InputKind` | `fiber/` | Input classification |
| `SpawnDirective` | `fiber/` | Child fiber spawning |
| `TransactionResult` | `fiber/` | Combiner outcome |
| `TriggerHandlerResult` | `fiber/` | Handler return type |
| `GasExhaustionPhase` | `fiber/` | Gas tracking |
| `FailureReason` | `fiber/` | Error classification |
| `ReservedKeys` | `fiber/` | Constants |

## Prerequisites

**BLOCKER**: This migration depends on:

- **Integration Tests card 699621e4** — `ProtoAdapters.fromProto` must be complete
  - Spec PR #98 defines the `fromProto` bidirectional conversion
  - Required for binary serialization compatibility

## Migration Plan

### Phase 1: Add Tessellation Trait Extensions (This Card)

**Goal**: Make proto types implement Tessellation's `DataOnChainState` and `DataCalculatedState` traits.

#### 1.1 Proto Fix: records.proto (2 lines)

```protobuf
// On-chain state
message OnChainState {
  option (scalapb.message).extends = "io.constellationnetwork.currency.dataApplication.DataOnChainState";
  
  map<string, FiberCommit> fiber_commits = 1;
  map<string, FiberLogEntryList> latest_logs = 2;
}

// Calculated state - queryable via ML0 endpoints
message CalculatedState {
  option (scalapb.message).extends = "io.constellationnetwork.currency.dataApplication.DataCalculatedState";
  
  map<string, StateMachineFiberRecord> state_machines = 1;
  map<string, ScriptFiberRecord> scripts = 2;
}
```

#### 1.2 UUID → String Key Migration

The hand-written types use `UUID` as map keys:
```scala
case class OnChain(
  fiberCommits: SortedMap[UUID, FiberCommit],
  latestLogs:   SortedMap[UUID, List[FiberLogEntry]]
)
```

Proto uses `string` keys (proto3 map constraint):
```protobuf
map<string, FiberCommit> fiber_commits = 1;
```

**Migration path**: Convert UUID ↔ String at adapter boundaries.

**Files requiring changes** (6 files):

| File | Change |
|------|--------|
| `DataStateOps.scala` | `UUID → String` for map operations |
| `FiberCombiner.scala` | `UUID → String` in handleCommittedOutcome |
| `ScriptCombiner.scala` | `UUID → String` in oracle updates |
| `FiberRules.scala` | `UUID → String` for lookup operations |
| `CommonRules.scala` | `UUID → String` for validation |
| `Validator.scala` | `UUID → String` for fiber existence checks |

**Conversion helpers** (add to `ProtoAdapters`):
```scala
object ProtoAdapters {
  def uuidToString(uuid: UUID): String = uuid.toString
  def stringToUuid(s: String): UUID = UUID.fromString(s)
  
  def uuidMapToStringMap[V](m: Map[UUID, V]): Map[String, V] =
    m.map { case (k, v) => uuidToString(k) -> v }
    
  def stringMapToUuidMap[V](m: Map[String, V]): Map[UUID, V] =
    m.map { case (k, v) => stringToUuid(k) -> v }
}
```

### Phase 2: Remove models Module (Future Card)

After Phase 1 is stable:

1. Update all imports: `xyz.kd5ujc.schema.{OnChain, CalculatedState}` → `ottochain.v1.{OnChainState, CalculatedState}`
2. Update type references throughout codebase
3. Delete `modules/models/`
4. Update `build.sbt` dependencies

## Open Questions Resolution

### Q1: Should `latestLogs` stay in OnChainState or move to CalculatedState?

**Decision**: **Stay in OnChainState** ✅

**Rationale**:
- `latestLogs` is part of the snapshot proof — it's included in the binary hash
- The `appendLogs` operation in `DataStateOps` modifies `onChain.latestLogs`
- Log entries are collected per-ordinal for external webhook signaling
- Moving to CalculatedState would break snapshot integrity verification

**Evidence**: `DataStateOps.scala:78-83`:
```scala
def appendLogs(entries: List[FiberLogEntry]): DataState[OnChain, CalculatedState] = {
  val grouped = entries.groupBy(_.fiberId)
  state.focus(_.onChain.latestLogs).modify { ... }
}
```

### Q2: Confirm proto CalculatedState.StateMachineFiberRecord = hand-written Records.StateMachineFiberRecord

**Decision**: **Structurally equivalent** ✅

**Field-by-field comparison**:

| Hand-written | Proto | Notes |
|--------------|-------|-------|
| `fiberId: UUID` | `fiber_id: string` | UUID → String conversion |
| `creationOrdinal: SnapshotOrdinal` | `creation_ordinal: SnapshotOrdinal` | ✅ Match |
| `previousUpdateOrdinal: SnapshotOrdinal` | `previous_update_ordinal: SnapshotOrdinal` | ✅ Match |
| `latestUpdateOrdinal: SnapshotOrdinal` | `latest_update_ordinal: SnapshotOrdinal` | ✅ Match |
| `definition: StateMachineDefinition` | `definition: StateMachineDefinition` | ✅ Match |
| `currentState: StateId` | `current_state: StateId` | ✅ Match |
| `stateData: JsonLogicValue` | `state_data: google.protobuf.Value` | ✅ Both use Protobuf Value |
| `stateDataHash: Hash` | `state_data_hash: HashValue` | ✅ Match |
| `sequenceNumber: FiberOrdinal` | `sequence_number: FiberOrdinal` | ✅ Match |
| `owners: Set[Address]` | `owners: repeated Address` | Set → repeated (order-independent) |
| `status: FiberStatus` | `status: FiberStatus` | ✅ Match |
| `lastReceipt: Option[EventReceipt]` | `last_receipt: optional EventReceipt` | ✅ Match |
| `parentFiberId: Option[UUID]` | `parent_fiber_id: optional string` | UUID → String |
| `childFiberIds: Set[UUID]` | `child_fiber_ids: repeated string` | UUID → String |

**Action**: No structural changes needed. UUID ↔ String conversion handled in ProtoAdapters.

### Q3: Is Circe-proto JSON format acceptable for `/onchain` DL1 endpoint?

**Decision**: **Yes, with scalapb-circe integration** ✅

**Current state**: `/onchain` endpoint returns `OnChain` serialized via Circe (derevo codecs):
```scala
case GET -> Root / "onchain" =>
  context.getOnChainState[OnChain].toResponse
```

**Migration path**:
1. Proto types get Circe codecs via `scalapb-circe` library
2. JSON format differences (snake_case vs camelCase) handled by ScalaPB options
3. Add to `scalapb.options` in proto files:
   ```protobuf
   option (scalapb.options) = {
     json4s_format: true  // or use scalapb-circe
   };
   ```

**Backward compatibility**: Existing clients expect camelCase. Options:
- **Option A**: Use ScalaPB's JSON name options for camelCase output
- **Option B**: Keep hand-written JSON codecs as a facade (deprecated)
- **Recommended**: Option A with deprecation notice for format change

**Note**: Binary wire format (protobuf) is the primary goal. JSON endpoints are secondary and can adapt gradually.

## Test Plan (TDD)

### Proto Trait Implementation Tests

```scala
class ProtoTessellationTraitsSuite extends CatsEffectSuite {
  
  test("OnChainState extends DataOnChainState") {
    assert(classOf[DataOnChainState].isAssignableFrom(classOf[OnChainState]))
  }
  
  test("CalculatedState extends DataCalculatedState") {
    assert(classOf[DataCalculatedState].isAssignableFrom(classOf[CalculatedState]))
  }
  
  test("OnChainState genesis has empty maps") {
    val genesis = OnChainState()
    assertEquals(genesis.fiberCommits.size, 0)
    assertEquals(genesis.latestLogs.size, 0)
  }
  
  test("CalculatedState genesis has empty maps") {
    val genesis = CalculatedState()
    assertEquals(genesis.stateMachines.size, 0)
    assertEquals(genesis.scripts.size, 0)
  }
}
```

### UUID ↔ String Round-Trip Tests

```scala
class UuidStringConversionSuite extends CatsEffectSuite {
  
  test("UUID to String preserves value") {
    val uuid = UUID.randomUUID()
    val str = ProtoAdapters.uuidToString(uuid)
    val back = ProtoAdapters.stringToUuid(str)
    assertEquals(uuid, back)
  }
  
  test("Map[UUID, V] to Map[String, V] round-trip") {
    val original = Map(
      UUID.randomUUID() -> "a",
      UUID.randomUUID() -> "b"
    )
    val asString = ProtoAdapters.uuidMapToStringMap(original)
    val back = ProtoAdapters.stringMapToUuidMap(asString)
    assertEquals(original, back)
  }
  
  test("Invalid UUID string throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      ProtoAdapters.stringToUuid("not-a-uuid")
    }
  }
}
```

### Wire Format Compatibility Tests

```scala
class WireFormatCompatibilitySuite extends CatsEffectSuite {
  
  test("Proto FiberCommit serializes to binary") {
    val commit = FiberCommit(
      recordHash = Some(HashValue("abc123")),
      stateDataHash = Some(HashValue("def456")),
      sequenceNumber = Some(FiberOrdinal(42))
    )
    val bytes = commit.toByteArray
    val parsed = FiberCommit.parseFrom(bytes)
    assertEquals(commit, parsed)
  }
  
  test("Proto StateMachineFiberRecord binary round-trip") {
    val record = StateMachineFiberRecord(
      fiberId = UUID.randomUUID().toString,
      creationOrdinal = Some(SnapshotOrdinal(1)),
      // ... full record
    )
    val bytes = record.toByteArray
    val parsed = StateMachineFiberRecord.parseFrom(bytes)
    assertEquals(record, parsed)
  }
  
  test("Proto OnChainState binary round-trip") {
    val state = OnChainState(
      fiberCommits = Map("uuid-1" -> FiberCommit(...)),
      latestLogs = Map("uuid-1" -> FiberLogEntryList(...))
    )
    val bytes = state.toByteArray
    val parsed = OnChainState.parseFrom(bytes)
    assertEquals(state, parsed)
  }
}
```

### Consumer File Correctness Tests

```scala
class DataStateOpsProtoSuite extends CatsEffectSuite {
  
  test("withRecord updates both OnChainState and CalculatedState") {
    val state = DataState(OnChainState(), CalculatedState())
    val fiberId = UUID.randomUUID()
    val record = createTestStateMachineRecord(fiberId)
    
    val result = state.withRecord[IO](fiberId, record).unsafeRunSync()
    
    assert(result.onChain.fiberCommits.contains(fiberId.toString))
    assert(result.calculated.stateMachines.contains(fiberId.toString))
  }
  
  test("appendLogs groups entries by fiberId") {
    val fiberId = UUID.randomUUID()
    val entry = createTestLogEntry(fiberId)
    
    val result = DataState(OnChainState(), CalculatedState())
      .appendLogs(List(entry))
    
    val logs = result.onChain.latestLogs.get(fiberId.toString)
    assert(logs.exists(_.entries.nonEmpty))
  }
}

class FiberCombinerProtoSuite extends CatsEffectSuite {
  
  test("createStateMachineFiber uses proto types internally") {
    // Test that FiberCombiner works with proto OnChainState/CalculatedState
  }
  
  test("processFiberEvent handles UUID→String conversion") {
    // Verify fiber lookup works with string keys
  }
}
```

### Regression Tests (Existing Behavior)

```scala
class MigrationRegressionSuite extends CatsEffectSuite {
  
  test("All 20+ existing test suites pass with proto types") {
    // Meta-test: verify OnChain.genesis → OnChainState() swap doesn't break tests
    // Actual validation: run full test suite after migration
  }
  
  test("Genesis state matches between hand-written and proto") {
    val hwGenesis = xyz.kd5ujc.schema.OnChain.genesis
    val protoGenesis = OnChainState()
    
    assertEquals(hwGenesis.fiberCommits.isEmpty, protoGenesis.fiberCommits.isEmpty)
    assertEquals(hwGenesis.latestLogs.isEmpty, protoGenesis.latestLogs.isEmpty)
  }
}
```

## Implementation Checklist

### Phase 1 Tasks

- [ ] Add Tessellation trait extends to `records.proto` (2 lines)
- [ ] Verify ScalaPB generates classes that compile with trait extends
- [ ] Implement UUID ↔ String helpers in `ProtoAdapters`
- [ ] Update `DataStateOps.scala` for string keys
- [ ] Update `FiberCombiner.scala` for string keys
- [ ] Update `ScriptCombiner.scala` for string keys
- [ ] Update `FiberRules.scala` for string keys
- [ ] Update `CommonRules.scala` for string keys
- [ ] Update `Validator.scala` for string keys
- [ ] Write TDD tests (10+ cases above)
- [ ] Run full test suite (37 suites)
- [ ] Update `/onchain` endpoint if needed

### Phase 2 Tasks (Future Card)

- [ ] Create migration card for models module removal
- [ ] Update all imports across codebase
- [ ] Delete `modules/models/`
- [ ] Update `build.sbt`

## References

- **Integration Tests card**: 699621e4 (prerequisite)
- **Prior art**: Done card 69896a5b (Model Migration predecessor)
- **Proto files**: `modules/proto/src/main/protobuf/ottochain/v1/`
- **Hand-written types**: `modules/models/src/main/scala/xyz/kd5ujc/schema/`
- **ScalaPB options**: https://scalapb.github.io/docs/customizations/

---

*Spec complete. Ready for TDD test implementation.*
