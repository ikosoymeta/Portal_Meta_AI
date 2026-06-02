#!/usr/bin/env bash
# Install dist/PortalMetaAI.apk to the Portal, grant the mic permission, set up
# adb-reverse so the app can reach the Mac proxy, and launch it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO/dist/PortalMetaAI.apk"
SERIAL="${PORTAL_SERIAL:-821LCM04Z1105A24}"
ADB="${ADB:-/opt/homebrew/bin/adb}"
PKG="com.ikosoy.portalmetaai"
PORT="$(python3 -c "import json,os;p='$REPO/server/config.json';print(json.load(open(p))['port'] if os.path.exists(p) else 8765)")"

[ -f "$APK" ] || { echo "ERROR: $APK not found. Run scripts/build.sh first."; exit 1; }

echo "installing $APK -> $SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK"
echo "granting RECORD_AUDIO (user build can't show the runtime dialog)…"
"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
echo "adb reverse tcp:$PORT (Portal -> Mac proxy)…"
"$ADB" -s "$SERIAL" reverse tcp:$PORT tcp:$PORT
echo "launching $PKG/.MainActivity"
"$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity"
echo "done. Start the proxy on the Mac:  bash scripts/run.sh"
