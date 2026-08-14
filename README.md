# Media Webhook Notifier (`media-webhook-notifier`)

[![Validate & Test](https://github.com/hononeko/media-webhook-notifier/actions/workflows/validate.yml/badge.svg)](https://github.com/hononeko/media-webhook-notifier/actions/workflows/validate.yml)
[![Container Image](https://img.shields.io/badge/GHCR-ghcr.io%2Fhononeko%2Fmedia--webhook--notifier-blue?logo=docker)](https://github.com/hononeko/media-webhook-notifier/pkgs/container/media-webhook-notifier)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![GraalVM](https://img.shields.io/badge/GraalVM-Native%20Image-E85F00?logo=oracle)](https://www.graalvm.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A high-performance, native microservice built with **Kotlin**, **Coroutines**, and **Hexagonal Architecture (Ports and Adapters)**, compiled ahead-of-time (AOT) with **GraalVM Native Image** into a static, distroless container image.

It ingests webhooks from **Sonarr**, **Radarr**, **Plex**, and **Jellyfin**, monitors **qBittorrent** in real-time to dispatch interactive status cards with **live in-place progress updates** to **Telegram** (and future notification sinks).

---

## 🌟 Key Features

* **⚡ Ultra-Low Footprint:** Instant cold startup (<25ms) and tiny memory footprint (<25 MB RSS) via GraalVM Native Image on Distroless Debian 12.
* **⏳ Live In-Place Telegram Updates:** Monitors active torrent downloads in qBittorrent and silently updates the Telegram status card every few seconds (ASCII progress bar, ETA, speeds, peers, WebUI deep links).
* **🛡️ Sliding Season Debouncer:** Automatically batches rapid multi-episode grabs sharing the same `downloadId` within a 5-second sliding window to eliminate chat spam.
* **🎬 Native Plex & Jellyfin Integration:** Directly ingests Plex `library.new` and Jellyfin `ItemAdded` events with rich metadata, TMDB/TVDB posters, and direct playback links without requiring Tautulli.
* **🔒 Dual Authentication Guard:** Secures webhook endpoints via HTTP Headers (`Authorization: Bearer <token>`, `X-Api-Key: <token>`) or Query Parameters (`?token=<token>`, `?apikey=<token>`).
* **🚦 Cloud-Native Kubernetes Probes:** Out-of-the-box `/livez`, `/readyz` (with graceful queue draining degradation), `/startupz`, and `/metrics` telemetry.
* **🧩 Provider Strategy & Schema Registry:** Extensible webhook provider strategies with externalized JSON schemas served at `/schema/{provider}`.

---

## 🚀 Quick Start with Docker

### Option 1: Docker CLI
```bash
docker run -d \
  --name media-webhook-notifier \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e SERVER_AUTH_TOKEN="your-secure-secret-token" \
  -e NOTIFICATIONS_TELEGRAM_BOT_TOKEN="123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ" \
  -e NOTIFICATIONS_TELEGRAM_CHAT_ID="-1001234567890" \
  -e QBITTORRENT_URL="http://qbittorrent:8080" \
  -e QBITTORRENT_USERNAME="admin" \
  -e QBITTORRENT_PASSWORD="adminadmin" \
  -e MEDIA_SERVER_BASE_URL="https://plex.example.com" \
  ghcr.io/hononeko/media-webhook-notifier:latest
```

### Option 2: Docker Compose (`docker-compose.yml`)
```yaml
services:
  media-webhook-notifier:
    image: ghcr.io/hononeko/media-webhook-notifier:latest
    container_name: media-webhook-notifier
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - SERVER_PORT=8080
      - SERVER_AUTH_TOKEN=your-secure-secret-token
      - SERVER_RATE_LIMIT_PER_MINUTE=120
      - NOTIFICATIONS_TELEGRAM_ENABLED=true
      - NOTIFICATIONS_TELEGRAM_BOT_TOKEN=123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ
      - NOTIFICATIONS_TELEGRAM_CHAT_ID=-1001234567890
      - NOTIFICATIONS_TELEGRAM_TOPIC_ID=  # Optional: For Telegram forum supergroups
      - QBITTORRENT_URL=http://qbittorrent:8080
      - QBITTORRENT_USERNAME=admin
      - QBITTORRENT_PASSWORD=adminadmin
      - QBITTORRENT_WEBUI_PUBLIC_URL=https://downloads.example.com
      - MEDIA_SERVER_TYPE=plex # "plex" or "jellyfin"
      - MEDIA_SERVER_BASE_URL=https://plex.example.com
```

---

## ☸️ Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: media-webhook-notifier
  namespace: media
spec:
  replicas: 1
  selector:
    matchLabels:
      app: media-webhook-notifier
  template:
    metadata:
      labels:
        app: media-webhook-notifier
    spec:
      containers:
        - name: notifier
          image: ghcr.io/hononeko/media-webhook-notifier:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: SERVER_AUTH_TOKEN
              valueFrom:
                secretKeyRef:
                  name: notifier-secrets
                  key: auth-token
            - name: NOTIFICATIONS_TELEGRAM_BOT_TOKEN
              valueFrom:
                secretKeyRef:
                  name: notifier-secrets
                  key: telegram-bot-token
            - name: NOTIFICATIONS_TELEGRAM_CHAT_ID
              value: "-1001234567890"
            - name: QBITTORRENT_URL
              value: "http://qbittorrent.media.svc.cluster.local:8080"
            - name: QBITTORRENT_USERNAME
              value: "admin"
            - name: QBITTORRENT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: qbittorrent-secrets
                  key: webui-password
          livenessProbe:
            httpGet:
              path: /livez
              port: http
            initialDelaySeconds: 2
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /readyz
              port: http
            initialDelaySeconds: 2
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /startupz
              port: http
            initialDelaySeconds: 1
            periodSeconds: 2
          resources:
            requests:
              cpu: 10m
              memory: 32Mi
            limits:
              cpu: 500m
              memory: 128Mi
```

---

## 📡 Inbound Webhook Configuration Guide

Point your media applications to the corresponding provider endpoints:

### 1. Sonarr (TV Shows & Anime)
* **URL:** `http://<notifier-host>:8080/api/v1/webhook/sonarr?token=<your-token>`
* **Method:** `POST`
* **Notification Triggers:**
  * `On Grab`: Triggers live download progress tracking.
  * `On Download` / `On Upgrade`: Sends media imported notification.
  * `On Rename`: Supported.
  * `Include Health Warnings`: Optional.

### 2. Radarr (Movies)
* **URL:** `http://<notifier-host>:8080/api/v1/webhook/radarr?token=<your-token>`
* **Method:** `POST`
* **Notification Triggers:**
  * `On Grab`: Triggers live download progress tracking.
  * `On Download` / `On Upgrade`: Sends movie imported notification.

### 3. Plex Media Server
* **URL:** `http://<notifier-host>:8080/api/v1/webhook/plex?token=<your-token>`
* **Plex Settings:** Add webhook URL under **Plex Settings &rarr; Webhooks**.
* **Events Handled:** `library.new` (dispatches "Now Available" card with playback deep link).

### 4. Jellyfin / Emby
* **URL:** `http://<notifier-host>:8080/api/v1/webhook/jellyfin?token=<your-token>`
* **Jellyfin Settings:** Configure via the **Webhook Plugin** &rarr; select `ItemAdded`.

---

## ⚙️ Configuration Reference

All settings can be configured via environment variables or `application.yaml`:

| Environment Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port the server listens on |
| `SERVER_AUTH_TOKEN` | `""` | Secret token(s). Comma-separated whitelist allowed (`"t1,t2"`) |
| `SERVER_RATE_LIMIT_PER_MINUTE` | `120` | Inbound rate limit per IP/caller (`<= 0` disables limit) |
| `NOTIFICATIONS_TELEGRAM_ENABLED` | `true` | Enable or disable Telegram notifications |
| `NOTIFICATIONS_TELEGRAM_BOT_TOKEN` | `""` | Telegram Bot API token from `@BotFather` |
| `NOTIFICATIONS_TELEGRAM_CHAT_ID` | `""` | Target chat or channel ID (e.g. `-1001234567890`) |
| `NOTIFICATIONS_TELEGRAM_TOPIC_ID` | `null` | Optional Telegram Forum topic thread ID |
| `NOTIFICATIONS_TELEGRAM_RATE_LIMIT_PER_MINUTE` | `30` | Outbound rate limit to prevent Telegram API 429 errors |
| `NOTIFICATIONS_TELEGRAM_SEND_PHOTOS` | `true` | Send poster images with fallback to HTML text on failure |
| `QBITTORRENT_URL` | `http://localhost:8080` | Internal URL to qBittorrent WebUI |
| `QBITTORRENT_USERNAME` | `""` | qBittorrent WebUI username |
| `QBITTORRENT_PASSWORD` | `""` | qBittorrent WebUI password |
| `QBITTORRENT_POLL_INTERVAL_SECONDS` | `5` | Download progress polling interval in seconds |
| `QBITTORRENT_MAX_POLLING_MINUTES` | `30` | Maximum time to track an active download |
| `QBITTORRENT_STALLED_TIMEOUT_MINUTES` | `15` | Timeout before flagging a download as stalled |
| `QBITTORRENT_WEBUI_PUBLIC_URL` | `""` | Optional public URL to qBittorrent WebUI for card buttons |
| `MEDIA_SERVER_TYPE` | `plex` | Active media server (`plex` or `jellyfin`) |
| `MEDIA_SERVER_BASE_URL` | `""` | Base URL of media server |
| `MEDIA_SERVER_PLEX_PUBLIC_URL` | `""` | Optional override for Plex Web client URL |
| `MEDIA_SERVER_JELLYFIN_PUBLIC_URL` | `""` | Optional override for Jellyfin Web client URL |

---

## 🔍 API & Probes Reference

### Inbound Webhook Endpoints
* `POST /api/v1/webhook/{provider}` (Header or Query token authentication)
* `POST /api/v1/webhook/{token}/{provider}` (Path-embedded token authentication)
* **Supported Providers:** `sonarr`, `radarr`, `servarr`, `arr`, `plex`, `jellyfin`, `emby`.

### Health & Kubernetes Probes
* `GET /livez`, `GET /health/live` &rarr; `200 OK` (Liveness probe)
* `GET /readyz`, `GET /health/ready` &rarr; `200 OK` / `503 Service Unavailable` (Readiness probe with queue draining degradation)
* `GET /startupz`, `GET /health/startup` &rarr; `200 OK` (Startup probe)
* `GET /health`, `GET /healthz` &rarr; `200 OK` (General service health)
* `GET /metrics` &rarr; `200 OK` (Runtime telemetry: memory usage, uptime, active trackers, event queue state)

### JSON Schemas
* `GET /schema/sonarr` &rarr; Sonarr Webhook JSON Schema
* `GET /schema/radarr` &rarr; Radarr Webhook JSON Schema
* `GET /schema/servarr` &rarr; Servarr Generic JSON Schema
* `GET /schema/plex` &rarr; Plex Webhook JSON Schema
* `GET /schema/jellyfin` &rarr; Jellyfin Webhook JSON Schema

---

## 🛠️ Development & Quality Gates

This repository enforces strict hexagonal architecture boundaries, zero framework dependencies in the domain core, and automated SemVer conventional commits.

```bash
# Run code formatting & linter
./gradlew ktlintCheck
./gradlew ktlintFormat

# Run full test suite with coverage report
./gradlew test jacocoTestReport

# Verify complete build & architecture rules
./gradlew check
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
