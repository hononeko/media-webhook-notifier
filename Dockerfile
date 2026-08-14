# syntax=docker/dockerfile:1.7
# Stage 1: Build Native Binary with GraalVM
FROM ghcr.io/graalvm/native-image-community:25-ol9 AS builder

WORKDIR /build

ARG GRADLE_ARGS=""
ENV GRADLE_ARGS=${GRADLE_ARGS}

# Copy Gradle wrapper and definition files for dependency caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

# Copy application source code
COPY src src

# Build native binary with optimization flags and BuildKit cache
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew nativeCompile --no-daemon ${GRADLE_ARGS}

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
