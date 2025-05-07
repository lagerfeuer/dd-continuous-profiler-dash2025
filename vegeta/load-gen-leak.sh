#!/usr/bin/env bash
set -e

# Allow override of target URL via $TARGET_URL
TARGET_URL="${TARGET_URL:-http://leaky-api-java:8082/movies}"

function load-gen-target-generator() {
  while true; do
    echo "{\"method\": \"GET\", \"url\": \"${TARGET_URL}?q=$(openssl rand -hex 12)\"}"
    sleep 0.1
  done
}

function load-gen-leak() {
  # kill any stray vegeta
  pkill -f vegeta &> /dev/null || true

  # launch vegeta attack in background, drop output
  load-gen-target-generator \
    | vegeta attack -lazy -format=json -rate=1 -duration=0 \
    &> /dev/null &

  echo "🕹  Vegeta leak‐test running against ${TARGET_URL}"
  # keep the container alive
  tail -f /dev/null
}

load-gen-leak
