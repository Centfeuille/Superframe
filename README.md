# Superframe — Inbetween

**Inbetween** is a native Android drawing and frame-by-frame animation editor. It is built with Kotlin and Jetpack Compose, with a timeline based on independently timed layers and cels.

> Project status: actively developed. Features and platform compatibility may change as development continues.

## Features

### Drawing and canvas

- Pressure- and tilt-aware stylus drawing, with finger navigation and touch gestures.
- Guided 5×5 S Pen calibration for global offset / rotation / scale / skew and position-dependent screen distortion.
- Brush and eraser presets, stabilization, pressure curves and texture controls.
- Selection, transform, fill, magic wand, line and shape tools.
- Unbounded drawing with optional bleed outside the Cut.
- Grid, onion skinning and Out of Peg reference adjustments.

### Animation timeline

- Independent timing for each drawing layer.
- Add, duplicate, delete, move and retime cels.
- Timeline zoom, playback-range controls and sequential buffered playback.
- Camera layers with animated framing and keyframes.
- Transformation folders with animated position, scale and rotation.
- Layer folders, visibility, opacity, locking and reordering.

### Reference and guides

- Import static reference images as separate, transformable layers.
- Camera framing and perspective guides, including live pressure-aware brush magnetism, independently configurable horizon / perpendicular guides, curvilinear circular-ray families between neighbouring vanishing points, and camera-lens controls linking horizontal field of view to focal length (36 mm full-frame reference). Guide rotation and 30° snapping are independent toggles.
- Reference images are guides, not drawing artwork, and are excluded from animation exports and project thumbnails.

### Media timeline — planned

The planned media workflow adds two time-based layer types:

- **Audio layers:** bring audio clips onto the timeline for synchronized playback while working on an animation. MP4 export will provide an option to include the audio; disabling it will produce a silent MP4.
- **Video layers:** bring video onto the timeline as a synchronized visual reference while editing. Video is reference material only: it is not drawing artwork, is not rendered into the animation canvas, and is not included in exports.

These media-layer capabilities are planned and should not be assumed to be available in the current build until implemented and validated.

### Import and export

- Import individual images and numbered image sequences.
- Export MP4, GIF, current-frame PNG/JPG and image-sequence ZIP files.
- Native `.inb` project files and packed `.inbetween` project archives.

### Hardware keyboard

- Physical keyboard shortcuts for common editing actions.
- Shortcuts can be customized in the app's keyboard settings.

## Getting the app

The project is an Android Studio / Gradle project. To build locally:

1. Clone or download this repository.
2. Open the project in Android Studio with a compatible Android SDK installed.
3. Sync Gradle, then build and run the `app` configuration on an Android device or emulator.

The current project configuration uses Java 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile/target SDK 34 and minimum SDK 26. Check the Gradle files for the authoritative configuration if these values change.

## GitHub Actions builds

The repository includes a workflow under `.github/workflows/` that builds an APK. When the permanent signing secrets are configured, it produces a signed release APK. Without them, it produces a debug fallback, which is not a substitute for a signed release when updating an existing installation.

See [`RELEASE_SIGNING_SETUP.md`](RELEASE_SIGNING_SETUP.md) for the one-time signing-key setup and the required GitHub Actions secrets. **Keep the release keystore and its passwords private and backed up. Never commit them to the repository.**

## Project files

- `app/` — Android application source.
- `AI_WORKING_AGREEMENT.md` — contributor and AI-assistance rules.
- `PROJECT_MEMORY.md` — current architecture, feature inventory and technical constraints.
- `CHANGELOG.md` — dated development history.
- `RELEASE_SIGNING_SETUP.md` — release signing instructions.

## Native project format

`.inb` is the native project format. The application maintains backward-compatible readers when the format evolves; consult `PROJECT_MEMORY.md` and `CHANGELOG.md` for the version currently implemented and migration notes.

## Contributing

Before changing the project, read `AI_WORKING_AGREEMENT.md` and `PROJECT_MEMORY.md`, then inspect the relevant source and recent changelog entries. Preserve existing project compatibility, avoid unrelated refactoring, and report exactly what was changed and validated.

## License

No license is currently specified. All rights remain with the respective copyright holder unless and until a license is added to this repository.


## Performance profiler

An opt-in profiler for finding stutter in drawing, audio, video and the GL compositor. It only observes: playback, rendering and project files are unchanged.

- **Open it:** the speedometer button in the editor's top toolbar shows / hides the HUD. The HUD shows FPS, frame time (average, p95, max), jank against the frame budget, dropped frames, CPU, RAM (Java, native, PSS), GC, GL prepare / commands / finish / swap, audio underruns, video dropped frames, player counts and the playback-clock numbers.
- **Budget:** Auto uses the display refresh rate (16.7 ms at 60 Hz, 11.1 ms at 90 Hz, 8.3 ms at 120 Hz). 60 / 90 / 120 pick one by hand.
- **Record:** Record / Stop in the HUD starts a detailed recording (independent of the HUD: hiding it does not stop it; a red REC indicator stays on screen). Reset clears the statistics. Pick the scenario (A-F) that matches what you are testing.
- **Export:** Export in the HUD (or the existing Diagnostics dialog) writes the diagnostic ZIP: `device-and-app.txt`, `application-log.txt`, `performance-summary.txt`, `performance-events.csv`, `frame-statistics.csv`, `media-statistics.csv`, `gl-statistics.csv`, `system-statistics.csv`, `thread-statistics.csv`, `test-session.txt`. Thread samples report CPU time per Linux task as a percentage of one core, allowing a busy main / render / media thread to be distinguished from aggregate process CPU. If Android restricts access to the process task table, the thread CSV is empty and the summary says unavailable. No drawings, project files or media are included. Everything stays on the device until you share the file.
- **Cost when off:** with the HUD hidden and no recording running, every hook returns after one flag check.
- **Limits:** frame samples and GL samples are capped (36 000 rows each, about five minutes of 120 Hz frames); later frames still count in the aggregates. A recording stops itself after 60 minutes. Per-thread samples are capped at 16,000 rows. GPU time per frame needs Android 12. Frame metrics are UI-window frames; decoder-reported dropped frames and audio underruns come from Media3 and are reported separately, never merged.

Source map: `PerformanceMetrics.kt` (data structures), `PerformanceProfiler.kt` (`Perf`: state, frame metrics, process sampler, hooks, export), `PerformanceHud.kt` (HUD, REC indicator), plus small hooks in `MediaSync.kt`, `GlLayers.kt`, `CrashDiagnostics.kt` and `MainActivity.kt`.
