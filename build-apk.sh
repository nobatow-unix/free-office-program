#!/usr/bin/env bash
# ICQ New — Kali Linux-da APK ýasamak (Android Studio gerek däl).
#   cd ~/Загрузки/icq-new && bash build-apk.sh
# Birinji gezek JDK + Android SDK + Gradle ýüklenýär (~1 GB), soň çalt işleýär.
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/android-sdk"
GRADLE_HOME_DIR="$HOME/gradle-8.9"
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"

need() { command -v "$1" >/dev/null 2>&1; }

echo "==> 1/4 Java"
if ! need wget || ! need unzip; then
  sudo apt-get update -y
  sudo apt-get install -y wget unzip
fi
pick_java() {
  local v d
  for v in 17 21; do
    for d in /usr/lib/jvm/java-"$v"-openjdk-*; do
      if [ -x "$d/bin/javac" ]; then echo "$d"; return 0; fi
    done
  done
  return 1
}
JH="$(pick_java || true)"
if [ -z "$JH" ]; then
  sudo apt-get update -y
  sudo apt-get install -y openjdk-17-jdk || sudo apt-get install -y openjdk-21-jdk
  JH="$(pick_java || true)"
fi
if [ -z "$JH" ]; then echo "JDK 17/21 gurap bolmady"; exit 1; fi
export JAVA_HOME="$JH"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"

echo "==> 2/4 Android SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  wget -q --show-progress -O "$tmp/cmdtools.zip" "$CMDTOOLS_URL"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDKM" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

echo "==> 3/4 Gradle 8.9"
if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  tmp="$(mktemp -d)"
  wget -q --show-progress -O "$tmp/gradle.zip" "$GRADLE_URL"
  unzip -q "$tmp/gradle.zip" -d "$HOME"
  rm -rf "$tmp"
fi

echo "==> 4/4 APK ýasalýar (birinji gezek 10-15 min)"
echo "sdk.dir=$SDK" > local.properties
export ANDROID_HOME="$SDK"
"$GRADLE_HOME_DIR/bin/gradle" :app:assembleRelease --no-daemon

APK="app/build/outputs/apk/release/app-release.apk"
OUT_DIR="$HOME/Загрузки"
[ -d "$OUT_DIR" ] || OUT_DIR="$HOME"
cp "$APK" "$OUT_DIR/icq-new.apk"
echo
echo "TAÝÝAR: $OUT_DIR/icq-new.apk"
echo "Telefona iber we guruň (ýa-da: $SDK/platform-tools/adb install -r $OUT_DIR/icq-new.apk)"
