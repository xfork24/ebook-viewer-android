#!/bin/bash
# M1 verification driver: boots a headless emulator (creating the AVD on
# first run) and executes the device-side checklist from docs/M1-验证清单.md
# via adb. Screenshot/text-evidence steps land in build/m1-evidence/.
# Usage: tools/m1_emulator_check.sh [boot|create-avd|check]
set -euo pipefail

SDK=/Users/hengliu/android-tools/android-sdk
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
IMG="system-images;android-34;google_apis;arm64-v8a"
AVD=m1viewer
EVID=build/m1-evidence

boot_adb() { "$ADB"; }

cmd="${1:-boot}"
case "$cmd" in
create-avd)
  echo no | JAVA_HOME=/opt/homebrew/opt/openjdk@17 "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    -n "$AVD" -k "$IMG" -d pixel_6 --force
  ;;
boot)
  mkdir -p "$EVID"
  "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 3
  done
  echo "BOOTED"
  ;;
check)
  mkdir -p "$EVID"
  echo "=== 1. install ==="
  "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
  "$ADB" logcat -c
  echo "=== 2. cold start ==="
  START=$(date +%s)
  "$ADB" shell am start -n dev.calibre.viewer/.MainActivity
  sleep 8
  "$ADB" exec-out screencap -p > "$EVID/01-home.png"
  echo "cold start elapsed: $(( $(date +%s) - START ))s"
  "$ADB" logcat -d -s ViewerJS ViewerServer MainActivity chromium > "$EVID/02-logcat.txt"
  echo "=== 3. open book via ACTION_VIEW ==="
  "$ADB" push /tmp/m1/test-book.epub /sdcard/Download/test-book.epub
  "$ADB" shell am start -a android.intent.action.VIEW -d "content://media/external/file/Download/test-book.epub" -t application/epub+zip dev.calibre.viewer 2>/dev/null || \
    "$ADB" shell am start -a android.intent.action.VIEW -t application/epub+zip -n dev.calibre.viewer/.MainActivity
  sleep 12
  "$ADB" exec-out screencap -p > "$EVID/03-book-open.png"
  echo "=== 4. volume page turns ==="
  "$ADB" shell input keyevent 25 && sleep 2 && "$ADB" exec-out screencap -p > "$EVID/04-page1.png"
  "$ADB" shell input keyevent 25 && sleep 2 && "$ADB" shell input keyevent 25 && sleep 2
  "$ADB" exec-out screencap -p > "$EVID/05-page2.png"
  echo "=== 5. last-read evidence ==="
  "$ADB" shell run-as dev.calibre.viewer cat files/last-read.json > "$EVID/06-last-read.json" 2>&1 || true
  cat "$EVID/06-last-read.json"
  echo "=== 6. kill + relaunch for position restore ==="
  "$ADB" shell am force-stop dev.calibre.viewer
  sleep 1
  "$ADB" shell am start -n dev.calibre.viewer/.MainActivity
  sleep 10
  "$ADB" exec-out screencap -p > "$EVID/07-after-restore.png"
  echo "=== 7. session data ==="
  "$ADB" shell run-as dev.calibre.viewer ls files/session/ || true
  "$ADB" shell run-as dev.calibre.viewer cat files/session/session_data.json > "$EVID/08-session.json" 2>&1 || true
  "$ADB" logcat -d > "$EVID/09-logcat-full.txt"
  echo "DONE, evidence in $EVID/"
  ;;
esac
