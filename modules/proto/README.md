# OttoChain Proto Module

This module provides Protocol Buffer definitions and ScalaPB-generated Scala types for OttoChain data structures.

## Build-Time Generation Strategy

### ScalaPB Configuration

The module uses **build-time generation** via sbt-protoc and ScalaPB:

```scala
Compile / PB.targets := Seq(
  scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb",
  scalapb.validate.gen() -> (Compile / sourceManaged).value / "scalapb"
)
```

**Generated files location:** `target/scala-2.13/src_managed/main/scalapb/` (gitignored)

**Benefits:**
- Consistent with Tessellation's build strategy
- Generated types available at compile time
- No runtime protoc dependencies
- IDE integration works seamlessly

### DataUpdate Mixin Integration

All OttoChain message types extend `io.constellationnetwork.currency.dataApplication.DataUpdate` via ScalaPB options:

```protobuf
message CreateStateMachine {
  option (scalapb.message).extends = "io.constellationnetwork.currency.dataApplication.DataUpdate";
  // ...
}
```

This enables:
- Direct submission to Tessellation's DataL1
- Type-safe metagraph integration
- Seamless ML0 processing

## Type System Architecture

### Proto vs. Scala Model Boundary

**Key architectural decision:** Proto types and Scala domain models serve different purposes and coexist:

- **Proto types** (`ottochain.v1.*`): Wire format, serialization, network transport
- **Scala models** (`xyz.kd5ujc.models.*`): Domain logic, business rules, type safety

### ProtoAdapters Migration Strategy

The `ProtoAdapters.scala` module handles conversion between proto and domain types:

```scala
// Phase 1: Records conversion (outbound only)
def toProto(record: FiberRecord): ottochain.v1.FiberRecord = ...

// Phase 2: Message conversion (inbound + outbound) - after PR #89
def fromProto(msg: ottochain.v1.OttochainMessage): models.Transaction = ...
```

**Current status:** Phase 1 complete, Phase 2 planned post-fiber-engine migration.

### Sequenced Trait Structural Gap

Generated proto types have structural differences from hand-written Scala types:

- **Generated:** `fiberId: String`, `targetSequenceNumber: Option[proto.FiberOrdinal]`  
- **Domain models:** `fiberId: UUID`, `sequenceNumber: models.FiberOrdinal`

The `Sequenced` trait cannot be directly implemented by generated types. ProtoAdapters bridges this gap during conversion.

## Testing Strategy

### Integration Tests

`ScalaPBIntegrationTest.scala` verifies:
1. **Compilation:** Generated types compile successfully
2. **Mixin verification:** All message types extend `DataUpdate`  
3. **Type safety:** Constructor and field access work correctly
4. **Union types:** `OttochainMessage` oneof functionality

**Coverage:** 6 message types tested (CreateStateMachine, TransitionStateMachine, ArchiveStateMachine, CreateScript, InvokeScript, OttochainMessage)

### CI Integration

Proto module included in CI pipeline:
```bash
sbt proto/compile proto/test
```

This ensures:
- Proto definitions compile successfully
- Generated types satisfy type constraints
- Mixin injection works correctly
- No regressions in proto → Scala conversion

## Proto Definitions

### Core Messages

| Message | Purpose | Key Fields |
|---------|---------|------------|
| `CreateStateMachine` | Create new fiber | `fiber_id`, `definition`, `initial_data` |
| `TransitionStateMachine` | Trigger state transition | `fiber_id`, `event_name`, `payload` |
| `ArchiveStateMachine` | Archive fiber | `fiber_id`, `target_sequence_number` |
| `CreateScript` | Create script fiber | `fiber_id`, `script_program`, `access_control` |
| `InvokeScript` | Execute script method | `fiber_id`, `method`, `args` |
| `OttochainMessage` | Union of all messages | `oneof message` |

### Validation Rules

Proto definitions include validation via protoc-gen-validate:
- `fiber_id`: minimum length 1
- `event_name`: minimum length 1  
- `method`: minimum length 1

## Development Workflow

### Adding New Message Types

1. **Define proto message** in `src/main/protobuf/ottochain/v1/messages.proto`
2. **Add DataUpdate mixin** via `scalapb.message.extends` option
3. **Add to union** in `OttochainMessage.oneof`  
4. **Write integration test** in `ScalaPBIntegrationTest.scala`
5. **Update ProtoAdapters** (if domain model exists)

### Proto Schema Evolution

**Field numbering policy:** 
- Reserve ranges 1-100 for core fields
- Reserve 101-200 for extensions
- Never reuse field numbers
- Document breaking changes in migration guide

### Generated Code Inspection

Generated Scala files are in `target/scala-2.13/src_managed/main/scalapb/`. 

**Useful for debugging:**
- Verify mixin inheritance
- Check constructor signatures
- Inspect validation logic
- Understand union type structure