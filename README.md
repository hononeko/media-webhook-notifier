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

Images are published for **`linux/amd64`** and **`linux/arm64`** (Raspberry Pi 4 and newer, Apple Silicon, Ampere) under the same tag; Docker picks the right one.

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

## ⚙️ Configuration

Everything is configured with environment variables. To get started you only need the ones from the Quick Start:

| Variable | Description |
|---|---|
| `SERVER_AUTH_TOKEN` | Token your media apps send with each webhook (see step 2). Without it, the webhook endpoints accept unauthenticated requests. |
| `NOTIFICATION_URL` | Where cards are sent: `telegram://<bot_token>@<chat_id>`. Append `?topic=<id>` to post into a forum topic. |
| `QBITTORRENT_URL`, `QBITTORRENT_USERNAME`, `QBITTORRENT_PASSWORD` | Download client for live progress tracking. For Transmission or a single connection URL, see [Download Client & Live Tracking](docs/CONFIGURATION.md#download-client--live-tracking). |
| `MEDIA_SERVER_TYPE`, `MEDIA_SERVER_URL`, `MEDIA_SERVER_PUBLIC_URL` | Plex or Jellyfin, for "Watch" links on Media Available cards. |

Every other option (download client tuning, the Valkey / Redis state store, card templates, rate limits) is in the **[Configuration Reference](docs/CONFIGURATION.md)**.

---

## 📚 Documentation & Deep Dives

* ⚙️ **[Configuration Reference](docs/CONFIGURATION.md):** Every environment variable, grouped by server, notifications, download client, state store, media server, and templates.
* 🔘 **[Telegram Interactions](docs/TELEGRAM_INTERACTIONS.md):** Inline-button handling behind `TELEGRAM_INTERACTIVE_ENABLED`: allowlist, polling vs. webhook mode, replica rules and troubleshooting.
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
