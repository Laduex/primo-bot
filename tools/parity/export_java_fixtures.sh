#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

TEST_CLASS="dev.saseq.primobot.parity.ParityFixtureExporterTest"

if command -v mvn >/dev/null 2>&1; then
  mvn -Dtest="$TEST_CLASS" test
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "error: neither mvn nor docker is available" >&2
  exit 1
fi

mkdir -p "${HOME}/.m2"

docker run --rm \
  -v "$PWD":/workspace \
  -v "${HOME}/.m2":/root/.m2 \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-17 \
  mvn -Dtest="$TEST_CLASS" test
