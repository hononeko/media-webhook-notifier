# MWN-1: Telegram Callback Listener & Authorization Guard (Design)

* **Ticket:** MWN-1, `feat(telegram): implement callback query listener and authorization guard`
* **Blocks:** MWN-3 (interactive buttons on live progress cards). **Related:** MWN-2 (download client controls)
* **Date:** 2026-10-12
* **Status:** Approved in brainstorming; pending written-spec review

---

## 1. Goal

Let the notifier receive Telegram `callback_query` updates (inline button clicks), make sure only allowlisted Telegram users can trigger anything, answer every click within Telegram's 3-second window, and hand valid clicks to a domain use case through a pluggable action registry.

MWN-1 ships only the infrastructure. **No production actions are registered**: every real click answers "not supported" until MWN-3 adds pause, resume and the rest. The whole feature sits behind a feature flag that is **off by default**. With the flag off, the service behaves exactly as it does today.

## 2. Decisions

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Callback data encoding | `mwn1:<op>:<ref>`, where `ref` is a short random key in the state store pointing to the download | `callback_data` is capped at 64 bytes. Composite `hash1\|hash2` ids and 64-char BitTorrent v2 hashes don't fit. Refs also give `ExpiredCallback` a real meaning and keep hashes out of button payloads. **Deviates from the ticket's `action:hash:operation`.** |
| D2 | Mode selection | Explicit `TELEGRAM_UPDATES_MODE=polling\|webhook`; the app registers or deletes the webhook itself at boot | Switching modes takes one variable and needs no manual `curl`. Avoids a stale webhook leaving polling stuck on 409. |
| D3 | MWN-1 action scope | An empty `BotActionHandler` registry; tests prove dispatch with fake handlers | No throwaway features. MWN-3 registers the real handlers. |
| D4 | Allowlist format | **Numeric Telegram user IDs only**; a non-numeric entry fails boot | Usernames can be changed or reclaimed by someone else. **Deviates from the ticket's "IDs / usernames".** |
| D5 | Where authorization lives | **Adapter (edge)**. The domain receives an already-authorized actor | Identity is platform-specific (Telegram user ids and secret header vs Discord signatures and roles). `TELEGRAM_ALLOWED_USERS` is Telegram config. Unauthorized input never reaches parsing or the state store. Matches the existing `AuthGuard`. |
| D6 | Error modelling | `UnauthorizedUser`, `ExpiredCallback`, `UnknownAction` under a new `DomainError.InteractionError` | AGENTS.md §2.2 wants every failure mode in `DomainError`. The adapter maps all of them to replies with one exhaustive `when`. `UnauthorizedUser` is produced only by the adapter. |
| D7 | Feature flag | One flag, `TELEGRAM_INTERACTIVE_ENABLED` (default `false`), for the whole interactive feature: the MWN-1 listener now and the MWN-3 buttons later. No flag framework | Single-tenant, self-hosted, boot-time toggle. Follows the existing `ENABLE_PREVIEW` pattern. A framework would add dependencies and native-image risk for features (runtime toggles, targeting) we don't need. **Replaces MWN-3's planned `TELEGRAM_ENABLE_INTERACTIVE_BUTTONS`.** |
| D8 | Webhook secret | `TELEGRAM_WEBHOOK_SECRET` is **required** in webhook mode (no derived default) | Deriving it from the bot token would change it silently whenever the token is rotated. |
| D9 | Provider mismatch | Flag on but notification provider isn't `telegram`: log a WARN and leave the feature off | Explicit enough without blocking boot. |

## 3. Non-goals

* Any real action (pause/resume/delete/recheck): MWN-2 and MWN-3.
* Rendering callback buttons on cards: MWN-3.
* Refactoring `TelegramPublisherAdapter` to share the new `TelegramBotApi` client: a possible later refactor.
* Turning the feature on or off at runtime, persisting the polling offset, leader election between polling replicas.
* Per-action permissions (e.g. admin-only delete). If MWN-3 needs them, that rule belongs in the domain and the adapter maps identity to a role.

## 4. Architecture

```
Telegram ──callback_query──▶ [webhook route | polling loop]            adapter/inbound/telegram
                                     │  Update DTO
                                     ▼
                         TelegramCallbackProcessor
                           1. guard: from.id ∈ allowlist, message.chat.id == TELEGRAM_CHAT_ID
                              └ fail ─▶ answerCallbackQuery(alert) ── stop, domain never called
                           2. decode "mwn1:<op>:<ref>"   (malformed ─▶ empty answer, DEBUG log)
                           3. useCase.handle(BotInteraction)    ── 2.5s budget
                           4. answerCallbackQuery(reply | mapped error)
                                     │
                                     ▼
                         BotInteractionService                      domain/service
                           op  → BotActionHandler      (none ─▶ UnknownAction)
                           ref → CallbackRefRegistry   (gone ─▶ ExpiredCallback)
                           handler.handle(actor, target)
```

### 4.1 Domain (`app.hononeko.notifier.domain`)

Adds no framework dependencies; `HexagonalArchitectureTest` must stay green.

* **`model/BotInteraction.kt`**
  * `data class BotActor(val platform: String, val id: String, val displayName: String)`: who clicked. Used for attribution (e.g. MWN-3's "Paused by …"), **not** for authorization.
  * `data class BotInteraction(val actor: BotActor, val operation: String, val ref: String)`
  * `data class BotReply(val text: String, val alert: Boolean = false)`
  * `@Serializable data class CallbackTarget(val downloadId: String)`: stored as JSON so MWN-3 can add fields without changing the stored format.
* **`port/inbound/HandleBotInteractionUseCase.kt`**
  * `fun interface HandleBotInteractionUseCase { suspend fun handle(interaction: BotInteraction): Either<DomainError, BotReply> }`
  * The error type is the broad `DomainError` so a handler's own failures (e.g. `DownloadClientError` in MWN-3) pass through unchanged, as in `TrackDownloadUseCase`.
* **`service/BotActionHandler.kt`**
  * `interface BotActionHandler { val operation: String; suspend fun handle(actor: BotActor, target: CallbackTarget): Either<DomainError, BotReply> }`
* **`service/CallbackRefRegistry.kt`** (depends on `StateStorePort`)
  * `suspend fun register(target: CallbackTarget, ttlSeconds: Long): String`: creates a 10-char base62 ref with `kotlin.random` and stores it with `StateStorePort.tryAcquire("cb:<ref>", ttl, json)`. On a collision it tries again with a new ref, up to a small bounded number of attempts. Refs don't need to be unguessable, because only allowlisted users ever reach a lookup.
  * `suspend fun resolve(ref: String): CallbackTarget?`: `null` if the ref is missing, expired or holds bad JSON.
  * The `mwn:` namespace prefix is added by the state store implementation, not here.
  * Only MWN-3 will call `register` in production. MWN-1 needs it anyway so `ExpiredCallback` can be tested end to end.
* **`service/BotInteractionService.kt`** implements `HandleBotInteractionUseCase`:
  1. Look up the handler by `operation`. If there isn't one: `UnknownAction(operation)`. This check comes first because it needs no I/O.
  2. `resolve(ref)`. If that returns `null`: `ExpiredCallback(ref)`.
  3. Return `handler.handle(actor, target)`.
  * Built with `handlers: List<BotActionHandler>`. Duplicate operations fail at construction.
* **`error/DomainError.kt`**:
  ```kotlin
  sealed interface InteractionError : DomainError {
      data class UnauthorizedUser(val userId: String, val reason: String) : InteractionError
      data class ExpiredCallback(val ref: String) : InteractionError
      data class UnknownAction(val operation: String) : InteractionError
  }
  ```

### 4.2 Adapter (`app.hononeko.notifier.adapter.inbound.telegram`)

* **`TelegramUpdateDtos.kt`**: minimal `@Serializable` `Update`, `CallbackQuery`, `User`, `Message`, `Chat`; unknown keys ignored.
* **`TelegramCallbackData.kt`**: `encode(op, ref)` / `decode(raw)` for `mwn1:<op>:<ref>`. `encode` rejects results over 64 UTF-8 bytes; `decode` returns `null` for anything else. MWN-3 uses `encode` when rendering buttons.
* **`TelegramCallbackGuard.kt`**: `check(query): Either<UnauthorizedUser, BotActor>`.
  * Rejects if `from.id` isn't in `allowedUserIds`.
  * Rejects if `message` is missing (inline-mode callbacks) or `message.chat.id != chatId`.
  * Logs every rejection at INFO with the user ID and reason, as an audit trail.
* **`TelegramCallbackProcessor.kt`**: shared by both modes. Runs guard → decode → use case → answer (§5.4).
* **`TelegramCallbackController.kt`**: the webhook route handler (§5.3).
* **`TelegramPollingService.kt`**: the long-polling loop (§5.2).
* **`TelegramBotApi.kt`**: a Ktor CIO client for `getUpdates`, `answerCallbackQuery`, `setWebhook`, `deleteWebhook`. It returns `Either` and never throws across the boundary.
  * Timeouts: 5s connect, 10s socket, 15s request by default. `getUpdates` overrides the request timeout per call to `pollTimeout + 10s`.
  * A 429 is parsed into `retry_after`.
* **`TelegramInteractionModule.kt`**: builds all of the above from config and exposes `start(scope)`, `close()`, and the route registration. This is the **single seam** the feature flag switches on or off.

### 4.3 Wiring (`Application.kt`)

```kotlin
// buildDependencies
val telegramInteractions =
    if (config.features.telegramInteractive) TelegramInteractionModule.create(config, stateStore, scope) else null
// module()
telegramInteractions?.let { registerTelegramWebhookRoute(it) }   // webhook mode only
// AppDependencies.close()
telegramInteractions?.close()
```

`create` returns `null`, after the WARN from D9, when the provider isn't Telegram. **Flag off** means no route (404), no polling job, no Telegram API calls, no state-store writes.

## 5. Behaviour

### 5.1 Boot (flag on)

1. Config validation fails fast (§6). It runs only when the flag is on.
2. Registration runs in the background in the app scope and **never blocks startup**. It retries with exponential backoff (1s up to 60s) until it succeeds or the app shuts down.
   * **webhook:** `setWebhook(url = <TELEGRAM_WEBHOOK_URL>/api/v1/telegram/webhook, secret_token, allowed_updates = ["callback_query"])`
   * **polling:** `deleteWebhook()`, then start the polling loop.

### 5.2 Polling loop

* `getUpdates(offset, timeout = TELEGRAM_POLL_TIMEOUT_SECONDS, allowed_updates = ["callback_query"])`.
* Set `offset = update_id + 1` **before** processing each update, so a bad update can't be redelivered forever.
* Each callback runs in its own coroutine in the supervisor scope, so a slow handler never stalls the loop.
* Each iteration catches everything except `CancellationException`; the job never dies.

| Condition | Handling |
|---|---|
| 409 Conflict | WARN: "another instance is polling this bot, or a webhook is registered". Back off 30s. |
| 429 | Wait `retry_after` (non-blocking), then continue. |
| 401 / 404 | ERROR: bot token rejected. Back off up to 5 min. |
| Network / timeout | Exponential backoff 1s → 30s; resets after a successful call. |

The offset isn't persisted. After a restart Telegram may resend recent updates; answering them fails with "query is too old", which is logged at DEBUG and ignored.

### 5.3 Webhook route: `POST /api/v1/telegram/webhook`

Registered only when the flag is on and the mode is `webhook`.

1. `InboundRateLimiter` (existing) → 429.
2. Check `X-Telegram-Bot-Api-Secret-Token` against `TELEGRAM_WEBHOOK_SECRET` with `MessageDigest.isEqual`. A missing or wrong header gets **401**. `SERVER_AUTH_TOKEN` is **not** involved.
3. Decode the body. If it can't be parsed: **200** and a WARN. Telegram retries anything that isn't 2xx for up to 24h.
4. Launch processing in the app scope and **respond 200 at once**. Telegram delivers updates one at a time, so holding the response would delay later clicks.

### 5.4 Answer guarantee and error mapping

* `val result = scope.async { useCase.handle(interaction) }`, then `withTimeoutOrNull(2.5s) { result.await() }`.
  * Plain `withTimeout` isn't used because it would cancel the handler.
  * On timeout, answer `"⏳ Working on it…"`. The handler keeps running and its eventual result is logged.
* Mapping to `answerCallbackQuery(text, show_alert)`:

| Outcome | Text | Alert |
|---|---|---|
| `Right(BotReply)` | `reply.text` | `reply.alert` |
| `UnauthorizedUser` | ⛔ You're not allowed to control this bot. | yes |
| `ExpiredCallback` | ⌛ This button has expired. | yes |
| `UnknownAction` | This action isn't supported. | yes |
| other `DomainError` | ⚠️ Action failed. (details logged at WARN) | yes |
| undecodable callback data | *(empty, just clears the spinner)* | no |

* Text is truncated to Telegram's 200-char limit.
* A failed `answerCallbackQuery` is logged and never thrown.

### 5.5 Shutdown

`close()` cancels the polling job, which aborts the in-flight long poll at once. Handlers still running are cancelled with the app scope, as today. The webhook is **left registered** so Telegram queues clicks while the app is down. Stays within the 5s budget (AGENTS.md §2.4).

## 6. Configuration

Bot token and chat ID come from the existing `NOTIFICATION_URL` / `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`.

| Variable | Default | Validated when | Rules |
|---|---|---|---|
| `TELEGRAM_INTERACTIVE_ENABLED` | `false` | always | Boolean feature flag → `AppConfig.features.telegramInteractive` |
| `TELEGRAM_UPDATES_MODE` | `polling` | flag on | `polling` or `webhook`; anything else fails boot |
| `TELEGRAM_ALLOWED_USERS` | — | flag on | Required; comma-separated **numeric** user IDs; empty or non-numeric fails boot |
| `TELEGRAM_WEBHOOK_URL` | — | flag on + webhook | Required; must parse as an `https://` URL; the app appends `/api/v1/telegram/webhook` |
| `TELEGRAM_WEBHOOK_SECRET` | — | flag on + webhook | Required; 1–256 chars `[A-Za-z0-9_-]`; `TELEGRAM_WEBHOOK_SECRET_FILE` supported (`getSecret`) |
| `TELEGRAM_POLL_TIMEOUT_SECONDS` | `25` | flag on + polling | 1–50 |

* New config types:
  * `FeatureFlags(telegramInteractive: Boolean = false)`
  * `TelegramInteractionConfig(mode, allowedUserIds: Set<Long>, webhookUrl, webhookSecret, pollTimeoutSeconds)`
* Both sit on `AppConfig`, are loaded in `ConfigLoader`, and validated with `require(...)` like `DownloadClientConfigLoader`.
* With the flag on, boot also fails if the bot token or chat ID is blank.
* With the flag on and provider ≠ `telegram`: WARN naming the ignored variables; the feature stays off (D9).

## 7. Deployment topologies (to be documented)

| Mode | Replicas | State store | Supported |
|---|---|---|---|
| polling | 1 | any | ✅ |
| polling | > 1 | any | ❌ **Not supported.** Replicas fight over `getUpdates` (409). Enable the flag on exactly one replica. |
| webhook | 1 | memory or Valkey/Redis | ✅ |
| webhook | > 1 | **Valkey/Redis required** | ✅ (callback refs must be shared). With in-memory state, a click routed to another replica answers "expired". |

The app cannot detect the replica count, so these rules are documentation only, not runtime checks. Telegram only delivers webhooks to ports 443, 80, 88 and 8443, so a reverse proxy normally fronts the service.

## 8. Documentation

* **Prerequisite, separate `docs:` PR:** move the README's configuration reference (currently `## ⚙️ Configuration Reference`) unchanged into `docs/CONFIGURATION.md`. The README keeps only what's needed to get started (`NOTIFICATION_URL`, `SERVER_AUTH_TOKEN`, `DOWNLOAD_CLIENT_URL`, `MEDIA_SERVER_*`) and links to the full reference.
* **In MWN-1:**
  * Add the §6 table to `docs/CONFIGURATION.md`.
  * New `docs/TELEGRAM_INTERACTIONS.md`:
    * what the feature does
    * how to enable it
    * choosing polling or webhook
    * how to find your numeric user ID
    * a ⚠️ callout near the top with the §7 rules (polling with more than one replica is **not supported**; webhook mode needs Valkey/Redis **only** for multiple replicas)
    * troubleshooting: 409s, "button expired", 401 on the webhook
  * Link it from the README's "Documentation & Deep Dives".

## 9. Testing

| Layer | Test | Covers |
|---|---|---|
| Domain | `BotInteractionServiceTest` | Dispatch via fake handler; `UnknownAction`; `ExpiredCallback`; handler error passes through; duplicate operations rejected |
| Domain | `CallbackRefRegistryTest` | Register/resolve round trip on `InMemoryStateStore`; TTL expiry via `nowMillis`; collision retry; bad stored JSON → `null` |
| Domain | `DomainErrorTest` | New `InteractionError` cases |
| Adapter | `TelegramCallbackDataTest` | Encode/decode round trip; 64-byte limit; malformed/foreign data → `null` |
| Adapter | `TelegramCallbackGuardTest` | Allowed user; unknown user; wrong chat; missing `message` |
| Adapter | `TelegramCallbackProcessorTest` | Unauthorized never calls use case; each error → expected answer; 2.5s budget with virtual time → "Working on it" and handler still completes; answer failure swallowed |
| Adapter | `TelegramBotApiTest` (`MockEngine`) | Request shapes for all four methods; 429 `retry_after` parsing; `getUpdates` timeout override |
| Adapter | `TelegramPollingServiceTest` (`MockEngine`) | Offset advance; 409/429/network backoff; cancellation stops the loop promptly |
| Adapter | `TelegramWebhookRouteTest` (`testApplication`) | Missing/wrong secret → 401; valid → 200 and use case invoked; malformed body → 200; rate limit → 429 |
| Config | `ConfigLoaderTest` | Flag defaults off; flag off skips validation; required vars; non-numeric user; `http://` webhook URL; missing/invalid secret; provider mismatch → disabled |
| Bootstrap | `ApplicationBootstrapTest` | Flag off → `/api/v1/telegram/webhook` 404 and no Telegram calls; flag on + webhook → route registered |

**Gates:**
* `./gradlew ktlintCheck test check`.
* One `./gradlew nativeCompile` before opening the PR, because new `@Serializable` types are added.

**Manual check against a real bot:**
* Send a message with an inline button whose data is `mwn1:ping:x` via `curl …/sendMessage`.
* An allowlisted account should get the "not supported" alert; any other account should get ⛔.
* Do this once in each mode.

## 10. Follow-ups

* Update MWN-3's ticket: use `TELEGRAM_INTERACTIVE_ENABLED` instead of `TELEGRAM_ENABLE_INTERACTIVE_BUTTONS`, encode buttons with `TelegramCallbackData.encode`, register refs with `CallbackRefRegistry`, and register handlers with `BotInteractionService`.
* MWN-2's ticket still names `TorrentClientPort`; since MWN-6 that port is `DownloadClientPort`.
* Optional: move `TelegramPublisherAdapter` onto `TelegramBotApi`.
