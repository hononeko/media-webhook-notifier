# Media Webhook Notifier (`media-webhook-notifier`)

[![Validate & Test](https://github.com/hononeko/media-webhook-notifier/actions/workflows/validate.yml/badge.svg)](https://github.com/hononeko/media-webhook-notifier/actions/workflows/validate.yml)
[![Container Image](https://img.shields.io/badge/GHCR-ghcr.io%2Fhononeko%2Fmedia--webhook--notifier-blue?logo=docker)](https://github.com/hononeko/media-webhook-notifier/pkgs/container/media-webhook-notifier)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![GraalVM](https://img.shields.io/badge/GraalVM-Native%20Image-E85F00?logo=oracle)](https://www.graalvm.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**`media-webhook-notifier`** is an ultra-lightweight, native microservice that turns webhooks from your home media stack into beautiful, interactive notification cards with **live in-place download progress tracking** in Telegram.

Compiled ahead-of-time (AOT) with **GraalVM Native Image** into a static, distroless container image with **<5ms cold start** and **~35MB RSS memory footprint**.

---

## 🌟 What It Does

* **⏳ Live Download Progress:** Monitors active downloads in **qBittorrent** or **Transmission** and silently edits the Telegram message in-place every few seconds with an ASCII progress bar, real-time speed, ETA, and peer stats.
* **🛡️ Smart Episode Debouncer:** Automatically groups rapid multi-episode grabs, imports, and quality upgrades into a single clean status card (no chat spam).
* **🍿 Media Available Cards:** Directly ingests Plex `library.new` and Jellyfin `ItemAdded` events with smart formatting for Seasons (e.g. `Futurama - Season 3` with Season poster), Episodes (`S03E01`), and Movies, complete with instant watch deep links.
* **🛎️ Request & Issue Tracking:** Ingests media requests and issue reports from Overseerr, Jellyseerr, and Seerr.
* **🎨 Fully Customizable Layouts:** Modify card titles, emojis, text, or localization via simple YAML templates.
* **🔒 Dual Authentication Guard:** Accepts tokens via HTTP Headers (`Authorization: Bearer <token>`, `X-Api-Key: <token>`) or query parameters (`?token=<token>`).

---

## 🚀 Quick Start

### 1. Run with Docker Compose

Create a `docker-compose.yml`:

```yaml
services:
  media-webhook-notifier:
    image: ghcr.io/hononeko/media-webhook-notifier:latest
    container_name: media-webhook-notifier
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      # Security & Networking
      - SERVER_AUTH_TOKEN=your-secret-token
      - NOTIFICATION_URL=telegram://<BOT_TOKEN>@<CHAT_ID>

      # Torrent Client (for live progress tracking)
      - QBITTORRENT_URL=http://qbittorrent:8080
      - QBITTORRENT_USERNAME=admin
      - QBITTORRENT_PASSWORD=adminadmin

      # Media Server (for direct playback links)
      - MEDIA_SERVER_TYPE=plex # "plex" or "jellyfin"
      - MEDIA_SERVER_URL=http://plex:32400
      - MEDIA_SERVER_PUBLIC_URL=https://plex.example.com
```

Start the container:
```bash
docker compose up -d
```

### 2. Configure Your Media Apps

Add webhook endpoints in each application's notification settings:

| Application | Webhook URL | Supported Events |
|---|---|---|
| **Sonarr** | `http://<host>:8080/api/v1/webhook/sonarr?token=your-secret-token` | On Grab, On Download, On Upgrade, Health |
| **Radarr** | `http://<host>:8080/api/v1/webhook/radarr?token=your-secret-token` | On Grab, On Download, On Upgrade, Health |
| **Plex** | `http://<host>:8080/api/v1/webhook/plex?token=your-secret-token` | `library.new` (New media added) |
| **Jellyfin / Emby** | `http://<host>:8080/api/v1/webhook/jellyfin?token=your-secret-token` | `ItemAdded` (via Webhook Plugin) |
| **Overseerr / Jellyseerr** | `http://<host>:8080/api/v1/webhook/seerr?token=your-secret-token` | Request Pending, Approved, Available, Issues |

---

## ⚙️ Configuration Reference

All settings can be configured via environment variables:

### Server & Authentication
| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port the server listens on |
| `SERVER_AUTH_TOKEN` | `""` | Secret token (or comma-separated tokens). Supports `SERVER_AUTH_TOKEN_FILE` |
| `SERVER_RATE_LIMIT_PER_MINUTE` | `120` | Inbound rate limit for webhooks (`<= 0` disables limit) |
| `ENABLE_PREVIEW` | `false` | Enables the `/api/v1/templates/preview` sandbox endpoint |

### Notifications & Telegram
| Variable | Default | Description |
|---|---|---|
| `NOTIFICATION_URL` | `""` | Sink URL: `telegram://<bot_token>@<chat_id>?topic=<id>&photos=true`. Supports `NOTIFICATION_URL_FILE` |

### Download Client & Live Tracking
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

**qBittorrent**

| Variable | Default | Description |
|---|---|---|
| `QBITTORRENT_URL` | `http://localhost:8080` | Internal network URL to the qBittorrent WebUI |
| `QBITTORRENT_USERNAME` | `""` | qBittorrent WebUI username |
| `QBITTORRENT_PASSWORD` | `""` | qBittorrent WebUI password. Supports `QBITTORRENT_PASSWORD_FILE` |

**Transmission** (labels require Transmission 3.00+)

| Variable | Default | Description |
|---|---|---|
| `TRANSMISSION_URL` | `http://localhost:9091` | Base URL (`/transmission/rpc` is appended) or the full RPC endpoint |
| `TRANSMISSION_USERNAME` | `""` | RPC username when `rpc-authentication-required` is enabled |
| `TRANSMISSION_PASSWORD` | `""` | RPC password. Supports `TRANSMISSION_PASSWORD_FILE` |

### State Store (Optional Valkey / Redis)
Persistent state storage across restarts and rollouts, enabling multi-instance topologies (e.g., Telegram and Discord notifier instances sharing the same qBittorrent, Starr apps, and Plex/Jellyfin server). Single-instance in-memory remains the zero-dependency default.

| Variable | Default | Description |
|---|---|---|
| `STATE_STORE_TYPE` | `memory` | State store backend: `memory` or `valkey` (also accepts `redis`). Auto-detected if URL is set. |
| `VALKEY_URL` | `""` | Connection URL: `valkey://[user:pass@]host:port[/db]` (or `redis://`). Supports `VALKEY_URL_FILE`, `REDIS_URL` |
| `STATE_KEY_PREFIX` | `mwn:` | Namespace prefix for keys (e.g. `mwn:tg:`, `mwn:discord:` to isolate multiple notification channels) |
| `STATE_TIMEOUT_MILLIS` | `2000` | Connection and socket timeout in milliseconds |
| `STATE_MAX_POOL_SIZE` | `16` | Maximum connection pool size |

### Media Server (Plex & Jellyfin)
| Variable | Default | Description |
|---|---|---|
| `MEDIA_SERVER_TYPE` | `plex` | Active media server (`plex` or `jellyfin`) |
| `MEDIA_SERVER_URL` | `""` | Internal network URL of the media server |
| `MEDIA_SERVER_PUBLIC_URL` | `""` | Public URL used for "Watch on Plex/Jellyfin" action buttons |

### Card Templates
| Variable | Default | Description |
|---|---|---|
| `TEMPLATES_FILE` | `""` | Path to custom `templates.yaml` file on disk. Supports `TEMPLATES_FILE_PATH` |
| `TEMPLATES_YAML` | `""` | Raw inline YAML template configuration string |

---

## 📚 Documentation & Deep Dives

* 🚀 **[Installation & Deployment Examples](docs/INSTALLATION_EXAMPLES.md):** Production Kubernetes manifests, Docker CLI, secret file mounts (`*_FILE`), and Linux Systemd service.
* 🎨 **[Card Templates & Formatting Guide](docs/TEMPLATES.md):** Complete tag reference, suppression rules, sandbox preview API, and the canonical default templates in [src/main/resources/templates.default.yaml](src/main/resources/templates.default.yaml).
* 📐 **[Hexagonal Architecture Blueprint](docs/ARCHITECTURE.md):** Layer boundaries, domain ports, error handling with Arrow-kt, and resilience policies.
* ⚡ **[GraalVM Native Image & Runtime](docs/GRAALVM_AND_RUNTIME.md):** Ahead-of-time compilation, memory benchmarks, and static binary builds.
* 📋 **[Technical Specification](docs/SPECIFICATION.md):** Full domain model specifications, payload schemas, and event lifecycles.

---

## 🔍 API & Observability

* **Webhooks:** `POST /api/v1/webhook/{provider}` (Header, Query, or Path token auth)
* **Template Sandbox:** `POST /api/v1/templates/preview` (when `ENABLE_PREVIEW=true`)
* **Dead Letter Queue (admin):** inspect and replay payloads that failed processing. These endpoints require `SERVER_AUTH_TOKEN` (header or query token) and answer `403` when no token is configured:
  * `GET /api/v1/dlq?limit=50&offset=0&status=pending|resolved` lists entries newest first (id, timestamp, provider, error, attempt count, status, payload preview)
  * `GET /api/v1/dlq/{id}` returns the full payload JSON and diagnostic stack trace
  * `POST /api/v1/dlq/{id}/replay` re-queues the payload on the event rail and marks the entry `resolved` (`409` if already replayed, `503` if the rail is full)
  * `DELETE /api/v1/dlq` clears the buffer
  * With the Valkey state store enabled, dead letters persist across restarts under `<STATE_KEY_PREFIX>dlq:snapshot`. Instances that share a key prefix also share one snapshot, so give each instance its own prefix.
* **Kubernetes Probes:** `GET /livez`, `GET /readyz`, `GET /startupz`, `GET /health`
* **Telemetry & Telemetry Metrics:** `GET /metrics` (JVM/native memory, uptime, active trackers, event queue state)
* **Provider JSON Schemas:** `GET /schema/{provider}` (`sonarr`, `radarr`, `servarr`, `plex`, `jellyfin`, `seerr`)

---

## 🛠️ Development

```bash
# Code formatting & lint checks
./gradlew ktlintCheck
./gradlew ktlintFormat

# Run unit, integration & architecture tests
./gradlew test

# Full build verification
./gradlew check
```

---

## 📄 License

Distributed under the [MIT License](LICENSE).
