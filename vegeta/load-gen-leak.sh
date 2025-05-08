#!/usr/bin/env bash
set -e

# Allow override of target URL via $TARGET_URL
TARGET_URL="${TARGET_URL:-http://leaky-api-java:8082/movies}"
LOAD_GEN_MODE=${LOAD_GEN_MODE:-0}

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
    | vegeta attack -lazy -format=json -rate=1 -duration=0 -max-workers=1 \
    &> /dev/null &

  echo "🕹  Vegeta leak‐test running against ${TARGET_URL}"
  # keep the container alive
  tail -f /dev/null
}

function load-gen-challenges() {
#    set +m
#    pkill -f vegeta &> /dev/null
    vegeta -cpus 1 attack -duration=0 -rate=1 -max-workers=1 -targets /usr/local/targets.http  &> /dev/null &
    echo "🕹  Vegeta challenges load gen running against multiple endpoints"
    tail -f /dev/null
}

function load-gen-timeline() {
#    set +m
#    pkill -f vegeta &> /dev/null
    echo "GET http://movies-api-java-timeline:8083/stats?q=the" | vegeta -cpus 1 attack -duration=0 -rate=0 -max-workers=4 &> /dev/null &
    echo "🕹  Vegeta timeline load gen running against stats endpoint"
    tail -f /dev/null
}

function load-gen-intro() {
    # make sure no other vegeta background tasks are already being run to avoid resource starvation
#    set +m
#    pkill -f vegeta &> /dev/null
    echo "GET http://intro-movies-api-java:8085/credits?q=and" | vegeta -cpus 1 attack -duration=0 -rate=1 -max-workers=1 &> /dev/null &
    echo "🕹  Vegeta intro load gen running against credits endpoint"
    tail -f /dev/null
}

if [ "$LOAD_GEN_MODE" -eq 1 ]; then
  echo "Variable is 1, running load gen leak"
  load-gen-leak
elif [ "$LOAD_GEN_MODE" -eq 2 ]; then
  echo "Variable is 2, running load gen challenges"
  load-gen-challenges
elif [ "$LOAD_GEN_MODE" -eq 3 ]; then
  echo "Variable is 3, running load gen timeline"
  load-gen-timeline
elif [ "$LOAD_GEN_MODE" -eq 4 ]; then
  echo "Variable is 4, running load gen intro"
  load-gen-intro
else
  echo "Variable is not a known mode (1,2,3, or 4) is: $LOAD_GEN_MODE"
fi
load-gen
