# Agent & Contributor Guidelines (`AGENTS.md`)

This document outlines the core architectural principles, development workflows, quality gates, and engineering standards for developing and maintaining **`media-webhook-notifier`** (`ghcr.io/hononeko/media-webhook-notifier`).

All automated agents and human contributors must adhere to the rules in this document.

---

## 1. Non-Negotiable Pre-Commit & Pre-Push Quality Gates

Before committing or pushing changes to remote, all of the following steps **MUST** pass locally:

1. **Code Formatting & Linting (`ktlint`):**
   * Code must adhere to Kotlin coding conventions:
     ```bash
     ./gradlew ktlintCheck
     ```
   * Auto-format code if needed:
     ```bash
     ./gradlew ktlintFormat
     ```
   * Zero lint warnings or formatting inconsistencies allowed.

2. **Test Suite Execution:**
   * All unit, integration, and architecture tests must pass cleanly:
     ```bash
     ./gradlew test
     ```
   * Ensure tests run deterministically with zero race conditions or unhandled coroutine exceptions.

3. **Build & Type Check:**
   * Verify complete compilation:
     ```bash
     ./gradlew check
     ```

4. **GraalVM Native Compilation (Pre-Release / Periodic):**
   * Ensure native image compilation succeeds without reflection errors:
     ```bash
     ./gradlew nativeCompile
     ```

---

## 2. Kotlin & Hexagonal Engineering Standards

### 2.1 Hexagonal Layer Boundaries
* **Domain Core (`app.hononeko.notifier.domain.*`):**
  * **Zero Framework Dependencies:** Strictly prohibited from importing Ktor, Netty, Hoplite, or raw HTTP client libraries.
  * Only Kotlin standard library, `kotlinx.coroutines`, `kotlinx.serialization`, `kotlinx.datetime`, and `arrow-core` are permitted.
* **Driving Adapters (`adapter.inbound.web.*`):**
  * Ingest HTTP webhooks, parse payloads, execute Inbound Ports (Use Cases), and map domain responses to HTTP status codes.
* **Driven Adapters (`adapter.outbound.*`):**
  * Implement Outbound Ports for external integrations (qBittorrent, Telegram Bot API, Discord, Plex, Jellyfin).

### 2.2 Functional Error Handling with Arrow-kt
* **No Unchecked Exceptions Across Boundaries:** Avoid throwing runtime exceptions in domain logic or outbound adapters.
* **Typed Errors:** Return `Either<DomainError, T>` or use the `arrow.core.raise.either` / `ensure` DSL.
* **Sealed Hierarchy:** All failure modes must be explicitly modeled under `DomainError`.

### 2.3 Fail-Fast Cloud Provider & Resilience Policy
* **Cloud API Fail-Fast:** Outbound calls to cloud providers (Telegram Bot API, Discord REST API, remote TMDB/TVDB CDNs) must have defensive timeouts (5s connect/socket).
* **Rate Limits (`429 Too Many Requests`):** If a rate limit is returned (`retry_after`), the adapter must fail fast immediately and return `DomainError.NotificationError.RateLimited`.
* **Non-Blocking Tracking Ticks:** In live download tracking loops, dropped ticks due to rate limiting or transient network hiccups must be skipped non-blockingly without killing the supervisor job.
* **Photo Fallback:** If photo delivery fails (e.g. Telegram CDN 400), automatically fall back to an HTML text card (`sendMessage`) without dropping the notification.

### 2.4 Structured Concurrency & Graceful Shutdown
* **Supervisor Scope:** Polling and tracking jobs run under `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
* **Debounce Window:** Rapid multi-episode grabs, imports, and upgrades are buffered for 5 seconds to prevent chat spam.
* **Graceful Termination:** On `SIGTERM` / `SIGINT`, cancel active tracking jobs, flush pending states, and terminate within 5 seconds.

### 2.5 Security & Input Sanitization
* **Authentication Guard:** Validate secrets on both HTTP headers (`Authorization: Bearer <token>`, `X-Api-Key: <token>`) and query parameters (`?token=<token>`, `?apikey=<token>`).
* **Torrent Hash Validation:** Validate torrent hash format before issuing queries to prevent qBittorrent empty-hash bugs.
* **Caption Limits:** Enforce Telegram's 1024-character caption limit with safe HTML truncation.

---

## 3. Configuration Management

* **Environment-Variable First:** All configuration keys must be bindable via standard uppercase environment variables (`SERVER_PORT`, `SERVER_AUTH_TOKEN`, `TELEGRAM_BOT_TOKEN`, `QBITTORRENT_URL`, etc.).
* **Fail-Fast Boot:** Missing mandatory variables or invalid URLs must halt application startup with clear diagnostic logs.
* **Password Files:** File-based secret mounts (`*_FILE`) are reserved for future phases.

---

## 4. Conventional Commits & Automated SemVer

This repository enforces the [Conventional Commits](https://www.conventionalcommits.org/) standard. Release versions and changelogs are automatically calculated by GitHub Actions using `PaulHatch/semantic-version`.

### Commit Types & Version Bumping Rules:
* `fix:` Patches a bug &rarr; **Patch Bump** (`0.1.0` &rarr; `0.1.1`)
* `feat:` Adds a new feature &rarr; **Minor Bump** (`0.1.0` &rarr; `0.2.0`)
* `feat!:` or `fix!:` (a `!` in the header), or a footer line starting `BREAKING CHANGE:` &rarr; **Major Bump** (`0.1.0` &rarr; `1.0.0`). A body that merely mentions them doesn't count.
* `refactor:`, `perf:`, `chore:`, `ci:`, `build:` and any other type &rarr; **Patch Bump**
* `docs:`, `test:` &rarr; **No version bump** (still included in the next release's changelog)

### Release Pipeline
Versions are calculated from git history (`version.yml`), ignoring `docs:` and `test:` commits. The rules, release notes and image smoke test are shared by all my repositories in [`vehkiya/actions`](https://github.com/vehkiya/actions).

1. **Every push to `main` (`main.yml`):** lint and tests run, then native images for `linux/amd64` and `linux/arm64` are built on their own runners and smoke-tested (`native-image.yml`). If there's something to release, both are published as one multi-arch **release candidate**: `X.Y.Z-rc.N`, `sha-<commit>` and `edge`, attested.
2. **Stable release (`cd.yml`, dispatched manually from `main`):** promotes the release candidate of the dispatched commit by re-tagging it as `X.Y.Z` and `latest` (same digest, no rebuild). It then tags the git commit, creates the GitHub Release, and prunes that version's other release candidates from GHCR. Dispatch with `dry_run` to check without changing anything; the release notes are shown in the run summary either way.
3. **Release notes (`vehkiya/actions/release-notes`):** the commits since the last release are grouped by type: 💥 Breaking changes (`type!:` or a `BREAKING CHANGE:` footer), 🚀 Features (`feat`), 🐛 Bug fixes (`fix`), 👷 CI (`ci`) and 📦 Others (every other type, including `docs:` and `test:`). Each entry is `type(scope): subject #PR`. Commit subjects are the release notes, so write them for a reader of the release.

---

## 5. Repository Structure Reference

```
.
├── .github/
│   └── workflows/
│       ├── validate.yml          # Reusable validation (lint, test, build scan)
│       ├── pr.yml                # PR validation workflow
│       ├── main.yml              # Main push: validation, native images, release candidate
│       ├── cd.yml                # Stable release: promote the release candidate, git tag & GitHub release
│       ├── version.yml           # Reusable SemVer + release-candidate version calculation
│       ├── native-image.yml      # Reusable multi-arch native image build + smoke test
│       ├── sonar.yml             # SonarQube / SonarCloud code quality analysis
│       └── codeql.yml            # GitHub CodeQL Static Analysis (manual build mode)

├── docs/                         # Specification & architecture blueprints
├── gradle/
│   ├── wrapper/                  # Pinned Gradle Wrapper
│   └── libs.versions.toml        # Gradle Version Catalog
├── src/
│   ├── main/
│   │   ├── kotlin/app/hononeko/notifier/
│   │   │   ├── Application.kt
│   │   │   ├── config/           # Hoplite ENV configuration loader
│   │   │   ├── domain/           # Core domain (zero framework dependencies)
│   │   │   │   ├── error/
│   │   │   │   ├── model/
│   │   │   │   ├── port/
│   │   │   │   └── service/
│   │   │   └── adapter/          # Inbound & Outbound Adapters
│   │   │       ├── inbound/web/
│   │   │       └── outbound/
│   │   └── resources/
│   │       ├── application.conf
│   │       └── logback.xml
│   └── test/
│       └── kotlin/app/hononeko/notifier/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── AGENTS.md
└── README.md
```
