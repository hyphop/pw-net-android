#!/bin/sh
# prepare-android.sh — Android C toolchain (SDK+NDK) into $HOME/android, no root

set -e

: "${PREFIX:="$HOME/android"}"
SDK="$PREFIX/sdk"
TMP="$PREFIX/tmp"
ENV="$PREFIX/env.sh"
mkdir -p "$SDK" "$TMP"

have() { command -v "$1" >/dev/null 2>&1; }

# --- host deps (no root) ---
REQ="curl unzip awk sed grep sort tar"
MISS=""
for x in $REQ; do
  if ! have "$x"; then MISS="$MISS $x"; fi
done
if [ -n "$MISS" ]; then
  echo "Missing:$MISS"
  echo "Install: sudo apt update && sudo apt install -y$MISS"
  exit 1
fi

# JDK 17 (no root, relocatable)
if command -v javac >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
else
  ARCH=$(uname -m); case "$ARCH" in
    x86_64) A=x64;;
    aarch64|arm64) A=aarch64;;
    *) echo "unsupported arch: $ARCH"; exit 1;;
  esac
  URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/$A/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p "$PREFIX"
  cd "$PREFIX"
  curl -fL -o jdk17.tar.gz "$URL"
  tar -xzf jdk17.tar.gz
  JAVA_HOME="$PREFIX/$(tar -tzf jdk17.tar.gz | head -1 | cut -d/ -f1)"
fi


export JAVA_HOME
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"

# sanity
if ! java -version >/dev/null 2>&1; then
  echo "[JDK] java not found after setup (JAVA_HOME=$JAVA_HOME)"
  exit 1
fi

# --- cmdline-tools (simple URL, overridable) ---
: "${CMDLINE_TOOLS_URL:=https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
echo "[SDK] downloading cmdline-tools"
cd "$TMP"
curl -fL "$CMDLINE_TOOLS_URL" -o cmdline-tools.zip

rm -rf "$SDK/cmdline-tools"
mkdir -p "$SDK/cmdline-tools/.in"
unzip -q -o cmdline-tools.zip -d "$SDK/cmdline-tools/.in"
if [ -d "$SDK/cmdline-tools/.in/cmdline-tools" ]; then
  mv "$SDK/cmdline-tools/.in/cmdline-tools" "$SDK/cmdline-tools/latest"
else
  mv "$SDK/cmdline-tools/.in" "$SDK/cmdline-tools/latest"
fi

# PATH with Android tools (simple exports)
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

# --- licenses (non-fatal if some remain) ---
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null || true

# --- pick latest versions (with blunt fallbacks) ---
PLAT="$(sdkmanager --list 2>/dev/null | grep '^platforms;android-' | sed 's/.*android-//' | sort -V | tail -n1)"
if [ -z "$PLAT" ]; then PLAT=34; fi

BUILD_TOOLS="$(sdkmanager --list 2>/dev/null | awk '/^build-tools;[0-9]/{print $1}' | sort -V | tail -n1)"
if [ -z "$BUILD_TOOLS" ]; then BUILD_TOOLS="build-tools;34.0.0"; fi

NDKPKG="$(sdkmanager --list 2>/dev/null | awk '/^ndk;[0-9]/{print $1}' | sort -V | tail -n1)"
if [ -z "$NDKPKG" ]; then NDKPKG="ndk;27.3.13750724"; fi

echo "[SDK] installing:"
echo "  platform-tools"
echo "  platforms;android-$PLAT"
echo "  $BUILD_TOOLS"
echo "  $NDKPKG"

sdkmanager --sdk_root="$SDK" \
  "platform-tools" \
  "platforms;android-$PLAT" \
  "$BUILD_TOOLS" \
  "$NDKPKG"

# --- NDK path ---
NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1)"
if [ -z "$NDK_HOME" ]; then echo "[NDK] not found under $SDK/ndk"; exit 1; fi
export ANDROID_NDK_HOME="$NDK_HOME"

# --- env file (plain exports) ---
echo "[ENV] writing $ENV"
{
  echo "export JAVA_HOME=$JAVA_HOME"
  echo "export ANDROID_HOME=$SDK"
  echo "export ANDROID_SDK_ROOT=$SDK"
  echo "export ANDROID_NDK_HOME=$NDK_HOME"
  echo 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"'
} > "$ENV"

echo
echo "OK."
echo "JAVA_HOME  -> $JAVA_HOME"
echo "SDK        -> $SDK"
echo "NDK        -> $NDK_HOME"
echo
echo "Next:"
echo "  . $ENV"
echo "  adb version && javac -version"
