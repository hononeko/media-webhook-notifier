# MWN-3: Callback Buttons on Cards & Acknowledge (Design)

* **Ticket:** MWN-3, `feat(telegram): acknowledge button on actionable cards and the callback-button foundation`
* **Builds on:** MWN-1 (callback listener, allowlist guard, `mwn1:<op>:<ref>` protocol). **Unblocks:** snooze/mute and Seerr approvals (separate tickets)
* **Date:** 2026-10-13
* **Status:** Approved in brainstorming; pending written-spec review

---

## 1. Goal

Give the people in the Telegram chat a way to say "I've got this" on cards that need a human: health issues, imports that need manual interaction, stalled downloads and failed Seerr requests. Pressing **✅ Acknowledge** stamps the card with who handled it and when, and removes the button, so everyone else in the chat can see the card has been dealt with.

Building Acknowledge also lays the foundation the next two features reuse:
- callback buttons declared on any card
- a ref that remembers the card
- handlers that edit the card they were pressed on

## 2. Background: why not download-client controls

MWN-2 and the old MWN-3 scope planned pause, resume, recheck and delete buttons on live progress cards. They were dropped:
- Pause, resume and recheck are easier in the qBittorrent UI.
- Deleting is the *arr apps' job.

The three button features worth building are, in order:
1. Acknowledge (this spec)
2. Snooze/mute noise
3. Seerr approve/decline, restricted to admins

## 3. Decisions

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Where buttons are decided | The domain declares them: `NotificationCard.interactions`, filled by the `CardFormatterService` builders | Which cards get which buttons is a product rule. The Telegram adapter only turns them into `callback_data`. |
| D2 | How a handler knows the card | The ref stores a serializable snapshot of the card plus its interactions. The click carries the message it came from (`origin`). | Handlers re-render the card from domain data instead of reconstructing HTML from Telegram's message entities. Snooze and Seerr reuse the same path. |
| D3 | What Acknowledge does | Edit the card in place: append `✅ Handled by <name> · HH:mm`, remove the callback buttons, keep URL buttons | Visible to everyone, keeps history. No state beyond the ref. |
| D4 | Which cards | Health **issue** (not restored), manual interaction, stalled (all three tracker halt paths), Seerr `REQUEST_FAILED` | User choice. |
| D5 | Ref lifetime | 30 days, deleted after a successful acknowledge | A health card may be acknowledged days later. Deleting the ref makes a second press answer "expired" instead of re-stamping. |
| D6 | Stamp time zone | Process default (`TZ`, UTC when unset) via `kotlinx-datetime` | The usual container convention. No new variable. |
| D7 | Admin-only actions | Not in this ticket | Nothing here needs it. Seerr approvals add `TELEGRAM_ADMIN_USERS`, `BotActor.isAdmin` and `BotActionHandler.requiresAdmin`, checked by `BotInteractionService` before the ref is resolved. |
| D8 | Flag | Reuses `TELEGRAM_INTERACTIVE_ENABLED` | With the flag off, cards are sent exactly as today and the state store is never touched. |

## 4. Non-goals

* Download-client controls (pause, resume, recheck, delete).
* Snooze, mute, Seerr approvals, admin roles: follow-up tickets.
* Buttons on live progress updates (`ProgressUpdate`). Only `NotificationCard` carries interactions.
* "Un-acknowledge". The card is history once stamped.
* Customizing button labels in `templates.yaml`.

## 5. Architecture

### 5.1 Domain

* **`CardInteraction(label: String, operation: String, params: Map<String, String> = emptyMap())`** in `domain/model`.
  * `NotificationCard` gains `interactions: List<CardInteraction> = emptyList()` and `footnote: String? = null`. The footnote is a plain-text line that publishers render last.
  * `equals`/`hashCode` are updated to include both fields.
* **Serializable cards.**
  * `NotificationCard`, `CardField`, `MediaSpecs`, `ActionLink`, `ActionStyle`, `NotificationLevel` and `CardInteraction` become `@Serializable`.
  * `artworkBytes` is `@Transient`. An edited photo card keeps its photo, because only the caption changes.
* **`CallbackTarget(card: NotificationCard)`** replaces `CallbackTarget(downloadId)`. Each interaction's `params` travel inside the card, so the handler finds its own by `operation`. No release ever wrote the old shape, so there is no stored data to migrate.
* **`CallbackRefRegistry.release(ref)`** deletes `cb:<ref>`. Invalid refs are a no-op.
* **`BotInteraction`** gains `origin: NotificationHandle? = null`.
* **`BotActionHandler.handle(interaction: BotInteraction, target: CallbackTarget)`** replaces `handle(actor, target)`, so handlers see `origin` and `ref`. `BotInteractionService` is otherwise unchanged.
* **`NotificationPublisherPort.editCard(handle, card): Either<NotificationError, Unit>`**. The default returns `NotificationError.DeliveryFailed("editCard is not supported by <providerId>")`.
* **`AcknowledgeHandler(publisher, registry, clock, timeZone)`**, operation `ack`:
  1. If `origin` is null, return `NotificationError.DeliveryFailed("no message to acknowledge")`.
  2. Build `target.card.copy(interactions = emptyList(), footnote = "✅ Handled by ${actor.displayName} · HH:mm")`.
  3. Call `publisher.editCard(origin, stamped)`. On `Left`, return the error and keep the ref.
  4. On `Right`, call `registry.release(ref)` and return `BotReply("✅ Acknowledged")`.
* **Builders** add `CardInteraction("✅ Acknowledge", "ack")` after template rendering, so a custom template can't drop it:
  * `buildHealthCard`, only when the card isn't a restored card
  * `buildManualInteractionCard`
  * `buildStalledCard`
  * `buildSeerrCard`, only when `eventType == REQUEST_FAILED`
* **Wiring.** `TelegramInteractionModule.create` registers `AcknowledgeHandler`, so `BotInteractionService` is no longer built with an empty list.

### 5.2 Telegram adapter

* **Publisher.** `TelegramPublisherAdapter` takes an optional `callbackRefs: CallbackRefRegistry?`. `Application.kt` passes one only when the flag is on.
  * When `callbackRefs` is non-null and the card has interactions, every method that sends a `NotificationCard` (`sendCard`, `startLiveProgress`, `completeProgress`, `cancelProgress`) registers `CallbackTarget(card without artworkBytes)` with a TTL of 30 days (2 592 000 s).
  * The keyboard gets the existing URL buttons in the first row and one row of callback buttons, each with `TelegramCallbackData.encode(op, ref)`.
  * If registration returns `null` or throws, the card is sent without the callback row and a WARN is logged.
  * When `callbackRefs` is null, `interactions` is ignored and the request body is identical to today's.
* **`editCard(handle, card)`.**
  * Uses `editMessageCaption` when `handle.isPhoto`, otherwise `editMessageText`. Both use the same HTML rendering as `sendCard`, including the footnote.
  * `reply_markup` is rebuilt from the card's URL actions. Callback buttons are added only if the card still has interactions.
  * A 400 whose description contains `message is not modified` maps to `Right(Unit)`. Any other failure maps as in the existing edit paths.
* **Footnote rendering.** The footnote is the last line, HTML-escaped. When truncating to the caption (1024) or text (4096) limit, the body is cut first so the footnote is always intact.
* **Callback origin.**
  * `TelegramMessage` gains `photo: List<JsonElement>? = null`. Only its presence matters.
  * `TelegramCallbackProcessor` passes `origin = NotificationHandle("telegram", message.chat.id.toString(), message.messageId.toString(), isPhoto = message.photo != null)`, or `null` when the callback has no message.
  * `providerId` matches the publisher's (`"telegram"`).

## 6. Behaviour

| Situation | Card | Popup |
|---|---|---|
| First press | Stamped, Acknowledge gone, URL buttons kept | "✅ Acknowledged" |
| Second press after success | Unchanged | "⌛ This button has expired." (ref released) |
| Two presses race | Last edit wins, or Telegram says "not modified" (treated as success) | "✅ Acknowledged" for both |
| Restart with in-memory store, or ref older than 30 days | Unchanged | "⌛ This button has expired." |
| Card deleted from chat | n/a | "⚠️ Action failed." (alert), WARN logged |
| Flag off | No Acknowledge button rendered | n/a |

The stamp uses the actor's display name from MWN-1: `@username` if the user has one, otherwise their first name.

## 7. Configuration & documentation

* No new variables. `TZ` controls the stamp time (UTC by default).
* `docs/TELEGRAM_INTERACTIONS.md` changes:
  * The intro no longer says "cards don't show action buttons yet".
  * A new **Buttons** section covers which cards carry Acknowledge, what pressing it does, the 30-day expiry, and that the in-memory store expires buttons on restart (use Valkey/Redis to keep them).
  * It notes that `TZ` sets the time in the stamp.
* `docs/CONFIGURATION.md`: one line under Telegram interactions pointing to the Buttons section and `TZ`.

## 8. Testing

* **`AcknowledgeHandlerTest`**:
  * stamp text with a fixed clock and zone
  * interactions cleared while URL actions are kept
  * `release` called on success and not on failure
  * null `origin` returns `Left`
* **`CallbackRefRegistryTest`**:
  * `release` makes `resolve` return null
  * an invalid ref is a no-op
* **`NotificationCardSerializationTest`**:
  * a round trip preserves every field except `artworkBytes`
  * `interactions` and `footnote` survive
* **`CardFormatterServiceTest`** additions:
  * Acknowledge is present on health issue, manual interaction, stalled and Seerr `REQUEST_FAILED` cards
  * Acknowledge is absent on health restored, grab, import and Seerr approved cards
  * a template-rendered health card still carries it
* **`TelegramPublisherAdapterTest`** additions (MockEngine):
  * with the flag off, `sendCard` sends no callback row and the registry is never called
  * with the flag on, there's a callback row with `mwn1:ack:<ref>` and the ref resolves to the card
  * when registration fails, the card is sent without the row
  * `editCard` uses caption versus text
  * "message is not modified" returns `Right`
  * the footnote survives truncation at 1024
* **`TelegramCallbackProcessorTest`** additions:
  * `origin` is mapped from text and photo messages
  * `origin` is null when there's no message
* **End to end** (bootstrap test with MockEngine): a health-issue webhook produces a card with Acknowledge, a simulated callback is processed, and the result is an `editMessageText` with the stamp and no callback row.

## 9. Risks

* **Native image time zones.** `TimeZone.currentSystemDefault()` needs the zone rules compiled into the native image. Check in the `main.yml` native build that the stamp works with `TZ` set to a non-UTC zone. If it doesn't, add `-H:+IncludeAllTimeZones` to `buildArgs`.
* **Snapshot size.** Cards are a few KB at most, which is fine for the in-memory store and Valkey alike.

## 10. Follow-ups (separate tickets)

* **Snooze/mute.** Snooze a health check (instance plus check type) for 24h; mute a series or movie. Both need a suppression check before sending, and state entries that expire.
* **Seerr approvals.**
  * A new Seerr outbound adapter (`SEERR_URL`, `SEERR_API_KEY`).
  * Approve/Decline on `REQUEST_PENDING` and Resolve on `ISSUE_CREATED`.
  * The admin-only rule from D7.
