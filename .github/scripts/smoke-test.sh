#!/usr/bin/env bash
# Boots a container image with no configuration, waits for /livez, and reports cold start and memory.
#
# Usage: smoke-test.sh <image>
#   READY_TIMEOUT_S  fail if /livez isn't answering after this many seconds (default 10)
#   MAX_RSS_MIB      fail if the container uses more memory than this once started (default 64)
#
# "Time to /livez" includes Docker starting the container; "server start" is what the app itself reports.
# Results are printed and, in GitHub Actions, added to the job summary.
set -euo pipefail

image=${1:?usage: smoke-test.sh <image>}
timeout_s=${READY_TIMEOUT_S:-10}
max_rss_mib=${MAX_RSS_MIB:-64}
port=18080
name="smoke-$$"

cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

start_ns=$(date +%s%N)
docker run -d --name "$name" -p "$port:8080" "$image" >/dev/null

until curl -fsS "http://localhost:$port/livez" >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != "true" ]; then
    echo "::error::$image exited before /livez answered"
    docker logs "$name"
    exit 1
  fi
  if (( ($(date +%s%N) - start_ns) / 1000000000 >= timeout_s )); then
    echo "::error::$image did not answer /livez within ${timeout_s}s"
    docker logs "$name"
    exit 1
  fi
  sleep 0.05
done
ready_ms=$(( ($(date +%s%N) - start_ns) / 1000000 ))

# Let startup work (state store, schedulers) settle before measuring memory.
sleep 2
usage=$(docker stats --no-stream --format '{{.MemUsage}}' "$name" | awk '{print $1}')
rss_mib=$(awk -v u="$usage" 'BEGIN {
  n = u + 0; unit = u; sub(/^[0-9.]+/, "", unit)
  f = (unit == "GiB") ? 1024 : (unit == "KiB") ? 1 / 1024 : (unit == "B") ? 1 / 1048576 : 1
  printf "%.1f", n * f
}')
server_start=$(docker logs "$name" 2>&1 | grep -o 'Application started in [0-9.]* seconds' | head -1 | grep -o '[0-9.]*' || echo "?")

arch=$(docker image inspect -f '{{.Architecture}}' "$image")
report=$(printf '| %s | %s ms | %s s | %s MiB |' "$arch" "$ready_ms" "$server_start" "$rss_mib")
echo "Time to /livez: ${ready_ms} ms, server start: ${server_start} s, memory: ${rss_mib} MiB ($arch)"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Smoke test: $image"
    echo "| Arch | Time to /livez | Server start | Memory |"
    echo "|---|---|---|---|"
    echo "$report"
  } >> "$GITHUB_STEP_SUMMARY"
fi

if awk -v r="$rss_mib" -v m="$max_rss_mib" 'BEGIN { exit !(r > m) }'; then
  echo "::error::$image uses ${rss_mib} MiB after startup, above the ${max_rss_mib} MiB limit"
  exit 1
fi
