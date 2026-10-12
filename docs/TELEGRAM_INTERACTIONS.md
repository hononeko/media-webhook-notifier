# Telegram Interactions

Telegram interactions let the notifier react to **inline-button clicks** on its Telegram cards. Each click is checked against an allowlist of Telegram users and answered within Telegram's 3-second window, then handed to the matching action.

This release adds the listener and the security checks only. Cards don't show action buttons yet; download controls such as pause and resume build on this. The feature is **off by default**: with `TELEGRAM_INTERACTIVE_ENABLED` unset, the service behaves exactly as before.

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

Startup fails with a message naming the variable if the allowlist is empty or contains anything other than positive numeric IDs. Clicks from anyone else, and buttons on messages outside `TELEGRAM_CHAT_ID`, get a "⛔ You're not allowed to control this bot." popup and never reach an action.

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

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Logs show `getUpdates returned 409` | Another process is polling the same bot, or a webhook is still registered (for example by a deployment in webhook mode). Keep exactly one poller per bot token. |
| Buttons answer "⌛ This button has expired." | The button's reference outlived its TTL, the service restarted with the in-memory state store, or the click reached a replica that doesn't share Valkey / Redis. |
| Webhook requests get `401` | The secret Telegram sends doesn't match `TELEGRAM_WEBHOOK_SECRET`. Check the logs for `Telegram webhook registered` after a secret change, and make sure your proxy forwards the `X-Telegram-Bot-Api-Secret-Token` header. |
| A user gets "⛔ You're not allowed to control this bot." | Their numeric ID isn't in `TELEGRAM_ALLOWED_USERS`, or the button sits on a message outside `TELEGRAM_CHAT_ID`. Rejections are logged at INFO with the user ID. |
| Logs show `TELEGRAM_INTERACTIVE_ENABLED is set but the notification provider is ...` | Interactions need the Telegram notification provider, so the feature stays off. |

All variables are listed in the [Configuration Reference](CONFIGURATION.md#telegram-interactions).
