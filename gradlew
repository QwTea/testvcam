#!/usr/bin/env bash
set -euo pipefail
REQUIRED_VERSION_MAJOR=8
REQUIRED_VERSION_MINOR=7
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle executable not found. Please install Gradle ${REQUIRED_VERSION_MAJOR}.${REQUIRED_VERSION_MINOR} or newer." >&2
  exit 1
fi
VERSION_LINE=$(gradle -v | head -n 1)
if [[ ! "$VERSION_LINE" =~ Gradle ]]; then
  VERSION_LINE=$(gradle -v | grep Gradle || true)
fi
VERSION=$(echo "$VERSION_LINE" | awk '{print $2}')
IFS='.' read -r major minor patch <<<"$VERSION"
if [[ $major -lt $REQUIRED_VERSION_MAJOR || ($major -eq $REQUIRED_VERSION_MAJOR && ${minor:-0} -lt $REQUIRED_VERSION_MINOR) ]]; then
  echo "Gradle $VERSION is too old. Need >= ${REQUIRED_VERSION_MAJOR}.${REQUIRED_VERSION_MINOR}" >&2
  exit 1
fi
exec gradle "$@"
