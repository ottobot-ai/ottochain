# ottochain-proto

ScalaPB-generated Scala types for the OttoChain protocol.

## Code Generation

Proto-generated Scala types are produced **at compile time** by `sbt-protoc`.

- Generated files live in `target/scala-2.13/src_managed/` and are **not checked into source control**
- SBT invokes `protoc` automatically before any compilation step
- CI compiles and tests this module via `sbt proto/test` (no manual generation needed)

To regenerate locally:
```bash
sbt proto/compile
```

## DataUpdate Mixin

All 6 `OttochainMessage` variants implement `io.constellationnetwork.currency.dataApplication.DataUpdate`
via the ScalaPB `scalapb.message.extends` option in `messages.proto`:

| Message | DataUpdate? |
|---------|-------------|
| `CreateStateMachine` | ✅ |
| `TransitionStateMachine` | ✅ |
| `ArchiveStateMachine` | ✅ |
| `CreateScript` | ✅ |
| `InvokeScript` | ✅ |
| `OttochainMessage` (union) | ✅ |

## Sequenced Trait Gap (Migration Note)

The hand-written `Updates.scala` model defines a `Sequenced` sub-trait for canonical ordering:
```scala
trait Sequenced {
  def fiberId: UUID                     // runtime type
  def targetSequenceNumber: FiberOrdinal // refined NonNegLong
}
```

Generated proto types **cannot implement `Sequenced` directly** due to type mismatches:
- Proto `fiberId: String` ≠ `UUID`
- Proto `targetSequenceNumber: Option[proto.FiberOrdinal]` ≠ refined `FiberOrdinal`

**Phase 1 (current):** `ProtoAdapters.scala` in `modules/fiber-engine/` provides outbound conversion
(runtime types → proto wire format). Ordering logic remains in the `models` module.

**Phase 2 (future):** Full bidirectional adapters + custom ScalaPB type mappings for `UUID` and
refined types. Required before the "Remove hand-written models module" card can be completed.

## Package Structure

With `flatPackage = true`, all generated types live directly in `ottochain.v1`:
```
ottochain.v1.CreateStateMachine
ottochain.v1.TransitionStateMachine
ottochain.v1.OttochainMessage
ottochain.v1.OttochainMessage.Message        // sealed oneof trait
ottochain.v1.OttochainMessage.Message.Empty  // default case
...
```