#!/usr/bin/env python3
"""Portal Meta AI — Mac-side proxy server.

The Portal can't reach Metamate (corp auth) and has no speech-to-text engine, so
the on-device app talks to this proxy over `adb reverse` (http://127.0.0.1:PORT):

    POST /stt   body = 16 kHz mono WAV   -> {"text": "<transcript>"}
                transcribes locally with whisper.cpp (runs on the Mac)

    POST /ask   {"text": "...", "uuid": "<optional>"}
                -> {"reply": "...", "uuid": "<conversation uuid>"}
                forwards to Metamate (Meta's internal AI) on the devserver via
                `ek run -s <SID> meta metamate.conversation query`. Pass the uuid
                back on each turn to keep follow-up context.

    GET  /health -> {"ok": true, ...}

Everything is stdlib. Configure paths/ids in config.json (see config.example.json).
Run with scripts/run.sh (sets up adb reverse first).
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")


def load_config():
    cfg = {
        "port": 8765,
        # whisper.cpp runs on the devserver over the ek bridge (the Mac sandbox
        # blocks building/running it locally). Paths are on the devserver.
        "remoteWhisperCli": "/home/ikosoy/portal_metaai/whisper.cpp/build/bin/whisper-cli",
        "remoteWhisperModel": "/home/ikosoy/portal_metaai/whisper.cpp/models/ggml-base.en.bin",
        "remoteTmp": "/tmp",
        "agent": "",          # optional metamate agent, e.g. "" for default routing
        "ekTimeout": 120,
        "verbose": True,
    }
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    return cfg


CFG = load_config()
_SID = {"v": None, "ts": 0}


def log(*a):
    if CFG.get("verbose"):
        print("[%s]" % time.strftime("%H:%M:%S"), *a, flush=True)


# --------------------------------------------------------------------------- #
# Metamate (devserver via ek bridge)
# --------------------------------------------------------------------------- #
def get_sid(force=False):
    """Session id of the devserver peer; cached, refreshed on demand."""
    if not force and _SID["v"] and time.time() - _SID["ts"] < 60:
        return _SID["v"]
    try:
        out = subprocess.run(["ek", "status", "-p"], capture_output=True,
                             text=True, timeout=20).stdout
        peers = json.loads(out)
        sid = peers[0]["session_id"] if peers else None
    except Exception as e:
        log("ek status failed:", e)
        sid = None
    _SID["v"], _SID["ts"] = sid, time.time()
    return sid


def shquote(s):
    return "'" + str(s).replace("'", "'\\''") + "'"


def metamate_query(text, uuid=None):
    """Return (reply, uuid). Runs on the devserver over the ek bridge."""
    inner = "meta metamate.conversation query --output=json --prompt=" + shquote(text)
    if uuid:
        inner += " --uuid=" + shquote(uuid)
    if CFG.get("agent"):
        inner += " --agent=" + shquote(CFG["agent"])

    for attempt in (1, 2):
        sid = get_sid(force=(attempt == 2))
        if not sid:
            return ("I can't reach the assistant service — the bridge to the "
                    "devserver looks down.", uuid)
        try:
            proc = subprocess.run(["ek", "run", "-s", sid, inner],
                                  capture_output=True, text=True,
                                  timeout=CFG.get("ekTimeout", 120))
        except subprocess.TimeoutExpired:
            return ("That took too long to answer. Let's try again.", uuid)
        out = (proc.stdout or "").strip()
        m = re.search(r'\{.*"response".*\}', out, re.S)
        if m:
            try:
                obj = json.loads(m.group(0))
                return (obj.get("response", "").strip() or "(no answer)",
                        obj.get("conversation_uuid") or uuid)
            except Exception as e:
                log("json parse failed:", e, out[:200])
        log("metamate attempt %d empty/err: %s" % (attempt, (proc.stderr or out)[:200]))
    return ("Sorry, I couldn't get an answer from Meta AI just now.", uuid)


# --------------------------------------------------------------------------- #
# Speech-to-text (whisper.cpp on the Mac)
# --------------------------------------------------------------------------- #
def transcribe(wav_bytes):
    """Run whisper.cpp on the devserver: push the wav over ek, transcribe, parse."""
    sid = get_sid()
    if not sid:
        return ""
    name = "pmai_%d.wav" % time.time_ns()
    local = os.path.join(tempfile.gettempdir(), name)
    rtmp = CFG["remoteTmp"].rstrip("/")
    remote = rtmp + "/" + name
    with open(local, "wb") as f:
        f.write(wav_bytes)
    try:
        subprocess.run(["ek", "run", "-s", sid, "rm -f " + remote],
                       capture_output=True, text=True, timeout=20)
        subprocess.run(["ek", "push", "-s", sid, local, rtmp + "/"],
                       capture_output=True, text=True, timeout=30)
        cmd = "%s -m %s -f %s -l en -nt -np -t 8" % (
            CFG["remoteWhisperCli"], CFG["remoteWhisperModel"], remote)
        proc = subprocess.run(["ek", "run", "-s", sid, cmd],
                              capture_output=True, text=True, timeout=60)
        text = (proc.stdout or "").strip()
        # drop whisper non-speech annotations: [BLANK_AUDIO], (gasps), *music* …
        text = re.sub(r"[\[(*][^\])*]*[\])*]", " ", text)
        text = re.sub(r"\s+", " ", text).strip()
        # ignore transcripts with no actual words (punctuation/noise only)
        if not re.search(r"[A-Za-z0-9]", text):
            return ""
        return text
    except Exception as e:
        log("whisper failed:", e)
        return ""
    finally:
        try:
            os.unlink(local)
        except Exception:
            pass
        subprocess.run(["ek", "run", "-s", sid, "rm -f " + remote],
                       capture_output=True, text=True, timeout=20)


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # quiet default logging

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        n = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(n) if n else b""

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True, "sid": bool(get_sid())})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            if self.path == "/stt":
                text = transcribe(self._read_body())
                log("STT:", repr(text))
                self._send(200, {"text": text})
            elif self.path == "/ask":
                data = json.loads(self._read_body() or b"{}")
                q = (data.get("text") or "").strip()
                if not q:
                    self._send(200, {"reply": "", "uuid": data.get("uuid")})
                    return
                log("ASK:", repr(q), "uuid=", data.get("uuid"))
                reply, uuid = metamate_query(q, data.get("uuid"))
                log("REPLY:", repr(reply[:120]))
                self._send(200, {"reply": reply, "uuid": uuid})
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            log("handler error:", e)
            self._send(500, {"error": str(e)})


def main():
    port = CFG.get("port", 8765)
    print("Portal Meta AI proxy on :%d" % port)
    print("  STT: whisper.cpp on devserver, /ask: Metamate on devserver (via ek)")
    print("  devserver SID:", get_sid() or "NONE (run `ek connect` in a terminal)")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
