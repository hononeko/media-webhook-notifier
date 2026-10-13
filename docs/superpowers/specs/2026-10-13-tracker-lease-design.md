# MWN-12: Tracking Leases for Clean Rollouts (Design)

* **Ticket:** MWN-12, `feat(state): distributed lock and tracker lease coordination via Valkey for multi-instance HA`
* **Date:** 2026-10-13
* **Status:** Approved in brainstorming; pending written-spec review

---

## 1. Goal

During a rollout or restart, exactly one pod edits each live download card, and the new pod continues the same card instead of posting a duplicate. A pod that crashes has its cards taken over by the lease expiring.

This is the "clean rollouts first" slice of MWN-12:
* The homelab runs 1 replica with `RollingUpdate` (`maxSurge: 25%`), so every rollout briefly runs two pods.
* Running several replicas permanently, fast crash takeover and cross-pod webhook dedupe are not part of this slice (§4).

## 2. Background: where duplicates come from today

* **Webhooks don't cause the duplicates.** A grab webhook reaches one pod (the Service load-balances), and that pod tracks it.
* **Reconciliation does.** Every pod sweeps the download client at startup and every `DOWNLOAD_CLIENT_RECONCILIATION_INTERVAL_MINUTES` (5). A download its own memory doesn't know about is picked up again:
  * tagged downloads (`mwn_msg:`) resume the existing card through `trackExisting`
  * untagged downloads get a new card through `track`
* **During a rollout**, the new pod's startup sweep resumes every card the old pod is still editing, so two pods edit the same messages.
* **Clean shutdowns delete the resume tags.** `runTrackingLoop`'s `finally` calls `cleanupTags` even when the loop is only cancelled by SIGTERM. After a clean restart or rollout, the download looks untagged and gets a duplicate card. Only a crash leaves the tags in place.
* **The finally block can also clean up another session's tags.** When `track` registers a duplicate session and cancels its own job, that job's `finally` removes the tags of the session that is still running.

## 3. Decisions

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Coordination | A lease per download, held by its tracking loop | Zero-downtime rollouts. Grabs keep their rich cards on whichever pod received them. Lays the groundwork for multiple replicas. |
| D2 | Gating | Leases only when the state store is `ValkeyStateStore` (`STATE_STORE_TYPE`, or its alias `STATE_TYPE`, is `valkey` or `redis`, or a Valkey URL is set) | Each pod has its own in-memory store, so in-memory locks can't coordinate pods. The per-download mutex and the local session registry already prevent duplicates within one pod. |
| D3 | Lease keys | `lock:track:<hash>` for each individual normalized hash; a `a\|b` download holds both | A later sweep that sees the hashes in another order, or only `b` still downloading, still finds `b` taken. |
| D4 | Lease TTL | `max(30, 3 × DOWNLOAD_CLIENT_POLL_INTERVAL_SECONDS)` seconds, renewed on every tick | Survives a couple of slow ticks, and a slow poll interval can't let the lease lapse. No new variable. |
| D5 | Owner id | `$HOSTNAME-<6 random alphanumerics>`, built once at startup | The pod name keeps logs readable. The suffix means a container restarted in the same pod doesn't assume it still owns old leases; it waits up to one TTL. |
| D6 | Renewal outcome | `RENEWED`, `LOST`, `STORE_UNAVAILABLE` | Only `LOST` (another owner holds the key) stops a loop. A Valkey blip keeps tracking. |
| D7 | Vanished lease on renew | Taken back with `SET NX` and reported as `RENEWED` | A lease that expired during a Valkey outage must not stop every loop when Valkey returns. |
| D8 | Tags on shutdown | Kept. Only real endings (complete, stalled, missing, max time) remove them | Fixes the clean-restart duplicate, with either store. |
| D9 | Handoff speed | After a sweep that saw `TRACKED_ELSEWHERE`, the next sweep runs after lease TTL + 5s instead of the full interval | The new pod resumes cards within about 35s of the old pod letting go. |
| D10 | Shutdown | `stopAll` cancels the loops and waits up to 3s for them before the state store closes | Their `finally` blocks release the leases while the store is still open, within the 5s budget in AGENTS.md. |

## 4. Non-goals

* Running 2+ replicas permanently as a supported setup (it works, but isn't tuned or documented as HA).
* Takeover faster than one lease TTL plus one short sweep after a crash.
* Cross-pod dedupe of grab webhooks (a Sonarr retry landing on another pod still sends a second card).
* Season-pack debouncing across pods (the 5-second debouncer stays per pod).
* Sharing `ActiveTrackerStore` / tracker snapshots across pods; the health dashboard stays per pod.
* Fencing tokens. A paused pod that lost its lease can make at most one more edit before its next renewal says `LOST`.

## 5. Architecture

### 5.1 Lock API on `StateStorePort`

```kotlin
suspend fun acquireLock(key: String, owner: String, ttlSeconds: Long, nowMillis: Long = System.currentTimeMillis()): Boolean
suspend fun renewLock(key: String, owner: String, ttlSeconds: Long, nowMillis: Long = System.currentTimeMillis()): LockRenewal
suspend fun releaseLock(key: String, owner: String): Boolean

enum class LockRenewal { RENEWED, LOST, STORE_UNAVAILABLE }   // domain/port/outbound
```

* **`ValkeyStateStore`:**
  * Keys get the store's `keyPrefix` (`mwn:` by default).
  * `acquireLock`: `SET key owner NX EX ttl`.
  * `renewLock`: one Lua script.
    * The key holds `owner`: `EXPIRE`, return `RENEWED`.
    * The key is missing: `SET NX EX`, return `RENEWED`.
    * Otherwise: `LOST`.
  * `releaseLock`: one Lua script that `DEL`s only when the value equals `owner`.
  * On `JedisException`:
    * `acquireLock` falls back to the in-memory store, like `tryAcquire`, and logs a WARN.
    * `renewLock` returns `STORE_UNAVAILABLE`. It does not ask the fallback, which would answer `LOST`.
    * `releaseLock` is best effort: it returns `false` and the lease expires by TTL.
* **`InMemoryStateStore`:** the same semantics with atomic `compute` on its map, honouring `nowMillis` expiry like its other operations. It is used as the Valkey fallback and in tests. The tracker never uses it for leasing (D2).

### 5.2 `TrackingLeases` (domain/service)

`class TrackingLeases(stateStore: StateStorePort, ownerId: String, ttlSeconds: Long, clock: () -> Long = System::currentTimeMillis)`

* **`keysFor(downloadId)`:**
  * Splits on `|`, trims, lowercases, drops blanks, de-duplicates and sorts.
  * Maps each hash to `lock:track:<hash>`.
* **`suspend fun acquire(downloadId: String): Boolean`:**
  * Acquires every key in order, all or nothing.
  * On the first failure it releases the keys it already holds and returns `false`.
* **`suspend fun renew(downloadId: String): LockRenewal`:**
  * Renews every key.
  * Any `LOST` gives `LOST`. Otherwise any `STORE_UNAVAILABLE` gives `STORE_UNAVAILABLE`. Otherwise `RENEWED`.
* **`suspend fun release(downloadId: String)`:** releases every key.
* **`val ttlSeconds`:** exposed so reconciliation can compute its retry delay.

### 5.3 `TrackDownloadUseCase` result

`track` and `trackExisting` return `Either<DomainError, TrackingStart>`, where `enum class TrackingStart { STARTED, ALREADY_TRACKED, TRACKED_ELSEWHERE }` lives in `domain/model`.

* Webhook ingestion (`IngestWebhookService`, the season debouncer flush in `Application.kt`) ignores the value.
* Reconciliation uses it (§5.5).

### 5.4 `DownloadTrackerEngine`

The constructor gains `leases: TrackingLeases? = null`.

* **`track`**, inside the existing per-download mutex:
  * If this pod is already tracking: `ALREADY_TRACKED`.
  * If `leases?.acquire(hash) == false`: an INFO log ("tracked by another instance"), then `TRACKED_ELSEWHERE`. No card is sent and no tags are added.
  * If the initial card fails to send: release the leases, then return the error as today.
  * Otherwise as today, returning `STARTED`.
* **`trackExisting`:** the same order. It returns `ALREADY_TRACKED`, `TRACKED_ELSEWHERE` before starting the loop, or `STARTED`.
* **Each tick** of `runTrackingLoop` first calls `leases?.renew(hash)`:
  * `LOST`: a WARN ("another instance owns <hash> now; stopping without touching the card"), then the loop exits *quietly*: no final card, no tag cleanup, no edits.
  * `STORE_UNAVAILABLE`: a DEBUG log, and the tick continues.
  * `RENEWED` or no leases: the tick runs as today.
* **`finally`:**
  * No longer calls `cleanupTags`. Every real ending already does (completion, stall, missing, max time).
  * Always releases the leases.
  * Drops the local session and the mutex entry, as today.
  * All of this runs under `NonCancellable`.
* **Unexpected exception in the loop:** the tags are kept and the leases released, so a later sweep resumes the same card.
* **`stopAll`** becomes `suspend fun stopAll(timeout: Duration = 3.seconds)`:
  * It cancels every session's job and joins them, bounded by `withTimeoutOrNull`.
  * `AppDependencies.close()` calls it inside its existing `runBlocking`, before `stateStore.close()`.
  * `ActiveTrackerStore.stopAll()` returns the cancelled jobs (`List<Job>`) so the engine can join them.

### 5.5 `DownloadReconciliationService`

* **`reconcile()`:**
  * Still returns the number of downloads it resumed (`Int`), so existing callers are unchanged.
  * Counts results that are `TRACKED_ELSEWHERE`, from both `trackExisting` and `track`, and stores the count in a new `val heldElsewhereLastSweep: Int`, next to the existing `runCount`/`resumedCount` counters.
* **The `start` loop:** after a sweep with `heldElsewhereLastSweep > 0`, the next delay is `(leaseTtlSeconds + 5).seconds`. Otherwise it is the configured interval.
  * `ReconciliationConfig` gains `leaseRetrySeconds: Long? = null`. It is null without leases, and the fast path never applies.

### 5.6 Wiring (`Application.kt`)

* When `stateStore is ValkeyStateStore`:
  * `ownerId = "${System.getenv("HOSTNAME") ?: "mwn"}-${random 6}"`
  * `leases = TrackingLeases(stateStore, ownerId, ttlSeconds = maxOf(30, 3 * pollIntervalSeconds))`
  * Log once at INFO: "Tracking leases enabled (owner …, ttl …s)".
* Otherwise `leases = null`.
* Pass `leases` to the engine and `leaseRetrySeconds` to reconciliation.

## 6. Behaviour

| Situation | Result |
|---|---|
| Steady single replica (Valkey) | Leases acquired and renewed. Sweep interval unchanged (5 min). |
| Rollout: new pod starts | Startup sweep gets `TRACKED_ELSEWHERE` for the old pod's downloads and sends no cards. It re-sweeps every ~35s. |
| Rollout: old pod gets SIGTERM | Loops cancelled; leases released; tags kept; shutdown waits ≤3s. |
| Rollout: handoff | The new pod's next short sweep acquires and resumes the *same* cards (≤ ~35s frozen). |
| Pod killed (no cleanup) | Leases expire after ≤ TTL. The next sweep anywhere resumes the cards (≤ ~65s). |
| Valkey down at acquire | In-memory fallback, so both pods may track during the outage (as today). WARN logged. |
| Valkey down at renew | `STORE_UNAVAILABLE`; tracking continues. |
| Valkey back, lease expired meanwhile | Renewal takes it back (`RENEWED`). |
| Another owner holds the lease at renew | `LOST`; the loop stops quietly. |
| `STATE_STORE_TYPE=memory` | No leases. Clean restarts keep the tags, so the same card continues. During a rollout overlap both pods edit briefly. |

## 7. Configuration & documentation

* No new variables.
* `docs/CONFIGURATION.md` gets a **Rollouts and replicas** subsection under *State Store*:
  * smooth rollouts need `STATE_STORE_TYPE=valkey` or `redis`
  * the handoff timings: ~35s on a rollout, ~65s after a crash
  * what the in-memory store does (a brief double edit, then the same card continues)
  * that permanent multi-replica operation isn't tuned yet

## 8. Testing

* **Lock contract**, in `InMemoryStateStoreTest` and `ValkeyStateStoreIntegrationTest` (Testcontainers, real Valkey):
  * a second owner can't acquire
  * the lock can be acquired again after expiry
  * renew and release by a non-owner fail or return `LOST`
  * renewing a vanished key takes it back
  * release by the owner frees the lock
* **`ValkeyStateStoreTest`:** `renewLock` returns `STORE_UNAVAILABLE` and `acquireLock` falls back on `JedisException`.
* **`TrackingLeasesTest`:**
  * `keysFor("B|a| a")` gives `[lock:track:a, lock:track:b]`
  * all-or-nothing acquire rolls back
  * the renewal combining rules
* **`DownloadTrackerEngineTest`** ("two pods": two engines sharing one `InMemoryStateStore` with different owners, a fake publisher and a fake download client):
  * B's `track` of A's download returns `TRACKED_ELSEWHERE` and sends no card
  * after `A.stopAll()`: no `removeTags`/`deleteTags` calls, the leases are released, and B's `trackExisting` returns `STARTED` and edits A's message id
  * a stolen lease makes A stop on its next tick with no publisher calls
  * `STORE_UNAVAILABLE` keeps A ticking
  * A holds `a|b`, so B's `trackExisting("b")` returns `TRACKED_ELSEWHERE`
  * `leases = null`: behaviour matches today except that cancellation keeps the tags
  * a failed initial card releases the leases
* **`DownloadReconciliationServiceTest`** (virtual time): a sweep with `TRACKED_ELSEWHERE` schedules the next sweep after `leaseRetrySeconds + 5`; otherwise after the interval.
* **Shutdown:** `stopAll` waits for the loops, and the leases are released before it returns.

## 9. Risks

* **Dragonfly Lua support.** The deployment uses Dragonfly (`STATE_TYPE=redis`, an alias of `STATE_STORE_TYPE`). Dragonfly supports `EVAL`, but scripts must declare their keys, so pass every key through `KEYS[]`. The Testcontainers test runs against Valkey; check a rollout against Dragonfly after merge.
* **Clock-free TTLs.** Valkey enforces TTLs server-side. The in-memory store uses `nowMillis`, which matters only in tests and in fallback mode.
