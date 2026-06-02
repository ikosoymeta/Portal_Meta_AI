# Portal Meta AI — voice assistant

A hands-free **Meta AI voice assistant** for the Meta Portal. Launch it from the
home screen, then either **tap the orb** or say **“Hi Meta”** to start a
conversation; speak naturally for follow-ups; say **“Meta Stop”** (or tap the
active orb) to end. The orb is **idle** when off and **active** during a session.
If you go quiet, it asks whether you want to end the conversation.

## How it works

The Portal can't run speech‑to‑text (no Google speech services) and can't reach
Meta AI directly (corp auth), so the app is **tethered to your Mac** (like the
Portal Calendar) and uses the devserver for the heavy lifting:

```
Portal app  (mic capture + orb UI + Nuance TTS)
   │  http://127.0.0.1:8765   (adb reverse)
   ▼
Mac proxy   (server/assistant_server.py)
   ├─ /stt  → whisper.cpp           (speech→text, on the devserver via `ek run`)
   └─ /ask  → meta metamate.conversation query   (Meta AI, on the devserver; --uuid threads follow-ups)
```

- **Speech‑to‑text:** whisper.cpp (`ggml-base.en`) built and run on the devserver.
- **Brain:** Metamate (Meta's internal AI) via `meta metamate.conversation query`.
- **Text‑to‑speech:** Android `TextToSpeech` pinned to `com.facebook.aloha.fbttsservice`
  (Nuance “Zoe”) — the default Portal engine (FbGiga5) has no third‑party voice model.
- **Wake/stop words** are matched from the transcript (no fragile hotword engine):
  “Hi/Hey Meta” starts, “Meta Stop” ends.

## Layout

```
app/        Android app (WebView orb + native mic/VAD/HTTP/TTS).  Build with buck2.
server/     assistant_server.py (Mac proxy) + config.  whisper.cpp lives here (gitignored).
scripts/    build.sh (devserver buck2), deploy.sh (install+grant+adb reverse), run.sh (proxy), gen_icons.py
```

## Run it

Prereqs: the `ek` bridge to the devserver is up (`ek connect devvm423…` in a real
terminal), and whisper.cpp is built on the devserver (see “Devserver setup”).

```bash
bash scripts/build.sh     # build the APK on the devserver
bash scripts/deploy.sh    # install, grant RECORD_AUDIO, adb reverse, launch
bash scripts/run.sh       # start the Mac proxy (keep running)
```

Then on the Portal: tap the orb or say **“Hi Meta”**.

## Devserver setup (one‑time)

whisper.cpp is built on the devserver because the Mac sandbox blocks cmake/codesign.
Push the sources + model and build with a conservative ISA (the devserver assembler
is too old for AVX‑VNNI):

```bash
# from server/whisper.cpp on the Mac, push sources (no .git/build/models) + the
# ggml-base.en.bin model to /home/<user>/portal_metaai/whisper.cpp on the devserver, then:
cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
      -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON \
      -DGGML_AVX512=OFF -DGGML_AVX_VNNI=OFF
cmake --build build --config Release --target whisper-cli -j 16
```

Paths are configurable in `server/config.json` (`remoteWhisperCli`, `remoteWhisperModel`).

## Notes / limits

- While **active**, it responds to *any* nearby speech (it's always listening) —
  end with “Meta Stop” or a tap.
- Corp `@meta.com` is the identity used for Metamate (via the bridge), so answers
  have your internal context.
- Latency per turn ≈ STT (~2 s) + Metamate (~3–8 s).
