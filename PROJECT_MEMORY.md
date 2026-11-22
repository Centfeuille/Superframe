Inbetween — Project Memory

«Purpose: Current project architecture, feature inventory, source map and technical constraints. This document describes the current known state, not the full development history.

Mandatory: Every AI contributor must read this document and "AI_WORKING_AGREEMENT.md" before working. Historical changes are recorded in "CHANGELOG.md".

Source of truth: The code is authoritative for actual implementation. When code and documentation disagree, investigate and update this document.»

1. Project identity

- App name: Inbetween
- Repository: Superframe
- Platform: Android
- UI: Jetpack Compose and Material 3
- Language: Kotlin
- Application ID / namespace: `com.example.inbetween`
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- CI Gradle version: 8.9
- Compile SDK: 34
- Target SDK: 34
- Minimum SDK: 26
- Java/Kotlin target: Java 17
- Version in this update: CI version code from `github.run_number` (local fallback 2), version name 0.4

The project is a native Android 2D drawing and frame-by-frame animation editor.

The central document concept is called a Cut. A Cut contains layers, cels, drawing operations, timing and optional camera/perspective guides.

Source/package naming note

The Kotlin package, Android namespace and application ID are `com.example.inbetween`. The source files are physically stored under `app/src/main/java/com/exemple/inbetween/` in the GitHub repository. This directory spelling is intentional for this repository snapshot: `exemple` is the folder name, while `example` is the declared Kotlin package. Do not rename either or treat the difference as an error without an explicit migration plan.

2. Feature inventory

2.1 Project and document management

- Landing screen lists saved Cuts and supports creating a new Cut.
- New Cut settings include name, frame rate, DPI, width and height. Current creation defaults are 24 fps and 144 DPI.
- Preset canvas sizes: 720p, 1080p, 4K and square.
- Document settings support width/height/aspect-ratio and DPI/frame-rate locks.
- Cuts are stored in the app's files directory.
- Native ".inb" format stores document metadata, layers, cels, drawing operations, camera data and perspective data.
- Packed ".inbetween" documents are ZIP files containing "cut.inb" and "thumb.png".
- Import assigns a new document ID.
- Storage reader supports V1–V23; current writer is V23. V12 adds per-cel link indices; V13 brush tip / texture / anti-aliasing; V14 dab jitter and wet mix; V15 persistent-cel flags and inbetween labels; V16 brush flow / cap, texture controls, wet pressure and embedded brush assets; V17 selection-clipped strokes and warp meshes; V18 independent perspective-guide rotation / lock / curvilinear settings; V19 per-guide curvature strength; V20 per-guide 30-degree rotation snapping and horizontal camera FOV; V21 per-guide curvilinear ray counts; V22 stores physical focal length directly; V23 stores the choice between vanishing-point-ray and guide-line magnetism. Builds older than V23 may not open V23 files. V9 introduced audio/video clip metadata; V10 embeds imported audio/video media bytes in the `.inb` document while retaining older-reader compatibility.
- Saving uses a temporary file and rename.
- Deleting a Cut removes its saved file.

2.2 Canvas and input

- Compose-based drawing canvas.
- Multi-frame selection transform: when the current playhead cel is among `ed.selCels`, `beginTransform` lifts the current selection mask from each selected editable drawing cel; one shared matrix is previewed and committed to each cel as its own existing region-transform operation. The playhead cel is the gizmo reference. Selected cels sharing the same stroke list are deduplicated.
- Onion skin: `onionAllLayers` controls whether neighbouring cels are drawn from every visible drawing layer (default on, preserving prior behavior) or only the active drawing layer. Opacity decays by a factor of 0.68 for each additional cel away from the playhead. The detailed Onion Options pop-up exposes this toggle.
- Document-to-screen and screen-to-document transforms.
- Stylus and eraser input through Android "MotionEvent", including pressure, tilt and event history.
- Stylus hover cursor preview; recent hover movement direction is used to choose perspective magnet paths.
- When a perspective layer is visible, brush and eraser strokes latch to the selected perspective path. The perspective layer has a saved magnet mode: vanishing-point mode uses VP rays as authoritative candidates (not competing with the horizon/perpendicular axes); guide mode magnetizes only to those two straight axes. If VP mode is selected with no VPs placed, the guide axes are used as a safe fallback. The straight-line shape tool shares the hover-guided latch and follows a sampled curved VP path when curvilinear mode is active.
- Finger gestures:
  - One finger pans.
  - Two fingers pinch-zoom and rotate.
  - Two-finger tap invokes Undo.
  - Two fingers coming within `RESET_PINCH_DP` (112 dp, `Tools.kt`) of one another and then releasing resets the view; the canvas tints purple while the reset gesture is armed. The old quick-pinch reset is removed.
  - All active finger contacts show touch rings on the canvas.
  - Three-finger drag scrubs through drawings/cels; with a Camera or Transformation layer selected, it steps through that layer's diamond-key times. Every cel / key step bumps a purple-to-white gradient on the left or right canvas edge, on the side the scrub moves toward (the old bottom-centre dot is removed).
- Canvas supports zoom, rotation, pan, horizontal flip and vertical flip. Finger gesture rotation snaps to 30° increments when the rotation gesture ends.
- Grid is always enabled and defaults to 30 document-pixel spacing. Its renderer uses adaptive level of detail: as zooming out makes base grid lines too dense or the visible document span would exceed the line budget, it draws a coarser exact-multiple interval (1, 2, 5, 10, 20... times the configured spacing). This keeps lines about 8 screen pixels apart and limits each axis to roughly 180 lines; zooming in restores the configured spacing. Tapping the grid toolbar button switches between Behind and In Front; the detailed Grid popup contains density only. In Behind mode the grid is drawn before the opaque white Cut page, so it is visible in the surrounding workspace but fully hidden over the white page. In Front mode the grid is drawn over the white page across the entire drawing workspace, including outside the Cut. In both modes all artwork is rendered after the grid and therefore always appears above it. Long-pressing the grid button opens density options. The video-mode Behind-grid plane uses the same adaptive interval.
- Onion skin for adjacent cels. Hold the onion button for Fade out / Turn off when scrubbing options. Out of Peg is a separate timeline control.
- Out of Peg: an independent timeline toggle, with Previous / Following adjustment buttons. The active adjacent onion reference (the previous / following drawing relative to the selected frame) can be moved, scaled, rotated and flipped on-canvas; the pose belongs to that specific drawing (cel), not to every onion skin of the layer, and a cyan dot in the top-right corner of its timeline cel shows it has an Out of Peg pose (it disappears when that pose is reset); its toolbar provides horizontal / vertical invert, aspect lock, 30-degree rotation magnet, reset and Apply. Apply retains the session-only pose and exits adjustment mode; turning Out of Peg off hides all reference poses and restores original onion positions. Source cels are never modified, and poses are not serialized into `.inb`.
- Unbounded drawing / Bleed button in the upper bar (`BleedBtn`, a frame with a stroke running past its edge; tap toggles, hold opens Bleed options via `ed.viewOpt == 3`). The options popup has the same toggle plus **Delete bleed ink** with a scope (`Editor.bleedScope`: This frame / This layer / Whole Cut). `clearBleedInk` (`Tools.kt`) replaces each touched cel with a trimmed copy inside one undo step: brush/eraser strokes keep every point whose dab can still touch the Cut (the Cut grown by the brush radius) and segments crossing that boundary are cut there, so the part inside the Cut looks the same and a stroke leaving and re-entering becomes several strokes; fills are clipped to the Cut; selection moves (kind 2) and imported images (kind 3) are not trimmed; locked layers are skipped. Unbounded drawing: ink is stored in unbounded document coordinates and every cel is rasterized into a sparse tile set (`Tiles.kt`: `TileCache` / `TileSet`, 512 px tiles that exist only where ink exists, within ±40960 px of the origin; farther ink is kept in the document but not rasterized). The canvas draws only the tiles in view. Bleed on = tiles are shown outside the Cut; Bleed off = the Cut rectangle clips them (nothing is deleted). Live strokes stamp straight into the touched tiles, so a stroke is visible in real time anywhere, including beyond the frame, with no release hitch. Standard render, thumbnails, flatten and exports use `CelCache`, a Cut-cropped bitmap derived from the tiles, so they stay cropped to the Cut and always match the canvas inside it (a moving Camera therefore does not reveal ink outside the Cut in exports). Tile memory budget follows `CelCache.budget` + 32 MB; sets are evicted LRU and rebuilt by replaying strokes (a set being drawn into live, by a stroke or a selection transform, is pinned). Selection, wand and fill masks cover the Cut with Bleed off (unchanged); with Bleed on they cover a document-space window (`Sel.ox/oy`, `workWindow`): the path bounds for lasso/rectangle selection, and Cut + painted content + visible area for wand/fill (capped to 8 MP, else a 3200x2400 window around the touch). Fill is therefore bounded by that window, not infinite.
- Reference image layers (kind 5): one import (one or several images picked together) becomes one static layer holding all of them (one cel, one kind-3 ink per image); the timeline's "Add reference" adds images to the selected reference layer. The layer has independent visibility, opacity, locking and z-order. The `.inb` stores, per image, only the Android document-provider URI and a 3×3 document-space matrix; image pixels are never embedded in `.inb` or its thumbnail. Details of the multi-image editing: see "2026-11-15 (4) — Reference layers" at the end of this file. The app requests persistable read access and decodes images on demand into a bounded cache (maximum 4096 px edge / 12 MP). If a URI can no longer be opened when a project loads, that image is silently dropped (the layer too when no image is left). Tapping an image auto-selects its layer; finger and S Pen can move it from inside the image, scale from corner handles, stretch from side handles, and rotate from exterior-corner zones. Flip H/V, aspect lock and rotation magnet are available. Reference layers can extend beyond the Cut frame on the canvas; they are guide-only and are excluded from exports and project thumbnails.
- Audio layers (kind 6) and video reference layers (kind 7): import through Android's document picker, copy the selected media into app-owned storage, and embed the media bytes in V10 `.inb` documents. The URI stored in the live layer points to the app-owned copy, not the original provider URI. Place the clip at the current playhead. Clip duration is derived from source duration and the Cut frame rate. Timeline blocks can be dragged horizontally to move clips; deleting the layer removes the reference. Audio uses one Media3 ExoPlayer per visible audio clip, with continuous playback and seeks only on clip entry, pause/resume, or timeline jumps. Video uses Media3 ExoPlayer / Android MediaCodec hardware decoding into an opaque TextureView, composited inside the Compose window beneath the drawing Canvas. The TextureView must NOT be flagged `isOpaque = true`: on the tablet that made the video darken (blended against black) instead of fading toward the white page when layer opacity was below 100%. The layer's user-controlled opacity stays on the parent graphicsLayer with the default compositing strategy (`ModulateAlpha` was also tried and removed together with `isOpaque`, 2026-11-13). TextureView is intentional: a separate SurfaceView / GLSurfaceView plane previously lagged during viewport pan / zoom / rotation and punched through the Compose page and grid. Workspace viewport transforms are applied on a workspace-sized graphics layer; the fitted video image has a second graphics layer for its saved media transform, animated Transformation-folder transform and opacity. The folder channels are evaluated as scalar values in the graphics-layer block to avoid allocating a temporary GroupTransform on each playback frame. Drawing remains in Compose above the video. Video-layer transform handles are shown only when that unlocked video layer is selected; selected-video stylus/finger gestures edit its media transform, while drawing layers retain normal drawing input. The video page is laid out at its fitted screen-space dimensions before applying user transforms, avoiding Compose constraint clamping that could distort the canvas/video aspect ratio. When video is active and Grid is set to Behind, the grid plane is placed above the white page but below the video texture; when Front is selected, the normal foreground grid pass is used. GL video surface alpha is the layer opacity directly (1.0 means fully opaque). Consequently, video references currently behave as background guides beneath all drawing layers rather than preserving arbitrary interleaving in the layer stack. Video is reference-only and is excluded from canvas artwork exports, thumbnails and MP4 output.
- MP4 export has an “Include visible audio layers” option. For compatible source codecs, visible audio clips intersecting the playback range are muxed as separate audio tracks with timeline offsets. This is track inclusion, not a downmix; playback apps may expose track selection rather than automatically mixing tracks. Formats not accepted by Android's MP4 muxer may fail export; audio transcoding / downmix remains follow-up work.
- Camera framing and perspective guides. Perspective guides have independent Curvilinear toggles, per-guide curvature strength (0–200%, default 100%), rotation-unlock and 30-degree snap locks. Curvilinear mode keeps each guide axis straight and emits an adjustable radial ray fan from every vanishing point; rays parallel to the guide remain flat, while curvature increases with angular distance from the guide and along the ray. S Pen hover direction helps select the intended guide or ray. While a perspective layer is visible, brush and eraser strokes are always constrained to a guide or ray; they are never left as ordinary freehand. If hover data is unavailable, the first contact movement selects the path. The camera lens links horizontal FOV and focal length for a 36 mm full-frame sensor; focal-length changes scale VP offsets around the optical centre. V22 persists physical focal length and per-guide ray counts; the reader supports V1–V22.
- New-frame cue: scrubbing past the last drawing builds a growing purple pull at the right edge; the next stroke creates a new frame only after a deliberate pull of `NEW_FRAME_PULL` (168 screen px, about 3.5 scrub steps); scrubbing back cancels it.

2.3 Magic Wand and Fill sampling

- Magic Wand and Fill share the `refAll` sampling option in their detailed tool options. The option is labelled “All visible drawing layers” and defaults on. When enabled, visible drawing layers are composited for region-boundary detection; hidden layers are ignored. When disabled, sampling uses only the selected drawing layer. The setting is shared by both tools.
- Colour tolerance is fixed (`WAND_FILL_TOL`, 24 %); there is no threshold control. While holding, a horizontal drag adjusts only the gap fill (1 px per 3 screen px, 0–`MAX_GAP_FILL` = 60 px, remembered per tool in `Editor.wandGap` / `fillGap`) and a vertical drag expands / reduces. The live blue preview uses the same resulting mask that is committed.

2.4 Drawing tools

| ID | Tool | Main behavior |
|---:|---|---|
| 0 | Brush | Pressure/tilt-aware strokes and presets |
| 1 | Eraser | Brush-based erasing and presets |
| 2 | Selection | Rectangular/freehand selection, inversion and transforms |
| 3 | Fill | Flood fill with tolerance and grow/shrink |
| 4 | Line / shapes | Line, rectangle, circle and ellipse |

Additional behavior:

- Straight-line endpoints are freehand; the line tool does not snap to perspective axes or vanishing-point directions.
- Two-stage ellipse creation.
- Interpolated brush path rendering.
- Cel bitmap cache with invalidation.
- Pixel masks and compact horizontal selection runs.
- Selection transforms use document-space matrices.
- Selection, camera, Transformation folders and Reference image layers share on-canvas transform handles (`Gizmo.kt`): corner squares scale, side squares/edges stretch, and dragging outside a corner rotates; handles work with finger and S Pen. The padlock (`Editor.xfLock`) keeps the aspect ratio. Reference image matrices are undoable. Cut-settings resizing uses ungrouped document coordinates and the fixed top-left page origin; its bottom-right corner and right/bottom edges resize the page. Stylus and finger input both route through the shared gizmo hit test while the settings draft is open. The settings panel is anchored at the canvas top-left so it does not cover the right/bottom resize handles. Camera corners write the scale key, camera sides change the frame's own width/height; folders scale uniformly and show a tinted content-box preview when selected.
- Transform bars expose a rotation magnet (`Editor.rotationSnap`, drawn as a horseshoe icon by `MagnetBtn` in `Panels.kt`, same 40 dp box / colours as `IconBtn`) that snaps gizmo rotation to absolute 30-degree angles measured from 0° (not relative drag increments) across selection, Camera, Transformation folders and Reference / Out of Peg transforms. Two-finger Camera / selection rotation also snaps on release when enabled.
- Fill and wand share flood-map calculations. Gap closing expands the tolerance-excluded barrier pixels by the dragged radius (square dilation, `dilateSquare`, O(N) regardless of radius) before reflooding from the seed; the blue live preview is generated from the same final mask that is committed. Fill retains its separate Grow / shrink adjustment.
- Fill can sample the current layer or all layers.

2.5 Brushes, erasers and color

- Brush presets: Ink pen, Fine liner, Pencil, Marker, Soft brush.
- Eraser presets: Hard eraser, Soft eraser, Pencil eraser.
- Parameters include size, opacity, hardness, spacing, pressure curves, tilt-to-width, taper, texture, stabilization method and strength.
- Stabilization methods include None, Lazy follow, One Euro, Moving average and Spline.
- Presets can be selected, edited, renamed, copied, deleted, reset, saved and exported.
- Presets persist in "brushes.json".
- ".inbrush" JSON import/export, format marker "inbetween-brush", version 1.
- Color picker uses a hue ring and saturation/value triangle.

2.6 Timeline, frames and layers

- Timeline is organized around per-layer cels.
- Each cel has its own duration.
- Add, duplicate, delete, retime and reposition frames.
- Long-press drag-to-reposition.
- Two-finger pinch changes horizontal timeline time scale.
- Timeline edge handle changes panel height.
- Playback controls include play/pause and first/last frame of the selected playback window. Playback uses a monotonic real-time frame clock and background pre-rendering of a short rolling window (current frame plus up to two upcoming ticks). The playhead does not wait for raster-cache readiness: late drawing frames may be skipped to keep timeline time aligned with continuous audio/video playback. The Buffering indicator reports a cache miss but does not pause media. Frames too large to fit together in the bitmap cache are rendered layer-by-layer on demand. GIF export is independent and renders every output frame with its timeline timing.
- Playback window has draggable start/end markers; Reset includes visible drawing cels and Camera / Transformation diamond-key times. When timeline items are selected, "Set playback window to selection" sets the range to the first and last selected cel / diamond key.
- Camera and Transformation diamond keys can be duplicated one frame later or deleted through the timeline copy/delete controls when such a layer is selected.
- Ruler shows frame numbers and a second count above elapsed seconds (e.g. frame 24 is marked second 1 at 24 fps). Frame and second labels thin out adaptively as zoom decreases to avoid overlap; tick positions, playhead and range markers share the same horizontal scroll and zoom scale.
- Playback end can extend into a blank timeline tail.
- Onion skin toggle; hold it for options: Fade out (shows after a scrub ends, fades in 0.5 s) and Turn off when scrubbing (`Editor.onionFade`, `onionOffScrub`, strength `onionK` in `DrawingCanvas`; session state).
- Layers support visibility, lock, opacity, rename, duplicate, delete and reorder. Dragging a layer (or selected layer set) near the top / bottom edge of the visible layer list auto-scrolls the list; the final drop index accounts for the scroll movement.
- Timeline toolbar chronometer opens a numeric keypad to set the duration in frames of the selected cel(s); when there is no cel multi-selection, it targets the current drawing cel, so it is available with a single active frame. Duration changes use the undoable Cut snapshot pathway.
- Layer groups/folders support membership and collapsed state.
- Transformation folders support animated X/Y offset, uniform scale and rotation.
- Transformation-folder children inherit interpolated transforms in canvas and supported exports.
- Camera and Transformation-folder channels have timeline controls and keyframes.
- Ruler is pinned above vertically scrolling layer rows and scrolls horizontally with cels. Only the S Pen (or a mouse) scrubs it: the playhead goes to the frame under the pen using the live cell width; fingers only pan. The timeline cell width is always a whole number of pixels (`cellPxUi` is rounded and `cell` derived from it) so tick / cel boxes, playhead and window markers share exactly the same frame grid.
- Timeline multi-selection: tap, then touch again within 350 ms near the first tap and move before the 0.2 s hold time to draw a selection rectangle (finger or pen). On the cel/key grid it selects frames and diamond keys; on the layer-name column it selects layers. A quick tap clears the selection. Hold + drag on a selected frame / key / layer moves the whole selection (frames reorder as a block per layer, keys shift in time, layers reorder together). State lives in `Editor` (`selCels`, `selKeys`, `selLayers`); keys are identified by `KeySel(layerId, channel or -1, time)`.
- Curve (interpolation) buttons and the ease slider apply to the selected diamond keys when any are selected; otherwise to the segment at the playhead. The shake button beside them opens Strength / Density / Horizontal lock / Vertical lock and shakes between the selected keys (`shakeSelected`).
- Add-layer menu entries and every layer-column row show a type icon in the same slot; Camera and Transformation three-dot menus have "Select all" (selects every diamond key).
- Frame drag hold is controlled by "CEL_HOLD_MS" in "Panels.kt".
- Playback performance rule: do not read `cut.time` in composition of large composables (`Timeline`, editor screen). Use layout/draw-phase lambdas (`offset { }`, `drawBehind`) or small scoped composables (`RulerNum`, `ChanValue`, `KeyMark`, `TransformBars`). Camera / folder channel evaluation (`evalChan`) is a binary search over time-sorted keys.
- Layer types documented in the model:
  - Drawing ("kind == 0")
  - Camera ("kind == 2")
  - Perspective ("kind == 3")
  - Background ("kind == 4")
- Background layers are inserted at the bottom.
- Flatten-down merges drawing layers while respecting timing overlap and opacity.
- Undo/Redo use action stacks and structural snapshots.

2.7 Camera and perspective

- Camera layers store dimensions and aspect/size locks.
- Camera keyframe channels: X, Y, scale and rotation.
- Interpolation/easing supported.
- Camera editing includes nudging, interpolation settings, shake between selected keys, rasterization and reset.
- Rasterize draws a red rectangle only at existing diamond key times and joins matching corners of consecutive rectangles with red lines; it creates no keys.
- Perspective layers store the shared intersection of a horizon guide and its perpendicular, plus vanishing points attached to either guide. Existing V1–V17 files load with the original horizontal / vertical defaults and a 90° camera FOV.
- The intersection is the optical centre. Each guide has independent rotation (locked by default), an independent 30° snap toggle (off by default), vanishing-point spacing lock, and Curvilinear mode. Unlock rotation with the 30° snap toggle off for freeform rotation. Guide controls are shown one guide at a time while a perspective layer is selected; S Pen hover displays each button's description.
- The camera-lens control sets horizontal FOV and focal length as linked values for a 36 mm full-frame sensor (default 90° / 18 mm). FOV = 2 atan(sensor width / (2 × focal length)). Changing focal length scales every vanishing-point offset from the optical centre by the focal-length ratio: longer focal lengths push VPs outward and shorter focal lengths pull them inward. The canvas also shows the horizontal camera view cone around the perpendicular optical axis.
- Vanishing points can be added, moved and deleted. When a guide's VP-distance lock is on, moving one point translates the other points on that same guide by the same amount, preserving their spacing.
- Each guide's Curvilinear mode preserves its guide axis as a straight line (the horizon is never bowed) and emits an adjustable 8–48 ray fan from every VP. Rays parallel to the guide remain straight; sagitta increases with the squared vertical component of ray direction and with distance along the ray. Per-guide ray count is serialized in V21; physical focal length is serialized in V22.
- Brush and eraser magnetism is applied to stabilized incoming samples immediately before stamping, not as a post-stroke rewrite. When any perspective layer is visible, every brush / eraser stroke is constrained to a guide or ray: there is no freehand fallback. Raw S Pen hover events estimate the approach direction and help select the intended path; if hover is unavailable, the first contact movement selects it. Once selected, every sample is fully projected onto that path until pen-up. Raw pressure and tilt values still feed the existing brush size / opacity / tilt curves unchanged. The straight-line tool endpoint does not use the perspective snapping helper.
- Each guide has its own Reset button. It restores that guide's rotation and guide-specific toggles about its vanishing point; the other guide keeps its direction and settings, while its line follows the moved intersection. Camera FOV is a separate shared lens setting.

2.8 Import and export

- Image import through Android document picker.
- Image-sequence ZIP import.
- PNG, JPG and JPEG supported.
- Numbered filenames sorted numerically.
- Optional parent folder interpreted as a group.
- Images fitted and centered; empty cels filled first, overflow creates one-frame cels.
- Export options:
  - MP4
  - GIF
  - Current-frame PNG/JPG
  - Image-sequence ZIP
  - Unique-frames image-sequence ZIP
- MP4 uses MediaCodec and MediaMuxer with AVC/H.264.
- GIF generation uses a per-frame adaptive median-cut 256-color palette, local color tables and LZW; frame delays use cumulative centisecond rounding to preserve the average frame rate without per-frame rounding drift.
- Image-sequence export supports visible drawing/background layers and optional group folders.
- Current-frame export renders without camera transform; animation/video uses camera-aware rendering.
- GIF size scale options: 100%, 50%, 25%.

2.9 Keyboard support

- Hardware keyboard support through "Keys.kt". Shortcuts are action-based and rebindable: `KeyAction` (enum, with app-default keys) -> `KeyBind(code, ctrl, shift)`; `KeyBindings` holds the active map, loads `keybinds.json` from the app files directory at editor start (no file = defaults; actions missing from the file get their defaults unless a saved key already uses the combination), `save` writes it, `resetToDefaults` deletes it. Settings > Keyboard shortcuts (`KeyboardShortcutsDialog`) edits a draft: tap a key to change it, + adds another, × removes one, Save stores and applies, Reset restores the app defaults immediately, leaving with unsaved edits asks first. A combination already used by another action is moved to the new action. Alt combinations cannot be bound. If a Shift combination has no binding of its own, Shift is ignored. New actions: Previous / Next keyframe (default Ctrl+Left / Ctrl+Right) jump between diamond keys on a Camera layer / Transformation folder, or between drawings on a drawing layer.
- Editor root handles focus; text fields retain their own key handling.
- Alt combinations are left to the system; Cmd behaves like Ctrl.
- One-shot actions ignore auto-repeat.
- Shortcuts include playback, frame stepping, layer navigation, frame operations, key creation, tool selection, brush size, selection, zoom, fullscreen, onion skin, mirror, grid depth (Shift+G), timeline, undo/redo, save and export.
- Add new shortcut actions to `KeyAction` and handle them in `handleKey` (the Settings list is generated from `KeyAction`).
- Canvas zoom/rotation/pan state lives in "Editor".

2.10 User interface

- Cut list, new-Cut dialog, editor, export flow and settings.
- Tool panel, quick panel, options panel, brush list/detail, color dialog, Cut settings, timeline, camera overlays and contextual popups.
- Fullscreen canvas mode. In fullscreen the top bar holds a minimal timeline: the current-layer bar (`MiniLayerButton`) is at the far left, with its up / down layer-navigation buttons immediately to the right, followed by compact timeline buttons (`Timeline(mode = 1)`) and the single-layer timeline unfold (`mode = 2`); tap or hold the layer name to unfold the list; hold a row inside the unfolded list and drag it to reorder. The minimal timeline is hidden when fullscreen ends. A translucent radial tool puck appears near the lower-right corner; long-press and drag outward to unfold the five tools, release toward one to select it, tap a selected tool again to open its detailed options, and tap away to dismiss. Detailed options are layered above the radial menu / dismiss surface so controls remain interactive.
- Cut settings is a side panel (`CutSettingsPanel`, tap the Cut name) with pending values (`Editor.cutDraft`: `CutDraft`) and on-canvas resize handles (bottom-right corner and right/bottom edges; the page origin remains anchored); Confirm applies and closes it. Aspect and dimension locks are respected. Selection transform handles also support moving the selected content by dragging inside the selection box.
- Contextual options and tooltip/long-press affordances.
- The Cut-list header exposes Diagnostics. `CrashDiagnostics.kt` maintains a bounded local log, records startup checkpoints and installs an uncaught-exception handler. A recovery screen can expose diagnostics after an incomplete startup or a saved-Cut load failure; a ZIP report can be exported through the document picker. Reports contain diagnostic/device/app information, not project or drawing files.
- Fullscreen current-layer control: up/down buttons beside the layer button move selection to the adjacent layer; tapping or holding the layer name unfolds/folds the list. Hold a row in the unfolded list and drag it to reorder. The former arrow below the layer button is removed.

## 3. Source map

| File | Main responsibility |
|---|---|
| `MainActivity.kt` | Activity lifecycle, startup recovery, navigation, Cut list, dialogs, editor, pickers and export orchestration. |
| `CrashDiagnostics.kt` | Bounded local diagnostic log, uncaught-exception capture, startup checkpoints and ZIP report generation. |
| `PerformanceMetrics.kt`, `PerformanceProfiler.kt`, `PerformanceHud.kt` | Opt-in frame / media / GL / process / per-thread profiler, HUD and diagnostic exports. |
| `Waveform.kt` | Audio decode to mono PCM (`AudioPeaks`, memory + cacheDir cache), `WaveformCanvas`, scrub engine (`AudioScrub`) and `AudioScrubHost`. |
| `CurveView.kt` | Curve view of Camera / Transformation channels (`KeyCurveEditor`): graph, channel list, key drag. |
| `Recovery.kt` | Crash-recovery snapshots of the open cut (`filesDir/recovery`), pending-restore detection at launch, `RecoveryAutosave` and `RecoveryDialog`. |
| `WhatsNew.kt` | In-app release notes (`ReleaseNote` list) and the one-time "What's new" dialog after an update. |
| `MediaSync.kt` | Main-thread owner of audio/video playback start, stop, preroll and drift correction. |
| `GlLayers.kt` | OpenGL ES layer compositor and ImageReader/EGL frame delivery. |
| `Editor.kt` | Core data model, undo/redo, frame/layer operations, editor state, brush/eraser presets and canvas view state. |
| `Keys.kt` | Rebindable hardware-keyboard shortcuts: `KeyAction` / `KeyBindings` (keybinds.json), `handleKey`, and the Settings dialog `KeyboardShortcutsDialog`. |
| `Canvas.kt` | Canvas rendering, stylus/finger input, drawing, onion / Out of Peg references, tile drawing, grid, perspective overlays and perspective-guide widget bars. |
| `Grid.kt` | Shared adaptive grid spacing used by the main canvas and video-mode Behind grid. |
| `Panels.kt` | Compose UI for tools, brushes, color, timeline, layers, camera/perspective controls and dialogs. |
| `BrushAssets.kt` | Imported brush stamps and textures: mask PNG library (`filesDir/stamps`), CRC-32 ids, import / decode, texture tiles. |
| `Brush.kt` | Brush model, presets, pressure curves, stabilization and serialization. |
| `Engine.kt` | Stroke sampling/stamping (including live perspective magnet mapping after stabilization and before stamping), Cut-cropped `CelCache`, playback prefetch and stabilization. |
| `Tiles.kt` | Sparse tiled cel cache (`TileCache`, `TileSet`, `TileSink`), tile transforms and fill/selection windows. |
| `Gizmo.kt` | Shared transform-handle hit testing, drag math, bounds and drawing. |
| `Camera.kt` | Camera/folder keyframes, interpolation, camera operations and perspective model. |
| `Tools.kt` | Selection, transforms, shapes, flood fill/wand, image helpers and flattening. |
| `Export.kt` | Rendering, playback segments, GIF/MP4 (including optional external audio tracks) and image-sequence exports. |
| `Media.kt` | Copies imported audio/video files into app-owned storage and reads media duration metadata. |
| `Store.kt` | `.inb` serialization/migrations, packed ZIP documents and local Cut loading. |
| `app/build.gradle.kts` | Android/Kotlin plugins, SDK levels, dependencies and app version. |
| `.github/workflows/build.yml` | Builds a permanently signed release APK when signing secrets are configured; otherwise uploads a debug-only fallback. |
| `RELEASE_SIGNING_SETUP.md` | One-time keystore creation, GitHub Actions secret setup and first-install transition instructions. |

## 4. Core model

- `Cut` is the document/project.
- `Layer` belongs to a Cut and represents a drawing layer, group/folder, camera, perspective or background layer. Reference-image layers are also supported.
- Drawing layers contain ordered `Cel` objects. A cel has a duration and replayable `Ink` operations.
- Transformation folders store ordered transform keys and interpolate translation, uniform scale and rotation for their child layers.
- `Ink` represents drawing and other replayable cel operations.
- `TileSet` rasterizes cel operations into sparse tiles for canvas display. `CelCache` is a Cut-cropped bitmap derived from those tiles for standard rendering/export paths.
- `Editor` holds editing state, document operations, undo/redo and brush/eraser preset state.

## 5. Critical maintenance constraints

1. **Document compatibility:** `Store.kt` writes V22 and reads V1–V22 (see §2.1 and the 2026-11-16 perspective update entries). V9 adds external media metadata; V10 appends an embedded media payload for each audio/video layer. All older versions remain readable. Any further serialization change must deliberately update writer and reader, increment the version and preserve older readers unless a migration decision is documented.
2. **Rendering/export consistency:** Check canvas, thumbnails, GIF, MP4, current-frame and image-sequence export as applicable when changing rendering or layers.
3. **Timeline semantics:** Cels have independent per-layer durations. Do not assume all layers share one frame list.
4. **Undo/redo:** User-visible document mutations should use the existing action pathway (`Cut.record`, `commit` or equivalent).
5. **Cache invalidation:** Keep `TileSet` operation counts and versions correct. `CelCache` derives from `TileSet.version`. Do not invalidate live-stamped tiles during `commit`; inspect the relevant cache code before changing this pathway.
6. **Input separation:** `Canvas.kt` has distinct stylus `MotionEvent` and finger-gesture paths. Pointer changes can affect pressure, tilt, hover, pan, zoom and scrubbing.
7. **Import/export round trips:** Test both directions when changing naming, folder interpretation or image placement.
8. **Testing:** The supplied ZIP contains no test source set or test files. Check the current repository before making claims about automated coverage.
9. **Known gaps:** Record partial, device-dependent or untested behavior explicitly.
10. **Out of Peg:** Previous/following drawings are light-table references, not editable source cels. Their position, independent X/Y scale, rotation and flip values are stored per adjacent `Cel` in session state (`Editor.referencePoses`), not in `.inb`. They are rendered in the onion pass and resettable per drawing. Persistent alignment would require an explicit versioned format change.

## 6. Build and verification status

- The supplied project uses Android Gradle Plugin 8.7.3, Kotlin / Compose compiler plugin 2.0.21, CI Gradle 8.9, compile/target SDK 34, min SDK 26 and Java 17. Verify current configuration before changing or relying on these values.
- The GitHub Actions workflow builds a signed release APK when all four signing secrets are configured; otherwise it builds a debug APK fallback. The fallback is not suitable for in-place updates across signing identities.
- Release signing uses the stable Android application ID `com.example.inbetween` and the `release` build type. The GitHub workflow sets `versionCode` from `github.run_number`; local builds default to version code 2 and version name 0.4.
- Required GitHub Actions repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (currently `superframe`), and `ANDROID_KEY_PASSWORD`. Generate the permanent key once with `scripts/prepare-release-signing.ps1`, store the Base64 file contents and passwords in repository Actions secrets, and keep a secure offline backup of `superframe-release.jks`. Never regenerate or replace this key after publishing signed builds; losing it prevents normal updates.
- The first install after switching from an APK signed with a different key requires uninstalling the old app. Subsequent release APKs signed with the same key can update in place. Uninstalling deletes app-private data, so back up documents before the one-time transition.
- The timeline UI was decomposed into smaller composables after Android 13 repeatedly reported a DEX `VerifyError` in the former monolithic `PanelsKt.Timeline`. A build from that refactor was reported successful, but later feature batches in the supplied history were not all built or tested on the tablet. Do not treat the earlier successful build as validation of later changes.
- After the latest feature batch, verify on the Android tablet: fullscreen layer navigation and row reordering; radial-menu detailed options; timeline duration keypad and ruler alignment; Out of Peg pose behavior; selection/Cut/reference-layer gizmos with finger and S Pen; reference-image URI import/save/reopen and missing-file behavior; layer auto-scroll; rotation magnet; Bleed performance/overflow; and sequential buffered playback under heavy frames.

## 7. Documentation map

- `PROJECT_MEMORY.md` describes current known architecture, behavior, source locations and maintenance constraints. It is not a historical changelog.
- `CHANGELOG.md` records dated historical changes, attribution and validation.
- `AI_WORKING_AGREEMENT.md` defines contributor workflow and project rules.
- Update the current-state inventory when behavior or architecture changes; add a separate changelog entry for each meaningful accepted change.

## Recent interaction update (2026-11-10)
- Canvas pinch-to-reset activation distance: `RESET_PINCH_DP` = 112 dp (80 was too hard to reach, 160 armed during ordinary pinches).
- Timeline supports centroid-anchored two-finger zoom and two-finger horizontal pan, in addition to ordinary one-finger scrolling.
- App version name: 0.4; CI continues to supply the monotonically increasing version code.


## Media timeline implementation (2026-11-10)
- Added `Layer.kind == 6` audio clips and `Layer.kind == 7` video reference clips. New imports are copied into app-owned `filesDir/media_assets`; the original provider URI is no longer needed after the copy.
- `.inb` writer advanced to V10. Each audio/video layer stores its metadata followed by the source media bytes. `Store.load` and package import restore the payload to app-owned storage and rewrite the live URI to the restored file. V1–V9 readers remain supported; V9 content URIs are migrated into app storage when their persisted provider grants are still available, then embedded on the next save. If an old provider grant has already been revoked, that source must be reimported.
- Visible audio clips now use Media3 ExoPlayer instances for continuous decoding/playback and timeline-boundary synchronization. Video references now use Media3 ExoPlayer with TextureView output; Media3 delegates decoding to Android MediaCodec hardware decoders when available. Video TextureViews are rendered beneath drawing artwork to preserve transparent canvas compositing; arbitrary video/drawing interleaving in the layer stack is not yet supported by this overlay architecture. Media3 version: 1.4.1 stable. MP4 export remains unchanged: compatible compressed audio tracks are muxed separately, not mixed down; audio transcoding/downmix remains follow-up work. Video reference clips remain excluded from exports and thumbnails.
- The old per-frame MediaMetadataRetriever decode/cache loop and the old MediaPlayer polling/seeking loop have been removed from preview playback.
- Timeline cell width is automatically reduced when a long media clip would otherwise make the Compose timeline exceed a safe width, preventing the oversized-constraint crash observed when importing longer audio/video.
- Validation: inspected the supplied Android 13 diagnostic report; the fatal stack is a Compose `Constraints` exception with an oversized width and zero height, not an audio-decoder stack. Ran a Kotlin compiler invocation without Android/Compose dependencies; it could not type-check the app and reported no parser `expecting`/syntax diagnostics. No Gradle wrapper/executable or Android SDK is present in this environment, so compilation, CI, serialization round-trip, media codec compatibility and tablet playback/export remain unverified.

### Media3 follow-up
- Video reference TextureView is composited below the drawing canvas; the canvas remains the sole stylus/touch interaction surface. The video layer itself is passive unless future explicit selected-layer transform handles are implemented.
- Grid depth follows the explicit Behind / In Front toggle: Behind grid is below the page and video plane; In Front grid is drawn in the canvas foreground.
- Timeline uses a monotonic real-time clock and does not wait for drawing-cache readiness. Late drawing frames may be skipped, while audio/video remain clocked to real time. Audio playback does not treat ordinary frame skips as user seeks; it seeks on clip entry, explicit backward timeline jumps and scrubbing.
- Audio decoding is not GPU-accelerated; Media3 uses the platform audio decoder/output path. The primary optimization here is continuous playback without per-frame pause/resume or seek churn.


## Media renderer follow-up (2026-11-10)

Latest follow-up patch changes preview to a monotonic real-time clock so media playback does not wait for raster-cache preparation. Late drawing frames can be skipped. Audio clip players are driven by timeline state changes and seek only on clip entry, backward timeline jumps / loop wrap, and scrubbing; ordinary forward frame skips do not restart audio. Video playback also reacts to frame changes and loop wrap without 16 ms polling. TextureView is disabled for touch and focus, and the drawing canvas is assigned a higher Compose z-index. Behind grid is drawn on a separate lower plane, below the white page and video; Front grid remains in the canvas foreground. No project format bump was needed because no serialized fields changed. Limitation: video is still a Compose TextureView plane behind the drawing Canvas, not a unified GPU-composited layer in arbitrary timeline stack order. Full arbitrary layer interleaving and selection-only independent video transforms remain a separate renderer project.


## 2026-11-10 — Video layer interaction separation
Video layers now store independent mediaX/mediaY/mediaScale/mediaRotation values. These are applied in Media3VideoReferenceLayer and edited only when the video layer itself is selected and unlocked. Canvas stylus and finger gestures route to the selected video transform instead of changing the global viewport. Drawing coordinate conversion ignores video-layer folder transforms. Drawing clip-to-document is no longer disabled merely because a video layer is visible. Store format V11 persists these values and continues reading V1–V10.


## 2026-11-10 — Workspace-centred media/page transform
The video page and Media3 video view now use a full-workspace-sized Compose parent for the viewport transform. This matches the pivot used by `DrawingCanvas.base()` (workspace centre), avoiding the prior mismatch where page-sized views transformed around their own centres while the drawing renderer transformed around the workspace centre. The video’s own media position, scale and rotation remain on the video image child, so the viewport is applied once and media-local transforms stay separate. No `.inb` format change. GitHub Actions push triggers include `main`, `master`, and `beta`; manual dispatch is preserved. Build/device verification is pending.


## GPU compositor migration — first implementation stage (2026-11-10)
- Replaced the video TextureView output with a dedicated OpenGL ES 2.0 rendering surface. Media3 still selects the device's MediaCodec decoder; decoded frames arrive through a Surface backed by an external `GL_TEXTURE_EXTERNAL_OES` texture and are drawn by a small shader on a dedicated GLSurfaceView render thread.
- The video surface is transparent and configured as a media overlay below the app window, so Compose drawing and interaction remain in the foreground. Existing page, viewport, media-local transform, clipping and opacity modifiers are retained.
- This is an incremental GPU video-output stage, **not yet the unified compositor**: drawing cels, grid and page are still rendered by the existing Compose renderer, and arbitrary video/drawing layer interleaving is not yet supported. The GL surface introduces a separate SurfaceView plane and therefore needs device validation for transparency, z-order, alpha, transforms and lifecycle.
- No `.inb` format change. No Gradle dependency or protected build configuration changes. Existing GitHub Actions workflow already includes `beta` alongside `main` and `master`.
- Validation: source inspection, Kotlin parser diagnostics and ZIP integrity checks. No Android SDK / Gradle build or tablet runtime test was available.


### 2026-11-10 — OpenGL video orientation correction
The GL external-OES quad uses canonical UVs `(0,0) (1,0) (0,1) (1,1)` and applies `SurfaceTexture.getTransformMatrix()` once in the vertex shader. Do not add a manual vertical flip on top of this producer matrix. Video layer rotation and workspace rotation remain geometry transforms. Confirm actual orientation on a device because decoder/producer transform behavior must be validated with the target hardware.

### GPU video quad coordinate and transparency follow-up (2026-11-10)

- The GL quad vertex order is bottom-left, bottom-right, top-left, top-right in NDC, where positive Y points upward. Compose/document coordinates use positive Y downward. When converting transformed document-space corners into NDC, pair the GL vertices with document-space bottom corners first (`localY = +halfH`), then top corners (`localY = -halfH`); otherwise the image is vertically inverted even when the SurfaceTexture producer matrix is correct.
- **Video output is a Media3 TextureView again (2026-11-10).** The GL SurfaceView stage-1 path is backed out for the video layer; `GlVideoSurface.kt` is kept in the repo but is no longer referenced. Reasons, found on the tablet: a SurfaceView is presented independently of the Compose window, so its baked-in viewport transform trailed the ink during rotate / pinch (laggy, worst zoomed out; snappy with the video layer hidden), and its hole punched through the window and erased the Compose page / grid / workspace colour (black canvas; an interim fix moved those into GL, which made the lag worse). Do not put viewport-dependent content on a separate SurfaceView again.
- Current design (`Media3Video.kt`): Compose draws grid (`Media3VideoGridBackground`, zIndex -1.5) and white page (`Media3VideoPageBackground`, zIndex -1); `Media3VideoReferenceLayer` (zIndex -0.5) is a workspace-sized node with the viewport `graphicsLayer` (pivot = workspace centre like the Canvas) containing a video-image-sized node with the folder + media transform and layer opacity as a second `graphicsLayer`, hosting the TextureView (`player.setVideoTextureView`). Every changing value is read inside the layer blocks, so viewport gestures and playback ticks do not recompose it. Drawing canvas stays at zIndex 2.

## Media playback synchronisation (2026-11-10)

- `MediaSync.kt` (object, main thread only) is the single owner of audio and video clip start / stop / seek during playback. Players are built by `newMediaPlayer` (short read-ahead `DefaultLoadControl` 1.5 s / 3 s, 150 ms to start) and registered by `Media3AudioLayerPlayback` and `Media3VideoReferenceLayer`. Supersedes the earlier per-composable `snapshotFlow` start logic and the "no drift correction" rule.
- Playback start: the clock effect in `MainActivity.kt` calls `MediaSync.begin` (pause + seek every clip under the playhead, wait until all are READY, max 700 ms, then `play()` them together) **before** taking `startNanos`. Each frame change calls `MediaSync.tick`: clips starting within ~0.5 s are primed (seek 0, paused) and started on their exact frame; running clips are re-seeked once on a loop wrap; leaving a clip pauses it. Drift checks run about once a second after an 0.8 s settling period. Current correction policy (2026-11-12): video requires 2 consecutive samples beyond 120 ms and has a 2 s cooldown; audio requires 3 consecutive samples beyond 300 ms and has a 5 s cooldown, because the supplied tablet report showed a stable ~190 ms audio player-position lag that triggered a seek/rebuffer/restart every second under the old shared 120 ms threshold. This suppresses repeated hard seeks but does not prove the underlying audio offset is inaudible or correct. `MediaSync.stop` pauses everything when playback ends or is cancelled. Scrub seeks while paused stay in the two composables.
- `Media3VideoReferenceLayer` does not recompose per playback frame or viewport gesture: all changing values are read in `graphicsLayer` blocks (see the TextureView bullets above).
- Still no unified GL compositor: drawing cels, tiles, onion skin, selection, handles and grids are Compose drawn. Any future GL compositor must not live on a separate SurfaceView next to Compose overlays / live ink (async presentation, extra pen latency); candidates are rendering layers below the active one into a TextureView-hosted texture in document space with the viewport applied by `graphicsLayer`, or moving everything including overlays into one GL surface. Measure on the tablet before and after.
- Unverified: all of this is source-reviewed only. Needs tablet testing with audio + video playing together (start alignment, sync after a minute, loop wrap, clip boundaries, scrubbing then play, hiding / showing layers during playback).

## GPU layer compositor (GlLayers.kt, 2026-11-10)

- What: the layers BELOW the active layer while editing (NOT during playback since 2026-11-13, see the profiler comparison at the end of this file), plus onion skins, are composited by OpenGL ES 2.0 and shown by `GlLayerPlane` (zIndex -0.25) between the page / video and the drawing Canvas (zIndex 2). Compose still draws the active layer, the layers above it, the live stroke, selection / lift, overlays and the Front grid, so pen latency and tools are unchanged. Z-order: grid -1.5, page -1, video -0.5, GPU plane -0.25, Canvas 2.
- How a frame gets to the screen (important): the GL thread renders into an `ImageReader` surface (RGBA_8888, GPU usage flags, Android 10+, 5 buffers). Each frame reaches Compose as a hardware `Bitmap` (`Bitmap.wrapHardwareBuffer`) paired with the `ScreenView` (document -> workspace px) it was rendered with; the pairing uses the EGL presentation time (= frame sequence number) read back as `Image.timestamp`. The plane's Canvas draws it through "viewport of the frame -> viewport now" (read in the draw phase), so a pan / rotate / zoom is exact in the same frame as the Canvas even before the next GL frame arrives; GL only supplies pixels. The surface is 10 % larger than the workspace on every side so the plane still covers the screen while it is a frame or two stale. Frames still sampled by the UI renderer are kept open (displayed + 2 older) before `Image.close()`.
- Why not SurfaceView / TextureView: both are presented a frame behind the Compose Canvas, so a viewport baked into the GL output trails the ink (jitter when rotating), and a SurfaceView also punches a hole through the window. Do not go back to either for viewport-dependent content.
- Decision rule: `gpuPlan(cut, ed)` returns K = number of leading `cut.layers` drawn on the GPU (editing: index of the active layer; playing: always -1), or -1 = Compose draws everything. -1 when the switch (Cut settings > "GPU layer compositor") is off, GL failed, no frame has been delivered yet, a visible reference-image layer (kind 5) exists, or there is nothing to draw (K = 0 and no onion). Reference images, camera / perspective overlays, gizmos, selection and live strokes always stay on Compose.
- Consistency: Compose skips only what the displayed frame really contains (`GpuLayers.shownK` / `shownOnion`, set together with the frame on the main thread), so a transition may double-draw a layer for a frame but never lose one. When the plane is in use (`planes` in `DrawingCanvas`) the page and Behind grid come from `Media3VideoPageBackground` / `Media3VideoGridBackground` beneath it; the Canvas does not draw them.
- Scene: `buildGlScene` (main thread, inside `snapshotFlow`, so it re-runs when any state it read changes) mirrors the Canvas layer / onion passes: opacity, folder transform, onion tint (red previous, green next, SRC_IN), out-of-peg pose, Bleed clipping (local tile clip + page clip in the shader). `screenView` is the same viewport transform as `DrawingCanvas.base()`. Scene and viewport changes re-render (a few textured quads per cel), coalesced to the latest state.
- GL thread (`GlLayerRenderer`): own EGL context and one window surface on the reader, premultiplied blending, asynchronous `eglSwapBuffers` without a per-frame `glFinish` (the EGL/ImageReader buffer queue carries GPU completion fences), tile textures uploaded from `TileSet.forEachTile` whenever `TileSet.version` changes (whole set), LRU budget 128 tiles (128 MB). Any EGL / GL / frame-wrap failure sets `GpuLayers.failed` and the app falls back to the Compose path for the session. Renderer is rebuilt when the workspace size changes. The explicit GPU wait was removed on 2026-11-12; verify frame delivery and viewport pairing on the tablet.
- Not done / ideas: per-tile versions (a changed set re-uploads all its tiles), texture prefetch for the next frames during playback, reference-image layers on the GPU, mipmaps for zoomed-out quality, older than Android 10 (uses the Compose path).
- Unverified: source-reviewed only. Check on the tablet: no jitter rotating / panning / pinching, layers / opacity / folder transforms / onion / Bleed identical to the standard renderer (toggle the switch to compare), drawing with layers below, switching the active layer, playback with audio + video, fast frame scrubbing (texture uploads), edges of the plane during very fast pans, and the fallback when the switch is off.


## 2026-11-11 batch (see CHANGELOG)
- `Cel.strokes` may be shared by clones (`cloneLinked`, `sameDrawing`); TileCache is keyed by that list so clones share tiles. Unique-frame export skips clones.
- `Cut.relabelLayers()` runs from `invalidateStructureCaches()` and after load: single-letter drawing-layer names follow position.
- `Editor` flags: `rippleRetime`, `tlPanRequest/tlPanFrame`, `tlFitRequest` (Timeline reacts via LaunchedEffect); `palettes` persisted to palettes.json.
- Brush gained `tip`, `textureId`, `textureAmt` (JSON keys optional); `texture` is the Grain slider. Textures are 128 px document-anchored shader tiles (Engine.kt `Textures`).
- Timeline: `Cut.maxTick()/tail()` allow a blank tail up to one minute; channel rows draw `ChannelSegments`; frame split lives in `timelineCutFrame` via the marquee gesture.
- Unverified by CI: everything in this batch (no build was possible).

## 2026-11-11 (2) notes
- Dab rendering (Engine.kt PathStamper): circle hard / soft / etched without texture are drawn directly; triangle, square, any textured brush, or aa > 0.5 go through a per-thread scratch bitmap (ScratchCache) and are stamped 1:1. Textures are ALPHA_8 128 px tiles (`Textures.tile`) multiplied in with DST_IN, anchored to document coordinates.
- `curveEval` is a monotone cubic through the points.
- Timeline ruler is a single Canvas; the empty tail of drawing rows is drawn in `TimelineDrawingTrack`.
- Camera overlay in Canvas.kt shows during playback on all layers (`camLayer`).
- Still unverified by CI.

## 2026-11-12 (2) notes
- Brush curves are (x, y, tangent) triples (`TAN_AUTO` = medium/smooth, `TAN_LINEAR`, or a slope); `curveEval` is a cubic Hermite. Old (x, y) pair files load as auto-tangent points.
- Ruler (Panels.kt `TimelineRuler`): one Canvas, levels of detail chosen from px per frame / second; frame numbers are multiples of a divisor of fps, or of 2/5/10 s.
- Anti-aliasing: `aaBlur = 3 * aa^3` px (aa > 0.02), 0 = aliased.
- Crash lesson: never `coerceIn(a, b)` with b possibly < a; check the range first.

## Performance profiler (2026-11-12)
- Spec: "Superframe - Performance Profiler" implementation plan. Implemented: phases 1-6 in code (foundation, frame metrics, audio/video analytics, GL + playback instrumentation, HUD and controls, export). Phase 7 (validation) NOT done: nothing was built or run.
- `Perf` (PerformanceProfiler.kt) is a singleton; `Perf.active` (volatile) = HUD shown or recording; every hook starts with that check.
- Hooks: `MediaSync.register/unregister` attach/detach the Media3 `AnalyticsListener` (`PlayerProbe`, audio vs video by `layer.kind == 7`); `MediaSync.tick/begin/checkDrift` report tick time, preroll, seeks, clip enter/exit and drift; `GlLayers.loop()` times prepare (`ensure()`), commands, explicit GPU wait (now zero because per-frame `glFinish` was removed), and swap; `GlLayers.fail()` counts failures; `CrashDiagnostics.makeReport()` appends the performance files when `Perf.hasData()`.
- The process sampler reads `/proc/self/task/*/stat` once per second and exports `thread-statistics.csv` with per-thread CPU percentages (one-core basis). The summary lists the busiest sampled threads. Access is best-effort; the rest of the profiler remains functional if task stats cannot be read. Profiler format version is 2.
- UI: `PerformanceHud` / `PerformanceRecordingIndicator` / `PerformanceEffects` are mounted in `EditorScreen`; the toolbar button is the Speed icon next to Share.
- Memory bounds: FloatRing 600/300/200, event and media-row caps 4000, frame and GL logs 36 000 rows, system log 3600 rows, per-thread CPU rows 16 000.
- Decoder dropped frames (Media3) and window frame metrics (Android) are separate numbers by design.
- 2026-11-12 allocation pass in `GlLayers.kt`: `GlItem` stores affine coefficients as scalar fields (no per-item `FloatArray`); `buildGlScene()` resolves transform-folder channels directly instead of creating a temporary transform map / `GroupTransform` objects and avoids temporary onion pairs/lists and default Out-of-Peg pose objects; GL texture IDs use one renderer-owned scratch array; GPU tile count is maintained incrementally and LRU eviction no longer sorts / allocates a temporary entries list every frame. Scene list / `GlScene` / `GlItem` objects are still created for each emitted scene; this is a targeted reduction, not a zero-allocation renderer.
- Latest Scenario F evidence (330 s, profiler v2): 10,653 UI frames, avg 25.90 ms, 80.46% over 16.67 ms, p95 33.66 ms in the final 600-frame window; process CPU avg 137.51% of one core; `HeapTaskDaemon` 37.81%, `RenderThread` 33.49%, app main 21.86%; 1,346 GC collections and 124,398 ms cumulative ART GC time; GL command / swap averages 0.80 / 0.83 ms; zero decoder drops, audio underruns or player errors; two seeks associated with loop handling. This confirms playback remains CPU / GC / UI-render limited, but does not identify allocation call sites.
- Follow-up: repeat the same 3–5 minute Scenario F test after this allocation reduction. If `HeapTaskDaemon` remains high, obtain an Android Studio allocation recording / heap allocation trace before additional pooling. `Media3VideoReferenceLayer` currently uses a `TextureView`; RenderThread cost may include video texture composition and should be investigated separately before changing that architecture. Drawing and editing remain intentionally locked during playback.


## Profiler build correction (2026-11-12)
- Fixed the first CI compilation error: `hudVisible` is a mutable Compose state property, so Kotlin generates `setHudVisible(boolean)` for its setter. The explicit `Perf.setHudVisible(Boolean)` method collided with that JVM signature.
- The control method is now `Perf.setHudVisibility(Boolean)`; UI call sites and `toggleHud()` use the new name. The observable property remains `Perf.hudVisible`, so UI state observation and profiler behavior are unchanged.
- This is a source-only naming correction. No profiler metrics, recording, export, playback, GL, media, project serialization or build configuration behavior changed.
- CI rebuild and device validation remain pending.


## 2026-11-12 — First performance optimization pass
- Diagnostics interpretation: the 8-core SM-P610 report's process CPU averaged 131.55% of one core (max 191.90%), so it was not literally single-core-only; however, aggregate CPU cannot identify the hot thread. UI frame metrics averaged 24.41 ms with 92.17% over the 16.67 ms budget. GL compositor samples were comparatively modest (commands avg 0.68 ms, explicit wait avg 0.42 ms, swap avg 0.68 ms). Audio player position averaged about 152 ms behind the timeline (median -192 ms); the report recorded 86 drift-correction seeks, each triggering a short buffering / stop / restart cycle. Decoder-reported audio underruns and video dropped frames were both zero.
- Changes: profiler now samples `/proc/self/task/*/stat` once per second and exports per-thread CPU samples in `thread-statistics.csv`, plus a top-thread summary. This is best-effort and reports each thread as percent of one core. Audio drift correction now requires three consecutive samples beyond 300 ms and has a 5 s cooldown; video correction requires two samples beyond 120 ms and has a 2 s cooldown. This avoids the observed repeated one-second audio seek/rebuffer loop while retaining recovery for larger sustained drift. Per-frame `glFinish()` was removed from the ImageReader EGL producer path; `eglSwapBuffers` now relies on the buffer queue's GPU completion fence rather than synchronously blocking the CPU.
- Important caveat: the audio player's stable ~190 ms offset has not been proven to equal audible output latency or the audible A/V offset. This pass prevents repeated disruptive seeks but does not claim to eliminate the underlying offset. Re-test with audio/video clapper or transient alignment and a new Scenario D recording before tuning thresholds or adding audio rate correction.
- Validation status: source-level review and Kotlin parser checks only; no Android SDK / Gradle build or tablet run available in this environment. Protected workflow, Gradle files, manifest, signing and project serialization are unchanged.

## 2026-11-12 — Playback rendering optimization (interaction policy preserved)
- The 231 s Scenario F diagnostic was requested to characterize the combined playback workload; it was not a request to enable drawing during playback. S Pen stylus input remains intentionally rejected while `cut.playing` is true, preserving the existing product behaviour. Selection, transform and drawing interactions remain unavailable during playback.
- Retained the independent Compose-side rendering optimization in `Canvas.kt`: when the GPU plane has presented every drawing layer, Compose skips the redundant drawing-layer walk and avoids rebuilding folder transforms that are not needed for active overlays. Compose still renders any layers not yet presented by the GPU plane and required selection / fill overlays.
- Diagnostic context: UI frame time avg 31.96 ms, 98.25% over the 16.67 ms budget; sampled per-thread CPU averages: RenderThread 47.87%, HeapTaskDaemon 35.22%, app main thread 25.73% (one-core basis). GC counters increased by 942 collections and 82,880 ms cumulative ART GC time during the recording. GL compositor CPU commands averaged 0.78 ms, EGL swap 2.56 ms (p95 15.37 ms). Media3 reported zero audio underruns, zero decoder-dropped video frames and zero player errors; MediaSync reported two seeks associated with loop handling.
- Interpretation: playback rendering / RenderThread load and GC activity are concerns, but the GC counters and HeapTaskDaemon sampling do not identify allocation call sites. Obtain an allocation trace before broad allocation changes. The inability to draw during playback is expected and is not a defect.
- Validation: source-level review only; no Gradle / Android SDK build or device validation has been performed for this update.

## 2026-11-13 (3) — Video opacity darkening backed out; What's new window
- Video opacity: both the opaque TextureView flag (2026-11-13) and `CompositingStrategy.ModulateAlpha` (2026-11-13 (2)) were removed after an on-device screenshot showed the video darkening at 25% opacity. Not re-verified on a device after the revert. Do not retry either without an A/B on the SM-P610 at partial opacity.
- `WhatsNew.kt`: `ReleaseNote` / `WhatsNew.notes` (newest first, plain-language, hand-written) and `WhatsNewHost()`, mounted in `MainActivity` next to `App(ed)`. Shows an `AlertDialog` once on startup when `WhatsNew.latestId` is above the id stored in SharedPreferences `whats_new` / `seen_id` (dismiss by "Got it" or tapping outside stores it). First run shows the 3 newest notes; later runs show only unseen notes (max 6). Title shows versionName and versionCode. Rule: every user-visible accepted change adds a note with the next id at the top of the list (also in `AI_WORKING_AGREEMENT.md` §8). No `.inb` or build-configuration change.
- Unverified: not compiled, not run on a device.

## 2026-11-13 (4) — Playback: GPU plane off, evidence (SM-P610, build 134, scenario F)
- Same media, compositor ON (113 s) vs OFF (70 s): UI frame avg 28.19 vs 25.18 ms (96.6% vs 97.3% over 16.67 ms); GC 431 collections (~3.8/s) vs 14; process CPU 154% vs 104% of one core; HeapTaskDaemon 32.8% vs not in the top threads; timeline tick interval avg 43.02 ms / max 446 ms vs 41.67 / 44.85 ms. GC per second tracked the GL frame rate (about 0.14 GC per GL frame), pointing at the per-frame `Bitmap.wrapHardwareBuffer` in `GlLayerRenderer.handleImage` (multi-MB native allocation registered per frame). Cause is inferred, not proven by an allocation trace.
- Change: `gpuPlan` returns -1 while `cut.playing`; playback uses the Compose renderer, editing keeps the plane.
- Video is NOT the bottleneck: frame cost is about the same with and without the video clip playing, and video opacity (50%) made no difference.
- Still open (both modes): about 25 ms per UI frame, RenderThread ~48% of a core, command issue ~10 ms and GPU ~8.5 ms per frame, even when little changes (frames before / after playback cost the same). In the OFF run, audio-only playback frames (page and grid drawn inside the Canvas) cost ~30 ms vs ~24 ms with the video clip active (page / grid in their own layers): hypothesis only, the in-Canvas page + grid (up to ~180 lines per axis) is a suspect. Next A/B candidates: grid off / coarser, fullscreen mode (hides the timeline UI), hiding the camera overlay.
- Unverified after the change: not compiled, not run.

## 2026-11-13 (5) — Crash recovery and range retiming
- `Cut.edits` (plain Long, not observable) counts undoable changes; `Recovery.tick` (about 1 Hz from `RecoveryAutosave` in the UI) snapshots the open cut after 2.5 s idle or 60 s of continuous editing, never while playing. Snapshot = normal `.inb` (V13) via `Store.write(c, out, embedMedia = false)`; media layers are written with size 0 and the reader keeps the app-owned `file://` copy (that branch already existed for v10+). Snapshots are removed by `Recovery.afterSave` after `Editor.saveAll` / `Editor.close`, by `Recovery.clear` in `Editor.remove`. `Editor.load` sets `recoveryPending` once per process; `ed.recoveryResolved` stops the dialog reappearing after a rotation. Do not call `Recovery.pending` outside the first load: live snapshots of the open cut would look like a crash.
- The range-retiming feature added here was removed again the same day (redundant with the duration pad and the interpolation buttons); there is no `Retime.kt`.

## 2026-11-13 (6) — Curve view, waveform, audio scrub
- Curve view: when a Camera layer or Transformation folder is selected AND expanded (`Layer.expanded`), `Timeline` (Panels.kt) shows `KeyCurveEditor` instead of the layer column + track rows (the < button sets `expanded = false`). Each channel is drawn in its OWN value range fitted to its keys (px, % and degrees cannot share an axis); the vertical axis labels follow the active channel. Selection is the existing `ed.selKeys`, so the toolbar copy / delete / interpolation buttons keep working. Drag a key: the dominant axis locks (time: `moveKeysSel`; value: `shiftValues`), one undo step via `cut.record`; drag on empty space pans, tap on empty space moves the playhead. No opacity channel (not in the data model).
- Waveform: `AudioPeaks.get(ctx, uri)` decodes once (MediaExtractor + MediaCodec, mono, <= 22050 Hz, max 20 min, 16-bit PCM only), keeps up to 96 MB in memory and writes `cacheDir/waveforms/*.wf` for app-owned `file://` media. `TimelineMediaTrack` draws `WaveformCanvas` behind the clip label.
- Audio scrub: `AudioScrubHost` (MainActivity) watches `open.time`; a change while not playing and with `ed.audioScrub` on calls `AudioScrub.scrub`. The engine plays Hann-windowed 1024-sample grains (hop 512) from the decoded PCM through one AudioTrack at the normal rate, so pitch is preserved; backwards scrubbing reverses the grain; the thread stops after 400 ms idle. Sources with a rate below 22050 Hz and audio that is not decoded yet are silent. Unverified: not compiled or run on a device.

## 2026-11-13 (7) — Curve view selection rule, audio status
- Curve view layer = selected expanded Camera / Transformation layer, else the first expanded one (saved cuts keep `expanded` but select another layer). The channel list is the LEFT column (240 dp). `AudioPeaks.status` (SnapshotStateMap keyed by media uri) drives the text after the audio clip label. Open problem: on the SM-P610 the waveform and scrub did not work in the build before this one; cause unknown, read the clip label and the "Audio decode" / "Audio scrub" lines of the diagnostics log first.

## 2026-11-13 (8) — Brush engine upgrade (CPU stamping kept)
- Stamping stays on the CPU (`PathStamper` into the tiles); the blueprint's GL ES 3.0 stamp shader was NOT built because `GlLayers.kt` only composites finished tiles.
- Spring stabilizer = `Stabilizer` method 5 ("Spring (string)"): critically-damped-ish mass-spring-damper (zeta 0.9) with a string dead zone, integrated in 4 ms substeps from the event timestamps; `settleStep` lets the nib finish its swing to the pen on lift. Strength maps to the string length (0.5-30 px) and the spring frequency (6-50 rad/s).
- Velocity taper (`Brush.velSize`): pen speed (EMA of document px / s, log-normalized against 2500 px/s) scales the width in `Session.emit`; it is baked into each point's width, so nothing extra is stored. Floor 12 %.
- Dab jitter (`scatter`, `sizeJitter`, `angleJitter`, `opacityJitter`): `PathStamper.dab` draws 5 random values per dab from `java.util.Random(seed)` BEFORE any early return; `Ink.seed` (new, stored in V14) makes a rebuild place the same dabs. Strokes copied or split keep `seed`; new shape-tool strokes get `newInkSeed`. Rotation only applies to triangle / square / etched tips (a round tip looks the same at any angle) and uses a larger scratch bitmap.
- Wet mix (`wet`, `wetLength`): `DabSink.sample` (implemented by `TileSink` from the tile bitmaps, 5 samples) gives the colour under the dab; the carried colour moves toward it and fades back to the ink colour with the distance since it last touched paint (`exp(-d / L)`, L = 30..830 px). Replaying a cel applies strokes in order, so it sees the same paint underneath. Not for erasers; previews (`CanvasSink`) show no mixing.
- Not built: separate "Pressure sensitivity" / "Minimum size" sliders (the pressure -> size curve already covers both), shader grain with `gl_FragCoord`, instanced draws, true smudge that drags paint into empty areas. Unverified: not compiled or run on a device.



## 2026-11-14 (1) — Timeline annotations and selection tools
- `Cel` now has `persistent` and `inbetweenName` state. V15 stores both immediately after each cel duration; V1–V14 remain readable. Undo snapshots preserve these annotations.
- Persistent cels are rendered as subdued red canvas references (excluding the active cel) and shown red in the timeline.
- Timeline selection mode enables direct tap selection and drag-marquee selection for frames and diamond-key rows. The pre-existing double-touch marquee remains when the mode is off.
- Detailed onion options control previous / following drawing counts independently (0–8).
- Eyedropper is tool index 5 and is available in the tool panel, fullscreen radial menu and minimal fullscreen upper bar. F6 toggles all reference-image layers; it is rebindable.
- Remaining user-requested work is explicitly listed in the 2026-11-14 (1) changelog entry. The current ZIP was not compiled because no Gradle executable or wrapper is available in the environment.

## 2026-11-14 (3) — Brush tips / textures import, flow and per-stroke opacity, brush page layout
- Imported stamps and textures (`BrushAssets`): a grey-scale mask kept as a PNG (white, alpha = ink), id = CRC-32 of the PNG, at most 256 px. Tips: transparent images use their alpha, others use dark = ink; textures: bright = ink; both are stretched to the full range. `Brush.tipImg` / `texImg` hold the id (0 = built-in). The library persists in `filesDir/stamps` (`t_<hex>.png`, `x_<hex>.png`); `Store` V16 embeds the assets the strokes use at the END of the cut and registers them on read, so a cut opens correctly on a device that never imported them. Long-press in the picker only forgets an asset from the library (it stays in memory and in cuts). `.inbrush` export / import carries the PNGs (`assets` array, base64).
- Tip pipeline: `PathStamper` tracks a smoothed stroke direction; angle = `tipAngle` + (direction if `tipFollow`) + jitter; `roundness` squashes the tip along its local y axis (scratch bitmap path, `ext` = 1.45 r when rotated). Round, full-aspect, unrotated tips keep the fast direct path. Textures: `texScale`, `texInvert`, `texMoving` (anchored to the stroke start instead of the page; the stroke start is `startX/startY`).
- Flow / opacity: dab paint = `flow` (and, for classic brushes, opacity) with spacing compensation; `Brush.capped` (not for erasers) makes opacity a ceiling for the whole stroke: the stroke is stamped at full opacity into its own `TileSet` (`Session.scratch` live, a temporary set in `TileSet.stamp` on rebuild) and laid on the cel with `TileSet.compositeFrom` at `b.opacity`; `DrawingCanvas` draws the live scratch set over the layer at layer opacity x stroke opacity. Wet mix reads from the cel (`TileSink(under = ...)`). The old "pressure -> opacity" curve is now labelled pressure -> flow (same data, `opacityCurve`). `wetPressure` scales the wet mix with that curve's output (no raw pressure is stored per point).
- Brush detail page: `OptionsPanel` no longer scrolls as a whole in detail mode; `BrushDetail` pins the preview and scrolls the collapsible `Section`s below it (`Editor.brushSections`, session only). Tips and textures are picture buttons (`PicButton`, `stampPreview`).
- Not built: deleting an asset from existing strokes, a texture offset / rotation control, brush folders, a per-point stored pressure. Unverified: not compiled or run on a device.

## 2026-11-14 (4) — Undo backups, onion warm-up, image inks, media load, brush selection
- Undo of a drawn stroke: `Session.backup` (`StrokeBackup`) records the pre-stroke tiles through `TileSet.recorder`; `Cut.commit(cel, ink, backup)` hands it to `UndoPixels` (80 MB budget). `Cut.undo` restores them via `TileSet.restoreFrom` only when the stroke is the last of its cel and the set's `count == strokes.size + 1`; otherwise the set rebuilds (`sync`: count > size -> clear + replay). `commitOp` (fill, shape, transform) passes no backup. Any new code that writes tiles during a live stroke must call `set.noteWrite(tile)`.
- Kind-3 inks have TWO data forms: encoded image bytes (imported / flattened images, on drawing layers) and a UTF-8 location (reference-image layers, kind 5, drawn directly by `DrawingCanvas`). `TileSet.stampImage` tells them apart by the image header (`isEncodedImage`) and decodes bytes through `InkImageCache`. Never read `Ink.data` as a string without that check.
- Onion skin / persistent reference cels: `DrawingCanvas` draws `TileCache.peekReady` sets only and calls `OnionWarmup.request` for the rest; it reads `OnionWarmup.ready` so it redraws when one is done (a 1.5 s cool-down per list avoids rebuild loops when the memory budget evicts).
- Kotlin trap fixed in `Store.read`: inside `apply { }`, `name = name` assigns the LOCAL variable to itself when a local has the same name as the receiver's property; use `this.name = name`.
- Brush choice: `brushes.json` now also holds `brushIdx` / `eraserIdx` (`Editor.selectBrush`, `saveAll`, `loadBrushes`). Unsaved edits to a preset are still not persisted across a process restart.
- `Store.read` repairs empty audio / video layers (V10+, blank `mediaUri`) from `media_assets/<cut>_<layer>.media` when that file exists, re-reading the duration; start frame resets to 0.

## 2026-11-15 — Performance changes from the SM-P610 report (see CHANGELOG)
- `Recovery.snapshot` serializes the cut to memory on the main thread (consistency rule: the cut is only mutated there) and hands the bytes to the single `recovery-io` thread for write, `fsync`, rename and pruning. `Recovery.invalidated` (cut id -> time of the last normal save / clear) lets the writer discard a snapshot that was serialized before that; `snapEdits` is a `ConcurrentHashMap` because the writer removes the entry when a write fails. Never move the serialization itself off the main thread without a consistent copy of the cut.
- The pen hover cursor is drawn by its own overlay `Canvas` (zIndex 3, no pointer input) at the end of the `DrawingCanvas` `Box`. Do not read `hover`, `fingerTouches`-style high-frequency state inside the big canvas draw lambda unless the redraw is needed anyway.
- `buildGlScene` must never call `TileCache.get` for onion cels (UI-thread rebuild): it uses `peekReady` + `OnionWarmup.request` and reads `OnionWarmup.ready`. Layers below the active one still use `TileCache.get` because the displayed GPU frame must really contain them (`GpuLayers.shownK`).
- `Store.read` keeps an existing `media_assets/<cut>_<layer>.media` of identical length instead of rewriting it from the `.inb` payload.
- Profiler event log: category `tiles`, events `rebuild-ui` / `rebuild-bg` (>= 8 ms, only while the HUD or a recording is active). Application log: "Startup load: ..." phase timings.
- Known open: tile budget while editing (128 MB) may be too small for dense multi-layer frames; unverified. Documentation and reader discrepancy found during the V20 update: the prior V19 writer was not listed in the reader magic-number switch. V19 is now explicitly recognized by the V20 reader.
- Unverified: not compiled, not run on a device.

## 2026-11-15 (2) — Stroke worker, no UI-thread replays, tile budget floor (see CHANGELOG)
- Live dabs: `Session.add` queues each point to `StrokeWorker` (single thread "stroke-stamp"); `PathStamper` is only used from that thread after construction. `Session.finish()` and `release()` run a task on the worker and wait, so call them before reading tiles, committing or discarding the backup. Do not stamp from the UI thread any more. `Session.onStamped` triggers a redraw (the canvas sets it to `tick++`).
- UI-thread draw code must use `TileCache.getForDraw` (never `get`): it returns a drawable set or null and requests a background rebuild (`OnionWarmup.request(list, urgent = true)`, thread "tile-build"); `TileSet.building` is true while `sync` replays (the set lock is held; never draw such a set on the UI thread). Pen down, fill / wand, export and background threads still use `TileCache.get` (may replay).
- `TileCache.floorBytes` (from device RAM in `MainActivity.onCreate`) is the minimum tile budget; `CelCache.budget`'s setter applies `max(v + 32 MB, floor)`.
- Cut thumbnails at startup are rendered by the "cut-thumbnails" background thread (`Cut.thumb` is Compose state).
- Unverified: not compiled, not run on a device.

## 2026-11-15 (3) — Selection / Warp / Puppet / Fill (see CHANGELOG)
- File format is now **V18** (reader V1-V18). Ink kinds: 0 stroke (`runs` = selection it was drawn inside, optional), 1 fill, 2 region transform, 3 reference image, 4 region warp (`runs` + `m = [cols, rows, verts...]`, vertices in final document space).
- `Xform.mesh` lives in document space before `Xform.mat`; commit bakes the matrix (`WarpMesh.mapped`). `Xform.mode` 0 / 1 / 2 (handles / grid warp / puppet). Puppet base mesh is the mesh at tool entry; pins hold rest (p) and current (q) positions.
- The selection widget bar is `SelectionBar` inside `DrawingCanvas` (not in `MainActivity`); it gets the on-screen box from a lambda read in its layout phase. The shimmering outline and the hover cursor are separate overlay canvases above the big canvas.
- Drawing inside a selection: `Session(clipRuns)` / `TileSink(clip)`; always call `TileSink.endClip()` (done by `Session.finish` / `release` and `TileSet.stamp`) or tile canvases keep a stale clip.
- Fill / Wand: `smartFlood` replaced the inline flood in `recompute`; `FillWork` holds the scratch arrays; `snapSeed` runs at pen down for Fill.
- Unverified: not compiled, not run on a device.

## 2026-11-15 (4) — Reference layers: several images, pick / delete, Relative position (see CHANGELOG)
- Model: a reference layer = one cel whose `strokes` are kind-3 inks, one per image (`Ink.data` = UTF-8 file location, NOT image bytes; `Ink.m` = 3×3 matrix). `Cut.referenceInks(layer)` lists them. `Editor.refSel` is the picked image (session only); gizmo, drag, Flip and Delete act on it. Never use `strokes.firstOrNull { kind == 3 }` to mean "the reference image" any more.
- Picking: `gDown` (Canvas.kt) picks the topmost image under a finger / pen over every visible, unlocked reference layer (`refInkAt`, uses `ReferenceBitmapCache.dimensions`, no decode) and selects its layer; a handle of the already picked image has priority. `Editor.refReturnId` = the non-reference layer the reference layer was entered from (set in `MainActivity`'s `LaunchedEffect(cut.layerId)`).
- Pen: down on empty space of a visible, unlocked reference layer = pen kind 14; a tap without movement calls `exitReference` (back to `refReturnId`). Fingers: one finger on an image moves it (handle drag); one finger elsewhere pans the canvas; two or more fingers on a reference layer transform images (target = image under the gesture centre or the image the first finger started on, else all images of the layer) as ONE undo step (`Cut.pushReferenceMatrices`, `ReferenceMatricesAct`); that gesture neither zooms the canvas, resets the view nor snaps the view rotation.
- Relative position (`Layer.refPinned`, session state, NOT in `.inb`): pinned images are stored in HOME-view coordinates (view reset, no flips; `homeScr` / `homeToDoc`) and drawn through `pinMatrix()` (home -> document of the current view) inside the normal view transform, so they stay on the screen. While a pinned reference layer is active `toDoc` / `toScr` use the home mapping (gizmo and drags work in screen space). `Editor.toggleRefPinned` converts the matrices in one undo step (includes the folder transform of an unpinned layer). Persisting the pin would need a V19 layer flag.
- Delete: `Cut.removeReferenceImage` (undo restores the index; the last image removes the empty layer instead). Load: `Store.read` drops only missing image files and removes a reference layer left without images.
- Unverified: not compiled, not run on a device.
- Follow-up (2026-11-15 (7)): tap detection on reference layers uses screen pixels (`PenState.farScr`, `TAP_SLOP_PX` = 40), not `PenState.moved` (document units). A pen-down in the handle zone outside every image (`PenState.refOutside`) that ends as a tap reverts the tiny drag and leaves the layer. Two-finger reference gestures keep the raw transform in `refG` and apply the rotation magnet live (single image: absolute 30-degree angle around its centre; several: total gesture rotation).


## 2026-11-16 — Perspective layer revamp (historical V18 behavior)
- `Persp` now treats the horizon / perpendicular intersection as the optical centre and supports independently rotated guide axes. Vanishing-point coordinates remain backward-compatible with V1–V17 values by storing their scalar axis coordinate relative to the existing intersection component.
- Each guide has an unlock-rotation toggle, 30° rotation step about its selected (or first) vanishing point, VP-distance lock, curvilinear toggle and independent reset. The shared FOV is the angular separation of the two guide axes at the optical centre; setting it keeps the selected guide's direction fixed and rotates the other about its vanishing point, moving the intersection so the guides continue to meet.
- Curvilinear mode renders a bowed / fisheye-style guide and uses a sampled curved path for brush magnetism. Straight perspective magnetism is applied to each incoming sample in `Session.feed` after stabilization and before stamping; pressure and tilt are still passed unchanged into the brush engine.
- Perspective brush / eraser magnetism uses a per-stroke path latch. When a perspective layer is visible, the nearest direction-compatible guide or vanishing-point ray is selected from S Pen hover trajectory (or first contact movement) and every sample is fully projected onto that path until pen-up. There is no capture-radius threshold and no ordinary freehand fallback. Pressure and tilt remain independent of position projection.
- `Store.kt` writes V18 and reads V1–V18. V18 stores both guides' rotation, rotation-unlock, VP-distance-lock and curvilinear flags.
- Perspective UI lives in `DrawingCanvas` (`Canvas.kt`): two icon rows, S Pen hover descriptions, FOV slider dialog and per-guide Reset.
- Validation in this change: source review and structural checks only. No Gradle executable / wrapper or Android SDK build was available; not compiled and not tested on the tablet.


## 2026-11-16 — Perspective bar interaction and smooth magnet acquisition (historical V19 state)
- The perspective widget bar is explicitly above the full-canvas drawing surface (`zIndex(3)` versus canvas `zIndex(2)`), so its buttons receive pointer input.
- The bar presents one guide at a time. `activePerspectiveGuide` follows taps on a vanishing point or either guide axis in `pDown`; the per-guide settings remain stored independently in `Persp`.
- Magnet acquisition no longer returns the fully projected point on the first captured sample. `PerspectiveMagnetState` tracks the last raw point and blend progress; the captured path is eased in with smoothstep over 0.85 × capture radius. This prevents a sudden position discontinuity when the latch is acquired while preserving the per-stroke path latch and pressure / tilt data.
- Manual `Canvas.kt` import correction remains intact: `KeyboardOptions` is from `androidx.compose.foundation.text`; `KeyboardType` remains from `androidx.compose.ui.text.input`.
- Validation: ZIP integrity, source assertions and targeted diff review. No Gradle build or tablet interaction test was run.


## 2026-11-16 — Curvilinear VP arcs, camera lens FOV and independent rotation locks
- Curvilinear geometry: each adjacent pair of vanishing points on the same guide now defines a family of circular arcs. Every arc is constructed through the exact two VP endpoints; positive and negative arc multipliers bend on either side of the chord. This replaces the prior quadratic guide bend and the per-VP sine-offset ray approximation. With fewer than two VPs on a guide, Curvilinear has no pair to define an arc and falls back to the straight guide.
- Rendering and magnetism use the same sampled circular arcs. Curvilinear magnet acquisition latches a specific adjacent VP pair and a specific arc for the remainder of the stroke; sampled geometry is retained for the stroke to avoid regenerating the arc family on every input sample. Guide hit-testing also measures distance to the displayed arc family.
- Rotation: each guide has an independent 30-degree snap toggle, default off. Rotation remains locked until unlocked; with the snap toggle off, drag rotation follows the pointer freely; with it on, drag rotation snaps to 30-degree increments. Both settings are included in undo snapshots.
- Camera lens: FOV is now horizontal camera field of view for a 36 mm full-frame sensor, linked to focal length by `f = 36 / (2 tan(FOV/2))`. The lens dialog edits either value and updates the other. FOV is no longer interpreted as the angle between the horizon and perpendicular guides, and it no longer rotates a guide. The canvas displays the horizontal camera view cone around the perpendicular optical axis; it does not change the separately controlled curvilinear arc strength.
- Line tool: removed perspective endpoint snapping from both the line-shape tool and straight-line brush mode.
- Persistence: `Store.kt` writes V20 and reads V1–V20. V20 appends the per-guide 30-degree snap booleans after the V19 curvature-strength floats, then the perspective camera FOV. The V19 magic number is now explicitly recognized in the reader; the previous reader switch had omitted it. V1–V19 projects default to 30-degree guide snapping disabled and 90-degree camera FOV.
- Files: `Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle wrapper / build scripts / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: source-level review, targeted searches, binary-format read/write ordering review, mathematical check of the full-frame FOV/focal relationship, and ZIP structure/integrity checks. No Gradle wrapper / executable or Android SDK build was available; not compiled and not tested on the tablet.
- Known limitations: Curvilinear arc families require at least two VPs on the same guide. Arc-family appearance and snapping thresholds need S Pen validation. FOV uses a full-frame sensor reference rather than a user-selectable sensor size.


## 2026-11-16 — Horizon-stable curvilinear rays, focal-length VP scaling and hover-gated magnetism

- AI model: GPT-6
- Category: Improved / Perspective tools and stylus input
- Curvilinear geometry: Replaced the previous VP-to-VP arc strips with an adjustable radial ray fan from every vanishing point. The guide axis itself is always drawn and hit-tested as a straight line. Rays parallel to the guide have zero curvature; ray sagitta grows with the vertical component of direction and along the ray, so the horizon remains flat. One VP is sufficient to display a ray fan.
- UI: Added a per-guide ray-count button that opens a slider (8–48 rays, even counts) and resets to 24. Curvature strength remains independently adjustable.
- Lens: Changing focal length scales VP offsets from the optical centre by `new focal length / old focal length`; FOV and focal length remain linked. This makes focal length visibly reposition existing VPs instead of changing only the blue view-cone overlay.
- Stylus: Tracks the S Pen's pre-contact hover cursor trajectory. Perspective magnetism is only eligible when a recent hover direction, initial stroke direction and candidate guide/ray direction agree; no recent hover trajectory means no magnet attraction. The existing per-stroke latch and pressure/tilt path remain intact.
- Persistence: V21 appends per-guide ray counts after the V20 camera FOV fields. V22 stores physical focal length directly in that lens field position. V20/V21 stored FOV values are converted to focal length on read; V1–V20 load with the default 24 rays, while V21 retains its stored ray counts.
- Files: `Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle wrapper/build scripts, `gradle.properties`, `.github/workflows/**`, manifest, SDK/Java and signing configuration.
- Validation: Source-level checks and archive integrity only. No Gradle wrapper/executable or Android SDK is present in this environment; no Android build or tablet test is claimed.
- Known follow-up: Validate hover-trajectory capture on the target S Pen and adjust the 900 ms freshness / 0.70 directional threshold if the tablet reports sparse or noisy hover samples. Check the visual ray fan at several zoom levels and with rotated guides.


## 2026-11-16 — V22 Release Kotlin nullability compile fix
- `Persp.magnet` accepts `hoverDirection: Offset?`; the local `normalized` helper accepts only a non-null `Offset`. Normalize the optional hover vector with `hoverDirection?.let { normalized(it) }` so absence remains `null` and the existing contact-movement fallback remains available.
- This is a source-only compile correction. It does not change the V22 file format, magnet selection thresholds, hover freshness window, or protected build configuration.
- Validation: the corrected nullable expression was compiled in an isolated Kotlin check and all `normalized(...)` call sites in `Camera.kt` were reviewed. Full Android build and tablet test remain outstanding.


[2026-11-16] — Vanishing-point ray authority and line-tool magnetism

- AI model: GPT-6
- Category: Fixed / Perspective magnetism
- Summary: Curvilinear ray families now connect adjacent vanishing points along each guide. The two central rays coincide with the straight guide; circular-arc sagitta grows quadratically away from the horizon and both ends meet the actual VP centres. A single VP retains an outward radial fallback fan.
- Magnetism modes: Added a saved per-perspective-layer toggle. VP mode makes vanishing-point paths authoritative and removes horizon/perpendicular axes as competing candidates; guide mode magnetizes only to the two guide axes. If VP mode is enabled but no VPs exist, guide axes remain a fallback so visible perspective layers do not produce unconstrained brush strokes.
- Line tool: The straight-line shape tool now uses the same hover-direction path acquisition as brush/eraser. When it acquires a curved VP path, the committed line follows the sampled curve between the projected endpoints rather than drawing a chord.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle wrapper/build scripts, `gradle.properties`, `.github/workflows/**`, manifest, SDK/Java and signing configuration.
- Validation: Source inspection and focused geometry/format checks are pending. No Android SDK / Gradle build or tablet runtime test is claimed.
- Known follow-up: Verify the ray-family appearance with multiple VPs on each guide, hover-guided capture for brush/eraser and line tool, mode-toggle behavior, and old V1–V22 document loading on tablet.


## V24 — Direction-aware VP family selection and averaged curvilinear paths
- Perspective magnet selection now chooses the VP family whose guide axis best matches the initial stroke / recent hover direction. This prevents horizontal gestures from selecting the perpendicular VP family and vertical gestures from selecting the horizontal family.
- Curvilinear magnet candidates are now generated as pointwise averages between adjacent ray paths within each neighbouring-VP family. The magnet selects these intermediate paths rather than snapping directly to one individual displayed ray.
- Existing V23 storage format is unchanged; no new persisted settings were introduced.
- Validation remains source-level only unless a Gradle/Android build and S Pen device test are performed.


## 2026-11-16 — Vector layers (format V24)
- A vector layer = `Layer.vector` on a kind-0 layer. Cels are keyframes; strokes are Ink kind 5 (`Vector.kt`: `toInk` / `toVectorStroke`). Never treat kind-5 ink `p` as brush points. `TileSet.stampVector` rasterizes via `VectorTessellator.path`.
- Editing a keyframe's strokes: swap the `Cel` for a new object (`swapVectorCel` / `replaceVectorCel`) so undo snapshots keep the old one; during a point drag the fresh cel is edited in place and `TileCache.invalidate` + `get` rebuild its tiles. `Cel.vecCfg` (interpolation settings) is in `LSnap`.
- Pen kinds 15 draw / 16 point edit / 17 cutter (`Canvas.kt`); `Editor.vecMode`, `vecSel`, `vecFalloff`, `vecRaise`. Bake = `bakeVectorKeys` (shortens the key to the step and inserts cels). `Store` writes V24 and reads V1-V24.
- Open: GL ribbon renderer / FBO export, live interpolation preview, Bezier editor. Not compiled or tested.
- Live interpolation: render paths call `Layer.drawCel(t)` (vector layers: `VectorLive`, cached transient cels, opt-in via `Cel.vecCfg`); editing paths keep `celAt`. In-place stroke edits must bump `Cel.vecRev`.
- Vector selection / transform: `Editor.vecSel` (points of `vecSelCel`) is shared by Point edit and the Selection tool; pen kinds 18-21 in `Canvas.kt`; `VectorEdit.transform` / `delete`. Shapes: `commitShape` writes a vector stroke when the layer is a vector layer.
- Reference anchors (V25): `VectorPoint.anchor`, stored sparsely in Ink kind 5 `m` after the 3 header floats. `VectorInterpolation.anchoredBlend` pairs same-rank anchors; the store writes V25 and reads V1-V25.
- Vector UI: timeline strip = draw / point edit / interpolation drag / cutter (`vecMode` 0 / 1 / 3 / 2). Everything else lives in `VectorWidgetBar` (bottom centre of the canvas). Interpolation drag = pen kind 22 + `createDraggedInbetween`; `rippleRetime` makes the new frame take time from the current key instead of pushing.
- Bezier line shape = `ed.lineShape == 4`, pen kind 23, `Bez` state in `DrawingCanvas`. Interpolation pairing is automatic (`pairAuto`: anchor colours, then ids, then geometry); `InterpolationMode` is only Interpolate (SAME_STROKE) or Hold (NONE) in the UI. `autoAnchor` assigns free colours in order.
- Vector selection scope: `vecScope(cut, ed.refAll)` -> `VecScope` (concatenated strokes of the active layer or all visible unlocked vector layers; `localKeys`, `split`); `applyScope` swaps cels as one undo step; selection cels live in `Editor.vecSelCels` (observable). Widget-bar state must be read unconditionally (no short-circuit) or the buttons go stale. Smooth = `VectorSmooth.refine`.

## 2026-11-17 — Vector crash fix, Merge, point brushes, vector eraser, pen
- `VectorLive.celAt` must never fill a state list inside a read-only snapshot (layout / draw): from such a snapshot it returns the held key and builds the transient cel on the main handler, then bumps `ready` (observed). Any new code that writes `Cel.strokes` must run in event handlers, global-snapshot threads or `cut.record`, never in draw lambdas.
- Vector draw: `VecPen` (Vector.kt) carries the brush's `Stabilizer`, smoothed pressure and tapers; `Canvas.kt` `vecSample` / `vecCommitStroke`. Merge = `Editor.vecMerge` + `VectorMerge.attach` (ends / T-junctions only). Point-edit brushes = pen kind 24 (`VectorBrush`, `Editor.vecBrush*`); vector eraser = pen kind 25 (`VectorEraser`). Widget bar (`VectorWidgetBar`) now also appears in Vector draw mode (Merge). Bezier second handle only exists in stage 2. See CHANGELOG 2026-11-17. Not compiled or run.

## 2026-11-18 (8) — Vector fills, incremental live editing (see CHANGELOG)
- Fills are closed `VectorStroke`s with `fill` 1 / 2, kept at the start of the cel list (under all lines); live fills (2) keep seed / gap / grow / across so `refillLiveFills` can recompute them. The raster Fill flow is shared; only the pixel source and commit differ for vector layers. Interpolated in-betweens morph fills; they are not recomputed.
- Live vector edits go through `VecLiveCel` (base tile set + dirty-tile reset). `VectorEdit` edit functions must keep returning the same instance for untouched strokes, or the optimisation degrades to redrawing everything. Not compiled or run.

## 2026-11-18 (7) — Brush look on vector lines (see CHANGELOG)
- Style fields live on `VectorStroke` (`hardness`, texture, tip shape / roundness / angle / follow) in the kind-5 data text; set from the brush by `withBrush`, carried through interpolation by `styleFrom`. Width follows the tip in `ribbon` (`tipFactor`); blur and texture are applied in `VectorTessellator.render` from `stampVector` only. New code building a stroke from another must `copy` it (or call `styleFrom`) or the look is lost. Not compiled or run.

## 2026-11-18 (6) — Vector wobble, Interpolation button (see CHANGELOG)
- Wobble lives on `VectorStroke` (`wobble`, `wobbleScale`, `wobbleSeed`, kind-5 data text, no format bump) and is applied only in `VectorTessellator.ribbon` (`wobbleDense`); set per keyframe by `setVectorWobble`. Live-interpolated in-betweens have none. New code that builds a `VectorStroke` from another must use `copy` (or pass these fields) or the wobble is lost.
- `VectorInterpolationHost` (in `DrawingCanvas`) owns the interpolation dialog; the timeline strip button and the drag bar button only set `ed.vecInterpOpen`. Not compiled or run.

## 2026-11-18 (5) — Rounded turns, point-preserving vector brushes (see CHANGELOG)
- `VectorTessellator.fillet` rounds turns of 55-170 degrees before the Catmull-Rom pass; only near-reversals stay corners and get round joins in `ribbon` (arrays grow on demand). Do not reintroduce per-vertex hard corners for ordinary turns.
- `VectorBrush` must not add points per segment: it moves existing points and only calls `ensureHandle` when no point is inside the radius. `VectorWidgetBar` shows the regular selection widgets (add / erase / invert / aspect lock / magnet / done) with the Selection tool; `vecXfMove` applies the rotation magnet. Not compiled or run.

## 2026-11-18 (4) — Vector Lock weight, anchor shortcuts, per-stroke anti-aliasing (see CHANGELOG)
- `Editor.vecLockWeight` (session) makes `VectorEdit.transform` leave `baseWidth` / `elevationOffset` unchanged. `KeyAction.SET_ANCHOR` (A) / `CLEAR_ANCHOR` (Shift+A) call `autoAnchor` / `clearAnchors` for the selected points (Selection tool, vector layer).
- `VectorStroke.aa` = `Brush.aa` at draw time; stored as an optional third `|` field of the kind-5 Ink data text (absent = 1 = anti-aliased), so no format bump. Rendering uses only the on / off threshold (> 0.02); the raster brush's `aaBlur` softness is not used for vectors. Not compiled or run.

## 2026-11-18 — Smooth vector curves, Uniform timing, flicker fixes (see CHANGELOG)
- Vector outline: `VectorTessellator.ribbon` densifies the control points along a centripetal Catmull-Rom curve (`smoothThrough`); corners (turn over about 55 degrees) stay sharp. Stored points are unchanged; hit tests still use the control polyline. `VectorSimplify.fit` (used by `vecCommitStroke` and `commitShape`) fits points to that drawn curve; `rdp` stays for other uses. `TileSet.stampVector` builds the outline once per stroke.
- Tile sets: the canvas skips a cel that has no tile set (`TileCache.getForDraw`). Anything that puts a NEW `Cel` object on the timeline for the active frame must build its tiles first (`swapVectorCel` does). `VectorLive.celAt` in a read-only snapshot returns a transient cel only when its tiles are ready, else the previous transient (`Entry.prev`) or the held key.
- Uniform timing: `Layer.uniformTiming` (folder), `Layer.uniformAttached` (layer), saved as V26 only when used. Sync lives in `Cut.syncUniformTiming`, called from `pushStruct`: any recorded step that changes the cels or durations of an attached layer is mirrored by cel identity onto the other attached layers of the same folder, inside the same undo step. New operations that change timing must go through `record` / `pushStruct` to be mirrored; frames made while drawing past the end use `ensureCelUniform`. Layers added to the folder later start detached.
- `Cut.duplicateEmptyVector()`: empty vector layer with the timing of the selected drawing layer.
- Smooth only inserts points between selected points (`VectorSmooth.refineMapped`).
- Vector Merge: `ed.vecMerge` toggle is `ToggleChip` in `VectorWidgetBar`. Reach = `VectorMerge.REACH_PX` (14 screen px) or half the brush size; the live rings (`vecMergeStart` / `vecMergeEnd` in `DrawingCanvas`) use `VectorMerge.snapTarget`, which must keep following the same rules as `VectorMerge.attach`.
- Vector tool layout (2026-11-18 (3)): no Vector draw / Point edit buttons any more. Brush tool on a vector layer draws (`vecMode` 0); Selection tool uses `Editor.vecPoints` (true = old Point edit behaviour, false = whole-line selection). Timeline strip keeps only Interpolation drag (`vecMode` 3) and Cutter (`vecMode` 2). `VectorWidgetBar` follows `ed.tool` (0 draw / drag bar, 2 point bar). Merge reach = `Editor.vecMergeRadius`; a line may close onto its own start (`VectorMerge.attach`).

## 2026-11-19 — Refill is incremental, clone after Select all, dragged in-between timing (see CHANGELOG)
- `refillLiveFills` is `suspend`: one fill per step with `yield()`, then one `cut.record` for the swap. Call it only from a coroutine on the main dispatcher; never run the floods off the main thread (the cut is only consistent there). `VectorFillBar` disables the buttons while it runs.
- `Cut.cloneFrames` treats `selAll == layer.id` as "every frame of the layer" when `ed.selCels` is empty.
- `createDraggedInbetween`: the new frame is carved from the previous keyframe first (`p.dur > 1`); the retime toggle only matters when the previous keyframe lasts one frame. Not compiled or run.

## 2026-11-19 (2) — Line weight on vector lines (see CHANGELOG)
- Weight design lives on `VectorStroke` (`inkCurve`, `inkLight`, `inkAngle`, `taperIn`, `taperOut`, kind-5 data text fields 21-25, no format bump) and is applied only in `VectorTessellator.inkWeight` (called from `ribbon`). Points, `baseWidth`, hit tests and interpolation use the clean data. New code that builds a stroke from another must `copy` it (or call `styleFrom`) or the weight is lost. Set per keyframe by `setVectorInk` (`VectorInkBar`). Not compiled or run.

## 2026-11-19 (3) — Line weight additions (see CHANGELOG)
- `inkIrr` / `inkIrrScale` (irregular weight, id-seeded noise along the line) and `aaSoft` (extra edge feather, shares the hardness blur in `VectorTessellator.render`) are stored as kind-5 data fields 26-28, no format bump. `InkParams` is what the bar applies (`setVectorInk(cut, layer, targets, params)`).
- `VectorInkPreview.wrap` is called from `Layer.drawCel`: while the Line weight bar's Preview is on, the targeted keyframes are drawn from a transient cel. It must stay main-thread only and must never be written to the document. Not compiled or run.

## 2026-11-19 (4) — Line weight bar layout and irregular ranges (see CHANGELOG)
- `VectorInkBar` sits bottom-left (`Canvas.kt`) and is scaled to 0.75 with `graphicsLayer` (origin bottom-left); do not also shrink its fonts or paddings. `inkIrr` range is 0..3, `inkIrrScale` 0.5..400 px (clamped in `Store`-side parsing in `Vector.kt` and in `inkWeight`). Not compiled or run.

## 2026-11-19 (5) — Merge sharpness / Merge button, eraser All layers, clone-preserving Duplicate layer, Copy frame selection (see CHANGELOG)
- `VectorStroke.joinSharp` is style field 29; `Editor.vecDocZoom` is set from `vecZoom()` in `Canvas.kt`. `duplicateLayer(true)` keeps clone groups; `duplicateFrame(selected)` copies the selection. Not compiled or run.
- Correction: Merge lives on the Brush tool and the Line / shapes tool (not the selection tool); `joinSelected` is currently unused.
- Correction 2: the selection tool (Points off) has the Merge button again (`joinSelected` is used); Merge is on Brush, Line / shapes and selection tools.
- 2026-11-19 (7): fills are traced without ring smoothing and drawn with `smoothThrough(..., sharp = 1f)`; selection-transform lag report open (see CHANGELOG).
- 2026-11-19 (8): `VectorPoint.hidden` (stored as anchor rank + 128), anchors are numbers (`anchorColor`, `ANCHOR_MAX` 99), `VectorGaps` + `VecGapShimmer`, `interpolateSelected`. Not compiled or run.
- 2026-11-19 (9): `VectorMerge.mergePoints` (point bar), crossing merge in `attach`, `interpolateIntoFrame` (three selected frames). Not compiled or run.
- 2026-11-20: interpolation drag with a selection uses `pairSelected` / `interpolateChosen` (partners by anchor, else id; no fading, no geometric guess), three frames resolve the selection with `vecSelectedLinesIn`. Not compiled or run.
- 2026-11-20: Copy / Paste of the selection: `SelectionClipboard` (session clipboard; pixels as embedded-image Ink kind 3, lines via `VectorEdit.delete` inverse + `applyScope`), `TileSet.copyRuns`, buttons in `SelectionBar` / `VectorWidgetBar`, `ClipboardBar` paste widget. Not compiled or run.
- 2026-11-20: vector widget bar text controls and the timeline Selection button are icons (`VIconBtn` in VectorUi.kt, `HighlightAlt` in Panels.kt). Not compiled or run.
- 2026-11-20: numbers are typed on the keypad (`NumberPad.kt`: `NumberField`, `NumberPadDialog`, expressions with + − × ÷, value opens selected); the Android keyboard stays for names only. Timeline numbers by drawing (`drawingNumber` / `frameLabel` in Editor.kt: a clone shows its source's number). Dialogs hide the system bars (`ImmersiveDialog.kt`: package-level `AlertDialog` shadows Material's; never import `androidx.compose.material3.AlertDialog` explicitly). Not compiled or run.
- 2026-11-20: auto anchors (`AutoAnchors.kt`: ends + curve tips, `VectorPoint.auto`, saved as AUTO_FLAG_OFFSET entries in the Ink's sparse anchor list; `refresh` after merge / delete; manual and traced anchors are never redone) and the line ghost (`LineGhost`, shimmer in Canvas.kt, previous / next toggle, `inherit` copies the ghost's anchor numbers onto a traced line). Not compiled or run; thresholds are a first guess.


## 2026-11-22 — Ordered vector interpolation anchors across merged strokes
- Open vector strokes now receive automatic anchors only at their two outer endpoints (`AutoAnchors.targets`). Curve-tip auto anchors remain available for closed strokes; open-stroke intermediate correspondence anchors are user-controlled.
- `AutoAnchors.refresh` rebuilds only structural auto anchors on changed open strokes. After `VectorMerge.attach` joins constituent strokes, anchors at internal joins are removed and the remaining structural endpoint anchors are placed at the first and last points of the complete merged stroke. Intermediate anchors (`auto=false`), including manually placed and inherited interior anchors, are retained at their existing points and are not regenerated or reordered.
- `AutoAnchors.inherit` marks only the actual first/last anchors inherited from an open ghost as structural (`auto=true`); interior ghost anchors remain non-automatic. This lets a traced endpoint move to the outer edge when separately traced pieces are merged.
- No `.inb` format change. Existing automatic anchors on open strokes that represented curve tips may be dropped the next time those strokes are refreshed; existing non-automatic anchors are preserved.
- Files: `app/src/main/java/com/exemple/inbetween/AutoAnchors.kt`, `app/src/main/java/com/exemple/inbetween/WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: source-level review, targeted behavior inspection of `VectorMerge.attach`, `AutoAnchors.refresh`, and `AutoAnchors.inherit`; diff and ZIP integrity checks. Not compiled or run on a device; endpoint identity behavior under complex multi-piece merge chains and cross-keyframe interpolation remains to be tested in the app.


## 2026-11-22 — Ghost tracing preserves available reference anchor correspondence
- `AutoAnchors.inherit` no longer requires every anchor on the reference ghost to be free in the current cel before using the remaining free anchors. When the new stroke is offset from the ghost, those available anchors are still placed by their relative path positions and retain their original numbers/order.
- `Canvas.kt` tracks whether a stroke was actually traced from the ghost. Such strokes do not fall back to fresh automatic endpoint numbering when inheritance is partial or all reference anchors are already present in the current cel. This prevents a ghost with anchors 1 and 2 from producing an unrelated traced stroke with fresh anchors 3 and 4. Merge refresh continues to maintain structural outer endpoints on the complete merged stroke.
- No `.inb` format change. Files: `AutoAnchors.kt`, `Canvas.kt`, `WhatsNew.kt` (note 55), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: source-level assertions, diff review and ZIP integrity only. Not compiled or tested on a device.


## 2026-11-22 — Preserve ghost correspondence identities during merge refresh
- `AutoAnchors.refresh` now distinguishes reuse of an existing automatic anchor identity from allocation of a new anchor number. Existing anchors shared with neighbouring keyframes are prioritized when structural endpoints move, ahead of unrelated automatic anchors brought in by another merged stroke; they can be reassigned to the new outer endpoints even though their numbers also occur in adjacent keyframes.
- Numbers shared with neighbouring keyframes remain reserved for genuinely new anchors. This fixes the merge regression where ghost-traced endpoints 1/2 were rejected or displaced and replaced by fresh 3/4 after another stroke was merged. No `.inb` format or persistence change.
- Files: `app/src/main/java/com/exemple/inbetween/AutoAnchors.kt`, `app/src/main/java/com/exemple/inbetween/WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: focused source inspection and diff review; no Android SDK / Gradle build or tablet runtime test available.
