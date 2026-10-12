# MWN-1 Telegram Callback Listener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Receive Telegram `callback_query` updates over long polling or a webhook, reject non-allowlisted users at the adapter edge, answer every click within 3s, and hand valid clicks to a domain use case with an empty action registry. Everything sits behind `TELEGRAM_INTERACTIVE_ENABLED` (default off).

**Architecture:**
* **Domain** (no framework imports): `BotInteractionService` resolves a short ref through `CallbackRefRegistry` (backed by `StateStorePort`) and dispatches to `BotActionHandler`s.
* **Adapter** (`adapter/inbound/telegram`): DTOs, callback-data codec, allowlist/chat guard, a Ktor `TelegramBotApi` client, the processor that answers Telegram, the polling loop and the webhook route.
* **Wiring:** one `TelegramInteractionModule`, which `Application.kt` builds only when the flag is on.

**Tech Stack:** Kotlin 2.4, kotlinx.coroutines 1.11, kotlinx.serialization 1.11, Arrow 2.2 (`Either`), Ktor 3.6 (server + CIO client), kotlin.test, kotlinx-coroutines-test, ktor-client-mock, ktor-server-test-host, mockk.

**Spec:** `docs/superpowers/specs/2026-10-12-telegram-callback-listener-design.md`

**Decisions this plan adds on top of the spec:**
1. **Provider-mismatch WARN (D9) lives in the config loader.** It forces the flag off there. `TelegramInteractionModule.create` never returns `null`.
2. **`TelegramBotApi` failures use the existing `DomainError.NotificationError`** (AGENTS.md §2.2). `DeliveryFailed` gains an optional `httpStatus: Int? = null` so polling can tell 409 from 401/404.
3. **`buildDependencies` gains `telegramApiEngine: HttpClientEngine? = null`** so tests never reach `api.telegram.org`.

## Global Constraints

- **Domain purity:** domain code (`app.hononeko.notifier.domain.*`) imports no `io.ktor`, `io.netty`, Hoplite or HTTP libraries (`HexagonalArchitectureTest`).
- **No unchecked exceptions across boundaries:** return `Either`. The only `require`/`check` throws are config validation and construction-time wiring errors.
- **Timeouts:** outbound Telegram calls use 5s connect, 10s socket, 15s request. `getUpdates` overrides socket/request to `(pollTimeout + 10) s`.
- **Callback data:** `mwn1:<op>:<ref>`, at most 64 UTF-8 bytes.
- **Refs:** 10 chars `[A-Za-z0-9]`, stored under key `cb:<ref>`. The `mwn:` prefix is added by `ValkeyStateStore`.
- **Answers:**
  - 2.5s budget for the use case; on timeout answer exactly `⏳ Working on it…`
  - answer text cut to 200 chars
  - a failed answer is logged, never thrown
- **Exact answer copy:**

| Outcome | Text | `show_alert` |
|---|---|---|
| `UnauthorizedUser` | `⛔ You're not allowed to control this bot.` | true |
| `ExpiredCallback` | `⌛ This button has expired.` | true |
| `UnknownAction` | `This action isn't supported.` | true |
| other `DomainError` or thrown exception | `⚠️ Action failed.` | true |
| undecodable data | `""` | false |

- **Webhook:**
  - route `POST /api/v1/telegram/webhook`
  - secret checked from header `X-Telegram-Bot-Api-Secret-Token` with `MessageDigest.isEqual`
  - 401 on mismatch, 200 for everything else after the rate limiter (429)
- **Registration:**
  - `allowed_updates = ["callback_query"]`
  - retry backoff 1s → 60s; never blocks startup
  - the webhook is never deleted on shutdown
- **Polling backoff:**

| Condition | Backoff |
|---|---|
| 409 | 30s |
| 429 | `retry_after` |
| 401/404 | exponential 30s → 5 min |
| network | exponential 1s → 30s, reset on success |

  The offset advances **before** each update is processed.
- **Config (validated only when the flag is on):**
  - `TELEGRAM_UPDATES_MODE`: `polling` (default) or `webhook`
  - `TELEGRAM_ALLOWED_USERS`: required; comma-separated **positive** integers
  - `TELEGRAM_WEBHOOK_URL`: required in webhook mode; `https://` with a host
  - `TELEGRAM_WEBHOOK_SECRET`: required in webhook mode; `^[A-Za-z0-9_-]{1,256}$`; `_FILE` supported
  - `TELEGRAM_POLL_TIMEOUT_SECONDS`: 1–50, default 25
  - the bot token and chat ID must not be blank
- **Gates before any commit is pushed:** `./gradlew ktlintCheck test check`. `./gradlew nativeCompile` runs once before the PR.

## Review Focus

1. **`TELEGRAM_CHAT_ID` set as `@channelname`.** Callback `chat.id` is numeric, so a plain string compare rejects every click. The guard must also accept `chat.username` equal to the name without `@`, case-insensitively. (Task 4)
2. **A handler throws instead of returning `Left`** (a future MWN-3 bug). The processor must still answer `⚠️ Action failed.`, and the polling loop must keep running. (Task 6)
3. **Allowlist formatting:** `" 123, 456,"` (spaces, trailing comma) must parse as `{123, 456}`. A negative id such as a pasted group id `-100123` must fail boot with a message naming `TELEGRAM_ALLOWED_USERS`. (Task 3)
4. **`TELEGRAM_WEBHOOK_URL` with a trailing slash, or already ending in `/api/v1/telegram/webhook`.** The registered URL must be exactly `<base>/api/v1/telegram/webhook`: no `//`, and the path never doubled. (Task 3)
5. **A webhook update with no `callback_query`** (e.g. a `message` update if `allowed_updates` was changed elsewhere) must get 200 and never reach the processor. (Task 8)

---

### Task 1: Callback ref registry

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/domain/model/BotInteraction.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/domain/service/CallbackRefRegistry.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/domain/service/CallbackRefRegistryTest.kt`

**Interfaces:**
- Produces:
  - in `BotInteraction.kt`:
    - `data class BotActor(val platform: String, val id: String, val displayName: String)`
    - `data class BotInteraction(val actor: BotActor, val operation: String, val ref: String)`
    - `data class BotReply(val text: String, val alert: Boolean = false)`
    - `@Serializable data class CallbackTarget(val downloadId: String)`
  - `class CallbackRefRegistry(stateStore: StateStorePort, random: kotlin.random.Random = Random.Default, clock: () -> Long = System::currentTimeMillis)`:
    - `suspend fun register(target: CallbackTarget, ttlSeconds: Long): String?`: `null` only if 5 attempts all collide or the store refuses
    - `suspend fun resolve(ref: String): CallbackTarget?`

- [ ] **Step 1: Write the failing tests.** Use `InMemoryStateStore` and a controllable clock (`var now = 1_000_000L`).

```kotlin
@Test fun `register then resolve returns the stored target`() = runTest {
    val ref = registry.register(CallbackTarget("abc|def"), ttlSeconds = 60)!!
    assertTrue(Regex("^[A-Za-z0-9]{10}$").matches(ref))
    assertEquals(CallbackTarget("abc|def"), registry.resolve(ref))
}
@Test fun `resolve returns null after ttl expires`() = runTest {
    val ref = registry.register(CallbackTarget("x"), ttlSeconds = 60)!!
    now += 61_000
    assertNull(registry.resolve(ref))
}
@Test fun `register retries on collision`() = runTest {
    // Random(seed) twice with the same seed produces the same first ref; pre-occupy it.
    val first = CallbackRefRegistry(store, Random(7), clock).register(CallbackTarget("a"), 60)!!
    val second = CallbackRefRegistry(store, Random(7), clock).register(CallbackTarget("b"), 60)!!
    assertNotEquals(first, second)
    assertEquals(CallbackTarget("a"), registry.resolve(first))
}
@Test fun `resolve rejects refs outside the alphabet without touching the store`() = runTest {
    val spy = spyk(InMemoryStateStore())
    assertNull(CallbackRefRegistry(spy, clock = clock).resolve("../../etc"))
    assertNull(CallbackRefRegistry(spy, clock = clock).resolve(""))
    coVerify(exactly = 0) { spy.get(any(), any()) }
}
@Test fun `resolve returns null for corrupt stored json`() = runTest {
    store.set("cb:AAAAAAAAAA", "not-json", null, now)
    assertNull(registry.resolve("AAAAAAAAAA"))
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests 'app.hononeko.notifier.domain.service.CallbackRefRegistryTest'`. Expected: compilation failure (unresolved `CallbackRefRegistry`).

- [ ] **Step 3: Implement the model file and `CallbackRefRegistry`.**
  - Store with `stateStore.tryAcquire("cb:$ref", ttlSeconds, Json.encodeToString(target), nowMillis = clock())`; read with `stateStore.get("cb:$ref", nowMillis = clock())`.
  - Decode with `runCatching`, returning `null` on failure.
  - Generate refs from a base62 alphabet with `random.nextInt(62)`.
  - Constants: `REF_LENGTH = 10`, `MAX_ATTEMPTS = 5`, `KEY_PREFIX = "cb:"`.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: PASS (5 tests).

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/app/hononeko/notifier/domain/model/BotInteraction.kt src/main/kotlin/app/hononeko/notifier/domain/service/CallbackRefRegistry.kt src/test/kotlin/app/hononeko/notifier/domain/service/CallbackRefRegistryTest.kt
git commit -m "feat(telegram): add callback ref registry backed by the state store (MWN-1)"
```

---

### Task 2: Bot interaction use case and domain errors

**Files:**
- Modify: `src/main/kotlin/app/hononeko/notifier/domain/error/DomainError.kt`: add `InteractionError`, and add `httpStatus: Int? = null` as the last parameter of `NotificationError.DeliveryFailed`
- Create: `src/main/kotlin/app/hononeko/notifier/domain/port/inbound/HandleBotInteractionUseCase.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/domain/service/BotActionHandler.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/domain/service/BotInteractionService.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/domain/service/BotInteractionServiceTest.kt`, plus additions to `src/test/kotlin/app/hononeko/notifier/domain/error/DomainErrorTest.kt`

**Interfaces:**
- Consumes: Task 1 (`BotActor`, `BotInteraction`, `BotReply`, `CallbackTarget`, `CallbackRefRegistry`)
- Produces:
  - `sealed interface DomainError.InteractionError : DomainError` with:
    - `UnauthorizedUser(val userId: String, val reason: String)`
    - `ExpiredCallback(val ref: String)`
    - `UnknownAction(val operation: String)`
  - `fun interface HandleBotInteractionUseCase { suspend fun handle(interaction: BotInteraction): Either<DomainError, BotReply> }`
  - `interface BotActionHandler { val operation: String; suspend fun handle(actor: BotActor, target: CallbackTarget): Either<DomainError, BotReply> }`
  - `class BotInteractionService(handlers: List<BotActionHandler>, refRegistry: CallbackRefRegistry) : HandleBotInteractionUseCase`

- [ ] **Step 1: Write the failing tests.** Build `BotInteractionService` with a fake `ping` handler, plus a registry on `InMemoryStateStore`.

```kotlin
@Test fun `dispatches to the handler for the operation with the resolved target`() = runTest {
    val ref = registry.register(CallbackTarget("h1"), 60)!!
    val result = service.handle(BotInteraction(actor, "ping", ref))
    assertEquals(Either.Right(BotReply("pong h1")), result)
}
@Test fun `unknown operation is UnknownAction and never reads the store`() = runTest {
    assertEquals(Either.Left(DomainError.InteractionError.UnknownAction("nope")), service.handle(BotInteraction(actor, "nope", "AAAAAAAAAA")))
}
@Test fun `missing ref is ExpiredCallback`() = runTest {
    assertEquals(Either.Left(DomainError.InteractionError.ExpiredCallback("AAAAAAAAAA")), service.handle(BotInteraction(actor, "ping", "AAAAAAAAAA")))
}
@Test fun `handler errors pass through unchanged`() = runTest { /* failing handler returns Left(DownloadClientError.DownloadNotFound("h1")); assert same Left */ }
@Test fun `duplicate operations are rejected at construction`() {
    assertFailsWith<IllegalArgumentException> { BotInteractionService(listOf(ping, ping), registry) }
}
```

In `DomainErrorTest`, add a test that builds all three `InteractionError` cases and asserts their fields, plus `is DomainError`. Also add `DeliveryFailed("telegram", "x").httpStatus == null`.

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*BotInteractionServiceTest' --tests '*DomainErrorTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement the error additions, the port, the handler interface and the service.** The service checks the operation (map lookup) before `refRegistry.resolve`. The `init` block calls `require(handlers.map { it.operation }.toSet().size == handlers.size)`.

- [ ] **Step 4: Run the tests and confirm they pass.** Run the same command plus `--tests '*HexagonalArchitectureTest'`. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/app/hononeko/notifier/domain src/test/kotlin/app/hononeko/notifier/domain
git commit -m "feat(telegram): add bot interaction use case, action registry and interaction errors (MWN-1)"
```

---

### Task 3: Feature flag and Telegram interaction config

**Files:**
- Modify: `src/main/kotlin/app/hononeko/notifier/config/AppConfig.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/config/TelegramInteractionConfigLoader.kt`
- Modify: `src/main/kotlin/app/hononeko/notifier/config/ConfigLoader.kt` (`load` calls the new loader after notifications are loaded)
- Modify: `docs/CONFIGURATION.md`: add a `## Telegram Interactions` section with the spec §6 table and a link to `TELEGRAM_INTERACTIONS.md` (created in Task 9)
- Test: `src/test/kotlin/app/hononeko/notifier/config/TelegramInteractionConfigTest.kt`

**Interfaces:**
- Produces:
  - `data class FeatureFlags(val telegramInteractive: Boolean = false)`
  - `enum class TelegramUpdatesMode(val key: String) { POLLING("polling"), WEBHOOK("webhook") }` with `companion fun fromKey(raw: String): TelegramUpdatesMode?`
  - `data class TelegramInteractionConfig(val mode: TelegramUpdatesMode = POLLING, val allowedUserIds: Set<Long> = emptySet(), val webhookUrl: String = "", val webhookSecret: String = "", val pollTimeoutSeconds: Int = 25)`, plus a computed `val webhookEndpoint: String`: `webhookUrl` with any trailing `/` and any trailing `/api/v1/telegram/webhook` removed, followed by `/api/v1/telegram/webhook`
  - `AppConfig` gains `features: FeatureFlags = FeatureFlags()` and `telegramInteraction: TelegramInteractionConfig = TelegramInteractionConfig()`
  - `internal object TelegramInteractionConfigLoader { fun load(reader: ConfigLoader.EnvReader, notifications: NotificationConfig): Pair<FeatureFlags, TelegramInteractionConfig> }`

- [ ] **Step 1: Write the failing tests.** Each test uses `ConfigLoader.load(env)`.

```kotlin
private val base = mapOf("TELEGRAM_BOT_TOKEN" to "1:T", "TELEGRAM_CHAT_ID" to "-1001", "TELEGRAM_INTERACTIVE_ENABLED" to "true", "TELEGRAM_ALLOWED_USERS" to "42")

@Test fun `flag defaults off and nothing is validated`() {
    val c = ConfigLoader.load(mapOf("TELEGRAM_ALLOWED_USERS" to "@alice", "TELEGRAM_UPDATES_MODE" to "bogus"))
    assertFalse(c.features.telegramInteractive)
}
@Test fun `polling is the default mode`() = assertEquals(TelegramUpdatesMode.POLLING, ConfigLoader.load(base).telegramInteraction.mode)
@Test fun `allowlist tolerates spaces and trailing comma`() =
    assertEquals(setOf(123L, 456L), ConfigLoader.load(base + ("TELEGRAM_ALLOWED_USERS" to " 123, 456,")).telegramInteraction.allowedUserIds)
@Test fun `username, negative id or empty allowlist fails boot naming the variable`() {
    for (bad in listOf("@alice", "-100123", "", " , ")) {
        val e = assertFailsWith<IllegalArgumentException> { ConfigLoader.load(base + ("TELEGRAM_ALLOWED_USERS" to bad)) }
        assertTrue(e.message!!.contains("TELEGRAM_ALLOWED_USERS"))
    }
}
@Test fun `unknown mode fails boot`()        // "TELEGRAM_UPDATES_MODE" to "push" → message contains TELEGRAM_UPDATES_MODE
@Test fun `blank bot token or chat id fails boot when enabled`()
@Test fun `webhook mode requires https url and valid secret`() {
    val wh = base + ("TELEGRAM_UPDATES_MODE" to "webhook")
    assertFailsWith<IllegalArgumentException> { ConfigLoader.load(wh + ("TELEGRAM_WEBHOOK_SECRET" to "s")) }                                      // url missing
    assertFailsWith<IllegalArgumentException> { ConfigLoader.load(wh + mapOf("TELEGRAM_WEBHOOK_URL" to "http://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "s")) }
    assertFailsWith<IllegalArgumentException> { ConfigLoader.load(wh + ("TELEGRAM_WEBHOOK_URL" to "https://x.example.com")) }                    // secret missing
    assertFailsWith<IllegalArgumentException> { ConfigLoader.load(wh + mapOf("TELEGRAM_WEBHOOK_URL" to "https://x.example.com", "TELEGRAM_WEBHOOK_SECRET" to "has space")) }
}
@Test fun `webhook secret can come from a file`()   // write temp file, TELEGRAM_WEBHOOK_SECRET_FILE → value read and trimmed
@Test fun `webhook endpoint never doubles slash or path`() {
    for (url in listOf("https://x.example.com", "https://x.example.com/", "https://x.example.com/api/v1/telegram/webhook"))
        assertEquals("https://x.example.com/api/v1/telegram/webhook", TelegramInteractionConfig(webhookUrl = url).webhookEndpoint)
}
@Test fun `poll timeout outside 1-50 fails boot`()
@Test fun `non-telegram provider disables the feature without failing`() {
    val c = ConfigLoader.load(mapOf("NOTIFICATION_URL" to "discord://abc", "TELEGRAM_INTERACTIVE_ENABLED" to "true"))
    assertFalse(c.features.telegramInteractive)
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*TelegramInteractionConfigTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement the config types, the loader and the `ConfigLoader.load` wiring.**
  - Read the flag with `reader.getBoolean(false, "TELEGRAM_INTERACTIVE_ENABLED", "telegram.interactiveEnabled")`.
  - Read the secret with `reader.getSecret("TELEGRAM_WEBHOOK_SECRET")`.
  - Validate the URL with `java.net.URI`: scheme `https`, non-blank host.
  - Validation failures throw from `require(...)`, and every message names the offending variable.
  - On provider mismatch, log one WARN through an slf4j logger: `TELEGRAM_INTERACTIVE_ENABLED is set but the notification provider is '<p>'; Telegram interactions stay disabled and TELEGRAM_UPDATES_MODE, TELEGRAM_ALLOWED_USERS, TELEGRAM_WEBHOOK_URL, TELEGRAM_WEBHOOK_SECRET and TELEGRAM_POLL_TIMEOUT_SECONDS are ignored.` Then return `FeatureFlags(false)` and skip validation.
  - Add the `docs/CONFIGURATION.md` section.

- [ ] **Step 4: Run the tests and confirm they pass.** Run the new test class plus `--tests '*ConfigLoaderTest'`. Expected: PASS, and the existing defaults test is unchanged.

- [ ] **Step 5: Commit.** `feat(telegram): add TELEGRAM_INTERACTIVE_ENABLED flag and interaction config (MWN-1)`

---

### Task 4: Update DTOs, callback-data codec and guard

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramUpdateDtos.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramCallbackData.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramCallbackGuard.kt`
- Test: `TelegramCallbackDataTest.kt` and `TelegramCallbackGuardTest.kt` under `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/`

**Interfaces:**
- Consumes: Task 1 (`BotActor`), Task 2 (`DomainError.InteractionError.UnauthorizedUser`)
- Produces:
  - `@Serializable` DTOs (all `internal`):
    - `TelegramUpdate(@SerialName("update_id") updateId: Long, @SerialName("callback_query") callbackQuery: TelegramCallbackQuery? = null)`
    - `TelegramCallbackQuery(id: String, from: TelegramUser, message: TelegramMessage? = null, data: String? = null)`
    - `TelegramUser(id: Long, @SerialName("first_name") firstName: String = "", username: String? = null)`
    - `TelegramMessage(@SerialName("message_id") messageId: Long, chat: TelegramChat)`
    - `TelegramChat(id: Long, username: String? = null)`
  - `object TelegramCallbackData`:
    - `fun encode(operation: String, ref: String): String?`: `null` if `op` doesn't match `^[a-z][a-z0-9_-]*$`, `ref` doesn't match `^[A-Za-z0-9]+$`, or the result exceeds 64 bytes
    - `fun decode(raw: String?): Decoded?` with `data class Decoded(val operation: String, val ref: String)`
  - `class TelegramCallbackGuard(allowedUserIds: Set<Long>, chatId: String)`:
    - `fun check(query: TelegramCallbackQuery): Either<DomainError.InteractionError.UnauthorizedUser, BotActor>`
    - the actor is `BotActor("telegram", from.id.toString(), from.username?.let { "@$it" } ?: from.firstName)`

- [ ] **Step 1: Write the failing tests.**

```kotlin
// TelegramCallbackDataTest
@Test fun `round trips`() = assertEquals(Decoded("pause", "k3Zq9xT2bA"), decode(encode("pause", "k3Zq9xT2bA")))
@Test fun `encode refuses payloads over 64 bytes`() = assertNull(encode("a".repeat(50), "b".repeat(20)))
@Test fun `decode rejects foreign or malformed data`() {
    for (raw in listOf(null, "", "dl:abc:pause", "mwn1:pause", "mwn1:pause:", "mwn1:pause:abc:extra", "mwn1:Pause:abc", "mwn1:pause:ab-c", "mwn1:pause:äbc"))
        assertNull(decode(raw), raw)
}
// TelegramCallbackGuardTest (allowed = {42}, chatId = "-1001")
@Test fun `allowed user in configured chat yields actor with @username`()
@Test fun `actor falls back to first name without username`()
@Test fun `unknown user is UnauthorizedUser with user id`()      // from.id = 7 → Left(UnauthorizedUser("7", reason)), reason mentions TELEGRAM_ALLOWED_USERS
@Test fun `wrong chat is UnauthorizedUser`()                      // chat.id = -999
@Test fun `callback without message is UnauthorizedUser`()
@Test fun `chat configured as @channel matches chat username case-insensitively`() {
    val guard = TelegramCallbackGuard(setOf(42), "@MyChannel")
    assertTrue(guard.check(query(chat = TelegramChat(-1002, "mychannel"))).isRight())
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests 'app.hononeko.notifier.adapter.inbound.telegram.*'`. Expected: compilation failure.

- [ ] **Step 3: Implement the DTOs, the codec and the guard.** The guard logs each rejection at INFO with the user ID and reason.

- [ ] **Step 4: Run the tests and confirm they pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(telegram): add callback DTOs, mwn1 callback-data codec and allowlist guard (MWN-1)`

---

### Task 5: Telegram Bot API client

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramBotApi.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramBotApiTest.kt`

**Interfaces:**
- Consumes: Task 4 DTOs; Task 2 `DeliveryFailed.httpStatus`
- Produces `class TelegramBotApi(botToken: String, engine: HttpClientEngine? = null, baseUrl: String = "https://api.telegram.org") : AutoCloseable` with:
  - `suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): Either<DomainError.NotificationError, List<TelegramUpdate>>`
  - `suspend fun answerCallbackQuery(callbackQueryId: String, text: String, showAlert: Boolean): Either<DomainError.NotificationError, Unit>`
  - `suspend fun setWebhook(url: String, secretToken: String): Either<DomainError.NotificationError, Unit>`
  - `suspend fun deleteWebhook(): Either<DomainError.NotificationError, Unit>`
- Error mapping, all with provider `"telegram"`:

| Response | Result |
|---|---|
| 429 | `RateLimited(retryAfterSeconds = parameters.retry_after ?: 5)` |
| other non-2xx, or `ok=false` | `DeliveryFailed(message = "HTTP <code>: <description>", httpStatus = code)` |
| `IOException` / timeout | `DeliveryFailed(httpStatus = null)` |

- [ ] **Step 1: Write the failing tests.** Use `MockEngine` and record the path and JSON body of each request.

```kotlin
@Test fun `getUpdates posts offset, timeout and allowed_updates and parses callback updates`()
    // path /bot1:T/getUpdates; body has "offset":10,"timeout":25,"allowed_updates":["callback_query"]; returns 1 update with callback_query.id == "cq1"
@Test fun `getUpdates extends the request timeout beyond the long poll`()
    // read the request's HttpTimeout capability in MockEngine: requestTimeoutMillis == 35_000 and socketTimeoutMillis == 35_000 for timeoutSeconds = 25
@Test fun `answerCallbackQuery truncates text to 200 chars and sends show_alert`()
@Test fun `setWebhook sends url, secret_token and allowed_updates`()
@Test fun `deleteWebhook keeps pending updates`()                 // body has "drop_pending_updates":false
@Test fun `429 maps to RateLimited with retry_after`()            // {"ok":false,"error_code":429,"parameters":{"retry_after":17}} → RateLimited("telegram", 17)
@Test fun `409 maps to DeliveryFailed with httpStatus 409`()
@Test fun `io failure maps to DeliveryFailed without status`()    // MockEngine throws IOException
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*TelegramBotApiTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `TelegramBotApi`.**
  - Client setup matches `TelegramPublisherAdapter`: CIO or the injected engine, `ContentNegotiation` with `ignoreUnknownKeys`/`explicitNulls = false`, and `HttpTimeout` 15s request / 5s connect / 10s socket.
  - `getUpdates` overrides the timeouts per request with `timeout { requestTimeoutMillis = …; socketTimeoutMillis = … }`.
  - Catch `IOException` and `HttpRequestTimeoutException`; let `CancellationException` propagate.

- [ ] **Step 4: Run the tests and confirm they pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(telegram): add Bot API client for updates, answers and webhook registration (MWN-1)`

---

### Task 6: Callback processor (answer guarantee)

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramCallbackProcessor.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramCallbackProcessorTest.kt`

**Interfaces:**
- Consumes: Task 2 `HandleBotInteractionUseCase`; Task 4 guard, codec and DTOs; Task 5 `TelegramBotApi.answerCallbackQuery`
- Produces: `class TelegramCallbackProcessor(guard: TelegramCallbackGuard, useCase: HandleBotInteractionUseCase, api: TelegramBotApi, scope: CoroutineScope, answerBudget: Duration = 2500.milliseconds)` with `suspend fun process(query: TelegramCallbackQuery)`. It never throws, except `CancellationException`.

- [ ] **Step 1: Write the failing tests.**
  - Run under `runTest`, with `scope = backgroundScope` and a `MockEngine` that records every `answerCallbackQuery` body.
  - The use case is a recording fake `HandleBotInteractionUseCase`.
  - Expected texts and alerts are the exact copy from Global Constraints.

```kotlin
@Test fun `unauthorized user is answered with alert and use case is never called`()
@Test fun `undecodable data gets an empty non-alert answer and no use case call`()
@Test fun `success answers with the reply text and alert flag`()          // Right(BotReply("Download paused")) → text "Download paused", show_alert false
@Test fun `each interaction error maps to its copy`()                     // ExpiredCallback, UnknownAction
@Test fun `other domain errors answer Action failed`()                   // Left(DownloadClientError.ConnectionFailed("u"))
@Test fun `throwing use case still answers Action failed`()              // fake throws IllegalStateException → "⚠️ Action failed."
@Test fun `slow use case answers Working on it and still completes`() = runTest {
    // fake: delay(5_000); completed.set(true); Right(BotReply("done"))
    processor.process(query)                       // returns after the 2.5s virtual budget
    assertEquals("⏳ Working on it…", answers.single().text)
    advanceUntilIdle(); assertTrue(completed.get())
}
@Test fun `failed answer call is swallowed`()                            // MockEngine returns 400 "query is too old" → process returns normally
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*TelegramCallbackProcessorTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `process`.**
  - Order: guard → decode → `scope.async { useCase.handle(…) }` → `withTimeoutOrNull(answerBudget) { deferred.await() }` → map the outcome → `api.answerCallbackQuery`.
  - Wrap the await in `runCatching`, rethrowing `CancellationException`, so a throwing handler maps to `⚠️ Action failed.`
  - On timeout, attach `invokeOnCompletion` to the deferred so the late result is logged at INFO.
  - Log a `Left` from the answer call at WARN.

- [ ] **Step 4: Run the tests and confirm they pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(telegram): add callback processor with 2.5s answer budget (MWN-1)`

---

### Task 7: Long-polling service and registration retry

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/ExponentialBackoff.kt`
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramPollingService.kt`
- Test: `ExponentialBackoffTest.kt` and `TelegramPollingServiceTest.kt` under `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/`

**Interfaces:**
- Consumes: Task 5 `TelegramBotApi`; Task 6 `TelegramCallbackProcessor`
- Produces:
  - `class ExponentialBackoff(initial: Duration, max: Duration)`: `fun next(): Duration` (doubles each time, capped at `max`) and `fun reset()`
  - `suspend fun retryUntilSuccess(name: String, backoff: ExponentialBackoff, block: suspend () -> Either<DomainError.NotificationError, Unit>)`: a top-level function in `ExponentialBackoff.kt`. It loops until `Right`. On `RateLimited` it waits `retryAfterSeconds`, otherwise `backoff.next()`, and logs each failure at WARN.
  - `class TelegramPollingService(api: TelegramBotApi, processor: TelegramCallbackProcessor, pollTimeoutSeconds: Int)` with `fun start(scope: CoroutineScope): Job`. The job runs `retryUntilSuccess("deleteWebhook", ExponentialBackoff(1.seconds, 60.seconds)) { api.deleteWebhook() }`, then the poll loop.

- [ ] **Step 1: Write the failing tests.**
  - Run under `runTest`, with `start(backgroundScope)`.
  - A `MockEngine` serves a scripted queue of responses per path.
  - Record `testScheduler.currentTime` for each `getUpdates` call.

```kotlin
// ExponentialBackoffTest
@Test fun `doubles up to max and resets`()   // 1s,2s,4s,…,30s,30s; reset → 1s
// TelegramPollingServiceTest
@Test fun `deletes webhook before the first getUpdates`()
@Test fun `retries deleteWebhook with backoff until it succeeds`()     // 2 network failures → getUpdates starts at t=3s (1s + 2s)
@Test fun `advances offset past each update and processes callbacks`() // update_id 5 then 6 → next request offset 7; processor invoked twice (mockk coVerify)
@Test fun `409 backs off 30 seconds`()                                 // next getUpdates at +30_000 ms
@Test fun `429 waits retry_after`()                                    // retry_after 17 → +17_000 ms
@Test fun `401 backs off starting at 30 seconds`()                     // +30_000, then +60_000
@Test fun `network errors back off from 1s and reset after success`()  // fail, fail → +1s, +2s; success; fail → +1s
@Test fun `processor exception does not stop the loop`()               // processor mock throws; a later getUpdates still happens
@Test fun `cancelling the job stops polling promptly`()                // job.cancel(); no further requests after advanceUntilIdle
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*ExponentialBackoffTest' --tests '*TelegramPollingServiceTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement the backoff helper and the polling service.**
  - Each update gets its own `scope.launch { processor.process(it) }`, inside a `try`/`catch` that logs and swallows non-cancellation throwables.
  - The loop body catches everything except `CancellationException`.
  - Branch on `DeliveryFailed.httpStatus`: 409, 401/404, or anything else (network).
  - The 409 WARN text: `getUpdates returned 409: another instance is polling this bot, or a webhook is registered`.

- [ ] **Step 4: Run the tests and confirm they pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(telegram): add long-polling listener with backoff (MWN-1)`

---

### Task 8: Webhook route

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramCallbackController.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramWebhookRouteTest.kt`

**Interfaces:**
- Consumes: Task 4 DTOs; Task 6 processor; the existing `InboundRateLimiter`
- Produces:
  - `class TelegramCallbackController(webhookSecret: String, processor: TelegramCallbackProcessor, scope: CoroutineScope)` with `suspend fun handle(call: ApplicationCall)` and `companion const val WEBHOOK_PATH = "/api/v1/telegram/webhook"`
  - `fun Route.registerTelegramWebhookRoute(controller: TelegramCallbackController, rateLimiter: InboundRateLimiter)`

- [ ] **Step 1: Write the failing tests.**
  - Use `testApplication`, `routing { registerTelegramWebhookRoute(...) }`, and a `mockk<TelegramCallbackProcessor>(relaxed = true)`.
  - Create `scope` as `CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)` so the launched processing runs before the assertions.

```kotlin
@Test fun `missing secret header is 401 and processor is not called`()
@Test fun `wrong secret is 401`()
@Test fun `valid callback update is 200 and reaches the processor`()   // coVerify { processor.process(match { it.id == "cq1" }) }
@Test fun `update without callback_query is 200 and ignored`()         // {"update_id":1,"message":{...}} → coVerify(exactly = 0)
@Test fun `malformed body is 200`()                                    // "not json"
@Test fun `rate limit returns 429`()                                   // InboundRateLimiter(limitPerMinute = 1), two requests
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*TelegramWebhookRouteTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement the controller and route.**
  - Order: rate limiter (429 with the `Retry-After: 60` header, as in `withAuthAndRateLimit`), then the secret compare (401, no body).
  - Read the body with `call.receiveText()` and decode with the controller's own `Json { ignoreUnknownKeys = true }`. Wrap the decode in `runCatching`; on failure log at WARN.
  - `scope.launch { processor.process(query) }`, then `call.respond(HttpStatusCode.OK)`.

- [ ] **Step 4: Run the tests and confirm they pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(telegram): add secret-guarded webhook route for callback updates (MWN-1)`

---

### Task 9: Feature module, application wiring and feature docs

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramInteractionModule.kt`
- Modify: `src/main/kotlin/app/hononeko/notifier/Application.kt`:
  - `AppDependencies` gains `telegramInteractions: TelegramInteractionModule? = null`
  - `close()` calls `telegramInteractions?.close()` before `scope.cancel()`
  - `buildDependencies` gains a `telegramApiEngine` parameter
  - `module()` registers the route
- Create: `docs/TELEGRAM_INTERACTIONS.md`
- Modify: `README.md`: add a "Documentation & Deep Dives" entry linking `docs/TELEGRAM_INTERACTIONS.md`
- Test: `src/test/kotlin/app/hononeko/notifier/TelegramInteractionBootstrapTest.kt`

**Interfaces:**
- Consumes: everything above
- Produces `class TelegramInteractionModule` with:
  - `val controller: TelegramCallbackController?`: non-null only in webhook mode
  - `fun start(scope: CoroutineScope)`:
    - webhook: launch `retryUntilSuccess("setWebhook", ExponentialBackoff(1.seconds, 60.seconds)) { api.setWebhook(config.telegramInteraction.webhookEndpoint, secret) }`
    - polling: `pollingService.start(scope)`
  - `fun close()`: cancels the started jobs and closes `api`; never deletes the webhook
  - `companion fun create(config: AppConfig, stateStore: StateStorePort, scope: CoroutineScope, engine: HttpClientEngine? = null): TelegramInteractionModule`, which builds `BotInteractionService(handlers = emptyList(), CallbackRefRegistry(stateStore))`

- [ ] **Step 1: Write the failing tests.** Use `testApplication` with `buildDependencies(config, scope, telegramApiEngine = recordingMockEngine)`.

```kotlin
@Test fun `flag off - no route and no Telegram calls`() {
    // AppConfig() → POST /api/v1/telegram/webhook is 404; recorded requests is empty; dependencies.telegramInteractions == null
}
@Test fun `webhook mode - route answers and setWebhook is registered`() {
    // features=FeatureFlags(true), notifications(botToken="1:T", chatId="-1001"),
    // telegramInteraction(mode=WEBHOOK, allowedUserIds={42}, webhookUrl="https://n.example.com/", webhookSecret="s3cret")
    // POST without header → 401; with header "s3cret" and a callback update → 200
    // eventually a request to /bot1:T/setWebhook with "url":"https://n.example.com/api/v1/telegram/webhook" and "secret_token":"s3cret"
    // after dependencies.close(): no request to /bot1:T/deleteWebhook was ever made (webhook stays registered)
}
@Test fun `polling mode - no route, deleteWebhook then getUpdates`()
@Test fun `close stops polling`()                                       // after dependencies.close(), no new getUpdates requests arrive
```

- [ ] **Step 2: Run them and confirm they fail.** Run: `./gradlew test --tests '*TelegramInteractionBootstrapTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement the module and the wiring.** In `buildDependencies`:

```kotlin
val telegramInteractions =
    if (config.features.telegramInteractive) {
        TelegramInteractionModule.create(config, stateStore, scope, telegramApiEngine).also { it.start(scope) }
    } else {
        null
    }
```

In `module()`, after `configureWebhookRouting(...)`: `dependencies.telegramInteractions?.controller?.let { c -> routing { registerTelegramWebhookRoute(c, dependencies.rateLimiter) } }`.

- [ ] **Step 4: Write `docs/TELEGRAM_INTERACTIONS.md` and add the README link.** Sections, in order:
  1. What it does: inline-button clicks; actions arrive with MWN-3; off by default.
  2. A ⚠️ callout with the spec §7 table: polling with more than one replica is **not supported**; webhook mode needs Valkey/Redis **only** for multiple replicas.
  3. Enable it: the flag, the allowlist, and how to find your numeric ID (send any message to the bot and read `message.from.id` from `getUpdates`, or use a user-info bot).
  4. Choose a mode: polling, or webhook (an HTTPS public URL; Telegram only delivers to ports 443/80/88/8443; set the secret).
  5. Troubleshooting: a 409 in the logs; buttons answering "expired"; 401 on the webhook.
  6. A link to `CONFIGURATION.md#telegram-interactions`.

- [ ] **Step 5: Run the whole suite.** Run: `./gradlew ktlintCheck test check`. Expected: BUILD SUCCESSFUL, including `HexagonalArchitectureTest` and the existing `ApplicationBootstrapTest`.

- [ ] **Step 6: Commit.** `feat(telegram): wire callback listener behind TELEGRAM_INTERACTIVE_ENABLED (MWN-1)`

---

### Task 10: Release gates and manual verification

**Files:** none, unless a gate fails.

- [ ] **Step 1: Native image.** Run: `./gradlew nativeCompile`. Expected: BUILD SUCCESSFUL with no reflection or serialization errors for the new `@Serializable` types.
- [ ] **Step 2: Manual polling check against a test bot.**
  - Run the binary with `TELEGRAM_INTERACTIVE_ENABLED=true`, `TELEGRAM_ALLOWED_USERS=<your id>`, and `NOTIFICATION_URL=telegram://<token>@<chat>`.
  - Send a test button: `curl -s https://api.telegram.org/bot<token>/sendMessage -H 'Content-Type: application/json' -d '{"chat_id":"<chat>","text":"MWN-1 test","reply_markup":{"inline_keyboard":[[{"text":"ping","callback_data":"mwn1:ping:AAAAAAAAAA"}]]}}'`
  - Expected: your account sees the alert `This action isn't supported.`; a second, non-allowlisted account sees `⛔ You're not allowed to control this bot.`
- [ ] **Step 3: Manual webhook check.**
  - Restart with `TELEGRAM_UPDATES_MODE=webhook`, `TELEGRAM_WEBHOOK_URL=<public https base>` and `TELEGRAM_WEBHOOK_SECRET=<secret>`.
  - `getWebhookInfo` should show the URL; then repeat step 2.
  - Restart in polling mode and confirm `getWebhookInfo` shows an empty URL.
- [ ] **Step 4: Open the PR.**
  - Title: `feat(telegram): callback query listener and authorization guard (MWN-1)`, from branch `mwn-1-telegram-callback-listener`.
  - The body lists the spec's three deviations from the ticket (D1, D4, D7) and the manual check results.
  - After opening, check that the Kaneo task has an `externalLinks` entry.
