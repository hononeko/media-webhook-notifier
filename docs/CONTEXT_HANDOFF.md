# Context Handoff & Implementation Reference (`CONTEXT_HANDOFF.md`)

This document captures all operational learnings, tested endpoints, edge-case discoveries, and configuration values from the live n8n workflow testing to serve as the definitive blueprint for the Kotlin implementation.

---

## 1. Verified Homelab Environment & Endpoints

| Service | Internal Kubernetes Endpoint | Public / Ingress URL | Notes |
| :--- | :--- | :--- | :--- |
| **qBittorrent WebUI** | `http://qbittorrent-ui.media.svc.cluster.local:8080` | `https://downloads.kerrlab.app` | Zero auth needed on internal cluster network. |
| **Plex Media Server** | `http://plex.media.svc.cluster.local:32400` | `https://plex.kerrlab.app` | Native webhook sends `library.new` multipart payload. |
| **Jellyfin** | `http://jellyfin.media.svc.cluster.local:8096` | `https://jellyfin.kerrlab.app` | Webhook plugin sends `ItemAdded` JSON. |
| **Sonarr (TV)** | `http://sonarr-tv.media.svc.cluster.local:8989` | `https://sonarr.kerrlab.app` | `instanceName: "Sonarr"` |
| **Sonarr (Anime)** | `http://sonarr-anime.media.svc.cluster.local:8989` | `https://sonarr-anime.kerrlab.app` | `instanceName: "Sonarr Anime"` |
| **Radarr (Movies)** | `http://radarr.media.svc.cluster.local:7878` | `https://radarr.kerrlab.app` | `instanceName: "Radarr"` |
| **Telegram Channel** | N/A | Chat ID: `-1002232887588` | `✨ Homelab Media ✨` channel. |
| **API Auth Secret** | N/A | `31eb769648d7ca6fabf89377cd781ac8b940230ab74335301ad4e4db1f1fda7e6eac1d8ce4b77a98897c6dcd2054ab28` | Pre-shared token for Header & Query Auth. |

---

## 2. Hard-Earned Edge Cases & Critical Lessons

### 2.1 qBittorrent API Quirks
* **Empty `hashes` Parameter Behavior:** 
  Calling `GET /api/v2/torrents/info?hashes=` without a hash returns **every torrent in the entire client**. The adapter must enforce `require(hash.isNotBlank())` and filter response arrays strictly with `response.hash.equals(hash, ignoreCase = true)`.
* **Metadata Allocation Grace Period:** 
  Newly added torrents take 5–15 seconds for qBittorrent to allocate metadata and fetch initial piece info. The engine must allow 6 retry attempts (`~30s`) showing `Waiting for qBittorrent to allocate metadata...` before assuming the torrent is missing.
* **Completion States:**
  A download is considered 100% complete when:
  `progress >= 1.0` OR `state in ["completed", "uploading", "stalledUP"]`.
* **Stall Detection:**
  A download is flagged as stalled when `state == "stalledDL"` OR (`dlspeed == 0` AND progress unchanged for 15 minutes / 180 ticks).

---

### 2.2 Telegram Bot API Handling
* **Rate Limits:**
  Telegram limits bots to **1 message edit/second per chat** and **~30 edits/minute per chat**. 
  - Individual polling rate: **5 seconds** per active download.
  - Multi-download concurrency: A token-bucket rate limiter channel prevents exceeding 25 edits/minute total.
* **Photo CDN Fetch Failure Fallback:**
  Telegram servers occasionally fail to fetch remote image URLs from TVDB/TMDB CDNs (`400 Bad Request: failed to get HTTP URL content`). The `TelegramPublisherAdapter` must catch image delivery errors and **automatically fall back to sending a text card (`sendMessage`)** so notifications are never dropped.
* **HTML Caption Limits:**
  Telegram captions are capped at 1024 characters. Summaries / overviews should be safely truncated to ~220 characters with balanced HTML tags.

---

### 2.3 Season Pack Batching / Debouncing
* When Sonarr grabs a season pack or multiple episodes at once, it fires separate `Grab` webhooks in rapid succession with the same torrent `downloadId`.
* The `DownloadTrackerEngine` must implement an in-memory debounce window (`5s`) that consolidates multiple episode grabs sharing the same hash into a single tracking card (e.g. `Severance - S02E01-E10`).

---

## 3. Formatting Helpers Reference

### Progress Bar (10 Chars):
```kotlin
fun drawProgressBar(percent: Int, length: Int = 10): String {
    val clamped = percent.coerceIn(0, 100)
    val completed = (clamped * length) / 100
    val remaining = length - completed
    return "█".repeat(completed) + "░".repeat(remaining)
}
```

### ETA Duration Formatter:
```kotlin
fun formatDuration(seconds: Long): String = when {
    seconds < 0 || seconds >= 8640000 -> "∞"
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}
```
