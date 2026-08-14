# Media Webhook Notifier (`media-webhook-notifier`)

A high-performance, native microservice built with **Kotlin**, **Coroutines**, and **Hexagonal Architecture (Ports and Adapters)**, compiled with **GraalVM Native Image** into a static, distroless container binary published to **`ghcr.io/hononeko/media-webhook-notifier`**.

It ingests webhooks from **Sonarr**, **Radarr**, **Plex**, and **Jellyfin**, monitors **qBittorrent** to dispatch real-time, interactive status cards and live in-place progress updates to **Telegram** (and future notification sinks).

---

## 🌟 Key Features

* **⚡ Ultra-Low Footprint:** Instant startup (<25ms) and tiny memory footprint (<25 MB RSS) via GraalVM AOT native compilation.
* **🛡️ Hexagonal Architecture & Arrow-kt:** Clean separation of concerns with domain ports and declarative, typed functional error handling.
* **⏳ Live In-Place Telegram Updates:** Monitors active torrent downloads in qBittorrent and silently updates the Telegram status card every few seconds (ASCII progress bar, ETA, speeds, peers, WebUI link).
* **🎬 Native Plex & Jellyfin Integration:** Directly parses Plex `library.new` and Jellyfin `ItemAdded` events with rich metadata, TMDB posters, and direct playback links without requiring Tautulli.
* **🔒 Dual Authentication Guard:** Supports both HTTP Header Auth (`Authorization: <token>`) and Query Parameter Auth (`?apikey=<token>`).
* **🧩 Modular Notification Providers:** Pluggable notification adapters (Telegram MVP, easily extensible to Ntfy, Discord, Webhooks).

---

## 📦 Container Registry

Images are published to GitHub Container Registry:

```bash
docker pull ghcr.io/hononeko/media-webhook-notifier:latest
```

---

## 📖 Architecture & Design Documentation

Detailed architectural blueprints, domain models, and technical specifications are available in [`docs/`](docs/):

* **[Functional & Technical Specification](docs/SPECIFICATION.md):** Inbound webhook formats, downloader tracking lifecycle, and Telegram card templates.
* **[Hexagonal Architecture & Arrow-kt Standards](docs/ARCHITECTURE.md):** Layer definitions, domain ports/adapters structure, and typed error hierarchy under `app.hononeko.notifier`.
* **[GraalVM Native & Runtime Architecture](docs/GRAALVM_AND_RUNTIME.md):** Native image build setup, reflection-free serialization, GHCR multi-arch builds, and Wasm feasibility analysis.

---

## 🛠️ Configuration Overview

Configuration is managed via environment variables or a YAML/HOCON configuration file (`application.conf`):

```yaml
server:
  port: 8080
  auth_token: "31eb769648d7ca6fabf89377cd781ac8b940230ab74335301ad4e4db1f1fda7e6eac1d8ce4b77a98897c6dcd2054ab28"

media_server:
  type: "plex" # "plex" or "jellyfin"
  base_url: "https://plex.example.com"
  jellyfin_url: "https://jellyfin.example.com"

qbittorrent:
  url: "http://qbittorrent-ui.media.svc.cluster.local:8080"
  poll_interval_seconds: 5
  max_polling_minutes: 30
  stalled_timeout_minutes: 15
  webui_public_url: "https://downloads.example.com"

notifications:
  telegram:
    enabled: true
    bot_token: "${TELEGRAM_BOT_TOKEN}"
    chat_id: "-1002232887588"
    rate_limit_per_minute: 30
```
