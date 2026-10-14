# Telegram Interactions

Telegram interactions let the notifier react to **inline-button clicks** on its Telegram cards. Each click is checked against an allowlist of Telegram users and answered within Telegram's 3-second window, then handed to the matching action.

Cards that need a person's attention carry an **✅ Acknowledge** button, and Seerr requests and issues can go to admins privately with **Approve**, **Decline** and **Resolve** buttons (see [Buttons](#buttons)). The feature is **off by default**: with `TELEGRAM_INTERACTIVE_ENABLED` unset, cards look exactly as before and the service never listens for clicks.

> [!WARNING]
> **Replica rules.** The service can't detect how many replicas you run, so these rules aren't enforced. Follow them yourself:
>
> | Mode | Replicas | State store | Supported |
> |---|---|---|---|
> | `polling` | 1 | any | ✅ |
> | `polling` | more than 1 | any | ❌ **Not supported.** Replicas fight over `getUpdates` (HTTP 409). Enable the flag on exactly one replica. |
> | `webhook` | 1 | memory or Valkey / Redis | ✅ |
> | `webhook` | more than 1 | **Valkey / Redis required** | ✅ Button references must be shared. With the in-memory store, a click that reaches another replica answers "button expired". |

## Enable it

1. Make sure notifications already go to Telegram (`NOTIFICATION_URL=telegram://<bot_token>@<chat_id>`, or `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID`).
2. Find the **numeric user ID** of everyone who should be able to press buttons. Usernames aren't accepted, because they can be changed or claimed by someone else. To find an ID:
   - ask a user-info bot such as `@userinfobot`. This works at any time, including when adding someone later, or
   - **only if the feature has never run for this bot:** send your bot any message, open `https://api.telegram.org/bot<bot_token>/getUpdates` and read `message.from.id`. Once the feature has run, this stops working: the service consumes updates (or a webhook makes `getUpdates` return 409), and the bot keeps receiving only button clicks. To use it later anyway, stop the service and request `getUpdates?allowed_updates=["message"]`, after removing any webhook with `deleteWebhook` in webhook mode.
3. Set:
   ```yaml
   environment:
     - TELEGRAM_INTERACTIVE_ENABLED=true
     - TELEGRAM_ALLOWED_USERS=123456789,987654321
   ```

   To have someone receive and decide Seerr requests, list them in `TELEGRAM_ADMIN_USERS` instead (see [Seerr buttons](#seerr-approve-decline-resolve)). Admins can press every button, so they don't need to be listed twice.

Startup fails with a message naming the variable if both lists are empty or either contains anything other than positive numeric IDs. Clicks from anyone else, and buttons on messages outside `TELEGRAM_CHAT_ID` (other than an admin's own private chat with the bot), get a "⛔ You're not allowed to control this bot." popup and never reach an action.

## Choose a mode

**Polling** (default, `TELEGRAM_UPDATES_MODE=polling`) needs no public ingress, so it works behind NAT or on a home network. On startup the service removes any registered webhook and then long-polls Telegram (`TELEGRAM_POLL_TIMEOUT_SECONDS`, default `25`).

**Webhook** (`TELEGRAM_UPDATES_MODE=webhook`) has Telegram push clicks to `POST /api/v1/telegram/webhook`:

```yaml
environment:
  - TELEGRAM_INTERACTIVE_ENABLED=true
  - TELEGRAM_ALLOWED_USERS=123456789
  - TELEGRAM_UPDATES_MODE=webhook
  - TELEGRAM_WEBHOOK_URL=https://notifier.example.com   # the path is appended for you
  - TELEGRAM_WEBHOOK_SECRET_FILE=/run/secrets/telegram_webhook_secret
```

- `TELEGRAM_WEBHOOK_URL` must be public and `https://`. Telegram only delivers webhooks to ports **443, 80, 88 and 8443**, so put the service behind a reverse proxy on one of those.
- `TELEGRAM_WEBHOOK_SECRET` (or `TELEGRAM_WEBHOOK_SECRET_FILE`) is required: 1–256 characters of `A-Z a-z 0-9 _ -`. Telegram sends it in the `X-Telegram-Bot-Api-Secret-Token` header, and requests without it get `401`. `SERVER_AUTH_TOKEN` isn't used for this endpoint.
- The service registers the webhook itself on startup and retries in the background if Telegram is unreachable. It leaves the webhook registered on shutdown, so clicks queue up while it restarts.

## Buttons

### ✅ Acknowledge

Lets everyone see that someone is dealing with a card. It appears on:

| Card | When | Sent to |
|---|---|---|
| Health issue | Sonarr / Radarr report a warning or error | each admin privately, when `TELEGRAM_ADMIN_USERS` is set; otherwise the channel |
| Manual interaction required | An import needs a person to finish it | the channel |
| Stalled download | Tracking stopped because the download stopped making progress | the channel |
| Seerr request failed | Seerr couldn't send an approved request to Sonarr / Radarr | the channel |

Pressing it edits the card in place: a `✅ Handled by @alex · 14:32` line is added at the bottom and the button is removed. For a health issue sent to the admins, **every admin's copy** is marked handled.

**Health issues** go privately to the admins, the people who can fix them, and keep the channel informational. "Health restored" cards follow them to the admins' private chats, so the outcome sits next to the issue. As with Seerr requests, each admin must have started a private chat with the bot once (see [Seerr: Approve, Decline, Resolve](#seerr-approve-decline-resolve)). If no admin can be reached, the channel gets an informational copy of the health issue without buttons, as with Seerr requests, and a warning is logged. Link buttons such as **Wiki** stay. The time follows the container's `TZ` variable (UTC when unset), for example `TZ=Europe/Bucharest`.

Each button works **once** and for **30 days**. After that, or after a restart with the in-memory state store, pressing it answers "⌛ This button has expired." Use the Valkey / Redis state store to keep buttons working across restarts.

### Seerr: Approve, Decline, Resolve

Lets **admins** decide Seerr (Overseerr / Jellyseerr) requests and issues without opening Seerr. These cards go **to each admin in a private chat with the bot, not to the channel**, so the channel stays informational: it learns the outcome from Seerr's own *Request Approved*, *Declined* and *Issue Resolved* webhooks.

| Event | Sent to | Buttons |
|---|---|---|
| New request (pending approval) | each admin, privately | **✅ Approve**, **❌ Decline** |
| New issue | each admin, privately | **✔️ Resolve** |
| Everything else (approved, available, declined, failed, comments…) | the channel, as before | — |

To turn them on, add the Seerr API and at least one admin:

```yaml
environment:
  - TELEGRAM_INTERACTIVE_ENABLED=true
  - TELEGRAM_ALLOWED_USERS=987654321        # may press Acknowledge in the channel
  - TELEGRAM_ADMIN_USERS=123456789          # also receives and decides Seerr requests
  - SEERR_URL=http://jellyseerr:5055
  - SEERR_API_KEY_FILE=/run/secrets/seerr_api_key
```

**Each admin must open a private chat with the bot and press Start once.** Telegram doesn't let bots message someone first. Until an admin has done this, sending to them fails, and the logs show `Could not send '…' to admin <id>; they may need to start a chat with the bot`.

- Every admin gets their own copy. When one admin decides, **every admin's copy** is edited: a `✅ Approved by @alex · 14:32` (or `❌ Declined by`, `✔️ Resolved by`) line is added and the buttons are removed, so nobody acts on a request that's already been decided.
- If **no** admin could be reached, the card goes to the channel without buttons, so the request isn't lost, and a warning is logged.
- Buttons are checked again when pressed: they only work for users still in `TELEGRAM_ADMIN_USERS`, in their own private chat. If you remove someone from the list, the copies they already have stop working.
- The API key acts as Seerr's admin user, so Seerr records the decision under that account, not the Telegram admin's.
- Seerr's *Settings → Network → CSRF Protection* can stay on: the service fetches Seerr's CSRF token when Seerr asks for one.
- Each card's buttons work once, like **Acknowledge**. While one press is being handled, a second press (a double-tap, or another admin pressing **Decline** on their copy) answers "⏳ Someone is already handling this."
- Without `SEERR_URL` / `SEERR_API_KEY`, or with the flag off, Seerr cards go to the channel exactly as before. With Seerr configured but `TELEGRAM_ADMIN_USERS` empty, they also stay in the channel without buttons, and startup logs a warning.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Logs show `getUpdates returned 409` | Another process is polling the same bot, or a webhook is still registered (for example by a deployment in webhook mode). Keep exactly one poller per bot token. |
| Buttons answer "⌛ This button has expired." | The button's reference outlived its TTL, the service restarted with the in-memory state store, or the click reached a replica that doesn't share Valkey / Redis. |
| Webhook requests get `401` | The secret Telegram sends doesn't match `TELEGRAM_WEBHOOK_SECRET`. Check the logs for `Telegram webhook registered` after a secret change, and make sure your proxy forwards the `X-Telegram-Bot-Api-Secret-Token` header. |
| A user gets "⛔ You're not allowed to control this bot." | Their numeric ID isn't in `TELEGRAM_ALLOWED_USERS` (or `TELEGRAM_ADMIN_USERS`), or the button sits on a message outside `TELEGRAM_CHAT_ID`. Rejections are logged at INFO with the user ID. |
| Logs show `TELEGRAM_INTERACTIVE_ENABLED is set but the notification provider is ...` | Interactions need the Telegram notification provider, so the feature stays off. |
| Acknowledge answers "⚠️ Action failed." | The card was deleted, or the bot can no longer edit it. The reason is logged at WARN. |
| Admins don't receive Seerr requests or health issues | Each admin must start a private chat with the bot once (logs: `they may need to start a chat with the bot`). For Seerr requests, also check that `SEERR_URL` and `SEERR_API_KEY` are set and `TELEGRAM_ADMIN_USERS` isn't empty; startup logs which one is missing. Requests that Seerr auto-approves are never sent for a decision. |
| Pending requests or health issues appear in the channel without buttons | No admin could be reached privately, so the card fell back to the channel. See the row above. |
| Someone gets "⛔ You're not allowed to control this bot." on Approve | They're no longer in `TELEGRAM_ADMIN_USERS`; copies sent while they were an admin stop working. |
| Approve answers "⚠️ Action failed." | Seerr was unreachable or refused the API key. The HTTP status is logged at WARN. The card is unchanged, so the button can be pressed again. |
| Approve answers "⚠️ This request no longer exists in Seerr." | The request (or issue) was deleted in Seerr after the card was sent. |

All variables are listed in the [Configuration Reference](CONFIGURATION.md#telegram-interactions).
