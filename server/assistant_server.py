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
        "remoteWhisperModel": "/home/ikosoy/portal_metaai/whisper.cpp/models/ggml-small.en.bin",
        "remoteTmp": "/tmp",
        # bias the decoder toward the wake/command phrases (helps short far-field clips)
        "whisperPrompt": "Hi Meta. Meta Stop. Meta Go Home. Yes. No.",
        "agent": "",          # optional metamate agent, e.g. "" for default routing
        "ekTimeout": 120,
        "verbose": True,
        # OPTIONAL per-user 6-digit employee ID. Not required: the identity
        # (name/title/team/FBID) is auto-pulled from `meta people` and Metamate
        # resolves you from your FBID. Set this only for extra context.
        "employeeId": "",
    }
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    return cfg


CFG = load_config()
_SID = {"v": None, "ts": 0}
USER_CTX = ""          # one-line identity preamble injected on new conversations


def log(*a):
    if CFG.get("verbose"):
        print("[%s]" % time.strftime("%H:%M:%S"), *a, flush=True)


# --------------------------------------------------------------------------- #
# User identity context (auto-pulled, to personalize Metamate answers)
# --------------------------------------------------------------------------- #
def _fetch_profile():
    """Auto-pull the configuring user's profile via `meta people.profile get`."""
    try:
        import getpass
        u = getpass.getuser()
        proc = subprocess.run(
            ["meta", "people.profile", "get", "--unixname", u, "--output=json"],
            capture_output=True, text=True, timeout=30)
        m = re.search(r"\{.*\}", proc.stdout or "", re.S)
        return json.loads(m.group(0)) if m else {}
    except Exception as e:
        log("profile fetch failed:", e)
        return {}


def _fetch_worker_location(fbid):
    """Best-effort coarse location (e.g. 'US - CA - Bay Area') via the devserver."""
    sid = get_sid()
    if not sid or not fbid:
        return ""
    try:
        inner = "meta people.worker describe --id=%s --output=json" % fbid
        proc = subprocess.run(["ek", "run", "-s", sid, inner],
                              capture_output=True, text=True, timeout=40)
        m = re.search(r"\{.*\}", proc.stdout or "", re.S)
        if m:
            return (json.loads(m.group(0)).get("locationName") or "").strip()
    except Exception as e:
        log("worker location fetch failed:", e)
    return ""


def build_user_context():
    """Build USER_CTX from the auto-pulled profile + the configured employee ID."""
    global USER_CTX
    p = _fetch_profile()
    eid = str(CFG.get("employeeId") or "").strip()
    bits = []
    if p.get("name"):
        bits.append(p["name"] + (" (%s)" % p["unixname"] if p.get("unixname") else ""))
    if eid:
        bits.append("employee ID " + eid)
    if p.get("title"):
        bits.append(p["title"])
    if p.get("team"):
        bits.append("team " + p["team"])
    # location / timezone so answers can be geo/time aware
    loc = (p.get("location") or "").strip()
    if (not loc) or ("remote location" in loc.lower()):
        wl = _fetch_worker_location(p.get("id"))    # e.g. "US - CA - Bay Area - Remote"
        if wl:
            loc = wl
    if loc:
        bits.append("based in " + loc)
    if p.get("timezone"):
        bits.append("timezone " + p["timezone"])
    if p.get("id"):
        bits.append("FBID " + p["id"])
    USER_CTX = ("" if not bits else
                "[Context about me, the person you are assisting: " + "; ".join(bits) +
                ". Use this to tailor your answers; don't repeat it back to me.]")
    log("user context:", USER_CTX or "(none)")
    return USER_CTX


def _with_context(text):
    return (USER_CTX + "\n\n" + text) if USER_CTX else text


def clean_markdown(t):
    """Strip markdown so the reply reads naturally aloud and displays cleanly
    (no spoken '## ' / '**' etc.)."""
    if not t:
        return t
    t = re.sub(r"```.*?```", " ", t, flags=re.S)          # code fences
    t = re.sub(r"`([^`]*)`", r"\1", t)                    # inline code
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", t)           # images
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)        # links -> text
    t = re.sub(r"(?m)^\s{0,3}#{1,6}\s*", "", t)           # headings at line start
    t = re.sub(r"\*\*([^*]+)\*\*", r"\1", t)              # bold
    t = re.sub(r"__([^_]+)__", r"\1", t)
    t = re.sub(r"\*([^*]+)\*", r"\1", t)                  # italics
    t = re.sub(r"~~([^~]+)~~", r"\1", t)
    t = re.sub(r"(?m)^\s{0,3}[-*+]\s+", "", t)            # bullet markers
    t = re.sub(r"(?m)^\s{0,3}>\s?", "", t)                # blockquote
    t = t.replace("##", "").replace("**", "").replace("`", "")  # stray leftovers
    t = re.sub(r"[ \t]{2,}", " ", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    return t.strip()


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
    # On a new conversation, lead with the user's identity context so Metamate
    # tailors answers; later turns inherit it via the conversation uuid.
    if not uuid:
        text = _with_context(text)
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
                return (clean_markdown(obj.get("response", "").strip()) or "(no answer)",
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
        prompt = str(CFG.get("whisperPrompt") or "").replace("'", "")
        pflag = (" --carry-initial-prompt --prompt '%s'" % prompt) if prompt else ""
        cmd = "%s -m %s -f %s -l en -nt -np -t 8 --beam-size 5%s" % (
            CFG["remoteWhisperCli"], CFG["remoteWhisperModel"], remote, pflag)
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
            self._send(200, {"ok": True, "sid": bool(get_sid()), "context": bool(USER_CTX)})
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
    build_user_context()
    print("  user context:", USER_CTX or "(none — set employeeId in config.json)")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
