# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this project is

Native Android app that transcribes a video 100% on-device with Whisper, lets the
user edit each word in a Compose editor, then burns the subtitles into a new MP4.
No backend, no network except the one-time model download from Hugging Face.

Targets the **Samsung Galaxy S23 Ultra** (Snapdragon 8 Gen 2, arm64-v8a only).

## Stack

- **Kotlin 2.0 + Jetpack Compose + Material 3** — UI
- **Media3 ExoPlayer** — preview playback in the editor
- **Media3 Transformer + TextOverlay** — subtitle burn-in (hardware MediaCodec H.264)
- **whisper.cpp v1.7.4** — compiled via NDK/CMake, called through a thin JNI bridge
- **FFmpeg-Kit (moizhassan fork, 16kb-aligned)** — used *only* for 16 kHz mono PCM
  audio extraction. Note: this fork is a minimal build without libx264 / libass /
  MediaCodec, which is **why we don't use FFmpeg for the burn step** — Media3
  Transformer handles encoding + overlay via Android's native pipeline instead.
- **kotlinx.coroutines + StateFlow** — async + UI state
- **OkHttp** — one-shot model download

## Architecture

```
MainActivity (Compose NavHost: Home → Transcribing → Editor → Export)
      │
      ▼
AppViewModel (thin) ── StateFlow ←─────────────  PipelineStore (singleton)
      │                                                 ▲
      │ startForegroundService(ACTION_*)                │ update state
      ▼                                                 │
PipelineService  (foreground service, keeps work alive in background / screen-off)
      │
      ├─► ModelDownloadService  → HF → files/models/
      ├─► FFmpegService         → 16 kHz mono PCM WAV
      ├─► WhisperService        → whisper.cpp JNI → word-level subtitles
      └─► SubtitleBurner        → Media3 Transformer + TextOverlay → MP4
```

State is owned by the process-lived `PipelineStore` object, **not** the ViewModel.
This is deliberate: the activity can be destroyed while `PipelineService` keeps
running in the background; UI reconnects to the same state on return.

## Word-level timing

whisper.cpp is invoked with three flags that together emit one word per segment
with native start/end times (no external alignment needed):

- `token_timestamps = true`
- `split_on_word = true`
- `max_len = 1`

Post-processing in `WhisperService` only merges trailing punctuation / leading
`'` / `-` fragments back onto their neighbour. **It does *not* pad durations**
— we trust whisper's `t0/t1` so silences between words stay silent on screen.

If you need sub-token precision, flip `cparams.dtw_token_timestamps = true` in
`whisper_jni.cpp` and pick an aheads preset matching the loaded model.

## Default model: Whisper small Q5_1 (~182 MB)

Chosen for on-device speed + good FR/EN quality. Two alternatives are
pre-configured in `ModelDownloadService.kt`:

| Model | Size | Notes |
|---|---|---|
| `SMALL_Q5` *(default)* | 182 MB | 3–4× real-time on S23 Ultra |
| `MEDIUM_Q5` | 515 MB | Better accuracy, ~2× real-time |
| `LARGE_V3_TURBO_Q5` | 574 MB | Best accuracy, ~3–4× real-time with optimized GGML build |

To change the default, edit `ModelDownloadService.defaultModel`.

## Build

```bash
bash setup.sh                 # clones whisper.cpp v1.7.4 + downloads Montserrat-Bold.ttf
./gradlew assembleDebug       # NDK compiles whisper.cpp (~1 min clean, ~5 s incremental)
./gradlew installDebug        # installs on connected device
```

Requires: Android SDK 35, NDK `27.1.12297006`, CMake `3.22.1`, JDK 17.

## Critical CMake note

`app/src/main/cpp/CMakeLists.txt` sets `GGML_NATIVE=OFF` and forces
`-march=armv8.2-a+dotprod+fp16`. **Don't remove these** — without them GGML's
own CMake `try_compile` feature probe runs against the host toolchain on
cross-builds, fails ("Failed to get ARM features"), and silently falls back to
a scalar C path that's 5–10× slower on quantized models.

## Foreground service

Any long operation (model download, transcription, export) runs inside
`PipelineService` (foreground, `dataSync` type) with a persistent notification
and a `PARTIAL_WAKE_LOCK`. The UI attaches via `PipelineStore.state` — if the
user backgrounds the app or the screen turns off, work keeps going.

`TranscribingScreen` + `ExportScreen` also set `FLAG_KEEP_SCREEN_ON` so the
display stays lit (a dim display on Android → Doze-like CPU throttling).

## File layout

```
app/src/main/
├── AndroidManifest.xml
├── cpp/
│   ├── CMakeLists.txt                  — builds libwhisper_jni.so
│   └── whisper_jni.cpp                 — JNI bridge (init / transcribe / cancel)
├── java/com/subtitlecreator/
│   ├── MainActivity.kt                 — Compose NavHost, 4 routes
│   ├── SubtitleCreatorApp.kt           — Application
│   ├── jni/WhisperLib.kt               — Kotlin facade around the .so
│   ├── service/
│   │   ├── PipelineService.kt          — foreground service
│   │   ├── PipelineStore.kt            — singleton StateFlow<UiState>
│   │   ├── WhisperService.kt           — PCM decode + transcribe + post-process
│   │   ├── FFmpegService.kt            — audio extract
│   │   ├── SubtitleBurner.kt           — Media3 Transformer + TextOverlay burn
│   │   └── ModelDownloadService.kt     — Hugging Face download
│   ├── model/Subtitle.kt
│   ├── util/FileUtils.kt               — URI ↔ File, MediaStore save
│   └── ui/
│       ├── AppViewModel.kt             — thin delegate to PipelineStore + service
│       ├── theme/{Theme,Color,Type}.kt
│       ├── components/KeepScreenOn.kt
│       └── screens/{Home,Transcribing,Editor,Export}Screen.kt
└── assets/fonts/Montserrat-Bold.ttf    — downloaded by setup.sh
```

## Things to watch out for

- **TextOverlay empty text** — Media3 1.5.1's `TextOverlay.getBitmap()` crashes
  with `IllegalArgumentException: width and height must be > 0` when the text
  measures to zero. `SubtitleBurner` returns a transparent `·` placeholder
  during silences. Don't change this to `""` or a size-1 space.

- **FFmpeg-Kit fork is minimal** — no libx264, no libass, no MediaCodec. Only
  used for `pcm_s16le` audio extraction. For any other FFmpeg-based operation,
  switch to Media3 Transformer or find a fuller fork.

- **NDK host prebuilt** — on Apple Silicon Macs the NDK still reports
  `darwin-x86_64` (runs under Rosetta). This is a host-side build concern only,
  the resulting arm64-v8a binaries are native.

## Privacy

100% offline after the initial model download. Only network call: the one-shot
GGML model fetch from `huggingface.co`. Nothing else leaves the device.
