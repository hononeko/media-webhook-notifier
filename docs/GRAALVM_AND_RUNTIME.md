# GraalVM Native Image & Runtime Architecture (`GRAALVM_AND_RUNTIME.md`)

## 1. Native Binary Compilation Overview

To achieve an ultra-lightweight footprint suitable for continuous sidecar or microservice deployment in Kubernetes / Docker Compose, `media-webhook-notifier` compiles ahead-of-time (AOT) to a static native executable using **GraalVM Native Image** and is published to GitHub Container Registry as **`ghcr.io/hononeko/media-webhook-notifier`**.

### Performance Target Metrics:
* **Cold Startup Time:** `< 25 ms` (instantaneous readiness).
* **Resident Set Size (RSS Memory):** `< 20 - 30 MB` at full load with multiple concurrent tracking jobs.
* **Container Image Size:** `< 35 MB` uncompressed (built on distroless base).

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

## 3. Multi-Stage Dockerfile (`ghcr.io/hononeko/media-webhook-notifier`)

```dockerfile
# Stage 1: Build Native Binary with GraalVM
FROM ghcr.io/graalvm/native-image-community:21-ol9 AS builder

WORKDIR /build

# Copy Gradle wrapper and definition files for dependency caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN ./gradlew dependencies --no-daemon

# Copy application source code
COPY src src

# Build native binary with optimization flags
RUN ./gradlew nativeCompile --no-daemon

# Stage 2: Minimal Distroless Runtime
FROM gcr.io/distroless/static-debian12:nonroot

LABEL org.opencontainers.image.source="https://github.com/hononeko/media-webhook-notifier"
LABEL org.opencontainers.image.description="Lightweight Kotlin native media webhook notifier with live Telegram progress cards"
LABEL org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Copy native binary from builder
COPY --from=builder /build/build/native/nativeCompile/media-webhook-notifier /app/media-webhook-notifier

# Expose HTTP Ingestion Port
EXPOSE 8080

USER nonroot:nonroot

ENTRYPOINT ["/app/media-webhook-notifier"]
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
