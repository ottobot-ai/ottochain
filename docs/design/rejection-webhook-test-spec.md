# Test Specification: ML0 Rejection Webhook Dispatch

**Status:** Specification — ready for TDD  
**Author:** @think  
**Feasibility:** @research ✅ (implementation ALREADY COMPLETE on main, tests only remain)  
**Card:** [🌐 Bridge: Dispatch rejection webhook events](https://trello.com/c/69962948) — Trello  
**Branch:** Create `test/ml0-rejection-webhook` from `main`

---

## 1. Context

The ML0 rejection webhook dispatch is **fully implemented** on `main`:

- `WebhookDispatcher.dispatchRejection(ordinal, signedUpdate, errors): F[Unit]` — complete
- `ML0Service.validateData` — runs per-update `validateSignedUpdate`, dispatches fire-and-forget on `Validated.Invalid`
- `RejectionTypes.scala` — `RejectionNotification`, `RejectedUpdate`, `ValidationError` with Circe codecs
- Indexer `/webhook/rejection` endpoint — live, stores to `RejectedTransaction` table, deduplicates by `updateHash`
- Indexer `GET /api/rejections` — fully queryable

**What is missing: tests only.** This spec defines exactly what @code must write.

---

## 2. Build Infrastructure (Required First)

The `l0` module has no test directory and lacks `commonTestSettings` in `build.sbt`. This must be added before any tests can be written.

### 2.1 `build.sbt` change

```scala
// BEFORE:
lazy val currencyL0 = (project in file("modules/l0"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    name := "ottochain-currency-l0"
  )

// AFTER:
lazy val currencyL0 = (project in file("modules/l0"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData, sharedTest % Test)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,  // adds weaver + munit, sets testFrameworks
    name := "ottochain-currency-l0"
  )
```

### 2.2 Create test directory

```bash
mkdir -p modules/l0/src/test/scala/xyz/kd5ujc/metagraph_l0/webhooks
```

### 2.3 Verify `sbt "currencyL0/test"` compiles with no tests (green baseline)

---

## 3. Unit Tests: `WebhookDispatcherSuite`

**File:** `modules/l0/src/test/scala/xyz/kd5ujc/metagraph_l0/webhooks/WebhookDispatcherSuite.scala`

**Package:** `xyz.kd5ujc.metagraph_l0.webhooks`

**Framework:** Weaver `SimpleIOSuite` (same as `ScalaPBIntegrationTest`, `DepthAndHashSuite`)

**Dependencies needed in scope:**
- `TestFixture.resource()` from `shared-test` (provides `SecurityProvider[IO]`, `L0NodeContext[IO]`)
- A mock `WebhookDispatcher` that captures dispatched payloads
- A mock `SubscriberRegistry` with a pre-registered subscriber pointing to a local test server (or a `Ref[IO, List[RejectionNotification]]` as a spy)

### 3.1 Test Setup Pattern

```scala
import cats.effect.{IO, Ref}
import cats.data.{NonEmptyList, Validated}
import xyz.kd5ujc.shared_test.TestFixture
import xyz.kd5ujc.shared_test.Participant._
import weaver.SimpleIOSuite

object WebhookDispatcherSuite extends SimpleIOSuite {

  // Spy dispatcher: captures dispatched rejections for assertion
  def spyDispatcher(spy: Ref[IO, List[RejectionNotification]]): WebhookDispatcher[IO] =
    new WebhookDispatcher[IO] {
      def dispatch(snapshot: *, stats: *): IO[Unit] = IO.unit

      def dispatchRejection(
        ordinal: SnapshotOrdinal,
        update: Signed[OttochainMessage],
        errors: NonEmptyChain[DataApplicationValidationError]
      ): IO[Unit] =
        // Build the notification same way the real impl does, add to spy
        spy.update(_ :+ buildNotification(ordinal, update, errors))
    }
}
```

### 3.2 Test Cases

```
TEST 1: "dispatchRejection constructs correct RejectionNotification payload"
  Given: a Signed[CreateStateMachine] with fiberId="test-fiber", signed by Alice
  And: errors = NonEmptyChain(FiberAlreadyExists("Fiber already exists"))
  When: dispatchRejection(ordinal=42, update, errors) is called
  Then: the captured RejectionNotification has:
    - event = "transaction.rejected"
    - ordinal = 42
    - rejection.updateType = "CreateStateMachine"
    - rejection.fiberId = "test-fiber" UUID
    - rejection.errors = [{code: "FiberAlreadyExists", message: "Fiber already exists"}]
    - rejection.signers is non-empty (Alice's key hex)
    - rejection.updateHash is non-empty 32-byte hex string (64 chars)

TEST 2: "dispatchRejection deduplicates — same update produces same updateHash"
  Given: the same Signed[OttochainMessage] dispatched twice
  When: updateHash computed for each
  Then: both hashes are equal

TEST 3: "dispatchRejection different updates produce different updateHashes"
  Given: two different Signed[OttochainMessage] with different fiberId
  Then: their updateHashes differ

TEST 4: "dispatchRejection fires for TransitionStateMachine — fiberId extracted correctly"
  Given: a Signed[TransitionStateMachine] with fiberId="fiber-456", targetSequenceNumber=3
  When: dispatchRejection called
  Then: rejection.updateType = "TransitionStateMachine"
  And: rejection.fiberId = "fiber-456"
  And: rejection.targetSequenceNumber = Some(3)  // if field is present in spec

TEST 5: "dispatchRejection does not deliver when no subscribers"
  Given: SubscriberRegistry with no active subscribers
  And: a real WebhookDispatcher (not spy) backed by a test HTTP server
  When: dispatchRejection called
  Then: no HTTP requests made to the test server
```

---

## 4. Unit Tests: `ML0ServiceRejectionSuite`

**File:** `modules/l0/src/test/scala/xyz/kd5ujc/metagraph_l0/webhooks/ML0ServiceRejectionSuite.scala`

Tests verify `validateData` behavior with the spy dispatcher.

```
TEST 6: "validateData dispatches rejection for each invalid update"
  Given: ML0Service with a spy WebhookDispatcher
  And: two updates — one valid CreateStateMachine (new fiberId), one invalid (duplicate fiberId)
  When: validateData(state, updates) called
  Then: spy captured exactly ONE rejection notification (for the invalid update)
  And: the valid update is NOT in spy
  And: validateData returns Invalid (because one update failed)

TEST 7: "validateData does NOT dispatch rejection for valid-only batch"
  Given: two valid updates (two new fibers)
  When: validateData(state, updates) called
  Then: spy captured ZERO rejections
  And: validateData returns Valid(())

TEST 8: "validateData dispatches all rejections in a mixed batch"
  Given: three updates — first valid, second invalid, third invalid
  When: validateData called
  Then: spy captured exactly TWO rejections (one per invalid update)
  And: first rejection's fiberId matches second update's fiberId
  And: second rejection's fiberId matches third update's fiberId

TEST 9: "validateData does NOT dispatch when webhookDispatcher is None"
  Given: ML0Service created with None webhookDispatcher
  And: one invalid update
  When: validateData called
  Then: no side effects (no spy to capture — verify by creating no spy and ensuring no IO exceptions)
  And: validateData returns Invalid (rejection tracking doesn't affect result)

TEST 10: "validateData returns combined errors — regression test"
  Given: three invalid updates with 1, 2, and 1 errors respectively
  When: validateData called
  Then: combined result has 4 total errors (regression check for combineAll)
  And: all individual error codes present in combined result

TEST 11: "validateData rejection dispatch is fire-and-forget — does not block"
  Given: a WebhookDispatcher.dispatchRejection that sleeps for 500ms
  When: validateData called with one invalid update
  Then: validateData returns BEFORE 500ms elapses
  (Verifies Async[F].start(dispatcher.dispatchRejection(...)).void semantics)
```

---

## 5. Integration Test: End-to-End Rejection Flow

**File:** `packages/bridge/test/e2e.test.ts` (add to existing test suite)

**Location in file:** Add a new `describe('rejection notifications', ...)` block alongside existing fiber tests.

**Prerequisites:** Running cluster (ML0 + DL1 + indexer). Uses existing `ML0_URL`, `DL1_URL`, `INDEXER_URL` from config.

```typescript
describe('rejection notification end-to-end', () => {

  TEST 12: "rejected transition appears in indexer rejection API"
    // Setup
    const fiberId = crypto.randomUUID()
    const wallet = generateWallet()
    const wrongWallet = generateWallet()  // NOT the fiber owner

    // Step 1: Create fiber
    await client.postData(sign(CreateStateMachine({ fiberId, ... }), wallet))
    const fiber = await waitForFiber(fiberId, 30000)
    assert.ok(fiber, 'Fiber must appear before rejection test')

    // Step 2: Submit invalid transition — signed by wrongWallet (not owner)
    const badTransition = sign(TransitionStateMachine({ fiberId, eventName: 'start' }), wrongWallet)
    await client.postData(badTransition)

    // Step 3: Wait and assert rejection in indexer
    const rejection = await waitForRejection(fiberId, 30000)
    assert.ok(rejection, 'Rejection should appear in indexer within 30s')
    assert.strictEqual(rejection.fiberId, fiberId)
    assert.strictEqual(rejection.updateType, 'TransitionStateMachine')
    assert.ok(rejection.errors.length > 0, 'At least one validation error')
    assert.ok(
      rejection.errors.some(e => e.code === 'NotSignedByOwner' || e.code.includes('owner')),
      `Expected ownership error, got: ${rejection.errors.map(e => e.code).join(', ')}`
    )
    assert.ok(rejection.updateHash.length === 64, 'updateHash should be 64-char hex')
    assert.ok(rejection.signers.length > 0, 'At least one signer recorded')
    assert.strictEqual(rejection.signers[0], wrongWallet.address, 'Signer should be wrongWallet')

  TEST 13: "duplicate rejection is deduplicated — only stored once"
    // Same invalidTransition submitted twice
    // First: verify rejection stored
    // Second: submit same signed update again (same updateHash)
    // Assert: indexer still has exactly ONE rejection for this updateHash
    const count = await indexerClient.getRejections({ fiberId })
    assert.strictEqual(count.total, 1, 'Dedup by updateHash must prevent double-store')

  TEST 14: "fiber with rejections still accepts valid transitions"
    // Same fiberId from TEST 12: valid transition from correct owner
    await client.postData(sign(TransitionStateMachine({ fiberId, eventName: 'start' }), wallet))
    const updated = await waitForState(fiberId, 'Active', 30000)
    assert.ok(updated, 'Fiber should accept valid transition despite prior rejections')
    // Rejection history is preserved (does not affect fiber state)
    const rejections = await indexerClient.getFiberRejections(fiberId)
    assert.ok(rejections.total >= 1, 'Rejection history preserved after successful transition')

})

// Helper (add alongside waitForFiber/waitForState):
async function waitForRejection(fiberId: string, timeoutMs = 30000): Promise<RejectedTransaction | null> {
  const startTime = Date.now()
  while (Date.now() - startTime < timeoutMs) {
    try {
      const response = await fetch(`${INDEXER_URL}/api/rejections?fiberId=${fiberId}&limit=1`)
      if (response.ok) {
        const data = await response.json() as { rejections: RejectedTransaction[], total: number }
        if (data.total > 0) return data.rejections[0]
      }
    } catch { /* ignore poll errors */ }
    await new Promise(r => setTimeout(r, 2000))
  }
  return null
}
```

---

## 6. Deployment Verification Checklist

@work must confirm before card can be marked Done:

- [ ] `WEBHOOK_URL` env var is set in the production cluster's ML0 deployment config (enables the HTTP client; without this, `webhookDispatcher = None` and ALL dispatch is silently skipped)
- [ ] The indexer auto-registers with ML0 on startup (`POST /data-application/v1/webhooks/subscribe`) — confirm this is working against the live cluster
- [ ] `prisma migrate deploy` has been run for the `RejectedTransaction` table in the deployed Postgres instance
- [ ] `GET /api/rejections` is accessible from the explorer/dashboard (confirm CORS + port routing)

---

## 7. Acceptance Criteria

- [ ] `build.sbt`: `currencyL0` has `commonTestSettings` and `dependsOn(sharedTest % Test)`
- [ ] `modules/l0/src/test/scala/` directory created with at least one `.scala` file
- [ ] All 11 Scala unit tests (§3 + §4) pass: `sbt "currencyL0/test"`
- [ ] All 3 integration tests (§5) pass against live cluster
- [ ] `waitForRejection()` helper added to `e2e.test.ts`
- [ ] Deployment checklist (§6) verified by @work
- [ ] No regression in existing `sbt test` (all shared-data tests still pass)

---

## 8. Implementation Notes for @code

**Creating the spy dispatcher:**
The real `WebhookDispatcher.make()` requires an `http4s.client.Client[F]` and a running subscriber. For unit tests, implement a local `WebhookDispatcher[IO]` directly (it's a trait) — no HTTP needed.

**Creating an invalid update:**
Use `TestFixture.resource()` to get `securityProvider` for signing. Create a `CreateStateMachine` with a fiberId that already exists in the test state (seeded via `Combiner.make[IO]().pure[IO]` then `combiner.foldLeft`). The second create for the same fiberId will fail `FiberAlreadyExists`.

**Getting fiber state for validateData:**
```scala
val genesisState = DataState(OnChain.genesis, CalculatedState.genesis)
// Optionally seed a fiber first via combiner, then validateData against seeded state
```

**Running a single suite:**
```bash
source ~/.sdkman/bin/sdkman-init.sh && sbt "currencyL0/testOnly *WebhookDispatcherSuite"
```

**Risk note (@research):** SubscriberRegistry is in-memory. ML0 restart loses subscribers; indexer must re-register. Document this behavior in an inline code comment — no fix needed now, but future card warranted.
