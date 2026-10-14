# Telegram Interactions

Telegram interactions let the notifier react to **inline-button clicks** on its Telegram cards. Each click is checked against an allowlist of Telegram users and answered within Telegram's 3-second window, then handed to the matching action.

Cards that need a person's attention carry an **✅ Acknowledge** button, and Seerr cards can carry admin-only **Approve**, **Decline** and **Resolve** buttons (see [Buttons](#buttons)). The feature is **off by default**: with `TELEGRAM_INTERACTIVE_ENABLED` unset, cards look exactly as before and the service never listens for clicks.

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

   To let someone approve Seerr requests as well, list them in `TELEGRAM_ADMIN_USERS` instead (see [Seerr buttons](#seerr-approve-decline-resolve)). Admins can press every button, so they don't need to be listed twice.

Startup fails with a message naming the variable if both lists are empty or either contains anything other than positive numeric IDs. Clicks from anyone else, and buttons on messages outside `TELEGRAM_CHAT_ID`, get a "⛔ You're not allowed to control this bot." popup and never reach an action.

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

Lets everyone in the chat see that someone is dealing with a card. It appears on:

| Card | When |
|---|---|
| Health issue | Sonarr / Radarr report a warning or error (not on "health restored") |
| Manual interaction required | An import needs a person to finish it |
| Stalled download | Tracking stopped because the download stopped making progress |
| Seerr request failed | Seerr couldn't send an approved request to Sonarr / Radarr |

Pressing it edits the card in place: a `✅ Handled by @alex · 14:32` line is added at the bottom and the button is removed. Link buttons such as **Wiki** stay. The time follows the container's `TZ` variable (UTC when unset), for example `TZ=Europe/Bucharest`.

Each button works **once** and for **30 days**. After that, or after a restart with the in-memory state store, pressing it answers "⌛ This button has expired." Use the Valkey / Redis state store to keep buttons working across restarts.

### Seerr: Approve, Decline, Resolve

Lets **admins** act on Seerr (Overseerr / Jellyseerr) cards without opening Seerr:

| Card | Buttons |
|---|---|
| New request (pending approval) | **✅ Approve**, **❌ Decline** |
| New issue | **✔️ Resolve** |

To turn them on, add the Seerr API and at least one admin:

```yaml
environment:
  - TELEGRAM_INTERACTIVE_ENABLED=true
  - TELEGRAM_ALLOWED_USERS=987654321        # may press Acknowledge
  - TELEGRAM_ADMIN_USERS=123456789          # may also approve, decline and resolve
  - SEERR_URL=http://jellyseerr:5055
  - SEERR_API_KEY_FILE=/run/secrets/seerr_api_key
```

- Only users in `TELEGRAM_ADMIN_USERS` can use these buttons. Anyone else, including the person who made the request, gets "⛔ You're not allowed to control this bot." and nothing changes in Seerr.
- Pressing a button edits the card: a `✅ Approved by @alex · 14:32` (or `❌ Declined by`, `✔️ Resolved by`) line is added and the buttons are removed. Seerr then sends its own *Request Approved* / *Declined* / *Issue Resolved* webhook as usual.
- The API key acts as Seerr's admin user, so Seerr records the decision under that account, not the Telegram admin's.
- Each card's buttons work once, like **Acknowledge**. While one press is being handled, a second press on the same card (a double-tap, or another admin pressing **Decline**) answers "⏳ Someone is already handling this."
- Without `SEERR_URL` / `SEERR_API_KEY`, or with the flag off, Seerr cards look exactly as before. With Seerr configured but `TELEGRAM_ADMIN_USERS` empty, the buttons stay off and startup logs a warning.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Logs show `getUpdates returned 409` | Another process is polling the same bot, or a webhook is still registered (for example by a deployment in webhook mode). Keep exactly one poller per bot token. |
| Buttons answer "⌛ This button has expired." | The button's reference outlived its TTL, the service restarted with the in-memory state store, or the click reached a replica that doesn't share Valkey / Redis. |
| Webhook requests get `401` | The secret Telegram sends doesn't match `TELEGRAM_WEBHOOK_SECRET`. Check the logs for `Telegram webhook registered` after a secret change, and make sure your proxy forwards the `X-Telegram-Bot-Api-Secret-Token` header. |
| A user gets "⛔ You're not allowed to control this bot." | Their numeric ID isn't in `TELEGRAM_ALLOWED_USERS` (or `TELEGRAM_ADMIN_USERS`), or the button sits on a message outside `TELEGRAM_CHAT_ID`. Rejections are logged at INFO with the user ID. |
| Logs show `TELEGRAM_INTERACTIVE_ENABLED is set but the notification provider is ...` | Interactions need the Telegram notification provider, so the feature stays off. |
| Acknowledge answers "⚠️ Action failed." | The card was deleted, or the bot can no longer edit it. The reason is logged at WARN. |
| Seerr cards have no Approve / Decline buttons | Check that `SEERR_URL` and `SEERR_API_KEY` are set and `TELEGRAM_ADMIN_USERS` isn't empty; startup logs which one is missing. Requests that Seerr auto-approves never get the buttons. |
| An admin gets "⛔ You're not allowed to control this bot." on Approve | Their numeric ID is in `TELEGRAM_ALLOWED_USERS` but not in `TELEGRAM_ADMIN_USERS`. |
| Approve answers "⚠️ Action failed." | Seerr was unreachable or refused the API key. The HTTP status is logged at WARN. The card is unchanged, so the button can be pressed again. |
| Approve answers "⚠️ This request no longer exists in Seerr." | The request (or issue) was deleted in Seerr after the card was sent. |

All variables are listed in the [Configuration Reference](CONFIGURATION.md#telegram-interactions).
