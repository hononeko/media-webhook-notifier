# Architecture & Engineering Standards (`ARCHITECTURE.md`)
## Hexagonal Architecture, Arrow-kt & Concurrency Model

`media-webhook-notifier` is structured strictly around **Hexagonal Architecture (Ports and Adapters)** under the **`hononeko`** GitHub organization (`group = "app.hononeko.notifier"`).

This guarantees that business rules (media tracking, card generation, notification routing) are 100% decoupled from transport layers (Ktor HTTP server), notification vendors (Telegram API, Ntfy), and download clients (qBittorrent).

---

## 1. Hexagonal Layer Hierarchy

```
                               ┌─────────────────────────────────────────┐
                               │           Driving Adapters              │
                               │  (HTTP Inbound / Webhook Controllers)   │
                               │  - ServarrWebhookController             │
                               │  - PlexWebhookController                │
                               │  - JellyfinWebhookController            │
                               │  - SchemaDocumentationController        │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │             Inbound Ports               │
                               │  - IngestWebhookPort                    │
                               │  - HandleMediaEventPort                 │
                               │  - QueryDownloadStatusPort              │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │              Domain Core                │
                               │  - Models: MediaItem, TorrentState,     │
                               │    NotificationCard, DeepLink           │
                               │  - Use Cases:                           │
                               │    * TrackActiveDownloadUseCase         │
                               │    * ProcessMediaAvailableUseCase       │
                               │    * GroupSeasonReleaseUseCase          │
                               │  - Arrow-kt Typed Errors & Domain Rules │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │             Outbound Ports              │
                               │  - TorrentClientPort                    │
                               │  - NotificationPublisherPort            │
                               │  - MediaServerMetadataPort              │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │            Driven Adapters              │
                               │  - QBittorrentClientAdapter (Ktor HTTP) │
                               │  - TelegramPublisherAdapter (Bot API)   │
                               │  - NtfyPublisherAdapter (Optional sink) │
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
    │   │       │   │   ├── TorrentProgress.kt    # Hash, percent, ETA, speed, state
    │   │       │   │   ├── NotificationCard.kt   # Header, body, artwork, buttons
    │   │       │   │   └── AppSource.kt          # Sonarr, Radarr, Plex, Jellyfin
    │   │       │   ├── error/
    │   │       │   │   └── DomainError.kt        # Arrow-kt typed error hierarchy
    │   │       │   ├── port/
    │   │       │   │   ├── inbound/              # Primary / Driving Ports (Use Cases)
    │   │       │   │   │   ├── IngestWebhookUseCase.kt
    │   │       │   │   │   ├── TrackDownloadUseCase.kt
    │   │       │   │   │   └── AnnounceMediaAvailableUseCase.kt
    │   │       │   │   └── outbound/             # Secondary / Driven Ports
    │   │       │   │       ├── TorrentClientPort.kt
    │   │       │   │       ├── NotificationPublisherPort.kt
    │   │       │   │       └── MediaServerPort.kt
    │   │       │   └── service/                  # Core Business Services
    │   │       │       ├── DownloadTrackerEngine.kt
    │   │       │       ├── CardFormatterService.kt
    │   │       │       └── SeasonDebouncer.kt
    │   │       │
    │   │       └── adapter/                      # Adapters (Framework & Vendor specific)
    │   │           ├── inbound/
    │   │           │   └── web/                  # Ktor HTTP Handlers
    │   │           │       ├── Routing.kt
    │   │           │       ├── ServarrWebhookHandler.kt
    │   │           │       ├── PlexWebhookHandler.kt
    │   │           │       ├── JellyfinWebhookHandler.kt
    │   │           │       └── SchemaHandler.kt
    │   │           └── outbound/
    │   │               ├── torrent/
    │   │               │   └── QBittorrentClient.kt
    │   │               ├── telegram/
    │   │               │   ├── TelegramClient.kt
    │   │               │   └── TelegramPublisherAdapter.kt
    │   │               ├── ntfy/
    │   │               │   └── NtfyPublisherAdapter.kt
    │   │               └── mediaserver/
    │   │                   ├── PlexAdapter.kt
    │   │                   └── JellyfinAdapter.kt
    │   └── resources/
    │       ├── application.conf
    │       └── logback.xml
    └── test/
        └── kotlin/app/hononeko/notifier/...
```

---

## 3. Declarative Error Handling with Arrow-kt

We avoid throwing unchecked runtime exceptions across domain boundaries. All domain operations and port calls return Arrow's `Either<DomainError, A>` or use the `Raise<DomainError>` DSL.

### 3.1 Domain Error Hierarchy (`DomainError.kt`)
```kotlin
package app.hononeko.notifier.domain.error

sealed interface DomainError {
    // Inbound / Webhook Errors
    sealed interface WebhookError : DomainError {
        data class Unauthorized(val reason: String) : WebhookError
        data class InvalidPayload(val details: String) : WebhookError
        data class UnsupportedEventType(val event: String) : WebhookError
        data object MissingTorrentHash : WebhookError
    }

    // Torrent Client Errors
    sealed interface TorrentClientError : DomainError {
        data class ConnectionFailed(val url: String, val cause: Throwable) : TorrentClientError
        data class TorrentNotFound(val hash: String) : TorrentClientError
        data class AuthenticationFailed(val reason: String) : TorrentClientError
    }

    // Notification Sinks Errors
    sealed interface NotificationError : DomainError {
        data class RateLimited(val retryAfterSeconds: Int) : NotificationError
        data class DeliveryFailed(val provider: String, val message: String) : NotificationError
        data class ImageFetchFailed(val url: String) : NotificationError
    }
}
```

### 3.2 Use Case Execution with Arrow Raise DSL
```kotlin
package app.hononeko.notifier.domain.service

import app.hononeko.notifier.domain.error.DomainError
import app.hononeko.notifier.domain.model.*
import app.hononeko.notifier.domain.port.outbound.*
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

class IngestWebhookService(
    private val trackerEngine: DownloadTrackerEngine,
    private val publisher: NotificationPublisherPort
) {
    suspend fun handleGrabEvent(payload: ServarrGrabPayload): Either<DomainError, Unit> = either {
        val hash = payload.downloadId?.trim()?.lowercase()
        ensure(!hash.isNullOrBlank()) { DomainError.WebhookError.MissingTorrentHash }
        
        // Spawn tracking coroutine safely
        trackerEngine.startTracking(hash, payload)
    }
}
```

---

## 4. Concurrency & Live Polling Engine

1. **Structured Concurrency:**
   - The `DownloadTrackerEngine` maintains an internal `ConcurrentHashMap<String, Job>` of active trackers.
   - All jobs are tied to a supervised `trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
2. **Channel-Based Rate Limiting:**
   - Outbound calls to the Telegram Bot API pass through an internal `Channel<TelegramRequest>` backed by a token-bucket rate limiter coroutine (30 messages/min cap, max 1 edit/sec per chat).
3. **Graceful Termination:**
   - On `SIGTERM` / `SIGINT`, the application cancels all active tracking jobs, flushes final status updates, and cleanly shuts down Ktor within 5 seconds.
