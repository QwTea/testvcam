# VirtualCam LSPosed Module

VirtualCam is an LSPosed module that replaces camera frames with user-selected media. The project is written entirely in Kotlin, uses Jetpack Compose Material 3 for its UI, and provides hooks for legacy `android.hardware.Camera`, Camera2, and CameraX pipelines. The module installs without additional configuration and stores its settings in device protected storage so that hooked processes can access the preferences before user unlock.

## Features

* Compose Material 3 GUI with previews for still images and video sources.
* Device protected, world-readable preferences plus a Room-backed recent history list.
* SAF proxy content provider to re-expose the selected media URI to hooked clients.
* Media pipeline with EXIF-aware orientation handling, bitmap scaling, and YUV/JPEG converters.
* Legacy camera hooks replacing preview callbacks and still captures.
* Camera2 preview surface swap with dummy surfaces and canvas painters.
* CameraX analyzer, image capture, and surface request interception.
* Diagnostics screen showing pipeline status and the last 20 log lines.

## Architecture

```
App UI (Compose)
 ├─ MainActivity – settings + SAF preview
 ├─ HistoryActivity – Room backed recent list
 ├─ DiagnosticsActivity – live metrics/logs
 └─ AboutActivity – version and disclaimer

Storage
 ├─ ModulePrefs (Device Protected Storage + MODE_WORLD_READABLE)
 ├─ VirtualCamProvider (SAF proxy)
 └─ Room database (Recent items)

Hooks
 ├─ HookEntry – installs once per target process
 ├─ FakeFrameInjector – bitmap/video scheduler + converters
 ├─ VideoDecoder – MediaMetadataRetriever/MediaCodec frames
 ├─ CameraXHooks – Analyzer/ImageCapture/SurfaceRequest
 └─ GlSurfacePusher – preview painter abstraction

Pipeline
 Source (SAF) → ExifUtil → BitmapTransform → BitmapConverters
   → FakeFrameInjector → {NV21 | YUV420 | JPEG} → Hooked API
```

## Media Flow

```
[User Media] --(SAF)--> [ModulePrefs]
                      \-> [VirtualCamProvider]
[HookEntry] -> [FakeFrameInjector]
   ├─ loadBitmap()/VideoDecoder
   ├─ scaleBitmap()/applyOrientationAndMirror
   ├─ bitmapToNV21 / writeBitmapIntoYUV420 / bitmapToJpeg
   └─ DiagnosticsState (active path + fps)
```

## GREP Markers

Key implementation sections are annotated with `// GREP:` comments so that automation scripts can quickly locate the relevant code:

| Marker | File | Purpose |
|--------|------|---------|
| `UI_MAIN` | `ui/MainActivity.kt` | Compose control panel |
| `UI_HISTORY` | `ui/HistoryActivity.kt` | Room backed history list |
| `UI_DIAG` | `ui/DiagnosticsActivity.kt` | Diagnostics metrics/logs |
| `UI_ABOUT` | `ui/AboutActivity.kt` | Version info + disclaimer |
| `PREFS_DPS_WORLD_READABLE` | `prefs/ModulePrefs.kt` | Device protected preferences |
| `PROVIDER_SAF_PROXY` | `provider/VirtualCamProvider.kt` | SAF proxy provider |
| `ROOM_*` | `data` package | Room entity/DAO/database |
| `EXIF_ORIENTATION` | `util/ExifUtil.kt` | Orientation helper |
| `BITMAP_TRANSFORM` / `YUV_CONVERTERS_IMPL` | `util/BitmapConverters.kt` | Bitmap scaling and YUV/JPEG conversion |
| `SURFACE_*` | `util/SizeUtil.kt` | Surface detection and painter |
| `INJECT_PREVIEW_AND_FRAMES` | `xposed/FakeFrameInjector.kt` | Hook core |
| `DECODER_CODEC_OR_MMR` | `xposed/VideoDecoder.kt` | Media decoding |
| `HOOK_CAMERAX` | `xposed/CameraXHooks.kt` | CameraX interception |
| `PREVIEW_CANVAS_OR_EGL` | `xposed/GlSurfacePusher.kt` | Preview painter |
| `HOOK_ENTRY` | `xposed/HookEntry.kt` | LSPosed entry point |

## Build & Verification

The project ships with the Gradle wrapper scripts (delegating to the environment Gradle ≥8.7). Build and lint the module with:

```bash
./gradlew clean assembleDebug lint ktlintCheck
./gradlew test
```

Install to a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable the module from LSPosed Manager, scope it to camera/RTC packages, and read logs using:

```bash
adb logcat | grep -i VirtualCam
```

## Runtime Usage

1. Launch VirtualCam and choose Photo or Video.
2. Pick a file via SAF; the module persists URI permissions.
3. Adjust scaling, FPS, format, API priority, and injection flags.
4. Save to persist settings and update the recent history list.
5. Enable the module in LSPosed, reboot target apps, and observe substituted frames.

## Diagnostics

`DiagnosticsActivity` renders `DiagnosticsState` snapshots showing:

* Active path (Legacy / Camera2 / CameraX / Static / Video).
* Negotiated preview size & format.
* Requested vs. measured FPS.
* The last 20 log messages buffered by `VirtualCamLogBuffer`.

## Troubleshooting

| Signature | Root Cause | Fix |
|-----------|------------|-----|
| `// GREP: AMBIGUITY_FIND_METHOD_EXACT` | Attempted to hook parameterless methods with `findMethodExact` causing overload ambiguity. | Use Java reflection (`getMethod()/getConstructor()`) as shown in Camera2 hooks. |
| `// GREP: AAPT_IC_LAUNCHER_MISSING` | Missing launcher mipmap resources during build. | Ensure adaptive icon XML/vector assets exist (`mipmap-anydpi-v26/ic_launcher.xml`). |
| `// GREP: JVM_TARGET_MISMATCH` | Kotlin and Java toolchain targets differ. | Both Gradle `compileOptions` and `kotlinOptions.jvmTarget` set to 17; the toolchain is configured to Java 17. |
| `// GREP: CAMERA2_SESSION_REJECTED` | Camera2 session fails when non-preview surfaces are replaced. | `FakeFrameInjector` guards replacements with `isPreviewSurface` and skips ImageReader outputs. |

## README_DISCLAIMER

This module is intended **only for testing and QA on your own devices**. Respect local laws, app policies, and user privacy. Do **not** deploy VirtualCam to defeat biometrics, anti-fraud, or DRM systems.

## Ideas for Improvement

* Auto-select the closest supported preview size by querying device characteristics and matching to source aspect ratio.
* Implement a ring buffer of generated frames to reduce allocations during high-FPS playback.
* Add lightweight profiling to track bitmap conversions and identify hot spots for further optimization.

## Suggested Refactor

Introduce a dedicated `PreviewInjector` interface with Canvas and GL/EGL implementations. Hooks would depend on the abstraction rather than directly calling `startSurfacePainter`, making it easier to swap rendering strategies per API or device.

