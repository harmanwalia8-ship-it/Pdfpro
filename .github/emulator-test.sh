#!/usr/bin/env bash
# Runs inside the booted emulator job: installs the app + tests, runs the smoke test,
# and collects screenshots and the log into emulator-output/.
set -u
OUT=emulator-output
mkdir -p "$OUT"

# The CI emulator is slow; keep its own "X isn't responding" pop-ups out of the screenshots.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c

# Run the instrumentation directly (not via Gradle) so the app and its screenshots
# stay on the device afterwards for us to pull.
adb shell am instrument -w -r app.margin.pdf.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"

adb pull /sdcard/Android/data/app.margin.pdf/files/screenshots "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true

grep -q "^OK (" "$OUT/instrument.txt"
