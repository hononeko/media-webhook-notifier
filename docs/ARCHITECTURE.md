# Architecture & Engineering Standards (`ARCHITECTURE.md`)
## Hexagonal Architecture, Arrow-kt & Concurrency Model

`media-webhook-notifier` is structured strictly around **Hexagonal Architecture (Ports and Adapters)** under the **`hononeko`** GitHub organization (`group = "app.hononeko.notifier"`).

This guarantees that business rules (media tracking, card generation, notification routing) are 100% decoupled from transport layers (Ktor HTTP server), notification vendors (Telegram API, Ntfy), and download clients (qBittorrent).

---

## 1. Hexagonal Layer Hierarchy

```
                               ┌─────────────────────────────────────────┐
                               │           Driving Adapters              │
                               │  (HTTP Inbound / Strategy Registry)     │
                               │  - WebhookProviderRegistry              │
                               │  - Sonarr / Radarr / Servarr Providers  │
                               │  - Plex / Jellyfin Providers            │
                               │  - SchemaController / Health            │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │             Inbound Ports               │
                               │  - IngestWebhookUseCase                 │
                               │  - TrackDownloadUseCase                 │
                               │  - AnnounceMediaImportedUseCase         │
                               │  - AnnounceMediaAvailableUseCase        │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │              Domain Core                │
                               │  - Models: MediaPayload,                │
                               │    DownloadItemProgress,                │
                               │    NotificationCard, ActionLink         │
                               │  - Use Cases & Domain Services:         │
                               │    * DownloadTrackerEngine              │
                               │    * CardFormatterService               │
                               │    * SeasonDebouncer                    │
                               │    * IngestWebhookService               │
                               │  - Arrow-kt Typed Errors & Domain Rules │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │             Outbound Ports              │
                               │  - DownloadClientPort                   │
                               │  - NotificationPublisherPort            │
                               │  - MediaServerPort                      │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │            Driven Adapters              │
                               │  - DownloadClientFactory (selects one)  │
                               │  - QBittorrentClientAdapter (Ktor HTTP) │
                               │  - TransmissionClientAdapter (Ktor RPC) │
                               │  - TelegramPublisherAdapter (Bot API)   │
                               │  - DiscordPublisherAdapter              │
                               │  - PlexMetadataAdapter                  │
                               │  - JellyfinMetadataAdapter              │
                               └─────────────────────────────────────────┘
```

---

## 2. Package & Source Directory Structure

* **Artifact Group:** `app.hononeko.notifier`
* **Repository:** `https://github.com/hononeko/media-webhook-notifier`
* **Container Registry:** `ghcr.io/hononeko/media-webhook-notifier`

```
media-webhook-notifier/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── Dockerfile
└── src/
    ├── main/
    │   ├── kotlin/
    │   │   └── app/hononeko/notifier/
    │   │       ├── Application.kt                # Application entrypoint & dependency wiring
    │   │       ├── config/                       # Type-safe configuration models
    │   │       │   ├── AppConfig.kt
    │   │       │   └── ConfigLoader.kt
    │   │       │
    │   │       ├── domain/                       # Core Domain (Zero framework dependencies)
    │   │       │   ├── model/
    │   │       │   │   ├── MediaPayload.kt       # Unified media representation
    │   │       │   │   ├── DownloadItemProgress.kt # Id, percent, ETA, speed, state, swarm/usenet stats
    │   │       │   │   ├── NotificationCard.kt   # Header, body, artwork, buttons
    │   │       │   │   └── AppSource.kt          # Sonarr, Radarr, Plex, Jellyfin
    │   │       │   ├── error/
    │   │       │   │   └── DomainError.kt        # Arrow-kt typed error hierarchy
    │   │       │   ├── port/
    │   │       │   │   ├── inbound/              # Primary / Driving Ports (Use Cases)
    │   │       │   │   │   ├── IngestWebhookUseCase.kt
    │   │       │   │   │   ├── TrackDownloadUseCase.kt
    │   │       │   │   │   ├── AnnounceMediaImportedUseCase.kt
    │   │       │   │   │   └── AnnounceMediaAvailableUseCase.kt
    │   │       │   │   └── outbound/             # Secondary / Driven Ports
    │   │       │   │       ├── DownloadClientPort.kt
    │   │       │   │       ├── NotificationPublisherPort.kt
    │   │       │   │       └── MediaServerPort.kt
    │   │       │   └── service/                  # Core Business Services
    │   │       │       ├── DownloadTrackerEngine.kt
    │   │       │       ├── CardFormatterService.kt
    │   │       │       ├── SeasonDebouncer.kt
    │   │       │       ├── IngestWebhookService.kt
    │   │       │       ├── MediaImportedService.kt
    │   │       │       └── MediaAvailableService.kt
    │   │       │
    │   │       └── adapter/                      # Adapters (Framework & Vendor specific)
    │   │           ├── inbound/
    │   │           │   └── web/                  # Ktor HTTP Handlers
    │   │           │       ├── WebhookRoutes.kt
    │   │           │       ├── AuthGuard.kt
    │   │           │       ├── InboundRateLimiter.kt
    │   │           │       ├── EventRail.kt
    │   │           │       ├── provider/             # Extensible Webhook Provider Strategies
    │   │           │       │   ├── WebhookProviderStrategy.kt
    │   │           │       │   ├── WebhookProviderRegistry.kt
    │   │           │       │   ├── AbstractServarrWebhookProvider.kt
    │   │           │       │   ├── SonarrWebhookProvider.kt
    │   │           │       │   ├── RadarrWebhookProvider.kt
    │   │           │       │   ├── ServarrWebhookProvider.kt
    │   │           │       │   ├── PlexWebhookProvider.kt
    │   │           │       │   └── JellyfinWebhookProvider.kt
    │   │           │       └── controller/
    │   │           │           └── SchemaController.kt
    │   │           └── outbound/
    │   │               ├── downloadclient/
    │   │               │   └── DownloadClientFactory.kt
    │   │               ├── qbittorrent/
    │   │               │   └── QBittorrentClientAdapter.kt
    │   │               ├── transmission/
    │   │               │   └── TransmissionClientAdapter.kt
    │   │               ├── telegram/
    │   │               │   └── TelegramPublisherAdapter.kt
    │   │               └── mediaserver/
    │   │                   └── MediaServerAdapter.kt
    │   └── resources/
    │       ├── application.yaml
    │       └── logback.xml
    └── test/
        └── kotlin/app/hononeko/notifier/...
```

---

## 3. Declarative Error Handling with Arrow-kt

We avoid throwing unchecked runtime exceptions across domain boundaries. All domain operations and port calls return Arrow's `Either<DomainError, A>`.

### 3.1 Domain Error Hierarchy (`DomainError.kt`)
* **`WebhookError`**: `Unauthorized`, `InvalidPayload`, `UnsupportedEventType`, `MissingTorrentHash`.
* **`DownloadClientError`**: `ConnectionFailed`, `DownloadNotFound`, `AuthenticationFailed`, `InvalidResponse`.
* **`NotificationError`**: `RateLimited(retryAfterSeconds)`, `DeliveryFailed`, `ImageFetchFailed`, `ChatNotFound`.

---

## 4. Single-Instance Topology & In-Memory Event Rail

### 4.1 1:1:1 Single-Purpose Microservice Model
To maintain minimal resource consumption (~35-40 MB RSS memory under load), each running container instance operates on a **1:1:1** mapping:
* **1 Download Client:** `qBittorrent` or `Transmission` instance, selected via `DOWNLOAD_CLIENT_TYPE` / `DOWNLOAD_CLIENT_URL` (SABnzbd is recognised but not yet supported).
* **1 Media Server:** `Plex` or `Jellyfin` instance (configured via `mediaServer.type`).
* **1 Destination Notification Sink:** (e.g. 1 Telegram chat / topic).

*Scaling / Multiple destinations:* To notify multiple distinct chats (e.g. separate 4K vs anime feeds) or bind multiple download clients, deploy additional container instances with their own lightweight environment variable configs.

### 4.2 In-Memory Event Rail (`kotlinx.coroutines.channels.Channel`)
Rather than introducing heavy external brokers (Kafka, RabbitMQ, Redis), the application leverages an in-process, lock-free, zero-overhead event rail built on **Kotlin Coroutine Channels**:

```
[Sonarr / Radarr / Plex]
           │
           │  HTTP POST /webhook/servarr
           ▼
┌────────────────────────────────────────────────────────┐
│ Ktor Inbound Webhook Controller                        │
│ 1. Validate Secret Token & Deserialization             │
│ 2. eventChannel.trySend(event)                         │
│ 3. Return HTTP 202 Accepted (< 2ms)                    │
└──────────────────────────┬─────────────────────────────┘
                           │
                           │  kotlinx.coroutines Channel<MediaPayload>
                           ▼
┌────────────────────────────────────────────────────────┐
│ Event Rail Consumer Worker (SupervisorScope)           │
│ - Buffers bursts and handles backpressure              │
│ - Dispatches to IngestWebhookService                   │
│   ├── SeasonDebouncer (5s sliding window batching)     │
│   ├── DownloadTrackerEngine (Supervised async loop)    │
│   ├── MediaImportedService                             │
│   └── MediaAvailableService                            │
└────────────────────────────────────────────────────────┘
```

#### Key Advantages:
1. **Sub-2ms Ingest Response (`202 Accepted`):** Webhook callers receive immediate HTTP responses without waiting for external API latency (Telegram Bot API, qBit WebUI).
2. **Backpressure & Burst Absorption:** If Telegram returns a `429 RateLimited` response or outbound network I/O lags, the channel safely buffers incoming events without dropping payloads or tying up HTTP server threads.
3. **Graceful Shutdown Drain:** On `SIGTERM` / `SIGINT`, the inbound channel closes, pending events are drained and dispatched, active trackers terminate safely, and the process exits within 5 seconds.

### 4.3 Dead Letter Queue & Replay
Payloads the rail cannot process land in a bounded `DeadLetterRingBuffer` (default 100 entries, oldest evicted first):
* **Rail rejections:** the urgent or standard channel is full or closed when `publish` is called.
* **Ingest failures:** `IngestWebhookService` returns a `DomainError` (e.g. Telegram unreachable) or throws. The error message is stored with a stack trace from the exception or the domain error's `cause`, capped at 8 KiB.

Each entry has a stable id, an `attemptCount` and a `pending` / `resolved` status. `EventRail.replayDeadLetter(id)` re-queues the stored `MediaPayload` with `attemptCount + 1` and marks the entry `resolved` atomically, so concurrent replays dispatch once. If the rail is full the entry stays `pending`. A replay that fails again is captured as a new entry with the incremented attempt count.

When the Valkey state store is enabled, `DeadLetterPersistence` mirrors the buffer into a single snapshot key (`<STATE_KEY_PREFIX>dlq:snapshot`) from a conflated background writer and restores it on startup, so dead letters survive restarts. Raw Plex multipart thumbnails (`artworkBytes`) are not persisted; replays fall back to poster URLs. The admin HTTP API is described in the README (`/api/v1/dlq`).
