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
events:
  download:
    progress:
      title: "{title}"
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
      title: "⏳ Queueing Download: {title}"
      subtitle: "{instance_name}"
      body: |
        ▪ <b>Quality:</b> {quality}
        ▪ <b>Group:</b> {release_group}
        ▪ <b>Size:</b> {size}
        ▪ <b>Indexer:</b> {indexer}

    import:
      title: "{import_icon} {import_action}: {title}"
      subtitle: "{instance_name} • {import_type}"
      body: |
        ▪ <b>Specs:</b> {specs}

        <i>{overview}</i>

    manual_interaction:
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
      title: "🍿 Now Available: {title}"
      subtitle: "{media_server_name}"
      body: |
        ▪ <b>Specs:</b> {specs}

        <i>{overview}</i>
      actions:
        - label: "🎬 Watch on {media_server_name}"
          url: "{deep_link_url}"
          style: "PRIMARY"

  seerr:
    request:
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
| `{instance_name}` | All Events | Resolved instance name (from header, query param, or fallback) | `Sonarr-4K`, `Radarr-Anime` |
| `{overview}` | Grab, Import, Available, Request | Synopsis or plot summary (truncated to `theme.max_overview_length`) | *Paul Atreides unites with Chani...* |
| `{poster_url}` | Grab, Import, Available, Request | URL to movie/series artwork poster | `https://image.tmdb.org/...` |
| `{source_name}` | All Events | Display name of originating service | `Sonarr`, `Radarr`, `Plex`, `Overseerr` |
| `{web_url}` | Grab, Import, Manual, Request | Web link back to originating service activity | `http://sonarr.lan:8989/series/...` |
| `{year}` | Grab, Import, Available | Release or air year | `2024` |
| `---` | `---` | `---` | `---` |
| **Media & Episode Details** | | | |
| `{title}` | All Media Events | Full formatted title (includes episode range if series) | `Severance (S02E01-E04)` |
| `{series_title}` | Grab, Import, Available | Series title without season or episode suffix | `Severance` |
| `{season}` | Grab, Import | 2-digit zero-padded season number | `02` |
| `{episode}` | Grab, Import | 2-digit zero-padded episode number | `01` |
| `{episode_range}` | Grab, Import | Formatted episode range notation | `S02E01-E04` |
| `{episode_title}` | Import, Available | Episode title name | `Hello, World` |
| `{quality}` | Grab, Import, Manual, Request | Quality profile or resolution label | `WEBDL-1080p`, `Remux-2160p` |
| `{release_group}` | Grab, Import, Manual | Torrent/Usenet scene or release group | `FLUX`, `NTb`, `FraMeSToR` |
| `{release_title}` | Grab, Import, Manual | Original raw release filename | `Show.S01E01.1080p.mkv` |
| `---` | `---` | `---` | `---` |
| **Download & Client Metrics** | | | |
| `{client}` / `{download_client}` | Grab, Progress, Stalled, Manual | Download client identifier | `qBittorrent` |
| `{download_id}` | Grab, Progress, Complete, Stalled | Torrent hash or download identifier | `a1b2c3d4...` |
| `{downloaded_size}` | Progress, Stalled | Downloaded byte count formatted | `1.85 GB` |
| `{download_time}` / `{duration}` | Progress, Complete | Elapsed time or estimated completion time | `4m 12s` |
| `{eta}` | Progress | Estimated time remaining | `1m 45s` |
| `{indexer}` | Grab, Manual | Indexer or tracker name | `TorrentLeech`, `PTP` |
| `{peers_info}` | Progress | Connected seeds and peers formatted | `45 (120) seeds • 12 peers` |
| `{progress_bar}` | Progress, Stalled | Visual Unicode progress bar | `[███████░░░]` |
| `{progress_percent}` | Progress, Stalled | Progress percentage formatted to 2 decimals | `74.50` |
| `{size}` / `{total_size}` | Grab, Progress, Complete, Import | Total file/torrent size formatted | `4.25 GB` |
| `{speed}` | Progress | Transfer speed formatted | `25.4 MB/s` |
| `{state}` | Progress | Current status label | `Downloading`, `Stalled` |
| `{webui_url}` | Grab, Progress, Complete, Stalled | Public WebUI link for download client | `http://qbittorrent.lan:8080` |
| `---` | `---` | `---` | `---` |
| **Media Server & Playback** | | | |
| `{audio_codec}` | Import, Available | Audio codec and audio channels | `EAC3 5.1`, `TrueHD Atmos` |
| `{deep_link_url}` | Available | Direct playback/detail link in media server | `https://app.plex.tv/desktop...` |
| `{duration}` | Available | Media playback runtime | `1h 45m` |
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
| `{media_type}` | Request | Requested media format | `🎬 Movie`, `📺 TV Series` |
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
    "artwork_url": "https://image.tmdb.org/t/p/w500/sample.jpg"
  },
  "telegram_html": "<b>🎯 Grabbing: Breaking Bad - S01E01 - Pilot</b>\n<i>Sonarr-4K</i>\n\n▪ <b>Quality:</b> WEBDL-1080p\n▪ <b>Size:</b> 2.00 GB",
  "tags_available": ["title", "series_title", "season", "episode_range", "quality", "size", "indexer", "webui_url", "poster_url", "instance_name", "source_name", "download_id"]
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
