#!/bin/bash
# Installs the Android SDK for cloud sessions. Idempotent; skipped when already installed.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then exit 0; fi

PROJECT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
export ANDROID_HOME="$HOME/android-sdk"
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"   # see developer.android.com/studio#command-line-tools-only
URL="https://dl.google.com/android/repository/$CMDLINE_ZIP"
STAMP="$ANDROID_HOME/.session-start.sha256"

if ! sha256sum --check --status "$STAMP" 2>/dev/null; then
  rm -f "$STAMP"
  if ! curl -fsSI --max-time 20 "$URL" >/dev/null 2>&1; then
    cat >&2 <<MSG
STOP: dl.google.com is unreachable from this environment, so the Android SDK cannot be installed.
Set the environment's network access to "Custom", keep "default package managers" ticked, and add:
  dl.google.com
  dl-ssl.google.com
MSG
    exit 1
  fi
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL "$URL" -o "$tmp/cmdline.zip"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
  SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --licenses >/dev/null || true
  "$SDKM" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
  sha256sum "$PROJECT/.claude/hooks/session-start.sh" > "$STAMP"
fi

# Every session
if [ -d "$PROJECT/apps/android" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$PROJECT/apps/android/local.properties"
fi
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$ANDROID_HOME\"" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=\"\$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin\"" >> "$CLAUDE_ENV_FILE"
fi
