# Security Policy (`SECURITY.md`)

## 🛡️ Supported Versions

The following table lists the release branches and versions of **`media-webhook-notifier`** that currently receive security patches and vulnerability updates:

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1.0 | :x:                |

---

## 🔒 Reporting a Vulnerability

We take the security of `media-webhook-notifier` seriously. If you believe you have discovered a security vulnerability or sensitive bug, please report it privately:

1. **GitHub Security Advisory (Preferred):**
   * Navigate to the [Security Advisories](https://github.com/hononeko/media-webhook-notifier/security/advisories/new) tab on GitHub and submit a private draft report.
2. **Direct Maintainer Contact:**
   * If GitHub Security Advisories are unavailable, contact the project maintainers via email at `security@hononeko.app` with detailed reproduction steps, potential impact, and sample payloads.

### What to Expect:
* **Initial Response:** Within 48 hours of receipt.
* **Triage & Patch:** We will validate the issue, prepare a fix on a private branch, and release a tagged patch version.
* **Public Disclosure:** Coordinated public release and CVE publication after affected users have had reasonable opportunity to update.

---

## 🏗️ Built-In Security Architecture

`media-webhook-notifier` employs several defense-in-depth security mechanisms by default:

### 1. Dual Authentication Guard
* Inbound webhooks can be secured via HTTP Headers (`Authorization: Bearer <token>`, `X-Api-Key: <token>`) or Query Parameters (`?token=<token>`, `?apikey=<token>`).
* Whitelist token verification supports multiple authorized tokens via comma-separated configuration (`SERVER_AUTH_TOKEN="token1,token2"`).

### 2. Inbound Rate Limiting & DoS Protection
* Built-in per-IP/caller token bucket rate limiting (`SERVER_RATE_LIMIT_PER_MINUTE`, default: `120`).
* Rejection with `429 Too Many Requests` and `Retry-After: 60` HTTP headers to shield downstream services.

### 3. Input Validation & Safe Sanitization
* Torrent hash format validation prevents empty-string or malformed RPC calls to download clients.
* Safe HTML sanitization and word-boundary truncation enforce Telegram's strict 1024-character caption limits without breaking HTML tags.

### 4. Minimal Static Distroless Container
* Production container images (`ghcr.io/hononeko/media-webhook-notifier`) are built on **`gcr.io/distroless/static-debian12:nonroot`**.
* No package managers, shells (`/bin/sh`, `/bin/bash`), or unnecessary system binaries exist in the runtime container.
* Runs by default under the unprivileged `nonroot:nonroot` user (UID 65532).
