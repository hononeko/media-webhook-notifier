# Distroless runtime with glibc for the GraalVM native executable, built with `-PcontainerImage`: everything is linked
# statically except glibc, the only static mode GraalVM supports on both amd64 and arm64. The binary must be built
# against a glibc no newer than this base image's (Debian 13: 2.41), so the CI runners are pinned to Ubuntu 24.04.
FROM gcr.io/distroless/base-debian13:nonroot

LABEL org.opencontainers.image.source="https://github.com/hononeko/media-webhook-notifier"
LABEL org.opencontainers.image.description="Lightweight Kotlin native media webhook notifier with live Telegram progress cards"
LABEL org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Native binary for the architecture being built; each architecture is compiled on its own runner.
COPY build/native/nativeCompile/media-webhook-notifier /app/media-webhook-notifier

# Expose HTTP Ingestion Port
EXPOSE 8080

USER nonroot:nonroot

ENTRYPOINT ["/app/media-webhook-notifier"]
