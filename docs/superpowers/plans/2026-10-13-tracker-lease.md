# MWN-12 Tracking Leases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During a rollout or restart, exactly one pod edits each live download card, and the new pod continues the same card instead of posting a duplicate.

**Architecture:**
- `StateStorePort` gains owner-checked locks: `SET NX EX` plus Lua on Valkey/Redis, and atomic `compute` in memory.
- A domain `TrackingLeases` holds one lock per hash. `DownloadTrackerEngine` acquires it before sending any card, renews it every tick and releases it in `finally`, which no longer deletes the resume tags.
- Reconciliation re-sweeps quickly while another pod holds leases.
- Leases are only wired in when the store is `ValkeyStateStore`.

**Tech Stack:** Kotlin 2.4, kotlinx.coroutines (virtual-time tests), Jedis `UnifiedJedis` (`set` with `SetParams`, `eval`), Testcontainers (`valkey/valkey:8-alpine`), mockk.

**Spec:** `docs/superpowers/specs/2026-10-13-tracker-lease-design.md`

## Global Constraints

- **Lock key:** `lock:track:<hash>` for each individual hash. Hashes are lowercase and trimmed, a `|`-joined id is split, and blanks are dropped. `ValkeyStateStore` adds its own `keyPrefix`.
- **Lease TTL:** `maxOf(30L, 3L * DOWNLOAD_CLIENT_POLL_INTERVAL_SECONDS)` seconds.
- **Reconciliation retry delay:** lease TTL + 5 seconds.
- **Owner id:** `"${System.getenv("HOSTNAME") ?: "mwn"}-<6 random [a-z0-9]>"`, built once per process.
- **`stopAll` bound:** 3 seconds.
- **Gating:** leases only when `stateStore is ValkeyStateStore`. Otherwise `leases = null` and `leaseRetrySeconds = null`.
- **Renewal:** only `LockRenewal.LOST` stops a loop. `STORE_UNAVAILABLE` keeps tracking.
- **Tags:** never removed on cancellation. Only completion, stall, missing and max-time paths call `cleanupTags`.
- **Lua:** scripts touch only `KEYS[1]`, because Dragonfly requires declared keys.
- **No new environment variables.**
- **Quality gate:** `./gradlew ktlintCheck test` before every commit (120-character lines). The integration test runs with `./gradlew integrationTest --tests '*ValkeyStateStoreIntegrationTest'`, and Docker is available locally.
- **Commits:** Conventional Commits with ` (MWN-12)` in the subject, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never run `nativeCompile` locally.

## Review Focus

1. **Same pod, composite `a|b` live, and a sweep sees only `b`.** It must not start a second loop on the same card: the lease on `b` is held by this pod's `a|b` loop. Pinned in Task 3.
2. **A tracking loop stuck in a non-cancellable call at shutdown.** `stopAll` must still return after its 3s bound, so shutdown stays within budget. Pinned in Task 3.
3. **Fast re-sweep winding down.** Once the other pod's leases are gone, the next sweep returns to the normal interval and doesn't stay on the 35s cadence. Pinned in Task 4.
4. **Slow poll intervals.** With `DOWNLOAD_CLIENT_POLL_INTERVAL_SECONDS=60`, the TTL is 180s, so a lease can't lapse between ticks. Pinned in Task 4.
5. **A webhook `track` and a sweep's `trackExisting` on the same pod for the same hash.** The second call gets `ALREADY_TRACKED`, not `TRACKED_ELSEWHERE`, because the local check comes before the lease. Pinned in Task 3.

---

### Task 1: Owner-checked locks on `StateStorePort`

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/domain/port/outbound/LockRenewal.kt`
- Modify:
  - `domain/port/outbound/StateStorePort.kt`
  - `adapter/outbound/state/InMemoryStateStore.kt`
  - `adapter/outbound/state/ValkeyStateStore.kt`
- Test (all under `src/test/kotlin/app/hononeko/notifier/adapter/outbound/state/`):
  - `InMemoryStateStoreTest.kt`
  - `ValkeyStateStoreTest.kt`
  - `ValkeyStateStoreIntegrationTest.kt`

**Interfaces:**
- Produces:
  - `enum class LockRenewal { RENEWED, LOST, STORE_UNAVAILABLE }`
  - `suspend fun acquireLock(key: String, owner: String, ttlSeconds: Long, nowMillis: Long = System.currentTimeMillis()): Boolean`
  - `suspend fun renewLock(key: String, owner: String, ttlSeconds: Long, nowMillis: Long = System.currentTimeMillis()): LockRenewal`
  - `suspend fun releaseLock(key: String, owner: String): Boolean`
  - All three are abstract on `StateStorePort`.

- [ ] **Step 1: Write the failing tests.**

  `InMemoryStateStoreTest` (with `t0 = 1_000_000L` and key `"lock:track:h"`):
  - **`lock can only be acquired by one owner until it expires`:**
    - `acquireLock(k, "a", 30, t0)` is true.
    - `acquireLock(k, "b", 30, t0)` is false.
    - `acquireLock(k, "b", 30, t0 + 30_000)` is true.
  - **`renew extends only the owner's lock`:**
    - A acquires at `t0`, then `renewLock(k, "a", 30, t0 + 20_000)` is `RENEWED`.
    - `acquireLock(k, "b", 30, t0 + 40_000)` is false.
    - `renewLock(k, "b", 30, t0 + 40_000)` is `LOST`.
  - **`renew takes back a vanished lock`:** on an empty store, `renewLock(k, "a", 30, t0)` is `RENEWED`, then `acquireLock(k, "b", 30, t0)` is false.
  - **`release only frees the owner's lock`:**
    - `releaseLock(k, "b")` is false, and the lock is still held.
    - `releaseLock(k, "a")` is true.
    - `acquireLock(k, "b", 30, t0)` is true.

  `ValkeyStateStoreTest` (mockk `UnifiedJedis` injected through the constructor's `injectedJedis`):
  - **`renewLock reports STORE_UNAVAILABLE when Valkey fails`:** `eval(any<String>(), any<List<String>>(), any<List<String>>())` throws `JedisConnectionException`, and `renewLock("k", "a", 30)` is `STORE_UNAVAILABLE`.
  - **`acquireLock falls back to memory when Valkey fails`:**
    - `set(any<String>(), any<String>(), any<SetParams>())` throws `JedisConnectionException`.
    - `acquireLock("k", "a", 30)` is true, then `acquireLock("k", "b", 30)` is false.

  `ValkeyStateStoreIntegrationTest`: **`lock contract against real Valkey`** uses its own `keyPrefix` (`"mwn:int:lock:"`). It covers:
  - the four in-memory behaviours above, using real time with `ttlSeconds = 1`
  - `Thread.sleep(1_500)` for the expiry case
  - "vanished" means the key was never set.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*InMemoryStateStoreTest' --tests '*ValkeyStateStoreTest'`
  - Expected: compilation failure (`acquireLock`/`renewLock`/`releaseLock`/`LockRenewal` unresolved).
- [ ] **Step 3: Implement.**
  - **In memory:** each method is one `store.compute(key) { _, existing -> … }`. Treat an expired item (`expiresAtMillis <= nowMillis`) as absent. `releaseLock` uses `remove(key, existing)` only when `existing.value == owner`.
  - **Valkey, with `jedis == null`:** delegate all three to `fallbackStore`.
  - **Valkey otherwise:**
    - `acquireLock`: `client.set(prefixed, owner, SetParams.setParams().nx().ex(ttlSeconds)) == "OK"`. On `JedisException`, log a WARN and call `fallbackStore.acquireLock`.
    - `renewLock`: `client.eval(RENEW_SCRIPT, listOf(prefixed), listOf(owner, ttlSeconds.toString()))`. `1L` maps to `RENEWED`, anything else to `LOST`. On `JedisException`, return `STORE_UNAVAILABLE`.
    - `releaseLock`: `client.eval(RELEASE_SCRIPT, …) == 1L`. On `JedisException`, return `false`.
    - Run all three on `Dispatchers.IO`, as the other methods do.
  - The scripts, exactly:

    ```lua
    -- RENEW_SCRIPT
    local v = redis.call('GET', KEYS[1])
    if v == ARGV[1] then redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1 end
    if not v then redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) return 1 end
    return 0
    ```

    ```lua
    -- RELEASE_SCRIPT
    if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
    return 0
    ```
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew integrationTest --tests '*ValkeyStateStoreIntegrationTest'`, then `./gradlew ktlintCheck test`
  - Expected: all PASS.
- [ ] **Step 5: Commit** with the message `feat(state): owner-checked locks on the state store (MWN-12)`.

### Task 2: `TrackingLeases` and the `TrackingStart` result

**Files:**
- Create:
  - `src/main/kotlin/app/hononeko/notifier/domain/service/TrackingLeases.kt`
  - `src/main/kotlin/app/hononeko/notifier/domain/model/TrackingStart.kt`
- Modify:
  - `domain/port/inbound/TrackDownloadUseCase.kt`
  - `domain/service/DownloadTrackerEngine.kt` (return types only)
  - `domain/service/IngestWebhookService.kt` (only if it doesn't compile)
- Test:
  - Create `src/test/kotlin/app/hononeko/notifier/domain/service/TrackingLeasesTest.kt`
  - Update the fakes in `IngestWebhookServiceTest.kt` and `DownloadReconciliationServiceTest.kt`, plus any `DownloadTrackerEngineTest` assertion on `Right(Unit)`.

**Interfaces:**
- Consumes (Task 1): `acquireLock`, `renewLock`, `releaseLock`, `LockRenewal`.
- Produces:
  - `enum class TrackingStart { STARTED, ALREADY_TRACKED, TRACKED_ELSEWHERE }`
  - `TrackDownloadUseCase.track(hash, initialPayload): Either<DomainError, TrackingStart>`
  - `TrackDownloadUseCase.trackExisting(hash, payload, handle, isPhoto): Either<DomainError, TrackingStart>`. The default implementation returns `Either.Right(TrackingStart.STARTED)`.
  - `class TrackingLeases(stateStore: StateStorePort, val ownerId: String, val ttlSeconds: Long, clock: () -> Long = System::currentTimeMillis)`, with:
    - `fun keysFor(downloadId: String): List<String>`
    - `suspend fun acquire(downloadId: String): Boolean`
    - `suspend fun renew(downloadId: String): LockRenewal`
    - `suspend fun release(downloadId: String)`

- [ ] **Step 1: Write the failing tests.** `TrackingLeasesTest` uses one `InMemoryStateStore`, a fixed clock and owners `"pod-a"`/`"pod-b"`, with TTL 30:
  - **`keysFor normalizes, de-duplicates and sorts hashes`:** `keysFor("B|a| a|")` is `listOf("lock:track:a", "lock:track:b")`.
  - **`acquire is all or nothing`:**
    - pod-b's `acquire("b")`, then pod-a's `acquire("a|b")` is false.
    - `store.acquireLock("lock:track:a", "x", 30, now)` is true, because `a` was rolled back.
  - **`renew is LOST when any hash belongs to another owner`:**
    - pod-a acquires `"a|b"`.
    - `store.releaseLock("lock:track:b", "pod-a")`, then `store.acquireLock("lock:track:b", "pod-b", 30, now)`.
    - pod-a's `renew("a|b")` is `LOST`.
  - **`renew is STORE_UNAVAILABLE when the store can't answer and nothing is lost`:**
    - A mockk `StateStorePort` returns `STORE_UNAVAILABLE` for `lock:track:a` and `RENEWED` for `lock:track:b`.
    - `renew("a|b")` is `STORE_UNAVAILABLE`.
  - **`release frees every hash`:** after pod-a's `acquire("a|b")` and `release("a|b")`, pod-b's `acquire("a|b")` is true.
- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*TrackingLeasesTest'`
  - Expected: compilation failure (`TrackingLeases` unresolved).
- [ ] **Step 3: Implement `TrackingStart`, `TrackingLeases` and the port change.**
  - The engine returns `ALREADY_TRACKED` where it currently returns `Right(Unit)` after "already running", and `STARTED` otherwise.
  - `IngestWebhookService` keeps `.bind()` and ignores the value.
  - Test fakes return `Either.Right(TrackingStart.STARTED)`.
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS. Only fake and assertion updates in existing tests.
- [ ] **Step 5: Commit** with the message `feat(tracking): per-hash tracking leases and TrackingStart result (MWN-12)`.

### Task 3: The tracker holds leases, keeps tags on shutdown and waits for its loops

**Files:**
- Modify:
  - `src/main/kotlin/app/hononeko/notifier/domain/service/DownloadTrackerEngine.kt`
  - `domain/port/outbound/ActiveTrackerStore.kt`
  - `adapter/outbound/tracker/InMemoryActiveTrackerStore.kt`
  - `Application.kt` (`AppDependencies.close`, lines 105-116)
- Test:
  - `src/test/kotlin/app/hononeko/notifier/domain/service/DownloadTrackerEngineTest.kt`
  - `src/test/kotlin/app/hononeko/notifier/adapter/outbound/tracker/InMemoryActiveTrackerStoreTest.kt`

**Interfaces:**
- Consumes (Task 2): `TrackingLeases`, `TrackingStart`, `LockRenewal`.
- Produces:
  - `DownloadTrackerEngine(…, scope, leases: TrackingLeases? = null)`
  - `suspend fun stopAll(timeout: Duration = 3.seconds)`
  - `ActiveTrackerStore.stopAll(): List<Job>`

- [ ] **Step 1: Write the failing tests.**

  `DownloadTrackerEngineTest` "two pods" setup:
  - one shared `InMemoryStateStore`
  - engines A and B, each with its own `InMemoryActiveTrackerStore`, `FakeNotificationPublisher` and `FakeTorrentClient`
  - `leases = TrackingLeases(store, "pod-a" / "pod-b", 30)`
  - `DownloadTrackerConfig(pollIntervalSeconds = 5)`
  - a `TestScope(StandardTestDispatcher(testScheduler))` per engine

  The tests:
  - **`a download tracked by another pod gets no second card`:**
    - `A.track("h1", grab)` is `Right(STARTED)`.
    - `B.track("h1", grab)` is `Right(TRACKED_ELSEWHERE)`.
    - B's `sentCards` and `addedTags` are empty.
  - **`a second request on the same pod is ALREADY_TRACKED`:** `A.track("h1")`, then `A.trackExisting("h1", grab, handle, false)` is `Right(ALREADY_TRACKED)` (Review Focus 5).
  - **`stopAll keeps resume tags and releases leases`:**
    - `A.track("h1")`, then `A.stopAll()`.
    - A's `removedTags` and `deletedTags` are empty.
    - `store.acquireLock("lock:track:h1", "x", 30)` is true, then `store.releaseLock("lock:track:h1", "x")`.
    - `B.trackExisting("h1", grab, NotificationHandle("fake", "chat123", "msg_live"), false)` is `Right(STARTED)`.
  - **`a pod that lost its lease stops without touching the card`:**
    - The progress provider returns 50% progress, then `A.track("h1")`.
    - `store.releaseLock("lock:track:h1", "pod-a")`, then `store.acquireLock("lock:track:h1", "pod-b", 30)`.
    - `advanceTimeBy(6_000)`.
    - `A.isTracking("h1")` is false.
    - A's `progressUpdates`, `completedCards`, `cancelledCards` and `removedTags` are all empty.
  - **`tracking continues when the store can't renew`:**
    - A spy store with `coEvery { renewLock(any(), any(), any(), any()) } returns LockRenewal.STORE_UNAVAILABLE`, and a progress provider returning 50%.
    - After `advanceTimeBy(12_000)`, `A.isTracking("h1")` is true and `progressUpdates` isn't empty.
  - **`a pod holding a|b blocks a sweep that only sees b`:**
    - `A.track("a|b")` is `STARTED`.
    - `B.trackExisting("b", …)` is `Right(TRACKED_ELSEWHERE)`.
    - `A.trackExisting("b", …)` is also `Right(TRACKED_ELSEWHERE)`, and A doesn't start a second loop: `A.activeTrackerCount()` is 1 (Review Focus 1).
  - **`a failed initial card releases the leases`:** A with `FakeNotificationPublisher(shouldFailStart = true)`, so `A.track("h1")` is a `Left`. Then `B.track("h1")` is `Right(STARTED)`.
  - **`without leases cancellation keeps tags`:** an engine with `leases = null`. After `track("h1")` and `stopAll()`, `removedTags` and `deletedTags` are empty.
  - **`stopAll returns within its bound when a loop won't cancel`:**
    - The progress provider runs `withContext(NonCancellable) { delay(60_000) }`.
    - `A.track("h1")`, then `advanceTimeBy(6_000)`.
    - Measure `currentTime` around `A.stopAll()`. The difference is ≤ 3_000 (Review Focus 2).

  `InMemoryActiveTrackerStoreTest`: **`stopAll returns the cancelled jobs`** — two sessions, and `stopAll()` returns both jobs, each `isCancelled`.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*DownloadTrackerEngineTest' --tests '*InMemoryActiveTrackerStoreTest'`
  - Expected: compilation failure (`leases` parameter, `stopAll` return type), then the assertions fail.
- [ ] **Step 3: Implement.**
  - **`track` and `trackExisting`:** under the existing mutex, check local first, then call `leases?.acquire(hash)`. A `false` result logs at INFO and returns `TRACKED_ELSEWHERE`. In `track`, a failed `startLiveProgress` calls `leases?.release(hash)` before returning the error.
  - **`runTrackingLoop`:** after each `delay` and before `processTrackingTick`, renew:
    - `LOST`: log a WARN and `return` from the loop function. The quiet exit skips the max-time card.
    - `STORE_UNAVAILABLE`: log at DEBUG and continue.
  - **`finally`:** drop `cleanupTags`. Under `NonCancellable`, call `leases?.release(hash)`, cancel the local session if it's still registered, and remove the mutex.
  - **`stopAll`:** `withTimeoutOrNull(timeout) { activeTrackerStore.stopAll().joinAll() }`.
  - **`AppDependencies.close()`:** move `downloadTracker.stopAll()` into the existing `runBlocking { … }`, after `flushAll`/`flush` and before `stateStore.close()`.
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS. Existing engine tests that asserted tag cleanup on cancellation must be changed to assert that tags are kept. Record that as a ruling.
- [ ] **Step 5: Commit** with the message `feat(tracking): hold leases while tracking and keep resume tags on shutdown (MWN-12)`.

### Task 4: Fast re-sweep, wiring and docs

**Files:**
- Modify:
  - `src/main/kotlin/app/hononeko/notifier/domain/service/DownloadReconciliationService.kt`
  - `Application.kt` (lines ~131-170)
  - `docs/CONFIGURATION.md` (the State Store section, line ~69)
- Test:
  - `src/test/kotlin/app/hononeko/notifier/domain/service/DownloadReconciliationServiceTest.kt`
  - `src/test/kotlin/app/hononeko/notifier/ApplicationBootstrapTest.kt`

**Interfaces:**
- Consumes: `TrackingStart` and `TrackingLeases` (Task 2), and the engine's `leases` parameter (Task 3).
- Produces:
  - `ReconciliationConfig(…, leaseRetrySeconds: Long? = null)`
  - `val DownloadReconciliationService.heldElsewhereLastSweep: Int`
  - `val DownloadReconciliationService.leaseRetrySeconds: Long?`
  - a top-level `fun trackingLeaseTtlSeconds(pollIntervalSeconds: Long): Long = maxOf(30L, 3L * pollIntervalSeconds)` in `TrackingLeases.kt`

- [ ] **Step 1: Write the failing tests.**

  `DownloadReconciliationServiceTest`, with a fake client that has one tagged download and counts `getActiveDownloads` calls, and `ReconciliationConfig(intervalMinutes = 5, leaseRetrySeconds = 35)` under virtual time:
  - **`a sweep that hit another pod's lease retries after the lease delay`:**
    - The fake `trackExisting` returns `TRACKED_ELSEWHERE`.
    - `start(testScope)` and `advanceTimeBy(100)` give 1 call, and `heldElsewhereLastSweep` is 1.
    - `advanceTimeBy(35_000)` gives 2 calls.
  - **`the fast re-sweep stops once the other pod lets go`:**
    - `TRACKED_ELSEWHERE` on the first sweep, then `STARTED`.
    - After `advanceTimeBy(100 + 35_000)` there are 2 calls.
    - `advanceTimeBy(35_000)` still gives 2 calls.
    - `advanceTimeBy(300_000)` gives 3 calls (Review Focus 3).
  - **`without lease retry the interval is unchanged`:** `leaseRetrySeconds = null` and `TRACKED_ELSEWHERE`. After `advanceTimeBy(100 + 35_000)` there is 1 call.

  `TrackingLeasesTest` (add): **`lease ttl scales with slow poll intervals`** — `trackingLeaseTtlSeconds(5)` is 30, and `trackingLeaseTtlSeconds(60)` is 180 (Review Focus 4).

  `ApplicationBootstrapTest`:
  - **`valkey store enables lease retry`:** `buildDependencies` with `state = StateConfig(type = "valkey", url = "")` (fallback mode, no server) gives `dependencies.reconciliationService!!.leaseRetrySeconds == 35L`.
  - **`memory store disables leases`:** the default config gives `leaseRetrySeconds == null`.
  - Close both dependencies afterwards.
- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*DownloadReconciliationServiceTest' --tests '*TrackingLeasesTest' --tests '*ApplicationBootstrapTest'`
  - Expected: compilation failure.
- [ ] **Step 3: Implement.**
  - **`reconcile()`:** count `TRACKED_ELSEWHERE` results from both `trackExisting` and `track` into a local counter. Assign it to `heldElsewhereLastSweep` (`@Volatile`) at the end of the sweep, and to 0 when the sweep returns early.
  - **`start`:** the delay after a sweep is `leaseRetrySeconds!! * 1000` when `leaseRetrySeconds != null && heldElsewhereLastSweep > 0`, otherwise the interval.
  - **`buildDependencies`:** after the state store exists:
    - `val leases = (stateStore as? ValkeyStateStore)?.let { TrackingLeases(it, ownerId(), trackingLeaseTtlSeconds(config.downloadClient.pollIntervalSeconds)) }`
    - log once at INFO: `"Tracking leases enabled (owner {}, ttl {}s)"`
    - pass `leases` to `DownloadTrackerEngine`
    - pass `leaseRetrySeconds = leases?.let { it.ttlSeconds + 5 }` to `ReconciliationConfig`
    - `ownerId()` is a private function returning `"${System.getenv("HOSTNAME") ?: "mwn"}-" + 6 random chars from [a-z0-9]`.
- [ ] **Step 4: Update the docs.** In `docs/CONFIGURATION.md`, after the State Store table, add a `### Rollouts and replicas` subsection:
  - **With `STATE_STORE_TYPE=valkey` or `redis`:**
    - Each live card is edited by one pod at a time.
    - During a rollout the new pod continues the old pod's cards within about 35 seconds of the old pod stopping.
    - After a crash, another pod takes over within about 65 seconds.
  - **With `memory`:** both pods edit cards briefly during a rollout overlap. After that, the same card continues, because resume tags are kept on shutdown.
  - **Limits:** running several replicas permanently isn't a tuned setup yet. Grab webhook retries and season-pack debouncing are per pod.
- [ ] **Step 5: Run the full checks.**
  - Run: `./gradlew ktlintCheck test`, then `./gradlew integrationTest --tests '*ValkeyStateStoreIntegrationTest'`
  - Expected: PASS. `HexagonalArchitectureTest` is green.
- [ ] **Step 6: Commit** with the message `feat(tracking): re-sweep quickly while another pod holds leases, wire leases for Valkey (MWN-12)`.

## After merge

- **Dragonfly:** during the first rollout, the new pod logs `Tracking leases enabled` and, while the old pod is alive, INFO lines saying downloads are tracked by another instance. Live cards keep updating, with no duplicate card, within about 35 seconds of the old pod stopping.
