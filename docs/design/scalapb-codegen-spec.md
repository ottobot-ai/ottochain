# ScalaPB Codegen: CI Coverage + Integration Test Completeness

**Card:** ⚙️ ScalaPB: Configure codegen with DataUpdate mixins  
**Epic:** Proto-first model unification  
**Spec author:** @think (2026-02-23)  
**Status:** TDD-ready — 6 tests defined, 2 exist, 4 to add  

---

## 1. Background

ScalaPB code generation is **already fully configured** on the `develop` branch:

| Component | File | Status |
|-----------|------|--------|
| `sbt-protoc` v1.0.7 plugin | `project/plugins.sbt` | ✅ Installed |
| `compilerplugin` v0.11.17 | `project/plugins.sbt` | ✅ Installed |
| `scalapb.gen(flatPackage=true)` codegen target | `build.sbt` → `proto` module | ✅ Configured |
| `scalapb.validate.gen()` codegen target | `build.sbt` → `proto` module | ✅ Configured |
| `DataUpdate` mixin on all 6 message types | `modules/proto/src/main/protobuf/ottochain/v1/messages.proto` | ✅ Done |
| Basic integration test (2 of 6 types) | `modules/proto/src/test/scala/xyz/kd5ujc/proto/ScalaPBIntegrationTest.scala` | ⚠️ Incomplete |

**All 6 OttochainMessage variants in `messages.proto` already have:**
```proto
option (scalapb.message).extends = "io.constellationnetwork.currency.dataApplication.DataUpdate";
```

This spec covers the **remaining gaps only**: CI, test completeness, and documentation.

---

## 2. Gaps to Address

### Gap 1: CI never tests the `proto` module (🔴 highest priority)

Current CI command (`.github/workflows/ci.yml`, `test` job):
```
sbt clean coverage sharedData/test coverageReport
```

The `proto` module is **never compiled or tested in CI**. A broken DataUpdate mixin, invalid proto syntax, or codegen regression will **pass CI silently** on every PR.

### Gap 2: Integration test covers 2 of 6 message types

`ScalaPBIntegrationTest.scala` has tests for:
- ✅ `CreateStateMachine`
- ✅ `TransitionStateMachine`
- ❌ `ArchiveStateMachine` — missing
- ❌ `CreateScript` — missing
- ❌ `InvokeScript` — missing
- ❌ `OttochainMessage` (union type) — missing

### Gap 3: Sequenced trait gap is undocumented

The hand-written `Updates.scala` models define a `Sequenced` sub-trait on `TransitionStateMachine`, `ArchiveStateMachine`, and `InvokeScript` for canonical ordering:
```scala
trait Sequenced {
  def fiberId: UUID
  def targetSequenceNumber: FiberOrdinal  // refined NonNegLong
}
```

Generated proto types **cannot implement this trait directly** because:
- Generated `fiberId: String` ≠ runtime `UUID`
- Generated `targetSequenceNumber: Option[proto.FiberOrdinal]` ≠ runtime `FiberOrdinal` (refined type)

The `ProtoAdapters.scala` adapter (PR #89, `modules/fiber-engine/`) is the correct Phase 1 solution (outbound conversion only). This gap must be documented so the "Remove hand-written models module" card (699621e1) has accurate scope.

---

## 3. Implementation Specification

### Task A: CI Fix

**File:** `.github/workflows/ci.yml`

Add a dedicated step to compile and test the proto module **before** the main test suite. This ensures any proto breakage is surfaced as a clear, named CI failure.

**Change to the `test` job:**
```yaml
      - name: Compile and test proto module
        run: sbt proto/compile proto/test

      - name: Run tests with coverage
        run: sbt clean coverage sharedData/test coverageReport
```

**Why a separate step (not combined):** `sbt coverage` instruments only `sharedData`, and coverage of the proto module is excluded by design (`coverageExcludedPackages := ".*\\.proto\\..*"`). Running proto tests separately avoids interference with coverage instrumentation.

**Acceptance criteria:**
- `sbt proto/test` runs in CI on every PR targeting `main`
- A broken DataUpdate mixin (e.g., removing `scalapb.message.extends` from `messages.proto`) causes CI to fail with a clear compilation error
- Proto compilation failure is reported as a separate named step (not buried in coverage output)

---

### Task B: Integration Test Completeness

**File:** `modules/proto/src/test/scala/xyz/kd5ujc/proto/ScalaPBIntegrationTest.scala`

**Updated import block** (replace existing 2-type import):
```scala
import ottochain.v1.{
  ArchiveStateMachine,
  CreateScript,
  CreateStateMachine,
  InvokeScript,
  OttochainMessage,
  TransitionStateMachine
}
```

**Add 4 new tests after the existing 2:**

#### Test 3: ArchiveStateMachine extends DataUpdate
```scala
test("Generated ArchiveStateMachine extends DataUpdate") {
  IO {
    val archiveSM = ArchiveStateMachine(
      fiberId = "test-fiber-id",
      targetSequenceNumber = None
    )

    // Structural check: generated type satisfies DataUpdate contract
    val dataUpdate: DataUpdate = archiveSM

    expect(archiveSM.fiberId == "test-fiber-id") &&
    expect(dataUpdate.isInstanceOf[DataUpdate])
  }
}
```

#### Test 4: CreateScript extends DataUpdate
```scala
test("Generated CreateScript extends DataUpdate") {
  IO {
    val createScript = CreateScript(
      fiberId = "test-fiber-id",
      scriptProgram = None,
      initialState = None,
      accessControl = None
    )

    val dataUpdate: DataUpdate = createScript

    expect(createScript.fiberId == "test-fiber-id") &&
    expect(dataUpdate.isInstanceOf[DataUpdate])
  }
}
```

#### Test 5: InvokeScript extends DataUpdate
```scala
test("Generated InvokeScript extends DataUpdate") {
  IO {
    val invokeScript = InvokeScript(
      fiberId = "test-fiber-id",
      method = "execute",
      args = None,
      targetSequenceNumber = None
    )

    val dataUpdate: DataUpdate = invokeScript

    expect(invokeScript.fiberId == "test-fiber-id") &&
    expect(invokeScript.method == "execute") &&
    expect(dataUpdate.isInstanceOf[DataUpdate])
  }
}
```

#### Test 6: OttochainMessage union type extends DataUpdate
```scala
test("Generated OttochainMessage union type extends DataUpdate") {
  IO {
    // The outer union wrapper itself also has the DataUpdate mixin.
    // Verify that an OttochainMessage wrapping any inner variant
    // satisfies the DataUpdate type constraint.
    val innerSM = CreateStateMachine(
      fiberId = "union-test-fiber",
      definition = None,
      initialData = None,
      parentFiberId = None
    )
    val msg = OttochainMessage(
      message = OttochainMessage.Message.CreateStateMachine(innerSM)
    )

    val dataUpdate: DataUpdate = msg

    expect(msg.isInstanceOf[DataUpdate]) &&
    expect(dataUpdate.isInstanceOf[DataUpdate]) &&
    expect(msg.message.createStateMachine.exists(_.fiberId == "union-test-fiber"))
  }
}
```

**Note on OttochainMessage.Message:** The `message` field uses a sealed trait (`OttochainMessage.Message`) with case class variants:
- `OttochainMessage.Message.CreateStateMachine(value: CreateStateMachine)`
- `OttochainMessage.Message.TransitionStateMachine(value: TransitionStateMachine)`
- `OttochainMessage.Message.ArchiveStateMachine(value: ArchiveStateMachine)`
- `OttochainMessage.Message.CreateScript(value: CreateScript)`
- `OttochainMessage.Message.InvokeScript(value: InvokeScript)`
- `OttochainMessage.Message.Empty` (default)

---

### Task C: Proto Module README

**File:** `modules/proto/README.md` (new file)

```markdown
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
```

---

## 4. TDD Test Matrix

| # | Test Name | File | Status |
|---|-----------|------|--------|
| T1 | `Generated CreateStateMachine extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ✅ Exists |
| T2 | `Generated TransitionStateMachine extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ✅ Exists |
| T3 | `Generated ArchiveStateMachine extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ❌ Add |
| T4 | `Generated CreateScript extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ❌ Add |
| T5 | `Generated InvokeScript extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ❌ Add |
| T6 | `Generated OttochainMessage union type extends DataUpdate` | `ScalaPBIntegrationTest.scala` | ❌ Add |

**CI test** (not a unit test — verified by CI step existing):
- `sbt proto/compile proto/test` runs in CI workflow | `.github/workflows/ci.yml` | ❌ Add

---

## 5. Dependencies

| Dependency | Status | Impact |
|-----------|--------|--------|
| PR #89 (fiber-engine module + ProtoAdapters) | In Code Review | Informs Phase 2 scope; this spec is **independent** — all changes target `develop` directly |
| Proto schema card (699621e0) | Complete (Test Definition) | Proto messages already finalized |
| "Remove hand-written models module" (699621e1) | Blocked on this card | Cannot proceed until Sequenced gap is resolved (Phase 2) |

---

## 6. Scope Boundaries

**In scope (this card):**
- CI workflow: add `proto/compile proto/test` step
- Integration test: add T3–T6
- Documentation: `modules/proto/README.md` (generation strategy + Sequenced gap)

**Out of scope (future cards):**
- ScalaPB custom type mappings for `UUID` and refined types (Phase 2)
- `OttochainMessage` `fromProto` adapters (Phase 2, after PR #89 merges)
- Removing hand-written models (separate card: 699621e1)
- TypeScript `ts-proto` cleanup (separate card)

---

## 7. Estimated Effort

| Task | Estimate |
|------|----------|
| A: CI fix (`ci.yml`) | ~20 min |
| B: Add 4 integration tests | ~40 min |
| C: `modules/proto/README.md` | ~30 min |
| **Total** | **~1.5h** |

Consistent with the `simple` label on the card.
