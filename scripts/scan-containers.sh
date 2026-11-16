#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Official multi-platform Trivy 0.76.2 image, pinned by digest.
scanner='aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb'
report_dir="$PWD/backend/build/reports"
mkdir -p "$report_dir"
scan_temp=$(mktemp -d)
trap 'rm -rf "$scan_temp"' EXIT
# Snapshot tracked files only: never scan a developer's untracked .env or local databases.
git ls-files -z | tar --null -T - -cf "$scan_temp/source.tar"
mkdir "$scan_temp/source"
tar -xf "$scan_temp/source.tar" -C "$scan_temp/source"
docker image save hookrelay-review:local -o "$scan_temp/image.tar"
chmod -R a+rX "$scan_temp"
docker run --rm --user "$(id -u):$(id -g)" \
  -e TRIVY_CACHE_DIR=/tmp/trivy-cache \
  -v "$scan_temp:/input:ro" -v "$report_dir:/reports" \
  "$scanner" image --input /input/image.tar --scanners vuln \
  --severity HIGH,CRITICAL --exit-code 1 --format json --output /reports/trivy-image.json
# Scan repository secrets independently of dependencies and OS packages.
docker run --rm --user "$(id -u):$(id -g)" \
  -e TRIVY_CACHE_DIR=/tmp/trivy-cache \
  -v "$scan_temp/source:/input:ro" -v "$report_dir:/reports" \
  "$scanner" fs --scanners secret --exit-code 1 --format json --output /reports/trivy-secrets.json /input
