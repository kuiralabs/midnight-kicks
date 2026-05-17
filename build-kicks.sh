#!/bin/bash
# Build Midnight Kicks — full pipeline
# Rebuilds Rust FFI → SDK AARs → Kicks APK → installs on device
set -e

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

# Step 4: Install on device (if connected)
echo ""
echo "── Step 4: Install ──"
if adb devices 2>/dev/null | grep -q "device$"; then
    adb install -r "$APK"
    echo "  ✓ Installed"
else
    echo "  ⚠ No device connected — APK at $APK"
fi

echo ""
echo "═══ Done ═══"
