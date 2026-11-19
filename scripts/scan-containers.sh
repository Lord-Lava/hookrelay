#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Official multi-platform Trivy 0.76.2 image, pinned by digest.
scanner='aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb'
report_dir="$PWD/backend/build/reports"
scan_cache="${HOOKRELAY_TRIVY_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/hookrelay/trivy}"
mkdir -p "$report_dir" "$scan_cache"
scan_cache=$(cd "$scan_cache" && pwd)
rm -f "$report_dir/trivy-image.json" "$report_dir/trivy-secrets.json"
scan_temp=$(mktemp -d)
trap 'rm -rf "$scan_temp"' EXIT
# Snapshot tracked files only: never scan a developer's untracked .env or local databases.
git ls-files -z | tar --null -T - -cf "$scan_temp/source.tar"
mkdir "$scan_temp/source"
tar -xf "$scan_temp/source.tar" -C "$scan_temp/source"
chmod -R a+rX "$scan_temp"
run_scanner() {
  docker run --rm --user "$(id -u):$(id -g)" \
    -e TRIVY_CACHE_DIR=/cache \
    -v "$scan_cache:/cache" -v "$scan_temp:/input:ro" -v "$report_dir:/reports" \
    "$scanner" "$@"
}
# Both checks run; scanner failures and secret findings fail the script.
# Image vulnerability findings are reported in the SARIF/JSON report but do not
# fail the build: base-image OS packages carry their own CVE lifecycle that is
# managed separately through image rebuilds (AGENTS.md §13). Secret findings
# always fail the build — no secrets may be committed to the repository.
scan_status=0
run_scanner fs --scanners secret --timeout 5m --exit-code 1 \
  --format json --output /reports/trivy-secrets.json /input/source || scan_status=1
if docker image save hookrelay-review:local -o "$scan_temp/image.tar"; then
  chmod a+r "$scan_temp/image.tar"
  # Cold Java DB downloads can exceed Trivy's default deadline. Keep auto-updates enabled.
  # Image vulnerabilities are reported, not failed-on: OS package CVEs are tracked
  # separately through base-image rebuilds, not per-PR gates.
  run_scanner image --input /input/image.tar --scanners vuln --timeout 20m \
    --severity HIGH,CRITICAL --exit-code 0 --format json --output /reports/trivy-image.json \
    || scan_status=1
else
  scan_status=1
fi
exit "$scan_status"
