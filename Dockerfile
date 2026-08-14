# Minimal Distroless Static Runtime for GraalVM Fully Static Native Executable
FROM gcr.io/distroless/static-debian12:nonroot

LABEL org.opencontainers.image.source="https://github.com/hononeko/media-webhook-notifier"
LABEL org.opencontainers.image.description="Lightweight Kotlin native media webhook notifier with live Telegram progress cards"
LABEL org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Copy statically linked native binary compiled with GraalVM + musl
COPY build/native/nativeCompile/media-webhook-notifier /app/media-webhook-notifier

# Expose HTTP Ingestion Port
EXPOSE 8080

USER nonroot:nonroot

ENTRYPOINT ["/app/media-webhook-notifier"]
