#!/bin/bash
# Build Midnight Kicks — full pipeline
# Rebuilds Rust FFI → SDK AARs → Kicks APK → installs on device
#
# Usage:
#   ./build-kicks.sh                    # one device connected → install on it
#   ./build-kicks.sh emulator-5554      # pick a specific device by serial
#   ./build-kicks.sh --all              # install on every connected device
#
# Without a target arg and multiple devices, the install step lists the
# devices and exits without installing (rather than picking one at
# random or breaking on adb's "more than one device" error).
set -e

TARGET_DEVICE="${1:-}"

KUIRA_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KICKS_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "═══ Midnight Kicks Build ═══"
echo "  Kuira: $KUIRA_ROOT"
echo "  Kicks: $KICKS_ROOT"
echo ""

# Step 0: Pick up newer Unity export if present
echo "── Step 0: Unity sync ──"
UNITY_EXPORT="$KICKS_ROOT/unity/build/android-export/unityLibrary"
TARGET="$KICKS_ROOT/unityLibrary"
if [ -d "$UNITY_EXPORT" ]; then
    export_mtime=$(stat -f %m "$UNITY_EXPORT" 2>/dev/null || stat -c %Y "$UNITY_EXPORT" 2>/dev/null)
    target_mtime=$(stat -f %m "$TARGET/build.gradle" 2>/dev/null || stat -c %Y "$TARGET/build.gradle" 2>/dev/null || echo 0)
    if [ "$export_mtime" -gt "$target_mtime" ]; then
        echo "  Newer Unity export detected — syncing"
        rm -rf "$TARGET"
        cp -R "$UNITY_EXPORT" "$TARGET"
        echo "  ✓ unityLibrary updated"
    else
        echo "  ✓ unityLibrary up-to-date"
    fi
else
    echo "  ⚠ No Unity export at unity/build/android-export/ — using existing unityLibrary/"
    echo "    To refresh: in Unity, menu Midnight Kicks → Export Android Library"
fi
echo ""

# Step 1: Cross-compile Rust FFI for arm64
echo "── Step 1: Rust FFI (arm64) ──"
cd "$KUIRA_ROOT"
if [ -f kuira-crypto-ffi/build-android.sh ]; then
    cd kuira-crypto-ffi && ./build-android.sh && cd "$KUIRA_ROOT"
else
    echo "ERROR: kuira-crypto-ffi/build-android.sh not found"
    exit 1
fi

# Step 2: Publish Kuira SDK to mavenLocal
#
# Every Kuira library module gets a `com.midnight.kuira:<name>:0.1.0-SNAPSHOT`
# publication with a real POM (transitive deps included). Kicks's
# `app/build.gradle.kts` consumes those coordinates from `mavenLocal()`,
# replacing the old file-based AAR refs + manually-redeclared transitive
# deps (zxing, bitcoinj, room, credentials, etc.). To pull in a new Kuira
# library on the Kicks side, add one `implementation(...)` line — no AAR
# copy, no manual transitive bookkeeping.
echo ""
echo "── Step 2: Publish Kuira to mavenLocal ──"
cd "$KUIRA_ROOT"
./gradlew :core:crypto:clean :core:compact-engine:clean --quiet
./gradlew publishToMavenLocal --quiet

# Step 3: Build Kicks APK
echo ""
echo "── Step 3: Kicks APK ──"
cd "$KICKS_ROOT"
./gradlew :app:clean :app:assembleDebug --quiet

APK="$KICKS_ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "  ✓ $(du -h "$APK" | cut -f1) APK"

# Step 4: Install on device(s)
#
# `adb install` without `-s <serial>` errors with "more than one device"
# when multiple emulators are running — common during PvP testing where
# we have 5554 + 5556 up at the same time. Pass a serial as $1 to pick
# one, or `--all` to install on every connected device. With exactly
# one device connected and no arg, default behaviour is preserved.
echo ""
echo "── Step 4: Install ──"
connected=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
n=$(printf '%s\n' "$connected" | grep -c .)

if [ "$TARGET_DEVICE" = "--all" ]; then
    if [ "$n" = "0" ]; then
        echo "  ⚠ --all specified but no device connected — APK at $APK"
    else
        echo "  Installing on $n device(s): $connected"
        for d in $connected; do
            adb -s "$d" install -r "$APK"
            echo "  ✓ Installed on $d"
        done
    fi
elif [ -n "$TARGET_DEVICE" ]; then
    if ! printf '%s\n' "$connected" | grep -qx "$TARGET_DEVICE"; then
        echo "  ✗ Device '$TARGET_DEVICE' not connected. Connected:"
        printf '%s\n' "$connected" | sed 's/^/      /'
        echo "  APK at $APK"
        exit 1
    fi
    adb -s "$TARGET_DEVICE" install -r "$APK"
    echo "  ✓ Installed on $TARGET_DEVICE"
elif [ "$n" = "1" ]; then
    adb install -r "$APK"
    echo "  ✓ Installed on $connected"
elif [ "$n" = "0" ]; then
    echo "  ⚠ No device connected — APK at $APK"
else
    echo "  ⚠ Multiple devices connected — pass one as arg:"
    printf '%s\n' "$connected" | sed 's/^/      ./build-kicks.sh /'
    echo "    Or use --all to install on every device."
    echo "  APK at $APK"
fi

echo ""
echo "═══ Done ═══"
