#!/usr/bin/env bash
# Start the Portal Meta AI proxy on the Mac and bridge it to the Portal.
#
#   - `adb reverse` maps the Portal's 127.0.0.1:PORT to the Mac's PORT, so the
#     on-device app can POST to the proxy with no networking setup.
#   - The proxy forwards /ask to Metamate on the devserver via the ek bridge, so
#     the ek bridge must be up (`ek connect devvm423...` in a real terminal).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${PORTAL_SERIAL:-821LCM04Z1105A24}"
ADB="${ADB:-/opt/homebrew/bin/adb}"
PORT="$(python3 -c "import json,os;print(json.load(open('$HERE/server/config.json'))['port'] if os.path.exists('$HERE/server/config.json') else 8765)")"

echo "adb reverse tcp:$PORT (Portal -> Mac)…"
"$ADB" -s "$SERIAL" reverse tcp:$PORT tcp:$PORT
echo "granting RECORD_AUDIO to the app (user build can't show the dialog)…"
"$ADB" -s "$SERIAL" shell pm grant com.ikosoy.portalmetaai android.permission.RECORD_AUDIO 2>/dev/null || true

echo "starting proxy (Ctrl-C to stop)…"
exec python3 "$HERE/server/assistant_server.py"
