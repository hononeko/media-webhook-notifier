# MWN-3 Acknowledge Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a **✅ Acknowledge** button to health-issue, manual-interaction, stalled and failed-Seerr-request cards. Pressing it stamps the card with who handled it and when, and removes the button. Along the way, build the callback-button foundation that snooze/mute (MWN-14) and Seerr approvals (MWN-15) reuse.

**Architecture:**
- **Domain:** cards declare `CardInteraction`s. The Telegram publisher stores a snapshot of the card under a callback ref and renders the buttons as a second keyboard row.
- **Click path:** MWN-1's processor passes the clicked message as `BotInteraction.origin`. `AcknowledgeHandler` re-renders the snapshot with a footnote and edits the message through a new `NotificationPublisherPort.editCard`.
- **Flag:** everything stays behind `TELEGRAM_INTERACTIVE_ENABLED`.

**Tech Stack:** Kotlin 2.4, Ktor 3.6 client (MockEngine in tests), Arrow `Either`, kotlinx.serialization, kotlinx-datetime, mockk.

**Spec:** `docs/superpowers/specs/2026-10-13-telegram-acknowledge-button-design.md`

## Global Constraints

- **Domain imports:** `app.hononeko.notifier.domain.*` must not import `io.ktor` or `app.hononeko.notifier.adapter` (`HexagonalArchitectureTest`). `kotlinx-datetime` and `kotlinx.serialization` are allowed.
- **Exact copy:**
  - button label `✅ Acknowledge`
  - operation `ack`
  - footnote `✅ Handled by <displayName> · HH:mm` (24-hour, zero-padded)
  - popup `✅ Acknowledged`
- **Ref TTL:** `2_592_000` seconds (30 days).
- **Flag off:** Telegram request bodies are byte-identical to today's, and the state store is never touched.
- **Keyboard layout:** URL buttons in row 1, callback buttons in row 2. A keyboard with neither is omitted.
- **Before every commit:** `./gradlew ktlintCheck test` must pass. ktlint's max line length is 120.
- **Don't run `./gradlew nativeCompile` locally.** `main.yml` builds native on main.
- **Commits:** Conventional Commits with ` (MWN-3)` in the subject, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Health payload with `eventType = HEALTH_ISSUE` but `level = "ok"` or `"restored"`.** The builder treats these as restored, so they must not get Acknowledge. Pinned in Task 3.
2. **Display name with HTML characters** (`<`, `&`). The footnote must be escaped, or Telegram rejects the edit with "can't parse entities". Pinned in Task 4.
3. **Photo card acknowledged.** The edit is `editMessageCaption`, never a new photo. The caption stays ≤1024 characters and the footnote stays intact. Pinned in Task 4.
4. **Live card halted as stalled.** `cancelProgress` must render the callback row and register the ref, just like `sendCard`. Pinned in Task 4.
5. **Second press after a successful acknowledge.** It answers "⌛ This button has expired." and makes no second edit. Pinned in Task 5.

---

### Task 1: Serializable cards, `CardInteraction` and the new `CallbackTarget`

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/domain/model/CardInteraction.kt`
- Modify:
  - `domain/model/NotificationCard.kt`
  - `domain/model/ActionLink.kt`
  - `domain/model/BotInteraction.kt`
  - `domain/service/CallbackRefRegistry.kt`
- Test:
  - Create `src/test/kotlin/app/hononeko/notifier/domain/model/NotificationCardSerializationTest.kt`
  - Modify `domain/service/CallbackRefRegistryTest.kt` and `domain/service/BotInteractionServiceTest.kt` (fixtures)

**Interfaces:**
- Produces:
  - `@Serializable data class CardInteraction(val label: String, val operation: String, val params: Map<String, String> = emptyMap())`
  - `NotificationCard.interactions: List<CardInteraction> = emptyList()` and `NotificationCard.footnote: String? = null`. Both are included in `equals`/`hashCode`.
  - `NotificationCard`, `CardField`, `MediaSpecs`, `NotificationLevel`, `ActionLink` and `ActionStyle` are `@Serializable`, with `artworkBytes` marked `@Transient`.
  - `@Serializable data class CallbackTarget(val card: NotificationCard)`, replacing `downloadId`.
  - `BotInteraction(actor, operation, ref, origin: NotificationHandle? = null)`.
  - `suspend fun CallbackRefRegistry.release(ref: String)`. It deletes `cb:<ref>`, and malformed refs are a no-op without touching the store.

- [ ] **Step 1: Write the failing tests**

`NotificationCardSerializationTest`:
- `round trip keeps every field except artworkBytes`:
  - Encode, then decode, a card with every field set, including `interactions = listOf(CardInteraction("✅ Acknowledge", "ack", mapOf("k" to "v")))`, `footnote = "x"` and `artworkBytes = byteArrayOf(1)`.
  - Assert `decoded == original.copy(artworkBytes = null)`.
- `card without interactions or footnote decodes with defaults`:
  - Decode `{"title":"T"}` and assert `interactions.isEmpty()` and `footnote == null`.

`CallbackRefRegistryTest`:
- Change fixtures to `CallbackTarget(NotificationCard(title = "a"))`.
- Add `release makes resolve return null`: register, release, then assert `resolve(ref) == null`.
- Add `release ignores malformed refs without touching the store`: use a spy and assert `coVerify(exactly = 0) { spy.delete(any()) }` for `release("../x")`.

`BotInteractionServiceTest`: switch fixtures to the new `CallbackTarget`. Handler contract changes come in Task 2.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*NotificationCardSerializationTest' --tests '*CallbackRefRegistryTest'`
  - Expected: compilation failure (`CardInteraction`, `footnote`, `release` unresolved).
- [ ] **Step 3: Implement the model changes and `release`.** Also update `CallbackRefRegistry`'s KDoc, which still talks about download ids, to say refs point to cards.
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS. The full suite is green, with only fixture updates in other tests.
- [ ] **Step 5: Commit** with the message `feat(domain): serializable cards with callback interactions and footnote (MWN-3)`.

### Task 2: Handler contract, `editCard` port and `AcknowledgeHandler`

**Files:**
- Create: `src/main/kotlin/app/hononeko/notifier/domain/service/AcknowledgeHandler.kt`
- Modify:
  - `domain/service/BotActionHandler.kt`
  - `domain/service/BotInteractionService.kt`
  - `domain/port/outbound/NotificationPublisherPort.kt`
- Test:
  - Create `src/test/kotlin/app/hononeko/notifier/domain/service/AcknowledgeHandlerTest.kt`
  - Modify `BotInteractionServiceTest.kt`

**Interfaces:**
- Consumes (Task 1): `CallbackTarget(card)`, `BotInteraction.origin`, `CallbackRefRegistry.release`, `NotificationCard.footnote` and `NotificationCard.interactions`.
- Produces:
  - `BotActionHandler.handle(interaction: BotInteraction, target: CallbackTarget): Either<DomainError, BotReply>`
  - `NotificationPublisherPort.editCard(handle: NotificationHandle, card: NotificationCard): Either<DomainError.NotificationError, Unit>`. The default returns `Left(DeliveryFailed(providerId, "editCard is not supported by $providerId"))`.
  - `class AcknowledgeHandler(publisher: NotificationPublisherPort, refRegistry: CallbackRefRegistry, clock: () -> Long = System::currentTimeMillis, timeZone: TimeZone = TimeZone.currentSystemDefault()) : BotActionHandler`
    - `operation = AcknowledgeHandler.OPERATION`, where `OPERATION = "ack"`
    - `companion const val REPLY = "✅ Acknowledged"`

- [ ] **Step 1: Write the failing tests.**

  `AcknowledgeHandlerTest` uses a mockk `NotificationPublisherPort` with `providerId = "telegram"`, a real `CallbackRefRegistry(InMemoryStateStore())`, `clock` fixed at `1_760_000_000_000` and `timeZone = TimeZone.of("Europe/Bucharest")`:
  - **`stamps the card, drops interactions and keeps URL actions`:**
    - The target card has `actions = listOf(ActionLink("📖 Wiki", "https://w"))` and `interactions = listOf(CardInteraction("✅ Acknowledge", "ack"))`.
    - Actor `displayName = "@alex"` and `origin = NotificationHandle("telegram", "-1001", "5")`.
    - Assert that `editCard` is called with `card.copy(interactions = emptyList(), footnote = "✅ Handled by @alex · 11:53")`.
    - Assert the result is `Right(BotReply("✅ Acknowledged"))`.
  - **`releases the ref after a successful edit`:** register the card, handle it, then assert `refRegistry.resolve(ref) == null`.
  - **`keeps the ref and returns the error when the edit fails`:** `editCard` returns `Left(DeliveryFailed("telegram", "boom"))`. Assert the result is that `Left` and that `resolve(ref) != null`.
  - **`returns an error without editing when origin is null`:** assert `Left(DeliveryFailed("telegram", "no message to acknowledge"))`, and `coVerify(exactly = 0) { publisher.editCard(any(), any()) }`.

  `BotInteractionServiceTest`: change the handler fakes to the new signature. Add `handler receives the interaction including origin`, which asserts that the `BotInteraction` passed to the handler equals the one given to the service.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*AcknowledgeHandlerTest' --tests '*BotInteractionServiceTest'`
  - Expected: compilation failure.
- [ ] **Step 3: Implement the contract change, the port default and `AcknowledgeHandler`.**
  - The time is `Instant.fromEpochMilliseconds(clock()).toLocalDateTime(timeZone)`, formatted as zero-padded `HH:mm`.
  - Release the ref only after `editCard` returns `Right`.
  - If the compiler asks for `@OptIn(ExperimentalTime::class)` on `kotlin.time.Instant`, add it.
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(domain): acknowledge handler and editCard port (MWN-3)`.

### Task 3: Acknowledge on the four card types

**Files:**
- Modify: `src/main/kotlin/app/hononeko/notifier/domain/service/CardFormatterService.kt`. The builders are `buildStalledCard` (467), `buildHealthCard` (990), `buildManualInteractionCard` (1068) and `buildSeerrCard` (1159).
- Test: `src/test/kotlin/app/hononeko/notifier/domain/service/CardFormatterServiceTest.kt`

**Interfaces:**
- Consumes: `CardInteraction`, and `AcknowledgeHandler.OPERATION` (Task 2).
- Produces: `CardFormatterService.ACKNOWLEDGE = CardInteraction("✅ Acknowledge", AcknowledgeHandler.OPERATION)`.

- [ ] **Step 1: Write the failing tests** in `CardFormatterServiceTest`:
  - **`acknowledge is offered on cards that need a human`:** assert `card.interactions == listOf(CardFormatterService.ACKNOWLEDGE)` for each of:
    - a health card with `eventType = HEALTH_ISSUE, level = "warning"`
    - a manual-interaction card
    - `buildStalledCard(grab, null, null)`
    - a Seerr card with `eventType = REQUEST_FAILED`
  - **`acknowledge is not offered on informational cards`:** assert `interactions.isEmpty()` for:
    - a health card with `eventType = HEALTH_RESTORED`
    - a health card with `eventType = HEALTH_ISSUE, level = "ok"` (Review Focus 1)
    - `buildGrabInitialCard`
    - `buildImportCard`
    - a Seerr card with `REQUEST_APPROVED`
  - **`custom templates keep the acknowledge button`:** using the custom engine from the existing `should render custom templates…` test, the health, manual-interaction and stalled cards all still carry `ACKNOWLEDGE`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*CardFormatterServiceTest'`
  - Expected: FAIL. `ACKNOWLEDGE` is unresolved, then the assertions fail.
- [ ] **Step 3: Implement.**
  - Each of the four builders has two `return NotificationCard(...)` paths (template and default).
  - Move each builder's body into a private `render…` function with the same parameters.
  - The public builder returns `render…(…).withAcknowledgeIf(condition)`, where the extension appends `ACKNOWLEDGE` to `interactions` when the condition holds.
  - The conditions:
    - health: `!isRestored`, using the builder's existing `isRestored` expression, extracted to a private function so both use it
    - manual interaction and stalled: always
    - Seerr: `payload.eventType == EventType.REQUEST_FAILED`
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS. Existing service tests that compare whole cards may need `interactions` added to their expected values. Update those expectations only.
- [ ] **Step 5: Commit** with the message `feat(cards): offer Acknowledge on health, manual-interaction, stalled and failed-request cards (MWN-3)`.

### Task 4: Telegram publisher renders callback rows, footnotes and `editCard`

**Files:**
- Modify:
  - `src/main/kotlin/app/hononeko/notifier/adapter/outbound/telegram/TelegramPublisherAdapter.kt`
  - `adapter/outbound/telegram/TelegramHtmlFormatter.kt`
- Test: `src/test/kotlin/app/hononeko/notifier/adapter/outbound/telegram/TelegramPublisherAdapterTest.kt` and `TelegramHtmlFormatterTest.kt`

**Interfaces:**
- Consumes: `CallbackRefRegistry.register`, `CallbackTarget(card)`, `NotificationCard.interactions` and `NotificationCard.footnote`, plus `TelegramCallbackData.encode(op, ref)` from MWN-1.
- Produces:
  - `TelegramPublisherAdapter(config: NotificationConfig, engine: HttpClientEngine? = null, callbackRefs: CallbackRefRegistry? = null)`, with `companion const val CALLBACK_TTL_SECONDS = 2_592_000L`.
  - `override suspend fun editCard(handle, card)`.
  - `TelegramHtmlFormatter.buildCardHtml(card: NotificationCard, maxChars: Int): String`.

- [ ] **Step 1: Write the failing tests.**

  `TelegramHtmlFormatterTest`:
  - **`footnote is the escaped last line`:** `buildCardHtml(card.copy(footnote = "✅ Handled by A<b>&c · 09:05"), 4096)` ends with `"\n\n✅ Handled by A&lt;b&gt;&amp;c · 09:05"`. This is Review Focus 2.
  - **`body is truncated before the footnote`:** a card whose body is 2000 characters long, with a footnote, rendered at 1024:
    - the result has length ≤ 1024
    - it ends with the escaped footnote
    - it contains `"..."`
  - **`without a footnote it matches the old truncation`:** `buildCardHtml(card, 1024) == truncateHtml(buildCardHtml(card), 1024)`.

  `TelegramPublisherAdapterTest` uses a MockEngine that records the path and body of each request. `sendMessage`, `sendPhoto`, `editMessageText` and `editMessageCaption` return `{"ok":true,"result":{"message_id":7}}`.
  - **`interactions are ignored without a callback registry`:**
    - `sendCard(card with interactions)` with `callbackRefs = null`.
    - The body has no `callback_data`.
    - The body equals what the same card sends with `interactions = emptyList()`.
  - **`callback row is rendered below URL buttons and resolves to the card`:**
    - `callbackRefs = CallbackRefRegistry(InMemoryStateStore())`, and the card has one URL action plus `ACKNOWLEDGE`.
    - `inline_keyboard[0][0].url` is set.
    - `inline_keyboard[1][0].callback_data` matches `^mwn1:ack:[A-Za-z0-9]{10}$`.
    - Resolving that ref returns `CallbackTarget(card)`.
  - **`card is sent without the callback row when registration fails`:**
    - A spy store whose `tryAcquire` throws `IllegalStateException`.
    - The body has the URL row and no `callback_data`.
    - The result is `Right`.
  - **`cancelProgress renders the callback row`:** `cancelProgress(NotificationHandle("telegram", "-1001", "5"), stalled card with ACKNOWLEDGE)`, with the registry, sends `editMessageText` whose body contains `mwn1:ack:`. This is Review Focus 4.
  - **`editCard uses editMessageCaption for photo messages`:**
    - `editCard(handle.copy(isPhoto = true), card with footnote and artworkUrl)`.
    - The path ends with `/editMessageCaption`.
    - The caption length is ≤ 1024 and ends with the footnote.
    - No `sendPhoto` call is made.
    - This is Review Focus 3.
  - **`editCard uses editMessageText for text messages and keeps URL buttons`:** the path ends with `/editMessageText`, there's one keyboard row with the URL button, and there's no `callback_data`.
  - **`editCard treats message is not modified as success`:** a 400 response with `{"ok":false,"description":"Bad Request: message is not modified"}` returns `Right(Unit)`.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*TelegramHtmlFormatterTest' --tests '*TelegramPublisherAdapterTest'`
  - Expected: compilation failure (`callbackRefs`, `editCard`, `buildCardHtml(card, maxChars)`).
- [ ] **Step 3: Implement.**
  - **`buildCardHtml(card, maxChars)`:** `suffix = "\n\n" + escapeHtml(footnote)` when the footnote is set, then `truncateHtml(buildCardHtml(card), maxChars - suffix.length) + suffix`. The one-argument `buildCardHtml(card)` ignores the footnote.
  - **`deliverCard`:** render the caption with `buildCardHtml(card, 1024)` and the text with `buildCardHtml(card, 4096)`.
  - **`InlineKeyboardButton`:** gains `@SerialName("callback_data") val callbackData: String? = null`. `explicitNulls = false` keeps flag-off bodies unchanged.
  - **`keyboardFor(card: NotificationCard): InlineKeyboardMarkup?`** (private, suspend):
    - the URL row comes from `card.actions`, as today
    - when `callbackRefs != null` and `card.interactions` isn't empty, register `CallbackTarget(card.copy(artworkBytes = null))` with `CALLBACK_TTL_SECONDS` and build the callback row
    - if registration returns null or throws a non-cancellation exception, log a WARN and skip the row
  - **Call it outside `sendMutex`,** so the state store is never called while the mutex is held.
  - **Shared edit path:** extract private `editWithCard(handle, card)` from the duplicated bodies of `completeProgress`/`cancelProgress`. It builds its markup with `keyboardFor(card)` and its HTML with `buildCardHtml(card, 1024 | 4096)`, so every method that sends a `NotificationCard` renders interactions and footnotes the same way. `completeProgress` and `cancelProgress` keep their `photoMessageRegistry.remove`, and `editCard` calls `editWithCard` without it.
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run: same command, then `./gradlew ktlintCheck test`
  - Expected: PASS. All 20 existing publisher tests still pass unchanged.
- [ ] **Step 5: Commit** with the message `feat(telegram): render callback buttons and footnotes, add editCard (MWN-3)`.

### Task 5: Click origin, wiring, end-to-end flow and docs

**Files:**
- Modify:
  - `src/main/kotlin/app/hononeko/notifier/adapter/inbound/telegram/TelegramUpdateDtos.kt`
  - `adapter/inbound/telegram/TelegramCallbackProcessor.kt`
  - `adapter/inbound/telegram/TelegramInteractionModule.kt`
  - `Application.kt` (lines 119-136 and 250-255)
  - `docs/TELEGRAM_INTERACTIONS.md`
  - `docs/CONFIGURATION.md`
- Test:
  - Modify `adapter/inbound/telegram/TelegramCallbackProcessorTest.kt` and `TelegramInteractionBootstrapTest.kt`
  - Create `src/test/kotlin/app/hononeko/notifier/adapter/inbound/telegram/AcknowledgeFlowTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `TelegramMessage.photo: List<JsonElement>? = null`
  - `TelegramInteractionModule.create(config: AppConfig, refRegistry: CallbackRefRegistry, publisher: NotificationPublisherPort, scope: CoroutineScope, engine: HttpClientEngine? = null)`, which registers `AcknowledgeHandler(publisher, refRegistry)`
  - In `buildDependencies`:
    - the state store is built before the publisher
    - one `CallbackRefRegistry(stateStore)` is created when `config.features.telegramInteractive`, otherwise null
    - it goes to `TelegramPublisherAdapter(config.notifications, telegramApiEngine, callbackRefs)` and to `TelegramInteractionModule.create`

- [ ] **Step 1: Write the failing tests.**

  `TelegramCallbackProcessorTest`:
  - **`origin is the clicked text message`:** a query with `message_id = 5`, chat `-1001` and no photo gives the use case `origin == NotificationHandle("telegram", "-1001", "5", isPhoto = false)`.
  - **`origin marks photo messages`:** a message with `photo = [ {…} ]` gives `isPhoto == true`.
  - **`origin is null when the callback has no message`.**

  `AcknowledgeFlowTest` is a component end-to-end test with one MockEngine shared by the `TelegramPublisherAdapter` and the `TelegramBotApi`:
  - **`health issue card can be acknowledged once`:**
    1. Build `registry = CallbackRefRegistry(InMemoryStateStore())`, a publisher with that registry, and `SystemHealthService(publisher)`.
    2. Build a `TelegramCallbackProcessor` with `TelegramCallbackGuard(setOf(42L), "-1001")`, `BotInteractionService(listOf(AcknowledgeHandler(publisher, registry, clock, TimeZone.UTC)), registry)`, the api and the test scope.
    3. Call `announce(health issue payload)`, then read `callback_data` from the `sendMessage` body.
    4. Call `process(query(id = "cq1", from 42 with username "alex", message 7 in -1001, data = that callback_data))`.
    5. Assert there's one `editMessageText` whose `text` ends with `✅ Handled by @alex · <HH:mm from clock in UTC>`, with no `callback_data` in its body.
    6. Assert `answerCallbackQuery` was called with `text == "✅ Acknowledged"`.
    7. Process the same query again with id `cq2`. Assert the answer is `"⌛ This button has expired."` and there's still exactly one `editMessageText` (Review Focus 5).

  `TelegramInteractionBootstrapTest`:
  - **`flag on - publisher renders the acknowledge row`:**
    - `buildDependencies(config(WEBHOOK), …, telegramApiEngine = engine)`.
    - `dependencies.notificationPublisher.sendCard(NotificationCard(title = "t", interactions = listOf(CardFormatterService.ACKNOWLEDGE)))`.
    - The `sendMessage` body contains `mwn1:ack:`.
    - Make the mock answer `sendMessage` with `{"message_id":5}`.
  - **`flag off - publisher ignores interactions`:** the same call with `AppConfig(notifications = NotificationConfig(botToken = "1:T", chatId = "-1001"))` produces no `callback_data`.
  - **`flag on - ack with an unknown ref answers expired`:** post the webhook update with `data = "mwn1:ack:AAAAAAAAAA"`. The answer is `"⌛ This button has expired."`, which proves the handler is registered. The existing `ping` assertion ("This action isn't supported.") stays.

- [ ] **Step 2: Run the tests and confirm they fail.**
  - Run: `./gradlew test --tests '*TelegramCallbackProcessorTest' --tests '*AcknowledgeFlowTest' --tests '*TelegramInteractionBootstrapTest'`
  - Expected: compilation failure (`photo`, the `create` signature), then the assertions fail.
- [ ] **Step 3: Implement the DTO field, the processor `origin`, the module `create` signature and the `buildDependencies` wiring.**
  - Remove the MWN-1 comment "MWN-3 registers the pause/resume/... handlers here".
  - Update any other `TelegramInteractionModule.create` callers that `grep` finds.
- [ ] **Step 4: Update the docs.**
  - **`docs/TELEGRAM_INTERACTIONS.md`:**
    - Replace the intro sentence "Cards don't show action buttons yet; …" with one saying that cards needing attention carry an Acknowledge button.
    - Add a `## Buttons` section before `## Troubleshooting`. It covers:
      - a table of cards with Acknowledge: health issue, manual interaction required, stalled download, Seerr request failed
      - what pressing it does (the stamp, the button removed, links kept)
      - that buttons expire after 30 days, after one use, and on restart with the in-memory store (use Valkey/Redis to keep them)
      - that the stamp time follows `TZ`, which is UTC when unset
    - Add a troubleshooting row: "Acknowledge answers ⚠️ Action failed. → the card was deleted, or the bot can no longer edit it".
  - **`docs/CONFIGURATION.md`:** under Telegram interactions, add one line linking to `TELEGRAM_INTERACTIONS.md#buttons` and mentioning `TZ`.
- [ ] **Step 5: Run the full checks.**
  - Run: `./gradlew ktlintCheck test`
  - Expected: PASS, with 0 failures. `HexagonalArchitectureTest` is green.
- [ ] **Step 6: Commit** with the message `feat(telegram): wire acknowledge button end to end (MWN-3)`.

## After merge

- **Time zones in the native image:** check that the native image from `main.yml` stamps the right local time with `TZ` set to a non-UTC zone (for example `Europe/Bucharest`). If it doesn't, add `-H:+IncludeAllTimeZones` to `buildArgs` in `build.gradle.kts` (spec §9).
- **Real-bot check:** trigger a health warning (or `POST` a Sonarr `Health` test payload), press Acknowledge in the group, and confirm the stamp shows your `@username` and the local time.
