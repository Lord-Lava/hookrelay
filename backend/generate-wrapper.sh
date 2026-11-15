#!/usr/bin/env sh
set -eu

# Bootstrap the official Gradle Wrapper from a version-pinned Gradle/JDK container.
# This avoids accidentally generating the wrapper with a globally installed Gradle 8.13 (or other version).
BACKEND_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_VERSION="9.9.1"
GRADLE_IMAGE="gradle:9.9.1-jdk25"
DISTRIBUTION_SHA256="d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228"
WRAPPER_JAR_SHA256="621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Do not use a system Gradle version to generate the wrapper." >&2
  exit 1
fi

DOCKER_USER="$(id -u):$(id -g)"
docker run --rm \
  --user "$DOCKER_USER" \
  --env GRADLE_USER_HOME=/tmp/gradle-user-home \
  --volume "$BACKEND_DIR:/workspace" \
  --workdir /workspace \
  "$GRADLE_IMAGE" \
  gradle --no-daemon wrapper \
    --gradle-version "$GRADLE_VERSION" \
    --distribution-type bin \
    --gradle-distribution-sha256-sum "$DISTRIBUTION_SHA256"

WRAPPER_JAR="$BACKEND_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Wrapper JAR was not generated." >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_WRAPPER_SHA256="$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL_WRAPPER_SHA256="$(shasum -a 256 "$WRAPPER_JAR" | awk '{print $1}')"
else
  echo "Neither sha256sum nor shasum is installed; cannot verify the Wrapper JAR." >&2
  exit 1
fi
if [ "$ACTUAL_WRAPPER_SHA256" != "$WRAPPER_JAR_SHA256" ]; then
  echo "Unexpected Gradle Wrapper JAR checksum: $ACTUAL_WRAPPER_SHA256" >&2
  exit 1
fi

chmod +x "$BACKEND_DIR/gradlew"
echo "Gradle $GRADLE_VERSION wrapper generated and checksum verified."
echo "Next: cd '$BACKEND_DIR' && ./gradlew --version && ./gradlew clean check bootJar"
