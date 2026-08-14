# Minimal Distroless Runtime for GraalVM Native Executable
FROM gcr.io/distroless/static-debian12:nonroot

LABEL org.opencontainers.image.source="https://github.com/hononeko/media-webhook-notifier"
LABEL org.opencontainers.image.description="Lightweight Kotlin native media webhook notifier with live Telegram progress cards"
LABEL org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Copy native binary compiled by GraalVM
COPY build/native/nativeCompile/media-webhook-notifier /app/media-webhook-notifier

# Expose HTTP Ingestion Port
EXPOSE 8080

USER nonroot:nonroot

ENTRYPOINT ["/app/media-webhook-notifier"]
