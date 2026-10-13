# Configuration Reference

All settings can be configured via environment variables. For a minimal setup, see the [Quick Start](../README.md#-quick-start).

## Server & Authentication
| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port the server listens on |
| `SERVER_AUTH_TOKEN` | `""` | Secret token (or comma-separated tokens). Supports `SERVER_AUTH_TOKEN_FILE` |
| `SERVER_RATE_LIMIT_PER_MINUTE` | `120` | Inbound rate limit for webhooks (`<= 0` disables limit) |
| `ENABLE_PREVIEW` | `false` | Enables the `/api/v1/templates/preview` sandbox endpoint |

## Notifications & Telegram
| Variable | Default | Description |
|---|---|---|
| `NOTIFICATION_URL` | `""` | Sink URL: `telegram://<bot_token>@<chat_id>?topic=<id>&photos=true`. Supports `NOTIFICATION_URL_FILE` |

## Telegram Interactions
Inline-button handling for Telegram cards, off by default. See [Telegram Interactions](TELEGRAM_INTERACTIONS.md) for setup, the deployment rules (polling supports **one replica only**; webhook mode with several replicas needs Valkey / Redis) and troubleshooting. Every variable except the flag is only read, and only validated, when the flag is `true`.

| Variable | Default | Description |
|---|---|---|
| `TELEGRAM_INTERACTIVE_ENABLED` | `false` | Feature flag for inbound button handling. Needs a Telegram `NOTIFICATION_URL` (or `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID`); with another provider it logs a warning and stays off |
| `TELEGRAM_UPDATES_MODE` | `polling` | How clicks reach the service: `polling` (no public ingress needed) or `webhook` |
| `TELEGRAM_ALLOWED_USERS` | — | Required. Comma-separated **numeric** Telegram user IDs allowed to press buttons; usernames are rejected at startup |
| `TELEGRAM_WEBHOOK_URL` | — | Webhook mode: required. Public `https://` base URL of this service; `/api/v1/telegram/webhook` is appended |
| `TELEGRAM_WEBHOOK_SECRET` | — | Webhook mode: required. 1–256 characters of `A-Z a-z 0-9 _ -`, checked against Telegram's `X-Telegram-Bot-Api-Secret-Token` header. Supports `TELEGRAM_WEBHOOK_SECRET_FILE` |
| `TELEGRAM_POLL_TIMEOUT_SECONDS` | `25` | Polling mode: long-poll timeout, 1–50 |

With the flag on, cards that need attention carry an **✅ Acknowledge** button; see [Buttons](TELEGRAM_INTERACTIONS.md#buttons). The time in its "Handled by" stamp follows the container's `TZ` (UTC when unset).

## Download Client & Live Tracking
Pick the client with `DOWNLOAD_CLIENT_TYPE` or the scheme of `DOWNLOAD_CLIENT_URL`. With neither set, qBittorrent is used, so existing `QBITTORRENT_*` setups keep working unchanged.

| Variable | Default | Description |
|---|---|---|
| `DOWNLOAD_CLIENT_TYPE` | `qbittorrent` | Active client: `qbittorrent` or `transmission` (`sabnzbd` is recognised but not supported yet and stops startup) |
| `DOWNLOAD_CLIENT_URL` | `""` | Single connection URL whose scheme selects the client: `qbittorrent://user:pass@host:8080`, `transmission://user:pass@host:9091`. Append `s` for HTTPS (`qbittorrents://`, `transmissions://`). Plain `http(s)://` URLs take the type from `DOWNLOAD_CLIENT_TYPE`. Credentials must be percent-encoded and override the client-specific variables below. Supports `DOWNLOAD_CLIENT_URL_FILE` |
| `DOWNLOAD_CLIENT_POLL_INTERVAL_SECONDS` | `5` | Tracking update polling interval (in seconds) |
| `DOWNLOAD_CLIENT_MAX_POLLING_MINUTES` | `30` | Max duration to track a single download |
| `DOWNLOAD_CLIENT_STALLED_TIMEOUT_MINUTES` | `15` | Timeout before alerting a download is stalled |
| `DOWNLOAD_CLIENT_MISSING_GRACE_ATTEMPTS` | `6` | Consecutive polls a download may be missing from the client before tracking stops |
| `DOWNLOAD_CLIENT_DEBOUNCE_SECONDS` | `5` | Sliding window to batch rapid multi-episode grabs, imports, and upgrades |
| `DOWNLOAD_CLIENT_WEBUI_PUBLIC_URL` | `""` | Public URL of the client's web UI for "Open WebUI" buttons |
| `DOWNLOAD_CLIENT_RECONCILIATION_ENABLED` | `true` | Resume tracking of tagged downloads after a restart |
| `DOWNLOAD_CLIENT_RECONCILIATION_INTERVAL_MINUTES` | `5` | How often to scan the client for tagged downloads to resume |
| `DOWNLOAD_CLIENT_TAG_PREFIX` | `mwn_` | Prefix for tracking tags (qBittorrent tags / Transmission labels), e.g. `mwn_tg_` or `mwn_discord_` to isolate channels |

Every `DOWNLOAD_CLIENT_*` tracking variable above still accepts its previous `QBITTORRENT_*` name (e.g. `QBITTORRENT_POLL_INTERVAL_SECONDS`) as a fallback.

### qBittorrent

| Variable | Default | Description |
|---|---|---|
| `QBITTORRENT_URL` | `http://localhost:8080` | Internal network URL to the qBittorrent WebUI |
| `QBITTORRENT_USERNAME` | `""` | qBittorrent WebUI username |
| `QBITTORRENT_PASSWORD` | `""` | qBittorrent WebUI password. Supports `QBITTORRENT_PASSWORD_FILE` |

### Transmission

Labels require Transmission 3.00+.

| Variable | Default | Description |
|---|---|---|
| `TRANSMISSION_URL` | `http://localhost:9091` | Base URL, Transmission's `/transmission` or `/transmission/web` path, or the full RPC endpoint ending in `/rpc` (e.g. behind a reverse proxy) |
| `TRANSMISSION_USERNAME` | `""` | RPC username when `rpc-authentication-required` is enabled |
| `TRANSMISSION_PASSWORD` | `""` | RPC password. Supports `TRANSMISSION_PASSWORD_FILE` |

## State Store (Optional Valkey / Redis)
Persistent state storage across restarts and rollouts, enabling multi-instance topologies (e.g., Telegram and Discord notifier instances sharing the same qBittorrent, Starr apps, and Plex/Jellyfin server). Single-instance in-memory remains the zero-dependency default.

| Variable | Default | Description |
|---|---|---|
| `STATE_STORE_TYPE` | `memory` | State store backend: `memory` or `valkey` (also accepts `redis`). Auto-detected if URL is set. |
| `VALKEY_URL` | `""` | Connection URL: `valkey://[user:pass@]host:port[/db]` (or `redis://`). Supports `VALKEY_URL_FILE`, `REDIS_URL` |
| `STATE_KEY_PREFIX` | `mwn:` | Namespace prefix for keys (e.g. `mwn:tg:`, `mwn:discord:` to isolate multiple notification channels) |
| `STATE_TIMEOUT_MILLIS` | `2000` | Connection and socket timeout in milliseconds |
| `STATE_MAX_POOL_SIZE` | `16` | Maximum connection pool size |

### Rollouts and replicas
With `STATE_STORE_TYPE=valkey` (or `redis`), each live download card is edited by one instance at a time. Instances coordinate through a short lease per download, renewed on every progress tick, so a rolling update needs no downtime:

- **Rollout:** the new instance leaves the old instance's cards alone while it's still running, and continues the *same* cards within about 35 seconds of the old one stopping.
- **Crash:** if an instance dies without shutting down, another instance takes its cards over within about 65 seconds, once the leases expire.
- **Valkey hiccups:** tracking carries on; a lease that lapsed during an outage is taken back on the next tick.
- **Finished while nobody watched:** a download that completes during a handoff or downtime gets its completion card, and its tags removed, at the next sweep.

With the default `memory` store, instances can't see each other: during a rollout overlap both may update the same card for a moment. After a restart the same card still continues, because shutdown keeps the download's resume tags.

Running several replicas permanently isn't a tuned setup yet: grab webhooks and season-pack batching are handled per instance.

## Media Server (Plex & Jellyfin)
| Variable | Default | Description |
|---|---|---|
| `MEDIA_SERVER_TYPE` | `plex` | Active media server (`plex` or `jellyfin`) |
| `MEDIA_SERVER_URL` | `""` | Internal network URL of the media server |
| `MEDIA_SERVER_PUBLIC_URL` | `""` | Public URL used for "Watch on Plex/Jellyfin" action buttons |

## Card Templates
| Variable | Default | Description |
|---|---|---|
| `TEMPLATES_FILE` | `""` | Path to custom `templates.yaml` file on disk. Supports `TEMPLATES_FILE_PATH` |
| `TEMPLATES_YAML` | `""` | Raw inline YAML template configuration string |
