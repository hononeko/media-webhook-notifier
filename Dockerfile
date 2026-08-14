# Stage 1: Build Native Binary with GraalVM
FROM ghcr.io/graalvm/native-image-community:25-ol9 AS builder

WORKDIR /build

# Copy Gradle wrapper and definition files for dependency caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

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
