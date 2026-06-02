#!/usr/bin/env bash
# Build the Portal Meta AI APK on the devserver with buck2, pull it to dist/.
# Requires an active `ek` bridge (run `ek connect <devserver>` in a real terminal).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO/app"
DIST="$REPO/dist"
SAMPLE_SUBPATH="fbandroid/apps/samples/portal_metaai"
TARGET="fbsource//$SAMPLE_SUBPATH:portal_metaai_arm64"

SID="$(ek status -p 2>/dev/null | jq -r '.[0].session_id // empty')"
[ -n "$SID" ] || { echo "ERROR: no ek peer connected. Run 'ek connect <devserver>' in a real terminal."; exit 1; }
echo "ek peer: $SID"

REMOTE_HOME="$(ek run -s "$SID" 'echo $HOME' | tr -d '\r' | tail -1)"
DEST="$REMOTE_HOME/fbsource/$SAMPLE_SUBPATH"
echo "syncing app/ -> $DEST"

tar czf /tmp/pmai_app.tgz -C "$APP_DIR" .
ek run -s "$SID" "rm -f /tmp/pmai_app.tgz"
ek push -s "$SID" /tmp/pmai_app.tgz /tmp/ >/dev/null
ek run -s "$SID" "mkdir -p $DEST && rm -rf $DEST/* && tar xzf /tmp/pmai_app.tgz -C $DEST"

echo "building $TARGET …"
OUT="$(ek run -s "$SID" -c "$REMOTE_HOME/fbsource" "buck2 build $TARGET --show-output 2>&1" | tr -d '\r')"
echo "$OUT" | tail -5
APK_REL="$(echo "$OUT" | awk '/portal_metaai_arm64\.apk$/ {print $NF}' | tail -1)"
[ -n "$APK_REL" ] || { echo "ERROR: could not find APK path in build output"; exit 1; }

mkdir -p "$DIST"
rm -f "$DIST/portal_metaai_arm64.apk"
ek pull -s "$SID" "$REMOTE_HOME/fbsource/$APK_REL" "$DIST/" >/dev/null
cp -f "$DIST/portal_metaai_arm64.apk" "$DIST/PortalMetaAI.apk"
echo "APK -> $DIST/PortalMetaAI.apk"
ls -la "$DIST/PortalMetaAI.apk"
