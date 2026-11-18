# Data-Driven Card Templates & Tags Reference (`docs/TEMPLATES.md`)

This guide explains how to customize notification cards and live download progress updates using declarative YAML templates.

---

## 1. Quick Start & Configuration

By default, **`media-webhook-notifier`** ships with curated, built-in card layouts for all events. You only need a template file if you wish to override or customize the text, layout, language, or styling.

A complete reference of all built-in card layouts is available in [src/main/resources/templates.default.yaml](../src/main/resources/templates.default.yaml). You can copy it as a baseline to start customizing:
```bash
cp src/main/resources/templates.default.yaml templates.yaml
```

### 1.1 Mounting Templates via Environment Variables
Specify the template location via environment variables or file mounts:

```bash
# Path to a mounted YAML file (Docker / Kubernetes ConfigMap)
TEMPLATES_FILE=/config/templates.yaml

# Alternative accepted environment variables
TEMPLATES_FILE_PATH=/config/templates.yaml
TEMPLATES_CONFIG_PATH=/config/templates.yaml

# Direct YAML string in environment variable
TEMPLATES_YAML="events:\n  grab:\n    title: '🎬 Custom: {title}'"
```

The application automatically checks for `templates.yaml` or `templates.yml` in the current working directory and `/config/templates.yaml`.

---

## 2. YAML Template Structure

The configuration consists of global `theme` options and per-event template definitions under `events`:

```yaml
# ==============================================================================
# Global Theme Settings
# ==============================================================================
theme:
  max_overview_length: 220       # Word-boundary truncation limit for {overview}
  progress_bar_length: 10        # Visual length of {progress_bar}
  progress_bar_style: "default"  # "default" (█/░) | "minimal" (=/-) | "circles" (●/○)
  date_format: "yyyy-MM-dd HH:mm"

# ==============================================================================
# Event Overrides
# ==============================================================================
# Events can be defined using a clean 1-level hierarchy (grouped by category)
# or in a flat structure (e.g. `grab:`, `request:`). Both work interchangeably.
#
# Optional event fields:
# - `enabled: true|false` (Turn an event off entirely; alias: `disabled: true`)
# - `image_embed: true|false` (Control photo attachment per event type)
# - `silent: true|false` (Send without sound/vibration; aliases: `silent_send`, `disable_notification`)
# - `chat_id: "-1001234567890"` (Route this event to a specific Telegram chat/channel ID)
# - `topic_id: 42` (Route to a forum topic ID; `0` clears a global topic; aliases: `thread_id`, `topic`)
# - `artwork_url: "{custom_url}"` (Custom artwork image URL)
# - `actions:` (Custom inline button links)
events:
  download:
    progress:
      title: "⏳ Downloading: {title}"
      subtitle: "{instance_name}"
      body: |
        <code>{progress_bar}</code> <b>{progress_percent}%</b>

        ▪ <b>Speed:</b> {speed} (ETA: {eta})
        ▪ <b>Transferred:</b> {size_formatted}
        ▪ <b>Peers:</b> {peers_info}
        ▪ <b>Status:</b> {state}

    complete:
      title: "✅ Download Complete: {title}"
      subtitle: "{instance_name}"
      body: |
        ▪ <b>Status:</b> 100% Downloaded
        ▪ <b>Total Size:</b> {total_size}
        ▪ <b>Quality:</b> {quality}

    stalled:
      title: "⚠️ Download Stalled: {title}"
      subtitle: "{instance_name}"
      body: |
        ▪ <b>Status:</b> Download Stalled (0 B/s)
        ▪ <b>Progress:</b> {progress_percent}% {progress_bar}

  servarr:
    grab:
      silent: true
      image_embed: true
      title: "⏳ Queueing Download: {title}"
      subtitle: "{instance_name}"
      body: |
        ▪ <b>Quality:</b> {quality}
        ▪ <b>Group:</b> {release_group}
        ▪ <b>Size:</b> {size}
        ▪ <b>Indexer:</b> {indexer}

    import:
      image_embed: false
      title: "{import_icon} {import_action}: {title}"
      subtitle: "{instance_name} • {import_type}"
      body: |
        ▪ <b>Specs:</b> {specs}
        ▪ <b>Size:</b> {size}

        <i>{overview}</i>

    manual_interaction:
      silent: false
      image_embed: false
      title: "✋ Manual Import Required: {title}"
      subtitle: "{instance_name} • Manual Intervention"
      body: |
        ▪ <b>Reason:</b> {reason}
        ▪ <b>Release:</b> {release_title}
        ▪ <b>Quality:</b> {quality}
        ▪ <b>Size:</b> {size}
        ▪ <b>Indexer:</b> {indexer}
        ▪ <b>Client:</b> {download_client}
      actions:
        - label: "📁 Open in {source_name}"
          url: "{web_url}"
          style: "PRIMARY"

    health:
      title: "{health_icon} {health_status}: {instance_name}"
      subtitle: "{instance_name} • {health_type}"
      body: |
        ▪ <b>Message:</b> {message}
        ▪ <b>Issue Type:</b> {issue_type}

  media_server:
    available:
      image_embed: true
      prefer_poster: false  # true: ignore the image Plex uploads with the webhook, use {poster_url}
      title: "🍿 {title} now available on {media_server_name}"
      body: |
        ▪ <b>Specs:</b> {specs}
        ▪ <b>Rating:</b> {rating}
        ▪ <b>Duration:</b> {duration}

        <i>{overview}</i>
      actions:
        - label: "🎬 Watch on {media_server_name}"
          url: "{deep_link_url}"
          style: "PRIMARY"

  seerr:
    request:
      image_embed: true
      title: "{request_icon} {request_action}: {subject}"
      subtitle: "{instance_name} • {request_status}"
      body: |
        ▪ <b>Requested By:</b> {requested_by}
        ▪ <b>Media Type:</b> {media_type}
        ▪ <b>Quality:</b> {quality}
        ▪ <b>Details:</b> {message}
      actions:
        - label: "🌐 Open in {source_name}"
          url: "{web_url}"
          style: "PRIMARY"

    issue:
      image_embed: false
      title: "{request_icon} {request_action}: {subject}"
      subtitle: "{instance_name} • {request_status}"
      body: |
        ▪ <b>Reported By:</b> {requested_by}
        ▪ <b>Issue Type:</b> {issue_type}
        ▪ <b>Issue Status:</b> {issue_status}
        ▪ <b>Comment:</b> {comment}
        ▪ <b>Details:</b> {message}
      actions:
        - label: "⚠️ View Issue in {source_name}"
          url: "{web_url}"
          style: "PRIMARY"
```

---

## 3. Available Tags Reference

Tags are placeholders surrounded by `{curly_braces}`. Below is the comprehensive list of tags, categorized by functionality with visual separators:

| Tag | Applicable Events | Description | Example Output |
| :--- | :--- | :--- | :--- |
| **Universal & Identity** | | | |
| `{instance_name}` | All Events | Resolved instance name (from header, query param, or fallback) | `Sonarr-4K`, `Kerrlab Plex`, `Home Jellyfin` |
| `{overview}` | Grab, Import, Available, Request | Synopsis or plot summary (truncated to `theme.max_overview_length`) | *Paul Atreides unites with Chani...* |
| `{poster_url}` | Grab, Import, Available, Request | URL to artwork poster (Season poster for seasons, with series fallback) | `https://image.tmdb.org/...` |
| `{source_name}` | All Events | Display name of originating service | `Sonarr`, `Radarr`, `Plex`, `Jellyfin`, `Overseerr` |
| `{web_url}` | Grab, Import, Manual, Request | Web link back to originating service activity | `http://sonarr.lan:8989/series/...` |
| `{year}` | Grab, Import, Available | Release or air year | `2024` |
| `---` | `---` | `---` | `---` |
| **Media & Episode Details** | | | |
| `{title}` | All Media Events | Full formatted title (e.g. `Futurama - Season 3`, `Dune: Part Two (2024)`) | `Futurama - Season 3` |
| `{item_title}` | Available | Direct raw item title from media server | `Season 3`, `Roswell That Ends Well` |
| `{series_title}` | Grab, Import, Manual, Available | Series title without season or episode suffix | `Futurama`, `Severance` |
| `{season}` | Grab, Import, Manual, Available | 2-digit zero-padded season number | `03` |
| `{season_number}` | Grab, Import, Manual, Available | Raw unpadded season number integer | `3` |
| `{season_title}` | Available | Season title label | `Season 3` |
| `{episode}` | Grab, Import, Manual, Available | 2-digit zero-padded episode number | `01` |
| `{episode_number}` | Grab, Import, Manual, Available | Raw unpadded episode number integer | `1` |
| `{episode_range}` | Grab, Import, Manual | Formatted episode range notation | `S02E01-E04` |
| `{episode_title}` | Import, Manual, Available | Episode title name | `Roswell That Ends Well` |
| `{episode_name}` | Import, Manual, Available | Alias for episode title name | `Roswell That Ends Well` |
| `{media_type}` | Available, Request | Media type (`season`, `episode`, `movie`, `show` in media servers) | `season`, `movie` |
| `{quality}` | Grab, Import, Manual, Request | Quality profile or resolution label | `WEBDL-1080p`, `Remux-2160p` |
| `{release_group}` | Grab, Import, Manual | Torrent/Usenet scene or release group | `FLUX`, `NTb`, `FraMeSToR` |
| `{release_title}` | Grab, Import, Manual | Original raw release filename | `Show.S01E01.1080p.mkv` |
| `---` | `---` | `---` | `---` |
| **Download & Client Metrics** | | | |
| `{client}` / `{download_client}` | Grab, Progress, Stalled, Manual | Download client identifier | `qBittorrent` |
| `{download_id}` | Grab, Progress, Complete, Stalled, Manual | Torrent hash or download identifier | `a1b2c3d4...` |
| `{downloaded_size}` | Progress, Stalled | Downloaded byte count formatted | `1.85 GB` |
| `{download_time}` / `{duration}` | Progress, Complete | Elapsed time or estimated completion time | `4m 12s` |
| `{eta}` | Progress | Estimated time remaining | `1m 45s` |
| `{indexer}` | Grab, Manual | Indexer or tracker name | `TorrentLeech`, `PTP` |
| `{peers_info}` | Progress | Connected seeds and peers formatted | `45 (120) seeds • 12 peers` |
| `{progress_bar}` | Progress, Stalled | Visual Unicode progress bar | `[███████░░░]` |
| `{progress_percent}` | Progress, Stalled | Progress percentage formatted to 2 decimals | `74.50` |
| `{size}` / `{total_size}` | Grab, Progress, Complete, Import, Manual | Total file/torrent size formatted | `4.25 GB` |
| `{speed}` | Progress | Transfer speed formatted | `25.4 MB/s` |
| `{state}` | Progress | Current status label | `Downloading`, `Stalled` |
| `{webui_url}` | Grab, Progress, Complete, Stalled | Public WebUI link for download client | `http://qbittorrent.lan:8080` |
| `---` | `---` | `---` | `---` |
| **Media Server & Playback** | | | |
| `{audio_codec}` | Import, Available | Audio codec and audio channels | `EAC3 5.1`, `TrueHD Atmos` |
| `{deep_link_url}` | Available | Direct playback/detail link in media server | `https://app.plex.tv/desktop...` |
| `{duration}` | Available | Media playback runtime (omitted on season-level notifications) | `1h 45m` |
| `{media_server_name}` | Available | Display name of configured media server | `Plex Media Server`, `Jellyfin` |
| `{rating}` / `{score}` | Available | Community rating formatted | `8.6/10` |
| `{resolution}` | Import, Available | Resolution label | `2160p (4K)`, `1080p` |
| `{video_codec}` | Import, Available | Video codec | `HEVC (H.265)`, `AVC (H.264)` |
| `---` | `---` | `---` | `---` |
| **Requests & System Health** | | | |
| `{health_icon}` | Health | Status emoji (`✅`, `🚨`, `⚠️`) | `🚨` |
| `{health_status}` | Health | Health status classification | `Health Error`, `Health Warning` |
| `{health_type}` | Health | Underlying health check category | `DiskSpace`, `DownloadClient` |
| `{issue_status}` | Request Issues | Issue state | `Open`, `Resolved` |
| `{issue_type}` | Health, Request Issues | Issue classification | `Video`, `Audio`, `Subtitles` |
| `{message}` | Health, Request Comments | Diagnostic or user comment message | `Indexers are unreachable` |
| `{reason}` | Manual Interaction | Rejection reason from Servarr | `Quality profile not matched` |
| `{request_action}` | Request | Action verb | `Approved`, `Available`, `Declined` |
| `{request_icon}` | Request | Action emoji | `🛎️`, `✅`, `🍿`, `❌` |
| `{requested_by}` | Request | Username who initiated request | `alice` |

---

## 4. Smart Rules & Formatting Engine

### 4.1 Missing-Tag Line Suppression
When using the multiline `body: |` template, if an optional tag in a line evaluates to `null` or empty (e.g. `{indexer}` on manual uploads or `{comment}` when none was provided), the template engine **automatically suppresses that entire line**.

```yaml
body: |
  ▪ <b>Quality:</b> {quality}
  ▪ <b>Tracker:</b> {indexer}
  ▪ <b>Release Group:</b> {release_group}
```
*If `{release_group}` is not present in the webhook payload, the third line is omitted completely without leaving an empty bullet point or broken tag.*

### 4.2 Word-Boundary Overview Truncation
The `{overview}` tag automatically trims long descriptions to `theme.max_overview_length` (default 220 characters) on space/word boundaries, adding `...` without cutting words in half.

### 4.3 Silent Delivery & Audible Alerts (`silent`)
Control whether notifications are sent silently (without alert tones or vibration on clients) per event type using `silent: true|false` (or aliases `silent_send`, `disable_notification`).

**Built-in Defaults:**
- `download.progress`: `silent: true`
- `servarr.grab`: `silent: true`
- `servarr.import`: `silent: true`
- `servarr.manual_interaction`: `silent: false` (Audible)
- `servarr.health`: `silent: false` (Audible)
- `download.complete`: `silent: false` (Audible)
- `download.stalled`: `silent: false` (Audible)
- `media_server.available`: `silent: false` (Audible)
- `seerr.request`: `silent: false` (Audible)
- `seerr.issue`: `silent: false` (Audible)

You can override any event's delivery mode in your `templates.yaml`:
```yaml
events:
  servarr:
    # Make imports audible instead of silent:
    import:
      silent: false
    # Keep health issues urgent and audible:
    health:
      silent: false
```

### 4.4 Turning Events Off (`enabled`)
Every event is announced unless its template sets `enabled: false` (or the alias `disabled: true`). Leaving it out keeps the event on. This suppresses an event without touching the webhook in Sonarr, Radarr, Plex or Seerr, for example to keep only the media server's "now available" card when both Servarr and Plex webhooks are set up:

```yaml
events:
  servarr:
    import:
      enabled: false   # "Now available on Plex" follows shortly anyway
```

Any event can be turned off: `servarr.grab`, `servarr.import`, `servarr.manual_interaction`, `servarr.health`, `media_server.available`, `seerr.request`, `seerr.issue`, `download.progress`, `download.complete` and `download.stalled`. The download events share a single message that is edited in place, so they behave as follows:

| Disabled event | Effect |
| :--- | :--- |
| `servarr.grab` | No download card and no download tracking at all; the `download.*` events never happen. |
| `download.progress` | The grab card stays as it is until the download completes or stalls (no live progress edits). |
| `download.complete` | Tracking ends normally, but the card isn't turned into the completion card; it keeps its last state. |
| `download.stalled` | Tracking stops normally, but the card isn't turned into the stall card; it keeps its last state. |

The preview endpoint (section 5) reports `"enabled": false` for a disabled event.

### 4.5 Poster Instead of the Uploaded Thumbnail (`prefer_poster`)
Plex uploads an image with its webhooks. For an episode without a still, that image is often a blurred, pillarboxed 16:9 composite. Set `prefer_poster: true` (or the alias `use_uploaded_thumb: false`) on `media_server.available` to use the series or season poster (`{poster_url}`) instead. When there is no poster, the uploaded image is still used.

```yaml
events:
  media_server:
    available:
      prefer_poster: true
```

### 4.6 Per-Event Destination Routing (`chat_id`, `topic_id`)
By default every event delivers to the global `NOTIFICATION_URL` destination (`chat_id` and optional `topic`). You can override `chat_id` and/or `topic_id` (aliases `thread_id`, `topic`) on individual events — for example keeping `"Now available"` announcements in a quiet broadcast channel while routing live downloads and auxiliary Servarr/Seerr events to a separate group or forum topic:

```yaml
events:
  servarr:
    grab:
      chat_id: "-1001234567890"
    import:
      chat_id: "-1001234567890"
      topic_id: 42
    manual_interaction:
      chat_id: "-1001234567890"
  # media_server.available omits chat_id/topic_id -> uses global default
```

- **Sensible defaults:** Omitting `chat_id` and `topic_id` keeps the global defaults. Overriding `chat_id` without specifying `topic_id` sends to that chat without inheriting the global `?topic=` (preventing thread mismatch errors). Setting `topic_id: 0` (or `<= 0`) explicitly clears a global topic back to General/no thread.
- **Live downloads:** `download.progress`, `download.complete`, and `download.stalled` edit the original `servarr.grab` message in place, so they stay in whatever chat and topic `grab` was delivered to.
- **Interactive callbacks:** Inline buttons (such as **✅ Acknowledge**) are accepted from any `chat_id` configured in `templates.yaml`, in addition to the global chat and admin DMs.

---

## 5. Stateless Sandbox Preview Endpoint

To test and visually preview templates in real-time without modifying running configuration files, enable the preview sandbox.

### 5.1 Enable Preview Mode
Set the environment variable:
```bash
ENABLE_PREVIEW=true
```

### 5.2 Test Template Preview API
Send a `POST` request to `/api/v1/templates/preview`:

```bash
curl -X POST http://localhost:8080/api/v1/templates/preview \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_SERVER_AUTH_TOKEN" \
  -d '{
    "event_type": "grab",
    "template_yaml": "events:\n  grab:\n    title: \"🎯 Grabbing: {title}\"\n    body: \"▪ <b>Quality:</b> {quality}\n▪ <b>Size:</b> {size}\""
  }'
```

#### Sample Response:
```json
{
  "status": "success",
  "event_type": "grab",
  "rendered_card": {
    "title": "🎯 Grabbing: Breaking Bad - S01E01 - Pilot",
    "subtitle": "Sonarr-4K",
    "level": "PROGRESS",
    "custom_body": "▪ <b>Quality:</b> WEBDL-1080p\n▪ <b>Size:</b> 2.00 GB",
    "artwork_url": "https://image.tmdb.org/t/p/w500/sample.jpg",
    "silent": true
  },
  "telegram_html": "<b>🎯 Grabbing: Breaking Bad - S01E01 - Pilot</b>\n<i>Sonarr-4K</i>\n\n▪ <b>Quality:</b> WEBDL-1080p\n▪ <b>Size:</b> 2.00 GB",
  "tags_available": ["title", "series_title", "season", "episode_range", "quality", "size", "indexer", "webui_url", "poster_url", "instance_name", "source_name", "download_id"],
  "enabled": true
}
```

---

## 6. Real-World Recipe Examples

### Recipe 1: Spanish Localized Card
```yaml
events:
  import:
    title: "📁 Archivo Importado: {title}"
    subtitle: "{instance_name} • Importación"
    body: |
      ▪ <b>Resolución:</b> {resolution}
      ▪ <b>Códec de Vídeo:</b> {video_codec}
      ▪ <b>Audio:</b> {audio_codec}
      ▪ <b>Tamaño:</b> {size}

      <i>{overview}</i>
```

### Recipe 2: Anime & Scene Release-Group Focus
```yaml
events:
  grab:
    title: "🌸 Descarga Iniciada: {title}"
    subtitle: "{instance_name}"
    body: |
      ▪ <b>Grupo:</b> [{release_group}]
      ▪ <b>Episodio:</b> {episode_range}
      ▪ <b>Calidad:</b> {quality}
      ▪ <b>Tracker:</b> {indexer}
```

### Recipe 3: Minimalist Single-Line Badges
```yaml
events:
  media_available:
    title: "🍿 Now Available: {title}"
    subtitle: "{media_server_name}"
    body: |
      {resolution} • {video_codec} • {audio_codec} • ⭐ {rating} • {duration}
```

---

## 7. Media Server Status Message

With `MEDIA_SERVER_STATUS_MONITOR_ENABLED=true` (see [CONFIGURATION.md](CONFIGURATION.md#server-status-message)), one pinned message shows whether the media server is up. It's edited in place with one of two templates:

```yaml
events:
  media_server:
    status_online:
      title: "🟢 {media_server_name} — Online"
      body: |
        ▪ <b>Back up at:</b> {since} (downtime: {downtime})
        ▪ <b>Last checked:</b> {checked_at}

    status_offline:
      silent: true
      title: "🔴 {media_server_name} — Offline"
      body: |
        ▪ <b>Down since:</b> {since}
        ▪ <b>Downtime:</b> {downtime}
        ▪ <b>Error:</b> {error}
        ▪ <b>Last checked:</b> {checked_at}
```

Edits never notify anyone. On initial startup or routine refreshes, `status_online` is silent and edited in place; on recovery (`DOWN` → `UP`), unless `status_online.silent: true` is explicitly set, the old pinned message is replaced with a fresh audible message and silently re-pinned so you get a notification. After `MEDIA_SERVER_STATUS_RECOVERY_SECONDS` (default `3600` s), `{since}` and `{downtime}` are cleared and the card is edited in place to drop the "Back up at" line. Before the first outage, the online card likewise omits the "Back up at" line.

| Tag | Description | Example Output |
| :--- | :--- | :--- |
| `{media_server_name}` | `MEDIA_SERVER_NAME`, or the server type | `Kerrlab Plex` |
| `{status}` | `Online` or `Offline` | `Offline` |
| `{status_icon}` | `🟢` or `🔴` | `🔴` |
| `{since}` | Offline: when checks started failing. Online: when it came back (empty before the first outage) | `14:10` |
| `{downtime}` | Offline: the outage so far. Online: the last outage's length (empty before the first outage) | `4m 18s`, `1h 12m` |
| `{checked_at}` | Time of the last check | `14:20` |
| `{error}` | Why the last check failed (offline only) | `HTTP 503`, `Connection refused` |
| `{source_name}` | `Plex` or `Jellyfin` | `Plex` |

Times on the current day show as `HH:mm`; older ones use `theme.date_format`. They're in the container's time zone (`TZ`).

The message is switched on and off with `MEDIA_SERVER_STATUS_MONITOR_ENABLED`; `enabled: false` (section 4.4) doesn't apply to these two templates.

Preview them with `"event_type": "status_online"` or `"status_offline"` on the preview endpoint.
