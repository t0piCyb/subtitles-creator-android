# subtitles-creator-android

Native Android app that transcribes videos 100% on-device with Whisper and burns subtitles
into the video using FFmpeg. Same workflow as `subtitles-creator-app` (Expo/React Native),
but fully native Kotlin + Jetpack Compose + whisper.cpp via JNI for maximum speed and
tight control over word-level timing.

Targets Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12 GB RAM) — arm64-v8a only.

## Default model: Whisper large-v3-turbo (Q5_0, ~574 MB)

Best accuracy/speed ratio for on-device transcription in 2026. Equivalent WER to
`large-v2` but 6–8× faster. Works equally well for French and English.

Downloaded on first launch from Hugging Face into app-private storage.

Alternative models are pre-configured in `ModelDownloadService.kt` (medium Q5 / small Q5).

## Word-level timing

whisper.cpp is called with three flags that together give word-perfect synchronisation
without having to reimplement forced alignment:

| Flag | Purpose |
|------|---------|
| `token_timestamps = true` | emit a timestamp per token |
| `split_on_word = true`    | cut segments on word boundaries, not token boundaries |
| `max_len = 1`             | hard-cap to 1 word per segment |

The result: every segment coming back from `whisper_full_get_segment_t0/t1` is one word
with its native start and end time. Post-processing in `WhisperService.kt` then:
1. Merges compound fragments (trailing punctuation, leading `'` or `-`) back onto their
   neighbour.
2. Enforces a 150 ms minimum visible duration so subtitles never flash.

If you need sub-token precision later, flip `cparams.dtw_token_timestamps = true` in
`whisper_jni.cpp` — whisper.cpp will run Dynamic Time Warping on the attention matrix.

## Stack

- **Kotlin 2.0 + Jetpack Compose + Material 3**
- **Media3 ExoPlayer** for preview playback
- **whisper.cpp** (v1.7.4) compiled via NDK/CMake, linked through a small JNI bridge
- **FFmpeg-Kit full** for audio extraction + ASS subtitle burning
- **OkHttp** for the one-time model download
- **kotlinx.coroutines + StateFlow** for the pipeline state

## Build

```bash
bash setup.sh                            # clone whisper.cpp + download Montserrat-Bold.ttf
./gradlew assembleDebug                  # ~5 min first build (NDK compiles whisper.cpp)
./gradlew installDebug                   # installs on connected device
```

If `setup.sh` is not executable yet:

```bash
chmod +x setup.sh && ./setup.sh
```

Requirements:

- Android Studio Ladybug+ (or CLI SDK) with NDK `27.1.12297006` and CMake `3.22.1`
- JDK 17
- A device on USB with ADB debugging enabled
- ~1.5 GB of free device storage (model + build artefacts + working videos)

## Workflow (4 screens)

```
Home ─► Transcribing ─► Editor ─► Export
```

1. **Home** — download the model once (~574 MB), pick a video from the gallery.
2. **Transcribing** — audio is extracted to 16 kHz mono PCM, then fed to whisper.cpp.
   Real-time progress via the `progress_callback` JNI hook.
3. **Editor** — ExoPlayer preview with live highlighting of the active subtitle; edit
   text, add, delete, jump to timestamp.
4. **Export** — FFmpeg burns the ASS overlay (Montserrat Bold, yellow, black outline)
   into a new MP4 and saves it to `Movies/SubtitlesCreator/` via MediaStore.

## File layout

```
subtitles-creator-android/
├── setup.sh                                — clones whisper.cpp, downloads font
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── cpp/
│   │   ├── CMakeLists.txt                  — builds libwhisper_jni.so
│   │   └── whisper_jni.cpp                 — JNI bridge (init / transcribe / cancel)
│   ├── java/com/subtitlecreator/
│   │   ├── MainActivity.kt                 — Compose NavHost, 4 routes
│   │   ├── SubtitleCreatorApp.kt           — Application
│   │   ├── jni/WhisperLib.kt               — Kotlin facade around the .so
│   │   ├── service/
│   │   │   ├── WhisperService.kt           — PCM decode + transcribe + post-process
│   │   │   ├── FFmpegService.kt            — audio extract + ASS burn
│   │   │   └── ModelDownloadService.kt     — Hugging Face download
│   │   ├── model/Subtitle.kt
│   │   ├── util/FileUtils.kt               — URI ↔ File, MediaStore save
│   │   └── ui/
│   │       ├── AppViewModel.kt             — single StateFlow pipeline
│   │       ├── theme/{Theme,Color,Type}.kt
│   │       └── screens/{Home,Transcribing,Editor,Export}Screen.kt
│   └── assets/fonts/Montserrat-Bold.ttf    — downloaded by setup.sh
└── build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
```

## Performance notes (S23 Ultra, Snapdragon 8 Gen 2)

- Build flags in `CMakeLists.txt` target `armv8.2-a+dotprod+fp16`, enabling the FP16 and
  dot-product instructions that GGML quantized kernels use for a 2–3× speed-up.
- 6 threads works best empirically (4 big cores + 2 mid). Override via
  `WhisperService.transcribe(nThreads = …)`.
- `use_gpu = false` — whisper.cpp's Vulkan backend on Adreno is not yet stable enough
  to prefer over the CPU path. Revisit when `GGML_VULKAN` matures.

## Privacy

100 % offline after the initial model download. No telemetry, no video ever leaves
the device. The only network request the app makes is the one-shot model download
from `huggingface.co`.

## Related

- `subtitles-creator/`    — original FastAPI + Whisper server version
- `subtitles-creator-app/` — React Native / Expo version (uses `whisper.rn`)
