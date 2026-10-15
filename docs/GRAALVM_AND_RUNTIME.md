# GraalVM Native Image & Runtime Architecture (`GRAALVM_AND_RUNTIME.md`)

## 1. Native Binary Compilation Overview

To achieve an ultra-lightweight footprint suitable for continuous sidecar or microservice deployment in Kubernetes / Docker Compose, `media-webhook-notifier` compiles ahead-of-time (AOT) to a static native executable using **GraalVM Native Image** and is published to GitHub Container Registry as **`ghcr.io/hononeko/media-webhook-notifier`**.

### Measured Footprint (smoke test, idle, no configuration):
* **Server start:** about **2 ms**, as reported by Ktor. Time from `docker run` to the first `/livez` answer is 250–400 ms, most of it Docker starting the container.
* **Memory:** about **21 MiB** after startup (2.13.1, amd64). CI fails a build whose image uses more than 64 MiB once started.
* **Image size:** about 160 MB uncompressed, most of it the native binary. The distroless glibc base adds about 39 MB.

---

## 2. Zero-Reflection & Serialization Strategy

Traditional JVM frameworks rely heavily on runtime reflection and dynamic proxies (Spring Boot, Jackson, Hibernate), which add hundreds of megabytes of overhead and require complex native image reflection configuration files.

### 2.1 Technology Choices for GraalVM Compatibility:
1. **HTTP Engine:** **Ktor Server with Netty / CIO engine** or **Http4k** (designed for native compilation without bytecode generation).
2. **JSON Serialization:** **`kotlinx.serialization`**
   - 100% compile-time code generation via Kotlin compiler plugin.
   - Zero runtime reflection, fully GraalVM Native Image compliant out of the box.
3. **HTTP Client:** **Ktor Client (CIO or Java HTTP Engine)**
   - Coroutine-native, non-blocking I/O.
4. **Functional Utilities:** **Arrow-kt Core (`arrow-core`)**
   - Zero reflection, pure inline functions and algebraic data types.

---

## 3. Multi-Arch Container Image (`ghcr.io/hononeko/media-webhook-notifier`)

Every release tag is a **multi-arch image** for `linux/amd64` and `linux/arm64`: Docker and Kubernetes pull the right one automatically.

### 3.1 How it's built
* **Native on each architecture:** `.github/workflows/native-image.yml` compiles the binary on an `ubuntu-24.04` (amd64) and an `ubuntu-24.04-arm` (arm64) runner, in parallel and without QEMU. The Dockerfile only copies that binary into the runtime image.
* **Smoke test:** each image is started with no configuration. It must answer `/livez` within 10 s and stay under 64 MiB, and the timings are written to the job summary (`.github/scripts/smoke-test.sh`).
* **Release candidate on every push to `main`:** when there's something to release, `main.yml` pushes each architecture's image by digest, then joins them into one multi-arch release candidate with `docker buildx imagetools create` and attests it (see 3.2). With nothing to release (only `docs:` / `test:` since the last version), it only builds and tests.
* **Stable release:** dispatching `cd.yml` **promotes** the release candidate built from that commit. It re-tags the exact tested image as `X.Y.Z` and `latest` (same digest, no rebuild, so the candidate's attestation still applies), then creates the git tag and the GitHub release. It stops if that commit has no release candidate. Afterwards it prunes that version's other release candidates from GHCR. Dispatching with `dry_run` checks the candidate and lists what would be tagged and pruned, without changing anything. Only `main` can publish.

### 3.2 Image tags and release channels

| Tag | Example | Moves? | Published by |
|---|---|---|---|
| `X.Y.Z` | `2.14.0` | no | a stable release (CD dispatch) |
| `latest` | | to each stable release | a stable release |
| `X.Y.Z-rc.N` | `2.14.0-rc.3` | no | every push to `main` with something to release; N counts the commits since the last release |
| `sha-<commit>` | `sha-1a2b3c4` | no | every release candidate; CD uses it to find the candidate to promote |
| `edge` | | to each release candidate | every release candidate |

* **Stable** (`X.Y.Z` / `latest`): for production. Renovate's default `ignoreUnstable` never offers a release candidate to a deployment pinned to a stable tag.
* **Release candidates** (`X.Y.Z-rc.N` / `edge`): to try a change from `main` before it's released. Pin an `-rc` tag by hand, and Renovate then follows newer candidates. A candidate disappears when its version is released, except the one that became the release, which lives on as `X.Y.Z`.
* Git tags and GitHub releases exist only for stable versions.

### 3.3 Linking and CPU baseline
The binary is built with `./gradlew nativeCompile -PcontainerImage`, which adds:
* **`--static-nolibc` (mostly static):** everything is linked statically except glibc, which the distroless base image provides. Fully static musl binaries aren't possible on `linux/aarch64` ([oracle/graal#5872](https://github.com/oracle/graal/issues/5872)), so both architectures use the same mode.
* **`-march=compatibility`:** GraalVM's defaults (`x86-64-v3` on amd64, `armv8.1-a` on arm64) don't run on CPUs common in home servers, such as Celerons and Atoms without AVX2, or the Raspberry Pi 4 (ARMv8.0).

Because glibc is linked dynamically, the binary must be built against a glibc **no newer than the base image's** (Debian 13: 2.41). That's why the runners are pinned to Ubuntu 24.04 (glibc 2.39), and why CI checks the newest `GLIBC_*` symbol the binary requires.

### 3.4 Dockerfile

```dockerfile
FROM gcr.io/distroless/base-debian13:nonroot
WORKDIR /app
COPY build/native/nativeCompile/media-webhook-notifier /app/media-webhook-notifier
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/app/media-webhook-notifier"]
```

To build an image locally (Linux, glibc 2.41 or older, GraalVM 25):

```bash
./gradlew nativeCompile -PcontainerImage
docker build -t media-webhook-notifier:local .
.github/scripts/smoke-test.sh media-webhook-notifier:local
```

---

## 4. WebAssembly (Wasm) Feasibility Analysis

As requested, we evaluated the feasibility of compiling this service to **Wasm (WebAssembly / WASI)**:

### 4.1 Current State of Kotlin/Wasm for Server Side:
* **Kotlin/Wasm (WasmGC):**
  - Kotlin supports compiling to WebAssembly using **WasmGC (Garbage Collection proposal)**.
  - Currently optimized primarily for **browsers (Chrome, Firefox, Safari)** and Node.js with `--experimental-wasm-gc`.
* **Server-Side Wasm (WASI):**
  - Server-side Wasm runtimes (Wasmtime, Wasmer, WasmEdge) are rapidly evolving, but socket-based network I/O (`WASI-HTTP` / `wasi:sockets`) in Kotlin/Wasm standard library is still experimental compared to JVM/Native.

### 4.2 Architectural Assessment & Roadmap:
* **Production Deployment Target:** **GraalVM Native Linux Binary (`amd64` / `arm64`)**
  - Production ready, full socket/TLS support, native epoll, distroless container compatible.
* **Experimental Wasm Track:**
  - Because our domain is strictly structured with Hexagonal Architecture (core domain has zero platform dependencies), the core business logic can be shared as a **Kotlin Multiplatform (KMP)** library (`jvm`, `native`, `wasmJs`, `wasmWasi`) in future phases as server-side WASI socket runtimes mature.
