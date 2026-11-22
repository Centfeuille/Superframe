## 2026-11-22 — Ghost-traced strokes keep reference anchor numbers

- AI model: GPT-6
- Problem: a stroke traced from a ghost could receive fresh endpoint numbers (for example 3 and 4) instead of the ghost's reference numbers (1 and 2). `AutoAnchors.inherit` filtered out numbers already used in the current cel, then required all reference anchors to remain free before its relative-position fallback could run. A partial overlap therefore made inheritance fail. Even when a partial trace succeeded, the normal post-draw endpoint fallback could add unrelated numbers.
- Fix: relative-position inheritance now accepts any non-empty ordered set of available ghost anchors. `Canvas.kt` records a ghost-trace decision and prevents traced strokes from receiving fresh auto endpoints when inheritance is partial or the ghost's anchors are already represented in the current cel. The merge path still refreshes structural endpoints on the complete merged stroke.
- `.inb` format unchanged.
- Files: `AutoAnchors.kt`, `Canvas.kt`, `WhatsNew.kt` (note 55), `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected build / signing infrastructure untouched.
- Validation: source assertions and diff review; ZIP integrity check. Not compiled and not tested on a device.

## 2026-11-22 — Ordered vector interpolation anchors across merged strokes
- AI model: GPT-6
- Category: Improved / Vector interpolation
- Open vector strokes now use automatic anchors only at their two outer endpoints. Automatic curve-tip anchors remain for closed strokes; intermediate correspondence anchors on open strokes are user-controlled and are not regenerated.
- Ghost-traced anchors at the actual start/end of an open reference stroke are marked structural. When traced pieces are later merged, `AutoAnchors.refresh` can move those endpoint anchors to the outer ends of the complete merged stroke and discard structural anchors that became internal joins. Intermediate non-automatic anchors keep their point positions and order.
- No `.inb` format change. Existing non-automatic anchors are preserved; existing auto curve-tip anchors on open strokes may be removed when that stroke is next refreshed.
- Files: `app/src/main/java/com/exemple/inbetween/AutoAnchors.kt`, `app/src/main/java/com/exemple/inbetween/WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle wrapper/build scripts, `gradle.properties`, manifest, SDK/Java and signing configuration.
- Validation: inspected `VectorMerge.attach`, `AutoAnchors.refresh`, ghost anchor inheritance and relevant callers; source diff and ZIP integrity checked. Not compiled or run on a device.
- Known follow-up: exercise multi-piece merges in both drawing directions, partial ghost tracing, intermediate-anchor order, and interpolation between keyframes on device.

## 2026-11-20 — Auto anchors at ends and curve tips, merge-aware; line ghost with previous / next toggle

- AI model: Claude
- Request: anchors created automatically while drawing, at the ends and the tips of curves in between, dynamic when a line is built by merging smaller ones; a "line order onion skin" where the line to draw shimmers so the new line matches the anchor points; a toggle for the ghost on the previous or the next frame. Agreed order: auto anchors + merge first, then the ghost.
- Model: `VectorPoint.auto` (the anchor was put there automatically). Saved without a format bump: the sparse anchor list of the Ink gets extra entries `AUTO_FLAG_OFFSET (1,000,000) + point index, 1`, which older builds skip (out of range for them); `toInk` / `toVectorStroke` read and write them. `VectorMerge.mid` and `VectorEraser.pointAt` carry the flag.
- New `AutoAnchors.kt`. `AutoAnchors.targets`: ends of an open line plus up to 6 tips (resample the control polygon, light smoothing, turning angle over a window of about 6 % of the length, local maxima above 100 - 70 x sensitivity degrees, minimum spacing 12 % of the length, tips near the ends dropped); a closed line gets its tips or two opposite points. `assignFresh` numbers them from the first number not in the taken set (marked auto). `refresh`: for every line with automatic anchors, clear them, find the new targets, reuse the number of the old automatic anchor nearest to a target (within 3 px or 3 % of the length), otherwise a free number; manual anchors stay and block their numbers; returns the same list object when nothing changed (so no empty undo step). `refreshFor(layer, cel, strokes, sens)` wraps it with the neighbour numbers.
- `AutoAnchors.inherit(stroke, ghost, blocked)`: the new line takes the ghost's anchor numbers, except numbers already used in its keyframe. Open lines: direction from the end distances (a line drawn the other way is read backwards), ghost ends land on the line's ends, other anchors at the nearest point of the new line with increasing position, falling back to the same relative arc length when the nearest point is far (more than 60 % of the larger bounding diagonal); closed lines: nearest point of each anchor, no order. A point is inserted where the line has no vertex within 0.4 % of its length. The inherited anchors are NOT automatic (they say which point matches which), so a later merge does not redo them.
- `LineGhost` (same file): `reference` (nearest previous / next keyframe with vector strokes whose drawing differs from the current one; clones and empty frames skipped), `unclaimed` (reference lines that no current line shares an anchor number with or lies on by its ends), `current(open, skip)`, `choose(stroke, open, shown, px)` (the shown line, unless the stroke lies clearly closer to another one; null when it lies on none, then it is just a new line).
- `Canvas.kt`: `vecAnchorNew(l, stroke)` runs when a freehand line (`vecCommitStroke`) or a Line / shape (`commitShape`) is committed: ghost on and a ghost line chosen -> inherit (the ghost line gets automatic anchors first through `replaceVectorCel`, a separate undo step, when it has none); otherwise `assignFresh` with the numbers used in this keyframe and in the neighbouring keyframes taken (so unrelated lines never pair by accident). After `VectorMerge.attach`, `vecRefreshAnchors` redoes the automatic anchors of the merged result. Ghost drawing: before the live stroke, the shown ghost line is drawn with `VectorTessellator` in cyan at 0.28-0.70 opacity (8 steps per 1.6 s cycle, so about 5 redraws a second) with its numbered anchor dots, the other untraced lines at 0.14; only on a visible, unlocked vector layer with the Brush (draw mode) or Line / shapes tool, not while playing.
- `VectorUi.kt`: draw / line bar gets Auto anchors (anchor icon), Ghost (sparkle), previous / next toggle and Skip (only while the ghost is on) and Tune (a panel with the tip sensitivity slider). The Delete and Merge points buttons redo the automatic anchors of the lines they changed.
- `Editor.kt`: `vecAutoAnchor` (default on), `vecTipSens` (0.5), `vecAnchorOpen`, `vecGhost` (default off), `vecGhostNext`, `vecGhostSkip`; session state, not saved.
- Files: `AutoAnchors.kt` (new), `Vector.kt`, `Editor.kt`, `Canvas.kt`, `VectorUi.kt`, `WhatsNew.kt` (note 53), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and bracket balance only (Canvas.kt's paren count of -1 is the same in the original). NOT compiled, not run on a device. Untested: how well tips are found on real strokes (the thresholds are a first guess, expect to tune them); inheritance on closed lines and on lines drawn with fewer points than the ghost has anchors; the ghost on a keyframe that is a clone; undo of the extra "anchor the ghost" step; the shimmer cost on large drawings; old files (no anchors) opened with auto anchors on (only new lines get anchors; use Anchor on the old ones or trace them with the ghost on); auto anchors now exist on every new line, so selection mode shows numbered dots on all of them.

## 2026-11-20 — Clone numbers, keypad for every number (+ − × ÷), system bars

- AI model: Claude
- Request: (1) cloned frames have the same number as the frames they copy on the timeline; (2) all (number) keyboards become the chronometer keypad, with ×, + and ÷ to set the number; (3) tapping a number selects the whole number so the next digit replaces it; (4) several interface buttons bring the Android system bars back: fix.
- (1) `drawingNumber(layer, index)` / `frameLabel(layer, index)` (end of `Editor.kt`): frames are numbered by drawing (identity of `Cel.strokes`): a clone shows the number of the first frame that has its drawing and does not use a number up, so [1, 2, 3, 3', 4] reads 1 2 3 3 4. A non-blank `inbetweenName` still wins. Used by `TimelineCelCell` (was `index + 1`) and by "Set inbetweens" (its base number was the position, now the drawing number). Only the displayed number changed; nothing stored. Decision to check: following frames are renumbered (a clone does not consume a number); the alternative was to leave gaps (1 2 3 3 5).
- (2, 3) New `NumberPad.kt`: `evalExpression` (+ − × ÷, decimals, signs, precedence), `formatNumber`, `NumberPadDialog(title, initial, decimals, min, max, onResult, onDismiss)` and `NumberField(...)`, a tappable outlined box that opens the pad (the Android keyboard is not used). The pad: C, backspace, ÷ ×, − +, ±, decimal point (disabled for whole-number fields), = (collapses the expression), live "= result" line, OK disabled while the expression is incomplete, result clamped to min/max. The edited value opens selected (highlighted): a digit or point replaces it, an operator continues from it; tapping the line selects it again. `NumpadDialog` (frame length, bake step, anchor number) is now a thin wrapper of it (whole numbers, min 1; before: 4 digits, no operators). Converted fields: `NumSlider` number box, `ResRow` (cut width / height / aspect / DPI / fps; the tick button is gone, a value applies on OK to the draft as before), the folder transformation dialog (X, Y, scale, rotation), the perspective lens dialog (FOV, focal length, still linked), `NewCutDialog` number fields (`Field(num = true)`). Name fields (cut, layer, folder names) still use the normal keyboard.
- (4) Cause: a Compose dialog is its own window and does not inherit the activity's hidden bars, so every AlertDialog showed them until it closed; text-field keyboards also bring them back without a focus change. `ImmersiveDialog.kt`: `HideSystemBarsInWindow()` (hides the bars in the dialog's window through `DialogWindowProvider`) and an `AlertDialog` with the Material 3 parameter list that calls it. It lives in the package, so files that star-import Material 3 pick it up with no change; the four files that imported the Material one explicitly (Canvas, Recovery, VectorUi, WhatsNew) lost that import (an explicit import would win). `MainActivity`: `onPostCreate` hides the bars at start; `guardBars()` hides them again when the keyboard finishes animating (`WindowInsetsAnimationCompat`); the existing `onWindowFocusChanged` re-hide stays. Dropdown menus and popups were not changed (the focus re-hide covers them); not verified on a device which buttons were the culprits.
- Files: `NumberPad.kt` (new), `ImmersiveDialog.kt` (new), `Editor.kt`, `Panels.kt`, `Canvas.kt`, `MainActivity.kt`, `VectorUi.kt`, `Recovery.kt`, `WhatsNew.kt` (note 52), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and bracket balance only (Canvas.kt's paren count of -1 is the same in the original). NOT compiled, not run on a device. Untested: the keypad on a phone-sized dialog, expression edge cases (5x-2, 3., repeated operators), whether the bars stay hidden on every Android version and with gesture navigation, popups opened from the timeline, the new package-level `AlertDialog` winning over the Material star imports (a compiler "ambiguity" error here would mean importing explicitly), rounding of FOV / focal length (now stored with one decimal), the renumbering of frames after a clone.

## 2026-11-20 — Icons instead of text: vector widget bar and timeline Selection button

- AI model: Claude
- Request: change the vector widget text buttons and the timeline selection button to icons.
- `VectorUi.kt`: new private `VIconBtn(icon, on, enabled, tint, tip, onClick)` (38 dp; `on` highlights, a disabled button is dimmed and ignores taps, `tint` colours the icon). In `VectorWidgetBar` the text chips / text buttons became icons with tooltips that state the current state: Merge (draw bar) `MergeType`, Points `ScatterPlot`, All layers `Layers`, Lock weight `LineWeight`, Shift `PanTool`, Smoothen `Gesture`, Distort `Transform`, Falloff `Adjust` (highlighted when the falloff is on), Select all `SelectAll`, Deselect `Deselect`, Delete `Delete`, Merge points `Link`, Hide / Show `VisibilityOff` / `Visibility`, Smooth `AutoFixHigh`, Anchor `Anchor` (kept its blue tint). Handlers are unchanged; `ToggleChip` / `Choice` stay (still used by the interpolation, fill, wobble and ink panels).
- `Panels.kt`: the timeline "Selection" / "Selection: ON" text button is `IconBtn(Icons.Filled.HighlightAlt, on = ed.timelineSelectMode, size = buttonSize)`.
- Not changed: the text buttons inside the dialogs and the Wobble / Line weight / Fill panels (Apply, Bake, Done...), and the Interpolate / Hold / curve choices.
- Files: `VectorUi.kt`, `Panels.kt`, `WhatsNew.kt` (note 51), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and bracket balance only. NOT compiled, not run on a device. Icon names come from material-icons-extended (BOM 2024.09.00) and were not compile-checked; if one is missing the compiler will name it. Untested: row width on a phone (15 icons in Points mode), whether the new icons read clearly next to the existing ones.

## 2026-11-20 — Copy / Paste of the selection (drawing, background and vector layers)

- AI model: Claude
- Request: a copy-selection button for background, vector and drawing layers; once copied, a Paste widget button that works on the same layer or frame or any other.
- New `SelectionClipboard.kt`: one session clipboard (`SelectionClipboard.item`, observable; the newest copy replaces the older; not saved in the .inb, no format change). `copy(cut, ed)` picks by the active layer: vector layer -> `copyLines`, drawing (kind 0) or background (kind 4) -> `copyPixels`. `paste(cut, ed)` pastes onto the active layer and frame and returns a message that the buttons show as a Toast (also the reason when the layer cannot take the clipboard: pixels need a drawing / background layer, lines need a vector layer; locked / hidden layer; transform in progress).
- Pixels: `TileSet.copyRuns(runs)` (Tiles.kt) is `liftRuns` without clearing; `null` when nothing is drawn in the selection. The copy is stored as PNG bytes + document position. Paste commits an embedded-image ink (Ink kind 3 with PNG `data` and a translate matrix, the same kind `imageInk` creates and `TileSet.stampImage` / `InkImageCache` already draw), so it is undoable through `cut.commit`, saved and redrawn without new code paths. The pasted pixels become the selection (`Sel` over the image bounds, alpha > 0) so Transform is one tap away.
- Lines: `copyLines` uses `VectorEdit.delete(strokes, allPoints - selected)`, which keeps whole selected lines and only the selected runs of partly selected ones. `pasteLines` appends them to the active layer's cel through `applyScope` (one undo step), keeps a line's id unless it clashes in the target cel (then `newStrokeId()`), bakes live fills (`fill` 2 -> 1) and selects the pasted points (`vecSelCels` / `vecSel`).
- Pasting onto the cel the copy came from shifts it by 24 px (cycling over 8 steps) so it does not sit exactly on the original.
- UI: Copy and Paste icons in `SelectionBar` (raster, next to Transform) and in the first row of `VectorWidgetBar` in selection mode (vector). Paste is highlighted once something is copied. `ClipboardBar` (SelectionClipboard.kt, wired in `Canvas.kt`, bottom right): a Paste widget for drawing / background layers while the Selection tool is on and nothing is selected (the selection bar is not shown then).
- Files: `SelectionClipboard.kt` (new), `Tiles.kt`, `SelectionBar.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt` (note 50), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and bracket balance only (Canvas.kt's paren count of -1 is the same in the original). NOT compiled, not run on a device. Untested: copy from a cel with unbounded tiles outside the cut, pasting across cuts of a different size, pasting onto a linked (shared-strokes) frame, undo / redo of a pasted image, saving and reloading a project with a pasted image, refAll with several vector layers, the Paste widget position next to other bottom-right UI. No keyboard shortcuts were added.

## 2026-11-20 — Interpolation drag: only the selected lines, partners by anchor

- AI model: Claude
- Problem: with lines selected, other lines were re-interpolated too. Causes found in source: `interpolate` paired the leftover lines by geometry (`pairDifferent`) and faded every unpaired line of the first frame in or out; in three-frame mode the selection was resolved against the current playhead scope (`vecSelectedLineIds`, by id), so after moving the playhead (`sameCels` false) it came back empty and meant "all lines".
- `VectorInterpolation.pairSelected(a, b, chosen)`: only the chosen lines of the last frame `b` look for a partner in the first frame `a`: most shared anchor numbers (nearest centroid breaks ties), else the same id. No geometric fallback. `interpolateChosen(a, b, chosen?, mode, t, keepRest)`: chosen lines are blended with their partner, nothing else of `a` is used, nothing fades; `keepRest` returns `b` with those lines replaced in place (stacking order kept; this replaces the older fills-under / lines-over regrouping), otherwise only the blended lines (preview). A chosen line without a partner stays as in `b`. `interpolateSelected` (two frames) delegates to it; empty selection = everything, as before (`interpolate` unchanged).
- Three frames: `vecSelectedLinesIn(ed, b)` resolves the selection for the last selected frame from `ed.vecSelCels` (`VecScope(emptyList(), vecSelCels)`, so it survives playhead moves; same frame by index, other frames by line id). null = nothing selected (all lines); empty = selected but not found in the last frame (nothing interpolated, the bar says so). `interpolateIntoMiddle` / `interpolateIntoFrame` take these indices; middle-frame lines with the id of the partner or of the chosen line are replaced, others stay. Removed `interpolateIds`; `vecSelectedLineIds` is now unused.
- Files: `Vector.kt`, `Canvas.kt`, `VectorUi.kt`, `WhatsNew.kt` (note 49), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and bracket balance only (Canvas.kt's paren count of -1 is the same in the original). NOT compiled, not run on a device. Untested: a selected line whose anchors exist only in the first frame, a selection made on the first frame (matched to the last by id only), refAll with several layers, stacking order of the created frame, the unmatched-selected-line case.

## 2026-11-19 (9) — Merge anywhere along lines, point-bar Merge button, interpolation drag on three frames

- AI model: Claude
- `VectorMerge.attach`: new crossing phase after the T-junction phase: where the body of the new line crosses the body of another line (centre-line segments, interior of both segments), both get a shared vertex (skipped when each already has a vertex within 0.6 px; max 12 per pair). Used by the Brush and Line / shapes tools with Merge on. Inserting a vertex on the chord of a spline line can shift the curve slightly there.
- `VectorMerge.mergePoints` replaces `joinSelected` (removed): the Merge button now lives in the point selection bar (Points on; the line-mode button is gone) and works on selected points: selected pairs merge at any distance (end+end join, same line's two ends close, end + inner point = the end moves onto that point, inner + inner meet at the midpoint); a point without a selected partner merges with the nearest other line within the Merge radius (end onto an end = join, else onto the body with an inserted vertex); a line with all points selected only offers its two ends and only within the radius. Plan-then-apply: moves, then insertions (per line, descending), then joins (the older line's identity and look win, `joinSharp` stamped), then closes. The Merge panel (Radius / Sharp joins) shows in that bar. `ed.vecDocZoom` is now refreshed on every composition (`vecZoom()` call in `Canvas.kt`); before, it was only written while drawing, so the old button could use a stale scale (my best guess for "doesn't work"; not verified).
- `VectorGaps`: a crossing with a vertex within 0.6 px on both lines, and an end sitting on a body vertex of the other line, are merged and no longer shimmer; ends meeting ends of separate lines still do.
- Interpolation drag, three frames: `vecSelectedFrames(layer, ed)` (ed.selCels on the layer, timeline order). Exactly 3: first and last are the references (`a.vecCfg` mode), `interpolateIntoFrame` replaces the middle frame with a fresh unique cel (no new frame; a clone of another frame is not changed along; `ed.selCels` / `vecSelCels` repointed, a selection on the old middle frame is cleared). Selected lines (`vecSelectedLineIds`, by stroke id) limit it to those lines; the other lines of the middle frame stay. Preview only while the playhead is on the middle frame. 2 or >=4 selected: the bar asks for three and the check does nothing; 0 or 1: the previous behaviour (including the selected-lines rule from (8)).
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt` (note 48), docs. Protected files untouched.
- Validation: source inspection and bracket balance only. NOT compiled, not run on a device. Untested: crossing merge on dense scribbles and closed lines, `mergePoints` with mixed whole-line and single-point selections and All layers, the three-frame mode with clones / refAll / frames of different durations, how a stale frame selection (left over from copy / link) now switches the drag tool into three-frame or "select three" mode.

## 2026-11-19 (8) — Gap shimmer, hidden points, interpolation drag on the selection, numbered anchors

- AI model: Claude
- Shimmer: `VectorGaps.find(strokes, reach)` (`Vector.kt`) returns the places where the centre lines of two different open / closed lines cross, and ends of open lines within `reach` (Merge radius / zoom, at least 2 px) of another line; clustered (0.8 x reach), max 160, skipped above 5000 points. `Canvas.kt` computes it (`vecGaps`, remembered on the overlay strokes) only for the selection tool and not during a drag (`vecLives != null`). `VecGapShimmer` (`VectorUi.kt`) is a separate full-size Canvas layer (zIndex 3, no touch handling) with an infinite transition, so only it redraws.
- Hidden points: `VectorPoint.hidden`; `halfWidth` returns 0 for a hidden point (the ribbon tapers to nothing). Stored in the sparse anchor pairs of ink `m` as rank + 128 (`toInk` / `toVectorStroke`), so no format bump; `Store` already writes the rank byte 0..255. Older builds read rank >= 128 as anchor 12. `mid()` hides only when both ends are hidden; `lerp` takes the nearer point's flag. Selection bar, Points on: Hide / Show button (Show when every selected point is hidden), one undo step via `applyScope`. The overlay draws hidden points as hollow rings.
- Interpolation drag on the selection: `VectorInterpolation.interpolateSelected(a, b, sel, mode, t, keepRest)`; `sel` = indices of lines of the dragged-to keyframe with a selected point (`vecSelectedLineIndices`, ignores a selection made on other cels). Only those lines are interpolated against the previous keyframe's lines that are not the same line as an unselected one; unselected lines of the current keyframe are kept in the created frame (fills under, then the interpolated lines, then the other lines: their stacking order among lines can change) and left out of the live preview. Empty selection = everything as before. The bar shows the count. `createDraggedInbetween` has a `sel` parameter.
- Anchors by number: `ANCHOR_MAX` 99; `anchorColor(rank, count)` blends blue, red, yellow over the numbers of a line (count = highest number on that line; 3 anchors give the three primaries, 2 blue and red). The point overlay writes the number outside the point (up-right) in that colour with a dark outline. The anchor dialog now has "Number in order" and "Set a number" (numpad) instead of the 12 colour buttons. Pairing is unchanged (`anchor` ints), so existing files pair exactly as before. `ANCHOR_COLORS` / `ANCHOR_NAMES` remain but are unused.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt` (note 47), docs. Protected files untouched.
- Validation: source inspection and bracket balance only. NOT compiled, not run on a device. Untested: shimmer cost with many lines and how many places flag (every crossing counts, including intentional ones), hidden points next to caps / fillets / ink weight / wobble, anchors above 12 in interpolation, selection plus interpolation drag with All layers, stacking order of the created frame.

## 2026-11-19 (7) — Vector fill reaches small corners; CHANGELOG restored

- AI model: Claude
- Cause (from reading the code, not measured): a fill's outline was (1) smoothed with two [1 2 1]/4 passes on the traced ring, which pulls every sharp tip inward, and (2) drawn through `VectorTessellator.smoothThrough`, whose fillet rounds any turn between about 55 and 170 degrees with a radius up to 45% of the neighbouring segments, so narrow tips were cut off.
- Fix: `maskToFillPoints` no longer smooths the ring (RDP at 1 px takes the staircase out and keeps the tips); `fillPath` calls `smoothThrough(..., sharp = 1f)` (no fillet, bends over ~30 degrees stay corners; gentle curves are still smoothed). Existing fills are drawn with the sharp corners at once; live fills get the new outline on Refill.
- Not changed: the flood itself (gap closing, Grow, tolerance).
- Selection transform lag (reported together): NOT fixed. Nothing changed this session in the transform path (`VecLiveCel`, `vecThrottled`, `TileSet.liveVector`); the only touch is `vecZoom()` also writing the plain field `ed.vecDocZoom`. Possible costs found by reading, unmeasured: per-tile `BlurMaskFilter` / texture layer in `VectorTessellator.render` for soft, textured or aaSoft lines; large fills in the moving set. Waiting for which transform and what is selected.
- Process note: the CHANGELOG was truncated by my edit in (6) (file opened for writing before it was read) and rebuilt from the uploaded v47 file plus entries (4), (5), (6); the wording of (4) and (5) was re-created from my notes, not byte-identical.
- Files: `VectorFill.kt`, `Vector.kt`, `WhatsNew.kt` (note 46), docs. Validation: source inspection only; not compiled, not run.

## 2026-11-19 (6) — Build fix: `vecZoom` declared before `commitShape`

- AI model: Claude
- Build log (run 187): `Canvas.kt:652 Unresolved reference 'vecZoom'` — `commitShape` (Line / shapes Merge) called the local function `vecZoom()`, which was declared about 150 lines later in the same composable. Moved the one-line declaration above `commitShape`; no other change.
- Validation: log read only; the only compile error in the log was this one (Kotlin stops at the first failing task, so later errors could still exist). NOT recompiled.

## 2026-11-19 (5) — Merge sharpness, Merge in the Line and selection tools, eraser across layers, Duplicate layer keeps clones, Copy frame copies the selection

- AI model: Claude
- Merge rounding: new `VectorStroke.joinSharp` (0..1, default 0 = unchanged look), stored as style-string field 29 (appended; `hasStyle()` includes it; `styleFrom` copies it). `VectorTessellator.smoothThrough/fillet` take `sharp`: the fillet radius is scaled by `1 - sharp` (skipped above 0.95) and a bend counts as a spline corner when cos < `REVERSAL_COS + (0.85 - REVERSAL_COS) * sharp`. Only the stroke ribbon passes it. `VectorMerge.attach(..., sharp)` stamps `max(joinSharp, sharp)` only when a line joined an end or closed a ring. `Editor.vecMergeSharp` default 0.7.
- Merge in the Line tool: Line / shapes (`ed.tool == 4`) on a vector layer shows the Merge chip + Radius / Sharp joins panel in `VectorWidgetBar` (`lineBar`); `commitShape` runs `VectorMerge.attach(..., ed.vecMergeSharp)` for open shapes when `ed.vecMerge` is on. The Brush tool keeps its chip and panel. The selection tool (Points off) has a "Merge" button: `VectorMerge.joinSelected` on the selected non-fill lines per cel, radius `ed.vecMergeRadius / ed.vecDocZoom` (written by `vecZoom()`), one undo via `applyScope`.
- Eraser: `vecEraseUp` erases on all visible, unlocked vector layers with a cel at the time when `ed.refAll` is on, else on the layer as before; one `cut.record` with `swapVectorCel`.
- `Cut.duplicateLayer(keep = true)`: cels sharing a drawing become `cloneLinked()` of the first copy in the new layer. "Duplicate but empty" unchanged.
- `Cut.duplicateFrame(selected)`: copies the selected cels (or all after Select all, else the current one) as regular copies after the last selected one; the Copy frame button and DUP_FRAME shortcut pass `ed.selCels`.
- Format: no version bump; field 29 is read with a default, older builds ignore it.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `Editor.kt`, `Panels.kt`, `Keys.kt`, `WhatsNew.kt` (note 45), docs. Validation: source inspection only; the first build of this failed (see (6)).

## 2026-11-19 (4) — Line weight bar: Irregular up to 300%, Irreg. scale down to 0.5 px, bottom-left at 75% size

- AI model: Claude
- Irregular: slider range 0..3 (0-300%); `VectorStroke.inkIrr` is now clamped 0..3 on read (was 0..1). The width factor `1 + inkIrr * 0.75 * noise` is unchanged, so beyond about 130% the thin parts hit the 0.08 floor and the thick parts the `INK_MAX` cap in `inkWeight`.
- Irreg. scale: slider range 0.5..200 px (square-root mapping, 0.1 px steps, one decimal shown below 10 px); `inkIrrScale` clamped 0.5..400 on read and in `VectorTessellator.inkWeight` (was 2..400; the slider itself stopped at 4 px). Strokes saved with the old limits render identically.
- Bar: `VectorInkBar` is anchored bottom-left (`Alignment.BottomStart`, 14 dp padding, `Canvas.kt`) and drawn at 75% with a `graphicsLayer` scale around its bottom-left corner (layout size unchanged). The Wobble bar stays top centre.
- No format bump. Older builds reading a file with Irregular above 100% clamp it to 100%.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt` (note 44), docs. Validation: source inspection only; not compiled or run on a device.

## 2026-11-19 (3) — Line weight: irregular weight, anti-aliasing slider, light dial, live preview

- AI model: Claude
- `VectorStroke` gains `inkIrr` (0..1), `inkIrrScale` (px, default 40) and `aaSoft` (px extra edge softness); kind-5 data text fields 26-28 (after the 21-25 of the previous entry), written whenever `hasStyle()`; older strokes read as 0 / 40 / 0. No format bump. `styleFrom` carries them.
- Irregular weight: `VectorTessellator.inkWeight` multiplies the half width by `1 + inkIrr * 0.75 * noise` (two octaves of value noise, `vnoise` / `lattice`), measured along the line in px / `inkIrrScale` and seeded by `id.hashCode()`, independent of curvature. Closed lines use a periodic lattice (no seam). `hasInk()` includes it.
- Anti-aliasing: the existing per-stroke `aa` (off above 0.02) plus `aaSoft`, applied in `VectorTessellator.render` as a `BlurMaskFilter` shared with the hardness feather (the larger radius wins); `bounds` grows by 3 x `aaSoft`. The bar's slider (0-0.17 = Off, up to 0.5 = Standard, above = feather 0-3 px) is only written to the lines once it has been moved (`InkParams.aa == null` otherwise).
- `InkParams` (all bar settings, `applyTo`) replaces the long argument list of `setVectorInk`.
- Preview: `VectorInkPreview` (Vector.kt) holds the bar's params and target keyframes; `Layer.drawCel` passes the cel through `VectorInkPreview.wrap`, which returns a cached transient cel (same snapshot / main-handler rules as `VectorLive`) built from `InkParams.applyTo`. Main thread only (exports and background renders get the real cel). Cleared by the bar's effect on close / Preview off / playback and by `DisposableEffect`. Not previewed: in-betweens produced by live interpolation (they are not targets).
- UI: `LightDial` (circle with ticks, line and arrow, tap or drag, 15 degree snap) replaces the Light from slider; Preview toggle; the bar scrolls (max 420 dp).
- Files: `Vector.kt`, `VectorUi.kt`, `Editor.kt`, `WhatsNew.kt` (note 43), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection, brace balance, diff review. NOT compiled, not run. Untested: Preview speed on dense frames and redraw while dragging sliders, preview of the active layer next to the GPU plane, undo after Apply with Preview on, how Anti-alias Off / feather look on tiles, the dial gestures with S Pen and finger, the bar's size on the tablet, irregular weight on very short or closed lines.

## 2026-11-19 (2) — Line weight for vector lines (curvature, light direction, tapers)

- AI model: Claude
- `VectorStroke` gains `inkCurve`, `inkLight`, `inkAngle` (default 315, the direction the light comes from, screen coordinates), `taperIn`, `taperOut` and `hasInk()`; stored as extra `|` fields 21-25 of the kind-5 Ink data text (written whenever `hasStyle()`, which now includes `hasInk()`); older strokes read as zeros. No format bump. `styleFrom` carries them, so live and baked in-betweens get the same weight.
- `VectorTessellator.inkWeight` (called from `ribbon` after the smooth curve and wobble, before the tip factor; not for fills) multiplies each half width by: a curvature term (smoothed turn per px, radius 30 px or tighter = full), a light term (smoothed dot of the outward normal, away from the centre of the line's points, with the light vector) and smoothstep tapers over taperIn / taperOut of the line length (open lines only, floor 12 %). Smoothing is `[1 2 1]` passes sized to about 8 px. Total factor clamped 0.08-2.5; `bounds` grows by `INK_MAX`.
- `setVectorInk` (one undo step, via `swapVectorCel`) and the `VectorInkBar` (top centre, `Editor.vecInkOpen`, button in `VectorToolStrip`; opening it closes the Wobble bar and the reverse): sliders Curvature, Light, Light from (15 degree steps), Taper in / out; Apply (selected keyframes or current), All frames, Off. All frames is one synchronous undo step (it only rewrites strokes and rebuilds tiles).
- Files: `Vector.kt`, `VectorUi.kt`, `Editor.kt`, `Canvas.kt`, `WhatsNew.kt` (note 42), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection, brace balance, diff review. NOT compiled, not run, not rendered. Untested: how the weights look at several zoom levels and on dense lines, the outward-normal rule on open lines that wrap around, flicker of curvature / light weight between interpolated frames, speed on dense layers, All frames on long layers, undo, thumbnails and exports, files with the new fields opened by older builds.
- Not built: thicker outer side of a bend (the ribbon is symmetric), a live preview before Apply, a Cut-wide light angle (the angle is stored per line from the last Apply), presets, silhouette-vs-detail weight.

## 2026-11-19 — Clone after Select all, non-blocking Refill, interpolation drag encroaches on the previous frame

- AI model: Claude
- Clone: `Cut.cloneFrames` (`Editor.kt`) fell back to the single frame at the playhead when `ed.selCels` was empty, so the three-dot "Select all" (`Cut.selAll == layer.id`, no `selCels`) cloned one frame. It now clones every cel of the layer in that case, as `emptyFrames` and the duration pad already did.
- Refill / All frames: the ANR came from `refillLiveFills` running every flood fill (`resolveFillPoints`) in one synchronous loop on the main thread. It is now a `suspend` function that resolves one fill at a time with `yield()` between them (the cut is still only read and mutated on the main thread), then swaps all new outlines in one short `cut.record` (one undo step, as before). Cels that were replaced or removed while it ran are skipped. `VectorFillBar` launches it from a coroutine, disables both buttons while it runs and shows a percentage. The swap itself (`swapVectorCel` builds tiles) is still synchronous, so a very large layer can still hitch briefly at the end.
- Interpolation drag: `createDraggedInbetween` now takes the new frame's time from the previous keyframe whenever that lasts more than one frame (`p.dur -= 1`), so the current keyframe and everything after it stay put, with or without the retime toggle. Only when the previous keyframe is a single frame does the earlier rule apply (toggle on and current longer than one frame: take from the current; otherwise push later). Button tooltip updated.
- Files: `Editor.kt`, `Vector.kt`, `VectorFill.kt`, `VectorUi.kt`, `WhatsNew.kt` (note 41), `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: source inspection and diff review only. NOT compiled, not run on a device. Untested: Refill progress / responsiveness on a long layer, Refill while the user edits, undo after Refill, clone after Select all with linked / persistent cels, dragged in-between timing at the previous frame's end and with a one-frame previous keyframe.

## 2026-11-18 (8) — Vector Fill tool (baked + live, across layers) and lighter vector transforms

- AI model: Claude
- Fill: `VectorStroke.fill` (0 line, 1 baked fill, 2 live fill) plus `fillGap`, `fillGrow`, `fillAcross`, `fillSeedX/Y`; stored as extra `|` fields after `tipFollow` in the kind-5 data text (`hasStyle()` is true for fills). No format bump. `VectorTessellator.fillPath` draws the smooth closed curve through the points, filled (even-odd); `path()` routes fills there, `bounds` gets +8 px, `interpolate` keeps fills (`styleFrom` copies the fill fields; the width filter lets them through), `VectorEdit.strokeAt` selects a fill by tapping inside it.
- Tool: the Fill tool is allowed on vector layers (`Canvas.kt`); the existing raster flow (flood map, `smartFlood` with auto gap, Grow, drag for gap, preview) is reused. Only the pixel source (`vecFillPixels`: the layer's lines, or every visible drawing layer's lines / painted pixels with "Across layers", drawn black on white, fills excluded) and the commit differ: `maskToFillPoints` traces the region outline (`traceMaskOutline`, pixel-edge following), smooths the staircase and simplifies it (RDP 1 px), `addVectorFill` inserts the fill after the last older fill (so fills are under all lines; later fills above earlier ones) as one undo step, or recolours an existing fill of the same area (`VectorEdit.sameRegion`) when tapped inside it.
- Live fills: `refillLiveFills` / `resolveFillPoints` redo the flood from the stored seed / gap / grow / across against the keyframe's current lines (tick = the key's start frame), one undo step; buttons Refill (selected keyframes or current) and All frames in the new `VectorFillBar` (Live fill, Across layers). Not automatic after edits (undo bookkeeping of pen strokes depends on the stroke list), not recomputed for live-interpolated in-betweens (they morph like baked fills); Refill runs synchronously on the main thread.
- Transform lag, cause: every pen move cleared and re-encoded every stroke of the cel (`toInk`) and rebuilt every tile of the cel on the UI thread. Fix: `VectorEdit.move / elevate / transform` return the same stroke instance when nothing of it changes; new `VecLiveCel` (`Vector.kt`) keeps a base `TileSet` of the strokes that never move and per change resets only the tiles the moving strokes touch (old and new place) to the base and draws the moving strokes there (`TileSet.liveVector`, `TileSet.stampStroke`, no encode / decode); only changed strokes are re-encoded into `cel.strokes`; the set is pinned meanwhile; `finish` rebuilds the cel once only when a moved stroke was below a static one. `Canvas.kt`: `vecLiveCels`, `vecThrottled` (one applied edit per 14 ms for the point drag and the transform handles, the newest skipped one is applied on release), `vecFinishLive` at every drag end. Falls back to the old full path when the stroke count changes.
- Build fix (run of 2026-11-18, log `logs_120223789416`): the first build failed with "Unresolved reference" errors in `Canvas.kt`, `VectorUi.kt` and all of `VectorFill.kt` because the new file was declared `package com.exemple.inbetween` (the folder name) instead of `com.example.inbetween`. Corrected in `VectorFill.kt`. No other error was in the log.
- Files: `Vector.kt`, `Tiles.kt`, `Canvas.kt`, `Editor.kt`, `VectorUi.kt`, `VectorFill.kt` (new), `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: source inspection, brace / paren balance. NOT compiled, not run, nothing measured. Untested: fill look and speed at full size, the outline trace on odd shapes and very large areas, islands (covered, not cut out), Refill on many keyframes, drag on a dense cel (z-order fix at release, point brushes touching new strokes rebuild the base), undo / redo after a drag and after a fill, fills and wobble / hardness together, exports.

## 2026-11-18 (7) — Brush look on vector lines: hardness, texture, tip shape / roundness / angle / follow

- AI model: Claude
- `VectorStroke` gains `hardness`, `texId`, `texAmt`, `texScale`, `texInvert`, `texImg`, `tipShape`, `roundness`, `tipAngle`, `tipFollow` (defaults = the plain line), `hasStyle()`, `tipActive()`, `withBrush(Brush)` (used by `vecCommitStroke`, `commitShape` and the live stroke in `Canvas.kt`) and `styleFrom` (the two interpolation constructors carry the look of stroke `a`; wobble is still not carried). Stored in the kind-5 Ink data text as extra `|` fields after the wobble ones (`id|brush|aa|wobble|scale|seed|hardness|texId|texAmt|texScale|texInvert|texImg|tipShape|roundness|tipAngle|tipFollow`), written in full only when `hasStyle()`; older strokes and older shorter texts read as the defaults. No format bump. A build before this change reading such a file would take the whole text after the first `|` as the brush id (unused for rendering).
- Tip shape: `VectorTessellator.tipFactor` scales each ribbon half width by the tip's extent across the line (ellipse for circle / etched, triangle, rectangle; axis = `tipAngle`, plus the line direction when `tipFollow`; triangle takes the mean of both sides). Caps stay round. Applied in `ribbon`, so path, strip, centerline-based hit areas that use `ribbon`, and bounds stay consistent.
- Edge softness and texture: new `VectorTessellator.render` (used by `TileSet.stampVector`, a software canvas): hardness < 1 sets a `BlurMaskFilter` of (1 - hardness) x the widest half width; a texture draws the line in a `saveLayer` and multiplies it with the brush's tile (`Textures.tile` / `BrushAssets.textureTile`, `texScale`, page-anchored) using DST_IN. `bounds` grows by 2.5 x that feather. The live preview (`draw`, hardware canvas) shows the tip shape but neither the blur nor the texture. Not used: Grain slider (`Brush.texture`), `texMoving`, Etched tip shape, dab spacing / jitter / wet / flow / capped.
- Files: `Vector.kt`, `Tiles.kt`, `Canvas.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: source inspection, brace / paren balance. NOT compiled, not run, not rendered. Untested: soft edge on thin lines (can look faint), speed of blur and saveLayer on dense drawings, texture alignment across tiles, flat nibs at sharp turns and closed shapes, undo / reopen of styled lines, exports and thumbnails.

## 2026-11-18 (6) — Per-keyframe vector wobble, Interpolation settings button restored

- AI model: Claude
- Interpolation button: the settings dialog (lines, curve, bake) was only reachable from the Interpolation-drag bar after the 2026-11-18 (3) merge. `VectorToolStrip` now has the LinearScale button again (sets `ed.vecInterpOpen`). The dialog moved out of `VectorWidgetBar` (which returns early for other vector modes) into `VectorInterpolationHost`, composed in `DrawingCanvas`, so it opens with Cutter or any other tool. The drag bar's own button still works.
- Wobble: `VectorStroke.wobble` (px), `wobbleScale` (px) and `wobbleSeed`. Stored in the kind-5 Ink data text (`id|brush|aa|wobble|scale|seed`, only when wobble > 0), so no format bump; older strokes read as 0. `VectorTessellator.ribbon` applies it after `smoothThrough`: `wobbleDense` cuts the curve into pieces under 0.4 x scale and pushes each point by periodic-for-closed value noise (separate noise for x and y). `bounds` grows by 1.5 x wobble. Points, hit-testing, selection, anchors and interpolation use the clean data.
- Per keyframe: `setVectorWobble` rewrites the strokes of the target cels through `swapVectorCel` inside one `cut.record` (undo works, tiles rebuild); each keyframe gets its own seed (kept unless "New look"), each stroke `seed + index x 7919`. Bar: `VectorWobbleBar` (top centre), targets = selected timeline cels else the current keyframe. Live-interpolated in-betweens are built by the interpolation constructors and therefore carry no wobble (decision: each frame is set on its own); a held keyframe keeps one wobble for its whole hold. Onion skin shows the wobbled line.
- Follow-up (user screenshot: the minimum was still too strong): Wobble sliders now reach 0.05 px amount and 2 px scale and are quadratic (fine steps near the minimum); `wobbleDense` and the data reader clamp scale at 2 px (was 4). At 2 px scale the curve is cut into 0.8 px pieces (cap 20000 pieces per line, then no wobble).
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `Editor.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: source inspection, brace / paren balance. NOT compiled, not run, wobble not rendered. Untested: look and speed of wobble on dense scribbles (extra points per piece), closed shapes seam, a keyframe whose interpolation hold starts wobbled then continues unwobbled (pop at the second frame), selection of cels after Apply, thumbnails / exports of wobbled lines. Not built: a timeline dot marking wobbled keyframes, a wobble chip on the drag Check button.

## 2026-11-18 (5) — Rounded sharp turns, point-preserving vector brushes, selection widgets on vector layers

- AI model: Claude
- Report (screenshot): scribbled vector lines with sharp turns looked blocky / chamfered. Found in source: `smoothThrough` kept every turn over ~55 degrees as a hard corner, and `ribbon` bevelled the outer side of sharp joins, so a reversal ended in a flat cut.
- `Vector.kt` `VectorTessellator`: new `fillet` pass at the start of `smoothThrough` rounds each turn between ~55 and ~170 degrees with a circular arc (centreline radius max(1.2 px, 1.6 x half width), tangent distance at most 45% of the neighbouring segments, 18-degree steps). Only turns beyond ~170 degrees remain corners (`REVERSAL_COS`). `ribbon` now emits a round join (arc of up to 8 steps on the outer side) instead of a bevel; its output arrays grow on demand. `centerline` / `VectorSimplify.fit` see the same curve. Side effect: shape-tool corners (rectangles) are rounded slightly. New `VectorTessellator.curveAt` (point on the drawn curve).
- `VectorBrush` (Shift / Smoothen / Distort): `densify` (which added points along every segment near the brush) is replaced by `ensureHandle`: existing points are moved; one point is inserted, on the drawn curve nearest to the brush centre, only when no control point lies inside the radius. `apply` signature unchanged.
- `VectorUi.kt`: with the Selection tool, `VectorWidgetBar` gets a top row with the regular selection widgets: Add, Erase (`ed.selOp`, used by `selOpOf`), Invert (all points minus the selection), aspect lock (`ed.xfLock`), rotation magnet (`MagnetBtn`) and Done (clears `vecSel`). `Canvas.kt` `vecXfMove` now applies `ed.rotationSnap` (absolute 30 degrees) to vector selection rotation. Fill and Transform from the raster `SelectionBar` are not shown: the vector handles are always active.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. No format change. Protected files untouched.
- Validation: source inspection, brace / paren balance. NOT compiled, not run on a device, and the new look was not rendered. Untested: how round the scribble turns look at your line widths (radius constants may need tuning), cost of the extra points on dense scribbles, closed shapes, round-join winding in the even-odd ring, the brushes on sparse lines, Invert, rotation magnet.

## 2026-11-18 (4) — Vector: Lock weight, Set / Clear anchor shortcuts, brush anti-aliasing

- AI model: Claude
- Lock weight: `Editor.vecLockWeight` (session state) and a `ToggleChip` "Lock weight" in the Selection-tool bar of `VectorWidgetBar`. `VectorEdit.transform` has a new `lockWeight` parameter (default false); when on, neither `baseWidth` nor `elevationOffset` is scaled. Only caller changed: `vecXfMove` in `Canvas.kt`.
- Shortcuts: `KeyAction.SET_ANCHOR` (default A) calls `autoAnchor` and `KeyAction.CLEAR_ANCHOR` (default Shift+A) calls the new `clearAnchors` (`VectorUi.kt`, same effect as "Clear anchor" in the dialog). Both act only on a vector layer with the Selection tool and a non-empty point selection. A had no binding before (Ctrl+A is Select frames). When every selected point already has an anchor, Set does nothing (the button opens the colour dialog; the shortcut does not).
- Anti-aliasing: `VectorStroke.aa` (default 1). New strokes take `Brush.aa` (`vecCommitStroke`, `commitShape`, live preview). `VectorTessellator.draw` / `drawPath` set `isAntiAlias = aa > 0.02` (same threshold as the raster engine). No format change: `aa` is appended to the Ink data text (`id|brushId|aa`) only when below 1, so strokes without it read as 1 and the V1-V26 reader is unchanged. An older build reading such a file would take "brushId|aa" as the brush id, which is unused for rendering. The raster brush's extra edge softness (`aaBlur`) is NOT applied to vectors; interpolated strokes take the aa of the first stroke.
- Files: `Vector.kt`, `VectorUi.kt`, `Keys.kt`, `Canvas.kt`, `Editor.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: source inspection and a full diff review only. NOT compiled, not run on a device. Untested: shortcuts with a hardware keyboard, Lock weight during gizmo scale / rotate, an aliased brush on a vector layer (aa 0) and on thumbnails / export, reopening a saved cut.

## 2026-11-18 (3) — Vector tools merged into Brush / Selection, Merge radius, self-merge

- AI model: Claude
- Timeline strip (`VectorToolStrip`): the Vector draw and Point edit buttons (and the hold-for-across-layers dialog) are removed; only Interpolation drag and Cutter remain, and each toggles back to drawing (`vecMode` 0) when tapped again. `Editor.tool` now has a setter: choosing the Brush tool from another tool resets `vecMode` to 0. `vecMode` 1 is no longer set anywhere (its branch in `penDown` is removed).
- Brush tool on a vector layer = vector draw (unchanged code path, `vecMode` 0). Selection tool on a vector layer: new `Editor.vecPoints` (default true). Points on routes pen down to `vecEditDown` / `vecBrushDown` (the old Point edit: tap / lasso points, drag, brushes, Raise, Falloff); off keeps `vecSelectDown` (whole lines, gizmo). The point overlay and brush circle follow the Selection tool (`vecLayerNow`, hover circle).
- `VectorWidgetBar` is still its own bar at the bottom centre, not part of the regular tool options: Brush tool -> Merge chip (+ Radius slider panel when on); Selection tool -> Points chip, All layers chip (`ed.refAll`, replaces the removed hold dialog), brushes / Raise / Falloff (Points on only), Select all, Deselect, Delete, Smooth, Anchor. Interpolation drag keeps its bar.
- Merge radius: `Editor.vecMergeRadius` (default 14 screen px, slider 4..80), used by the live rings and the commit (`VectorMerge.REACH_PX` removed). Reach per line is max(radius, half its width) as before.
- Self-merge: `VectorMerge.attach` closes a line into a ring when its last point is within the reach of its first, even if it joined nothing else, provided it has at least 4 points and is longer than 4 x the reach (a scribble or dot does not close). The live ring appears at the line's own start under the same conditions (`vecMergeLen`).
- Files: `Editor.kt`, `VectorUi.kt`, `Canvas.kt`, `Vector.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. No format change. Protected files untouched.
- Validation: source inspection, brace / paren balance check, grep that no stale `vecMode == 1` / `REACH_PX` / `layersOpen` references remain. NOT compiled, not run on a device. Untested: switching tools while in Cutter / Interpolation drag, the Selection tool's own option panel being shown in Points mode (it still shows its lasso / wand choices, which only apply with Points off), self-merge feel, ring placement.

## 2026-11-18 (2) — Vector Merge: visible toggle, live join rings, one reach

- AI model: Claude
- Report: the Merge button sat inside the bar as a plain text button whose only on state was a coloured "● " prefix (it looked like it did not toggle), and the conditions for a join were invisible and inconsistent. Found in source: the reach was `max(8 screen px, half brush size)` (small for a pen with stabilizer lag) and nothing showed whether an end was in reach.
- `VectorUi.kt`: `ToggleChip` (filled primary "Merge: On" / grey "Merge: Off") replaces `Choice` for Merge; the hint under the bar states the rule.
- `Canvas.kt`: while drawing with Merge on, rings mark where the start and the current end would join (`vecMergeStart`, `vecMergeEnd`, drawn with the live stroke); the lines of the shown keyframe are read once at pen down (`vecMergeLines`). The commit uses the same reach (`VectorMerge.REACH_PX` = 14 screen px, or half the brush size if larger).
- `Vector.kt`: `VectorMerge.snapTarget` follows the same rules as `attach` (nearest free end first, else a point on a line's body not at its very ends), so the ring and the result agree. `attach` is unchanged.
- Not changed: lines that merely cross are not joined; only the shown keyframe of the active layer is considered; a line whose end returns to its own start is not auto-closed unless it also joined something.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. No format change. Protected files untouched.
- Validation: source inspection, brace / paren balance check. NOT compiled, not run on a device. Untested: ring placement with zoom / rotation, whether 14 px feels right, cost of the per-point `snapTarget` on dense keyframes.

## 2026-11-18 — Warmer vector curves, vector flicker, Uniform timing, Duplicate Empty Vector, Smooth on selected points

- AI model: Claude
- Vector curves (`Vector.kt`): `VectorTessellator.ribbon` now builds the outline around a centripetal Catmull-Rom curve through the control points (`smoothThrough`, `crAt`) instead of straight chords. Every control point (and the hand's wobble) is kept; pieces per span follow how far the curve bows from its chord (flatness 0.12 px, at most 16 per span), straight spans get none; a turn sharper than about 55 degrees stays a corner; width is interpolated linearly. Nothing new is stored, so existing vector strokes also render smoother. `bounds()` margin grew by 1 px for overshoot. `TileSet.stampVector` tessellates once per stroke (`VectorTessellator.drawPath`) instead of once per tile.
- Vector capture (`VectorSimplify.fit`, `Canvas.kt` `vecCommitStroke` and `commitShape`): points are fitted to the curve they are drawn along. A coarse RDP pass (1.8 x epsilon), then a point is added only in a span where the drawn curve is farther than epsilon from the pen samples (at most 6 rounds). `rdp` is unchanged apart from sharing `rdpKeep`. Python prototype of the same math: a 90-degree arc of 200 samples gave 15 points against 17 for plain RDP, with the same maximum deviation.
- Live vector stroke (`Canvas.kt`): drawn with `VectorTessellator.draw` from `vecPts` (same width / curve / opacity as the committed line) instead of a 85 % alpha uniform polyline; `vecAdd` no longer copies the whole point list per point (`vecLive` counter).
- Flicker, found in source (not reproduced on a device): the canvas draws a cel only when `TileCache.getForDraw` has its tile set, otherwise the layer is skipped until a background rebuild finishes. (1) `swapVectorCel` (Merge, Smooth, point-edit end, eraser, anchors...) put a brand-new `Cel` on the timeline with no tile set: it now rasterizes it first (`TileCache.get`). (2) `VectorLive.celAt` handed out a new transient cel before its tiles existed, and returned the held key while a stale entry was being rebuilt (the canvas jumped to the key pose after each stroke on an interpolated frame): in a read-only snapshot it now returns the cel only once `TileCache.peekReady`, requests the tiles (`OnionWarmup.request`, urgent) and meanwhile shows the previous transient cel (`Entry.prev`, one generation) or the held key; non-draw callers (export) still get the exact cel.
- Uniform timing (`Editor.kt`, `Panels.kt`, `Store.kt`): `Layer.uniformTiming` (folder) and `Layer.uniformAttached` (layer), both in `LSnap` so undo restores them. Folder three-dot "Uniform timing" (`Cut.setUniformTiming`, also on plain folders, which had no three-dot): members take the timing of the member with the most cels (ties: longest) and are attached; layers added later start detached. Layer three-dot "Detach timing" / "Attach timing" (`detachTiming`, `attachTiming`; attach copies the other attached layers' timing). Dynamic: `pushStruct` calls `syncUniformTiming(before)`; the attached layer whose cels or durations differ from the snapshot is the source, and the others are rebuilt by cel identity (removed frame removed at the same position, new frame inserted there as an empty frame, moved frame moved, retimed frame retimed), in the same undo step. A layer that leaves its folder, or whose folder is removed or switched off, is detached. A frame created by drawing past the end (`ensureCelUniform`, used by the drawing tools in `Canvas.kt`) is added to the others (not undoable, like before). Deleting a frame also deletes the frame at that position in the other attached layers.
- Format: a cut that uses Uniform timing is written as V26 (flags after the stamp assets); all other cuts are still written as V25, so they stay readable by V25 builds. V26 files are not readable by older builds.
- Duplicate Empty Vector (`Cut.duplicateEmptyVector`, three-dot of drawing layers, both menus): a new empty vector layer with the same cel count and durations, same folder, opacity and visibility; named like other vector layers; not attached to Uniform timing.
- Smooth (`VectorSmooth.refineMapped`, `VectorSmoothDialog`): points are only added on segments whose two end points are both selected (an unselected part of a line is left alone); after applying, the selection stays on the originally selected points plus the added ones. `refine(s, strength)` still works on the whole line.
- Files: `Vector.kt`, `Canvas.kt`, `Tiles.kt`, `Editor.kt`, `Panels.kt`, `VectorUi.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched (workflows, Gradle, gradle.properties, manifest, signing).
- Validation: source inspection of all call sites, brace / paren / bracket balance check, Python prototype of the spline and fit math. NOT compiled (no Kotlin compiler or Android SDK here), not run on a device. Untested: whether the flicker is gone, how the smoothed ribbon looks and costs on dense drawings, corner threshold feel, every timeline operation under Uniform timing (drag, split, delete, Remove all), V26 save / load, Smooth selection after apply.
- Known follow-up: hit tests (eraser crossings, Merge junctions, Cutter) still use the control polyline, so they differ from the drawn curve by up to about the fit tolerance; the point overlay and the interpolation drag preview use the same points, which lie on the curve.

## 2026-11-17 — Vector crash fix, Merge, point brushes, vector eraser, pen pressure / stabilization, Bezier handle

- AI model: Claude
- Crash (bug report `Inbetween-bug-report (5)`, app 0.4 (171), SM-P610, two fatals 06:54 and 07:04): `IllegalStateException: Cannot modify a state object in a read-only snapshot`, thrown by `SnapshotStateList.add` during a Choreographer frame. Cause found in source: `VectorLive.celAt` (reached from render paths through `Layer.drawCel`, e.g. `Canvas.kt`) created a transient `Cel` and filled its `SnapshotStateList` while the canvas draw/layout pass (a read-only snapshot) was running, for a vector key with live interpolation. Fix: when `Snapshot.current.readOnly`, `celAt` returns the held key, queues the build on the main handler (global snapshot) and bumps an observed counter so the draw runs again; background / editing callers still build synchronously. The log's older fatals (2026-11-12 coerce range, 2026-11-14 mutex) are different, earlier problems and were not touched. The trace is obfuscated, so the link to `celAt` is an inference from the stack shape (list `add` inside a frame callback) and the only draw-time list write found, not a proven line match.
- Merge (vector draw bar, `Editor.vecMerge`): `VectorMerge.attach`. A new line whose end lies within max(8 screen px, half its width) of another open line's end is joined into one stroke (older stroke's id / colour / width kept; a chain that closes becomes a ring). An end on the body of another line snaps onto it and the junction point is inserted in both lines. Lines that merely cross are NOT merged.
- Point-edit brushes (`Editor.vecBrush` 1 Shift / 2 Smoothen / 3 Distort, `vecBrushRadius`, `vecBrushStrength`): buttons on the Point edit widget bar; Radius and Strength sliders appear above the bar while one is on. `VectorBrush.apply` (pen kind 24) works on all strokes of the selection scope, adds points where segments cross the brush, never changes the stroke count, one undo step per drag. The point selection is cleared when a brush stroke starts (point numbers change).
- Eraser on vector layers (tool 1, pen kind 25, `VectorEraser`): removes the part of the line under the pen between the nearest crossings with other lines (or the line's end); one undo step per pen stroke. Closed shapes need at least two crossings, else the whole shape goes.
- Vector pen (`VecPen`): the selected brush's stabilizer (all methods), pressure through `sizeCurve` with the smoothed-pressure filter, start / end taper; simplification is now pressure-aware (`VectorSimplify.rdp(..., widthScale)`). Not replicated: velocity taper, tilt, opacity-from-pressure, dab textures.
- Bezier: the second handle rests on its end point and is not drawn until the first handle is placed (`Bez.c2` starts at `b`; drawn only in stage 2).
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `Editor.kt`, `Tools.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. No `.inb` format change. Protected files untouched (workflows, Gradle, gradle.properties, manifest, signing).
- Validation: bug report read, source inspection, brace / paren balance check. NOT compiled (no Kotlin compiler or Android SDK here), not run on a device. Untested: whether the crash is gone, Merge junctions, brush feel and cost on dense drawings, eraser at crossings, stabilization / pressure parity, Bezier flow.
- Known follow-up: crossing lines cannot be one polyline, so Merge handles ends and T-junctions only; Distort accumulates per pen event (feel needs tuning); eraser radius uses the eraser preset size.

## 2026-11-16 (9) — Vector selection across layers, Anchor availability fix, Smooth

- AI model: Claude
- Selection across layers: the Selection tool and Point edit now work on every visible, unlocked vector layer at the playhead when the existing "All visible drawing layers" toggle (`Editor.refAll`) is on, and on the active layer only when it is off. `VecScope` (`Vector.kt`) concatenates the strokes of those keyframes, so point keys span them; select (point, rectangle, lasso, polygon, wand), move, raise / lower, transform handles, Select all, Delete, Anchor and Smooth all act across the scope, each as one undo step (`applyScope`, `vecBeginLive` in `Canvas.kt`). The Cutter stays on the active layer. Holding the Point edit button opens a dialog with that toggle. The selection now stores its keyframes in an observable `Editor.vecSelCels` (was the plain `vecSelCel`).
- Anchor availability bug: `VectorWidgetBar` computed `has` with a short-circuiting `&&`, so while the old plain `vecSelCel` did not match it never read `ed.vecSel` and was not subscribed to selection changes; the button stayed disabled until another state (the raise toggle) forced a recomposition. The bar now reads the selection state first and unconditionally.
- Smooth (widget bar, needs a selection): slider 0-100%. For every stroke with a selected point it adds points through a centripetal Catmull-Rom spline between the existing points (which are kept, with anchors). Per segment the count depends on the sharper of its two end bends: under about 2 degrees counts as straight and gets none; up to 8 per segment at full strength for bends of 29 degrees or more; never closer than 1.5 px. The dialog shows how many points will be added. The strokes stay selected afterwards.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `Editor.kt`, `WhatsNew.kt`. No format change. Validation: brace checks and source review only; not compiled or run.

## 2026-11-16 (8) — Bezier line tool, automatic anchors, automatic line pairing

- AI model: Claude
- Bezier curve (Line tool, shape 4; drawing and vector layers): drag the two end points (perspective magnetism applies like the Line shape), lift, move the pen to place the first handle and tap, then place the second handle and tap to apply (`Bez` / `bezierPts` in `Tools.kt`, pen kind 23 in `Canvas.kt`). The curve is sampled about every 3 px; on a vector layer `commitShape` simplifies it (RDP) into an editable vector stroke, on a drawing layer it is an ordinary brush stroke. Handles are drawn on the canvas while pending; changing tool cancels it.
- Anchor button: selected points that are not anchors take the next free colours in the fixed order (blue, red, yellow, green, orange, purple, then the tertiaries), one per point in stroke order (`autoAnchor`). Only when every selected point is already an anchor does the button open the colour menu to change it.
- Automatic same / different lines: the Lines choice is gone (the dialog offers Interpolate or Hold). `VectorInterpolation.pairAuto` pairs (1) strokes that share an anchor colour (most shared first), (2) strokes with the same id, (3) the rest by position, overlap and drawing order; unpaired strokes still shrink and fade.
- Line weight: the earlier "weight over anchors" effect was strokes with different ids staying unpaired (so their width faded instead of morphing along the anchors). Anchored strokes now pair first, and paired strokes blend width, per-point pressure, opacity and colour (that blending already existed; it now applies to them).
- Files: `Tools.kt`, `Canvas.kt`, `Panels.kt`, `Vector.kt`, `VectorUi.kt`, `WhatsNew.kt`. No format change. Validation: brace checks and source review only; not compiled or run.

## 2026-11-16 (7) — Interpolation drag and the vector widget bar

- AI model: Claude
- Request: an Interpolation drag button; vector buttons on the timeline limited to Vector draw, Point edit, Interpolation drag and Cutter; the rest on a widget bar tied to its button, bottom centre of the canvas.
- Interpolation drag (`Editor.vecMode == 3`, pen kind 22 in `Canvas.kt`): with a vector keyframe selected, dragging left or right on the canvas sets where the in-between sits between the previous keyframe and the current one (`Editor.vecDragT`, 260 dp of travel = 100%); the in-between is previewed live over the canvas. The widget bar's Check button calls `createDraggedInbetween` (`Vector.kt`): a new one-frame keyframe is inserted before the current one. The previous key's Lines setting pairs the strokes (Hold counts as Same line); anchors are honoured. With the timeline's retime toggle on (`rippleRetime`) the frame takes its time from the current keyframe (from the previous one if the current lasts one frame) so the layer length is kept; with it off, the current keyframe and later ones are pushed one frame later. Undo is one step.
- Timeline strip (`VectorToolStrip`): Vector draw, Point edit, Interpolation drag, Cutter only.
- Widget bar (`VectorWidgetBar` in `VectorUi.kt`, placed in `DrawingCanvas`, bottom centre): for Point edit and the Selection tool: raise / lower, falloff, Select all, Deselect, Delete, Anchor; for Interpolation drag: percentage, reset, interpolation settings (lines, curve, bake), confirm. The point overlay now shows only for Point edit, Cutter and the Selection tool.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `Editor.kt`. No format change. Validation: brace checks and source review only; not compiled or run.

## 2026-11-16 (6) — Anchor interpolation: stroke direction

- AI model: Claude
- Bug (user screenshots): two rectangles with four matching anchor colours interpolated into a twisted 6-point bow-tie. Cause: the second rectangle's points were stored counter-clockwise while the first was clockwise, so the anchors appeared in opposite point order. `anchorPairs` keeps only anchors in increasing order in both strokes, so only yellow and blue survived and the strokes were stretched between them (the red and green anchors were dropped).
- Fix: `align()` tries stroke B in both directions and keeps the one that honours more anchors (tie on closed rings: compare the signed area winding), then rotates closed rings to start at the first anchor. The no-anchor closed blend also matches winding now. No format change. Not compiled or run.

## 2026-11-16 (5) — Anchor colour order by colour theory

- Anchor ranks now run: primaries (blue, red, yellow), secondaries (green, orange, purple), then the six tertiaries (red-orange, yellow-orange, yellow-green, blue-green, blue-violet, red-violet): 12 ranks (`ANCHOR_COLORS`, `ANCHOR_NAMES`, `ANCHOR_MAX` = 12). The dialog lists them in rows by tier. No format change; anchors saved by the previous build keep their rank number, so rank 3 now shows yellow instead of green and ranks 4-8 shift colours. Not compiled or run.

## 2026-11-16 (4) — Reference anchors for vector interpolation (format V25)

- AI model: Claude
- Request: an "Anchor" button that turns selected points into colour-coded reference anchors (primary, secondary, tertiary, then the rest) so matching colours in the next keyframe lead the interpolation; several anchors allowed.
- Model: `VectorPoint.anchor` (0 none, 1-8). Colours in `ANCHOR_COLORS` order: blue (primary), red, green, yellow, purple, cyan, orange, pink. One point per colour per stroke.
- Interpolation: `anchoredBlend` splits both strokes at the anchors they share, resamples each pair of segments against each other and blends, so every anchor lands exactly on its partner; anchors stay on the generated points, so baked and live frames keep the chain. Anchor order that conflicts between the two keys is ignored. Closed rings start at the first anchor. In Different-lines mode a shared anchor colour strongly favours pairing those two strokes (`pairDifferent`). Without common anchors the old arc-length blend is used.
- UI: "Anchor" button on the vector strip (enabled with selected points): pick a colour, "Number selected points in order" (per stroke, blue, red, green...), or "Clear anchor". Anchors show as larger colour-coded dots in the edit overlay.
- Storage: V25 appends sparse anchors (point index, rank) to each vector stroke; V1-V24 still read.
- Not done: rank numbers drawn on the dots; anchors between two different strokes in Same-line mode beyond id matching.
- Files: `Vector.kt`, `VectorUi.kt`, `Store.kt`, `Canvas.kt`, `WhatsNew.kt`, docs. Protected files untouched. Validation: brace checks and source review only; not compiled or run.

## 2026-11-16 (3) — Selection, transform and line / shape tools on vector layers

- AI model: Claude
- Request: the Selection, transform and line tools must work with the vector layer.
- Selection tool (tool 2) on a vector layer selects vector strokes instead of pixels, using `Editor.vecSel` (the point set already used by Point edit): rectangle and lasso (any stroke with a point inside is selected whole), polygon, and the Magic Wand toggle (the stroke under the pen); Shift / Ctrl or the add / erase mode add to / subtract from the selection. Pen kinds 18 (handle drag), 19 / 20 / 21 (wand / rect-lasso / polygon) in `Canvas.kt`.
- Transform: the selected points get the shared gizmo (`Gizmo.kt`): corners scale, sides stretch, outside the corners rotates, inside moves, padlock keeps the aspect. `VectorEdit.transform` maps the points; a stroke whose points are all selected also scales its width. One undo step per drag (same swap-the-cel pattern as point edit).
- Line / shape tool (tool 4): line, rectangle, circle and ellipse become vector strokes (`commitShape` branches on `Layer.vector`; closed shapes are stored closed, simplified with RDP). Perspective hover latching of the line tool still applies.
- Timeline strip: Select all / Deselect / Delete (removes the selected points; a stroke left with under two points disappears) while the Selection tool or Point edit is active. Eraser and Fill are still blocked on vector layers.
- Not done: rotation magnet and warp / puppet on vector selections; pixel-style selection masks, clipping a vector stroke to a pixel selection.
- Files: `Vector.kt`, `VectorUi.kt`, `Canvas.kt`, `WhatsNew.kt`, `CHANGELOG.md`. No format change; protected infrastructure untouched. Validation: brace checks and source review only; not compiled or run.

## 2026-11-16 (2) — Live interpolation preview for vector keys

- AI model: Claude
- Category: Feature / vector layers
- Context: the V25 build compiled and ran (reported by the user). This adds the live (transient) interpolation the first entry listed as not built.
- Change: `Layer.drawCel(t)` returns, for a vector layer whose key has interpolation settings (`Cel.vecCfg`, mode not Hold), a cached transient cel for each in-between frame of that key's hold (`VectorLive` in `Vector.kt`); everything else returns `celAt(t)`. Render sites switched to it: canvas layer / folder passes, playback prefetch and readiness (`Engine.kt`), export (`Export.kt`), GPU scene (`GlLayers.kt`) and `renderWindow` (`Tiles.kt`). Editing, selection and drawing still use `celAt`. Interpolation is opt-in (Apply or Bake stores settings; a key without settings holds). Baking now sets the baked keys to Hold so live interpolation is not layered on top. Point drags bump `Cel.vecRev` so cached frames rebuild. No format change (still V24).
- Files: `Vector.kt`, `Editor.kt`, `Canvas.kt`, `Engine.kt`, `Export.kt`, `GlLayers.kt`, `Tiles.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected infrastructure untouched.
- Still not built: GL ribbon renderer / FBO export, Bezier editor. Validation: source review and brace checks only; not compiled or run after this change.

## 2026-11-16 — Vector layers and Grease Pencil-style interpolation (format V24)

- AI model: Claude
- Category: Feature / vector layers / document format
- Request: implement the "Vector Layer & Grease Pencil-Style Interpolation Implementation Plan".
- Design decision (differs from the plan's `LayerType.VECTOR`): a vector layer is a drawing layer (`kind == 0`) with `Layer.vector = true`; its cels are the keyframes and hold strokes as Ink kind 5 (x, y, pressure, elevation per point; width, closed, opacity in `m`; `"<id>|<brush>"` in `data`). Timeline, per-layer timing, undo snapshots, onion skin, thumbnails and exports therefore work unchanged. `VectorPoint` / `VectorStroke` / `VectorKeyframe` / `InterpolationConfig` exist in `Vector.kt`; the keyframe settings live on `Cel.vecCfg`.
- Done: `VectorTessellator` (miter joins with the 0.25 limit and bevel, round caps, closed rings, triangle-strip builder); rasterization through `TileSet.stamp` / `stampInk`; RDP simplification of drawn strokes; Vector draw / Point edit (tap, lasso, drag, raise/lower via `elevationOffset`, smoothstep falloff) / Cutter (segment intersection, new id for the further pieces); interpolation (arc-length resampling, same-line and different-line pairing, easing via `easeFn` or a cubic Bezier, unpaired strokes shrink and fade) and bake on 1s / 2s / N; toolset strip and interpolation dialog (`VectorUi.kt`); Store V24 (layer flag, kind-5 inks, per-keyframe settings; V1-V23 still read).
- Existing multi-key selection (tap, double-touch drag marquee) selects the keyframes the menu acts on.
- Files: `Vector.kt` (new), `VectorUi.kt` (new), `Editor.kt`, `Canvas.kt`, `Panels.kt`, `Store.kt`, `Tiles.kt`, `Engine.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts / wrapper / versions, `gradle.properties`, manifest, signing.
- NOT built: the OpenGL VBO / signed-distance shader pipeline in `GlLayers.kt` and the FBO export path (vector strokes are rasterized per document pixel like other layers, so they are not resolution independent at zoom); live (transient) interpolation during playback; a Bezier editor in `CurveView.kt`. Eraser, selection, fill and shape tools are blocked on vector layers (a toast says so). Perspective magnetism is not applied to vector strokes.
- Validation: brace/parenthesis balance checks and source review only. No Gradle / Android SDK / Kotlin compiler here: NOT compiled, NOT run on a device. Expect possible compile errors on the first CI build.

## 2026-11-16 — V22 Release Kotlin nullability compile fix

- AI model: GPT-6
- Category: Build fix / Perspective stylus input
- Build report: `:app:compileReleaseKotlin` failed at `Camera.kt:345:36` because nullable `Offset?` hover input was passed directly to a helper requiring non-null `Offset`.
- Fix: normalize the hover vector only when it is present (`hoverDirection?.let { normalized(it) }`). A missing hover vector remains `null`, preserving the existing fallback to the first contact movement.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle wrapper / build scripts / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: exact failing expression corrected; isolated Kotlin null-safe expression compiled; source search confirms no other call passes a nullable hover vector to `normalized`. Full Android Release build and tablet testing were not available in this workspace.

## 2026-11-16 — Hover-directed perspective drawing and direct focal-length persistence (format V22)

- AI model: GPT-6
- Category: Bug fix / Perspective input / Camera lens
- Clarification: S Pen hover should improve magnetization; while a perspective layer is visible, no ordinary freehand brush strokes should be produced.
- Stylus input: Raw `ACTION_HOVER_ENTER`, `ACTION_HOVER_MOVE` and `ACTION_HOVER_EXIT` events are captured before contact. The smoothed hover direction helps select the closest compatible guide or ray. Android may send `HOVER_EXIT` immediately before `ACTION_DOWN`, so the last approach vector is retained briefly. If hover direction is unavailable, the first real contact movement selects the path. Once selected, every sample is projected fully onto that path; there is no freehand fallback or unmagnetized lead-in. Brush and eraser strokes are both constrained while any perspective layer is visible.
- Curvilinear: The guide/horizon axis remains exactly straight, including when rotated. Radial rays from each vanishing point have zero curvature when parallel to the guide and progressively stronger curvature as their direction moves away from it. The per-guide ray-count button opens the 8–48 even-count slider.
- Lens: Physical focal length in millimetres is the source value; horizontal FOV is derived from the 36 mm sensor width. Applying a focal length scales each vanishing-point offset from the optical centre by the focal-length ratio, so VP positions change in guide geometry as well as the blue FOV cone. V20/V21 files remain compatible: their stored FOV is converted to focal length on load without scaling their already lens-adjusted VP coordinates a second time.
- Persistence: `.inb` writer is V22. V22 stores focal length in the existing lens field followed by V21 ray-count fields. The reader continues to support V1–V21 and reads V22.
- Files: `Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts / wrapper / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: Source-level review of hover event handling, magnet path selection, VP scaling, V20/V21/V22 serialization ordering and backward-compatible FOV conversion. No Gradle wrapper / executable or Android SDK was available; NOT compiled and NOT tested on the tablet.
- Known follow-up: Test with the target S Pen to confirm hover events arrive before pen-down; verify ray selection at multiple zooms and guide rotations, absence of freehand gaps, and V20/V21 load followed by V22 save/reopen.

## 2026-11-16 — Curvilinear VP arcs, camera lens FOV and independent rotation locks (format V20)

- AI model: GPT-6
- Category: Feature / perspective geometry / input behavior / document format
- Request: correct Curvilinear so vanishing points remain on their assigned guide and rays curve toward adjacent vanishing points; make guide rotation and 30-degree snapping independent togglable locks; implement camera FOV / focal length correctly; remove unwanted line snapping.
- Research basis: Krita documents Curvilinear Perspective as circular arcs intersecting two vanishing points, with arc-circle geometry defined by the two VP endpoints and an adjustable intermediate curvature parameter. Clip Studio Paint documents fisheye rulers as curved perspective guides with separately adjustable vanishing points, distortion strength and field of view. Camera lens FOV/focal length uses the standard rectilinear relationship `FOV = 2 atan(sensorWidth / (2 × focalLength))`; this implementation uses a 36 mm full-frame sensor width.
- Changes: replaced the previous quadratic guide curve and sine-offset VP rays with a family of circular arcs for each adjacent pair of VPs on the same guide. All arcs share the exact VP endpoints. The canvas overlay, guide hit-testing and brush magnet use the same arc geometry; the brush latches one specific arc per stroke. Added a per-guide 30° snap lock separate from the rotation-unlock lock; unlocked rotation is freeform when 30° snapping is off. Replaced the old guide-axis-angle FOV control with linked horizontal FOV / focal length fields; changing lens values no longer rotates guides. Removed perspective endpoint snapping from the straight-line tool and straight-line brush mode.
- Persistence: advanced `.inb` writer to V20. V20 appends per-guide 30° snap booleans and the shared camera FOV. The reader now explicitly recognizes V19 (the prior reader's magic-number switch had omitted it) and supports V1–V20. V1–V19 files default to 90° camera FOV and 30° guide snapping disabled (freeform when rotation is unlocked).
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle wrapper / build scripts / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: reviewed the source changes, call sites, V20 binary read/write ordering, old-version defaults and the FOV/focal-length formula; checked archive integrity and compared protected infrastructure against the supplied archive. NOT compiled and NOT tested on the tablet because no Gradle wrapper / executable or Android SDK is available in this workspace.
- Follow-up: build with the existing beta workflow; test at least two VPs on one guide, both curvature directions, VP movement / spacing lock, S Pen path acquisition, freeform and 30° rotation modes, FOV/focal edit in both directions, line tool freehand endpoints, and V19-to-V20 save/reopen.

## 2026-11-16 — Perspective bar hit-testing, single-guide switching and smooth magnet acquisition

- AI model: GPT-6
- Category: Bug fix / interaction
- Report: perspective bar buttons did not respond; the user wants the original compact bar appearance with only one guide active at a time, automatically switching when the horizon or perpendicular guide is interacted with; brush strokes visibly jumped when magnetism acquired a path.
- Changes: raised the perspective bar above the full-screen drawing Canvas (`zIndex(3)` above Canvas `zIndex(2)`); replaced the two-tab header with one active guide label; `pDown` switches the active guide when a vanishing point or guide axis is touched. Added per-stroke blend progress and last-point tracking so magnetism eases into the acquired path using smoothstep over 0.85 × capture radius instead of snapping on the acquisition sample.
- Preserved: the `KeyboardOptions` import from `androidx.compose.foundation.text`, the `KeyboardType` import, the V19 file format and existing per-guide settings. No protected infrastructure changed.
- Files: `Canvas.kt`, `Camera.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: ZIP integrity and targeted source/diff checks. NOT compiled and NOT tested on the tablet.
- Follow-up: build on beta; verify every perspective button responds to S Pen/finger taps; tap/drag each guide and its vanishing points to confirm the single bar follows the touched guide; draw several strokes that acquire straight and curvilinear paths and check the stroke start has no visible jump while pressure and tilt remain responsive.

## 2026-11-16 — Adjustable per-guide curvilinear strength and Kotlin import fixes (format V19)

- AI model: GPT-6
- Category: Feature / Canvas / persistence / build fix
- Request: add a control for the strength of the Curvilinear perspective effect; also fix the current Release compile errors.
- Changes: added a Curvilinear strength button to the selected guide bar. It opens a 0–200% slider (100% default); 0% makes the guide straight and 200% doubles the default fisheye bend. Horizon and perpendicular retain independent strengths. Reset restores the selected guide's strength to 100%. The same strength scales both the rendered curved guide and the live brush-magnet curve. Added the missing `Arrangement` and `fillMaxWidth` imports reported by the build log. Preserved `KeyboardOptions` from `androidx.compose.foundation.text` and the existing `KeyboardType` import.
- Compatibility: writer advanced to V19. Reader still accepts V1–V18; V19 appends one strength float per perspective guide after its existing V18 settings. Older app builds may not understand V19 files.
- Files: `Canvas.kt`, `Camera.kt`, `Store.kt`, `WhatsNew.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts / wrapper / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: inspected the supplied Release build log and fixed the two reported unresolved imports; reviewed the V19 read/write ordering and older-version defaults; checked Kotlin source edits and ZIP integrity. No Android Gradle build or device test has been run.
- Known limits / follow-up: curvature strength scales the existing fisheye-style guide approximation; it is not a physical lens model. Build and tablet validation remain required.

## 2026-11-16 — Perspective layer: live brush magnetism, independent guide controls, FOV and Curvilinear (format V18)

- AI model: GPT-6
- Category: Feature / Canvas / data format
- Request: replace post-stroke perspective attraction with real-time brush magnetism; keep pen pressure; add separate horizon / perpendicular widget bars with rotation unlock, 30° rotation, vanishing-point distance lock, field-of-view angle, Curvilinear fisheye mode, logos with S Pen hover descriptions, and Reset. The intersection of the two guides is the camera optical centre.
- Changes: `Persp` now models both guide axes around their shared intersection. Each guide can be rotated independently (rotation is locked until unlocked; the 30° button advances in 30° steps), and its VP-distance lock translates the other vanishing points on that guide by the same delta. The FOV slider sets the angular separation between the two axes at the intersection, keeping the selected guide's direction fixed while rotating the other about its vanishing point; the intersection moves so the guides continue to meet. Curvilinear mode draws a bowed guide and uses a sampled curved path for brush attraction. `Session.feed` now magnetically maps every stabilized input sample immediately before stamping; pressure and tilt values are passed through unchanged. The post-stroke `attract` rewrite was removed. Two separate icon rows show tooltips on S Pen hover, and each guide has a Reset button.
- Compatibility: `.inb` writer advanced to V18; reader supports V1–V18. V18 appends the two guides' rotation, rotation-unlock, VP-distance-lock and Curvilinear flags after the existing perspective data. V1–V17 files load with the original horizontal / vertical 90° defaults. Older builds cannot open V18 files.
- Files: `Camera.kt`, `Canvas.kt`, `Engine.kt`, `Tools.kt`, `Editor.kt`, `Store.kt`, `WhatsNew.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts / wrapper / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: source-level inspection, search of perspective call sites, file-format read/write path review, and Kotlin delimiter / archive checks. No Gradle executable or wrapper / Android SDK build was available; NOT compiled and NOT tested on a device.
- Known limits / follow-up: FOV is the angle between the two guide axes (not a physical lens calibration); Curvilinear is a lightweight fisheye-style bowed guide approximation, not a full stereographic lens projection. Test S Pen hover delivery, guide rotation / drag, VP spacing lock, FOV changes, curved magnetism, pressure-sensitive brushes, undo / redo and V17-to-V18 save / reopen on the tablet.

## 2026-11-15 (8) — Selection preview, independent Magic Wand toggle and minimal timeline layout

- AI model: GPT-6
- Category: UI / Selection
- Request: remove the blue fill from the selection preview; make Magic wand a toggle that can coexist with the other selection tools; move the current-layer bar to the far left of the fullscreen minimal timeline and put its up/down navigation buttons to its right.
- Changes: completed selections now rely on the existing animated marching-ants outline without drawing the translucent blue mask bitmap. The three geometric selection modes remain mutually exclusive, while `Editor.selWand` independently switches pointer selection to Magic Wand and preserves the selected geometric mode underneath. In fullscreen, `MiniLayerButton` now precedes the compact timeline controls; its current-layer bar is followed by the up/down layer buttons.
- Compatibility: no `.inb` format or protected infrastructure changes.
- Files: `Canvas.kt`, `Editor.kt`, `Panels.kt`, `MainActivity.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts / wrapper / dependency versions, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: source inspection and targeted diff review against the supplied ZIP; ZIP integrity and Kotlin delimiter-balance checks. No Android Gradle build, CI build or tablet interaction test was performed.
- Known follow-up: verify the selection outline with all three geometry tools and Magic Wand on/off; verify live wand gap adjustment; check fullscreen layer-bar placement and layer navigation on the tablet.

## 2026-11-15 (7) — Reference layers: tap-out tolerance, magnet for two-finger rotation

- AI model: Claude Sonnet 5.5
- Category: Bug fix / follow-up to 2026-11-15 (6)
- Report: the pen tap outside the images only worked with zero movement; two-finger rotation of references ignored the magnet button.
- Cause: (1) the tap test reused `PenState.moved`, which is measured in DOCUMENT units (> 6 doc px), so at low zoom a pixel or two of S Pen jitter counted as a drag; and a pen-down in the picked image's handle zone (outside the image, up to ~60 px beyond a corner) was taken as a handle drag, so it never reached the tap-out. (2) the magnet only ran on release, only for a single image, and only when the whole gesture rotated more than 0.5 degrees.
- Changes (`Canvas.kt`, `Tools.kt`): `PenState.downScr / farScr / refOutside` and `TAP_SLOP_PX` (40 screen px): a tap is judged in screen pixels. A pen-down that starts a handle drag but lies outside every image is remembered (`refOutside`); if it ends within the slop, the tiny handle change is reverted and `exitReference` runs. Two-finger reference gestures now accumulate the raw gesture in `refG` (unsnapped) and show `refBefore * refG` with the magnet on top, live: one image snaps to an absolute multiple of 30 degrees around its own centre, several images snap the gesture's total rotation around the fingers. The release-time snap was removed.
- Files: `Canvas.kt`, `Tools.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched; file format unchanged (V17).
- Validation: source review, bracket balance, diff review. NOT compiled, NOT run.
- Known limits: a tap that starts INSIDE an image never exits (it picks / moves that image). The 40 px slop is a guess for the SM-P610; adjust `TAP_SLOP_PX` if it feels too loose or too tight.

## 2026-11-15 (6) — Reference layers: several images, Add / Delete reference, Relative position, tap-out, two-finger transform

- AI model: Claude Sonnet 5.5
- Category: Feature
- Request: (1) pick several images at once for a reference layer; (2) an "Add reference" button on the timeline when a reference layer is selected; (3) a "Delete reference" button that appears when interacting with one image and deletes only that one; (4) a "Relative position" button on the widget bar that makes the images position-independent of zoom / pan (an image in the upper-right corner stays there); (5) a pen tap outside every image applies the change and returns to the layer we came from; (6) finger pinch / two-finger touch zooms and rotates the references: on one image only that image, outside every image all the references.
- Findings: the data model already allowed several images per reference layer (one cel, one kind-3 ink per image, each with its own matrix and path; the V17 writer / reader store any number of strokes per cel), so NO file-format change was needed. Every place that read `strokes.firstOrNull { kind == 3 }` assumed one image per layer: gizmo box, hit test, drag, widget bar, cache warm-up and the missing-file check on load.
- Changes: **Model** (`Editor.kt`): `Layer.refPinned` (session state, not stored); `Editor.refSel` (picked image), `Editor.refReturnId`, `Editor.refPinProvider`; `Cut.addReferenceImages` / `appendReferenceImages` (images cascade by 4 % of the smaller Cut side so they do not hide each other; each image is one undo step, as before), `Cut.referenceInks`, `Cut.removeReferenceImage` (undo restores the stacking index; deleting the LAST image removes the empty layer in one undo step and returns to the previous layer), `Cut.flipReferences`, `Cut.pushReferenceMatrices` and the undo acts `ReferenceMatricesAct` (several matrices + the pin flag) and `ReferenceRemoveAct`; `Editor.toggleRefPinned`. `addReferenceImage` (single) was replaced by the list versions. **Picker / timeline** (`Panels.kt`): the reference pickers use `OpenMultipleDocuments`; shared `importReferences(uris, target)`; "Add reference" text button in `TimelineToolbarContent` while a reference layer is selected (so also in the fullscreen minimal timeline). **Canvas** (`Canvas.kt`): `refLocal` / `refInkAt` (topmost image under a screen point, via `ReferenceBitmapCache.dimensions`, no bitmap decode); `gDown` picks the image under the finger or pen (its layer is selected, as before) but a handle of the already picked image wins over an image below it; `gBox(5)` / the drag act on the picked image only (the gizmo is shown only for a picked image); pen down on empty space of a visible, unlocked reference layer starts pen kind 14 and a tap (no movement) runs `exitReference` (clears the pick, selects `Editor.refReturnId` or the top drawing layer); two fingers on a reference layer pinch / rotate / pan the image under the gesture centre (or the image a one-finger drag started on), else every image of the layer, as one undo step; the quick-pinch view reset, the view-rotation snap and view zoom are not applied during such a gesture; the rotation magnet snaps a single image to an absolute 30 degrees around its centre on release. **Relative position**: a pinned layer stores its matrices in HOME-view coordinates (view reset, no flips) and is drawn with `pinMatrix()` (home -> document of the current view), so it stays on the screen; `toDoc` / `toScr` use the home mapping while a pinned reference layer is active, so the gizmo and drags work in screen space; folder transforms are ignored for pinned layers. `toggleRefPinned` converts the matrices so nothing jumps when the switch is pressed (including the folder transform of an unpinned layer inside a Transformation folder). **Widget bar** (`MainActivity.kt`): Flip H / Flip V (picked image, else all images around their common centre), aspect lock, magnet, **Relative position** (highlighted when on), **Delete reference** (only with a picked image); `LaunchedEffect(cut.layerId)` records the layer the reference layer was entered from and clears a stale pick; cache warm-up covers every image. **Load** (`Store.kt`): a missing image file now drops only that image; the layer is removed when no image is left (before: the first image decided for the whole layer).
- Bug found and fixed on the way: the old Flip H / Flip V buttons decoded `Ink.data` as image bytes, but it holds the UTF-8 file location, so the width / height were -1 and the mirror centre was wrong. `Cut.flipReferences` uses the real image size.
- File format: unchanged (V17). `refPinned` is NOT persisted: after saving and reopening, pinned images come back as ordinary document-anchored images at the position they have in the default (reset) view. Persisting it needs a V18 layer flag; not done because it would make Cuts unreadable by older builds and needs your decision.
- Files: `Editor.kt`, `Canvas.kt`, `Panels.kt`, `MainActivity.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, `gradle.properties`, manifest.
- Validation: source review, bracket-balance check against the original files and a full diff review. NOT compiled, NOT run (no Gradle / Android SDK / Kotlin compiler here); this is a compile-blind change, expect a possible build round.
- Behaviour changes to know about: (a) with a reference layer selected, two-finger gestures no longer zoom / rotate the canvas (they transform the images); leave the layer (pen tap outside) or use the keyboard zoom keys to move the view; one finger on empty space still pans the canvas (it does not move pinned images). (b) While a reference layer is selected every pen tap on empty canvas leaves it, so the pen cannot be used to deselect an image without leaving the layer. (c) A freshly imported single image is picked at once; several images start with none picked. (d) A pinned layer follows the workspace size: toggling fullscreen changes the home view's fit, so pinned images scale with it.
- Known limits / follow-up: (a) `refPinned` not saved (see above). (b) Two-finger magnet snapping is only for a single picked image. (c) Images cannot be reordered (bring to front / send back). (d) Hit testing uses the whole image rectangle, transparent pixels included.
- Test: import 3 images at once (one layer, cascaded); tap one (gizmo + Delete reference appear); drag, scale, rotate it with finger and S Pen; pinch it with two fingers (only it changes) and pinch on empty space (all change); Add reference on the timeline; Delete reference, then undo / redo (order restored); delete the last image (layer goes, previous layer selected); tap empty canvas with the pen (returns to the previous layer); Relative position on, then pan / zoom / rotate / flip the canvas with a drawing layer selected (images stay on screen), off again (no jump); undo of pin on / off; Flip H / V on one image and on all; save, close, reopen (all images back, pin off); delete one image file on the device and reopen (only that image missing).

## 2026-11-15 (5) — Build fix 2

- AI model: Claude Sonnet 5.5
- Category: Bug fix (build)
- Build log (`logs_118461536048.zip`): `compileReleaseKotlin` failed with "Platform declaration clash ... setMode(I)V" at `Tools.kt:148` and `:163`: `Xform` has `var mode by mutableIntStateOf(0)` (generates `setMode(int)`) and my `fun setMode(m: Int)`.
- Fix: renamed the function to `Xform.switchMode`; updated the two callers in `SelectionBar.kt`. No behaviour change. No protected files touched. Not compiled here; later compiler phases may report more.

## 2026-11-15 (4) — Build fix for the previous entry

- AI model: Claude Sonnet 5.5
- Category: Bug fix (build)
- Build log (`logs_118454153722.zip`, step "Build signed release APK"): `compileReleaseKotlin` failed with two errors in `Canvas.kt`: `Unresolved reference 'selModHeld'` (line 300) and `'paintClip'` (line 504). Both are local declarations of `DrawingCanvas` that were declared after their first use.
- Fix: moved `selModHeld` and `paintClip` / `clipCache` above `gDown`. No behaviour change. No protected files touched.
- Validation: only these two errors were reported; the Kotlin compiler stops at the first failing phase, so more errors may appear in a later phase. Not compiled here.

## 2026-11-15 (3) — Transform: Warp / Puppet Warp / widget bar; Selection improvements; Smarter Fill (format V17)

- AI model: Claude Sonnet 5.5
- Category: Feature
- Requested items and how they were read: (1) "All selection handles, transform controls and widget bars must move with the transformed ..." (the sentence is cut off in the request): read as the widget bar and handles following the transformed selection. (2) Warp button with increments + strength sliders and incremental deformation. (3) Puppet Warp with place / move / remove pins. (4) Stroke-aware selection, shimmering outline, Fill button, auto fill option, add / erase buttons and held keys, invert, selection-type logos, draw and paint inside the selection. (5) Smarter fill.
- Changes: **Widget bar** (`SelectionBar.kt`, new): composed inside `DrawingCanvas` and placed under (else above, else at the top of) the on-screen box of the selection / transformed content, re-placed in the layout phase; the old fixed bar in `MainActivity` was removed. Buttons: Add, Erase, Invert, Fill, Transform, aspect lock, magnet, done; while transforming: cancel, lock, magnet, Warp, Puppet, reset, apply. **Warp / Puppet** (`Warp.kt`, new; `Xform` in `Tools.kt`): `WarpMesh` (grid of document-space vertices over the lifted bitmap, drawn with `drawBitmapMesh`), grid warp nudges one node and its neighbours with a smooth fall-off (`nudgedMesh`, Strength = reach), Increments resamples the mesh (`resampled`); Puppet Warp uses a moving-least-squares rigid deformation of a 24x24 mesh from the pins (`puppetMesh`); `Xform.mode` 0 handles / 1 warp / 2 puppet, `Xform.mesh` is in document space BEFORE `Xform.mat`. Canvas: `wpDown / wpMove / wpUp` (pen kind 13), grid points / pins drawn in the main canvas, preview via `drawBitmapMesh`. Commit: `applyTransform` bakes `mat` into the mesh and stores an ink of **kind 4** (runs + `m = [cols, rows, vertices...]`), replayed by `TileSet.drawMeshTiles`. **Selection**: `selOp` (replace / add / erase, sticky buttons + Shift / Alt / Ctrl in `MotionEvent.metaState`), `combineSel`; `strokeAwareSel` (painted pixels + anti-alias edge inside the outline, plus strokes that are >= 60 % inside and poke <= 6 px out; runs on a background thread, the quick result is shown first); marching-ants outline traced by `buildOutline` on a background thread and drawn by its own overlay canvas (14 fps phase, does not redraw the page); `Fill` (`fillSelectionNow`), `selAutoFill`, `selSmart`, `paintInSel` options with selection-type glyphs (`SelGlyph`) in the options panel. **Paint inside selection**: strokes carry the selection runs in `Ink.runs` (kind 0, new meaning) and `TileSink` clips every tile canvas it touches to `clipPathOf(runs)` (`endClip` after the stroke; capped strokes clip in `compositeFrom`); shapes and fill are clipped the same way. **Fill** (`SelectionFill.kt`): `smartFlood` (grows the gap-closed fill back by the gap radius inside the original region, which removes the unfilled band along lines; with `fillAuto` repeats the flood with gaps 3, 6, 10, 16, 24 px when the fill reaches the window border, keeps the plain result if none contains it), `snapSeed` (a tap on a thin shape moves to the nearest open area <= 14 px), shared by Wand (without the automatic ladder).
- File format: **V17** (`Store.kt`). Strokes drawn inside a selection write flag 32 of the second flag byte followed by the runs; ink kind 4 writes runs + a counted float array. V1-V16 still load unchanged; older builds cannot open V17 files.
- Files: `Warp.kt` (new), `SelectionFill.kt` (new), `SelectionBar.kt` (new), `Tools.kt`, `Tiles.kt`, `Engine.kt`, `Store.kt`, `Canvas.kt`, `Editor.kt`, `Panels.kt`, `MainActivity.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched.
- Validation: source review and bracket balance only. NOT compiled, NOT run; this is a large compile-blind change, expect a first build round with small fixes.
- Known limits / follow-up: (a) Selection tint stays (blue); the shimmer is added on top. (b) `drawBitmapMesh` has no anti-aliased edge. (c) Warp / Puppet apply to the lifted selection of every selected cel with the same mesh. (d) Puppet pins snap to the nearest mesh point (24 x 24 cells). (e) Held-key add / erase needs a hardware keyboard that reports modifier meta state with pen events; the toggle buttons are the fallback. (f) Strokes drawn inside a selection store its runs per stroke (shared in memory, repeated in the file). (g) Stroke-aware refinement is skipped for windows over 3 M pixels or when the cel is not built yet. (h) Fill's automatic gap ladder can take a few hundred ms on a full-HD window when the fill is a legitimate fill of the outside. (i) Selection-type "logos" are drawn glyphs, not vector assets.
- Test: rectangle / lasso / point / wand with Add and Erase (buttons and Shift / Alt); shimmer while zoomed / rotated; transform then Warp: drag points at several Increments and Strength values, Apply, undo / redo, save and reopen (V17); Puppet: place 3 pins, bend, remove a pin, Apply; bar follows the selection and stays on screen near edges; draw / erase / shape / fill inside a selection, then undo, save, reopen and replay; Fill on an outline with a small gap, on a line, and on the outside of a drawing; Wand with gap drag.

## 2026-11-15 (2) — Performance: large brushes, scrubbing, onion skin (second SM-P610 report, build 0.4 / 144)

- AI model: Claude Sonnet 5.5
- Category: Performance
- Evidence (`superframe-performance-1794729985.zip`, scenario E, 114 s): 1095 UI frames, 95.2 % over budget; `input_ms` avg 58 / 52 ms in the first 20 s with a 2.0 s maximum (the earlier Recovery fix is in this build, so that is not the cause); one `tiles/rebuild-ui` event of 57 473 ms for a 50-stroke cel (strokes 0->50) = the 57.7 s frame; `rebuild-bg` events of 17-59 ms for ONE-stroke cels roughly every 50 ms at 52-54 s with tile memory 124-145 MB of a 128 MB budget (sets evicting each other; `onion-warmup` thread up to 108 % of a core); startup log: 7 cuts read 612 ms but thumbnails 18 736 ms (the 2026-11-15 media-copy change was not the startup cost); native heap 425-555 MB, PSS max 757 MB; GL compositor modest. The recording again contains no playback.
- Changes: (1) `Session` (`Engine.kt`): dabs are stamped on one background thread (`StrokeWorker`, "stroke-stamp"); the pen-event thread only smooths and queues points. `finish` and `release` wait for the queue (`onWorker`), so commit / undo backup see every dab; `Session.onStamped` (set in `Canvas.kt`) bumps `tick` so the canvas redraws when stamped pixels are ready. (2) `TileCache.getForDraw` (`Tiles.kt`): UI-thread draw paths (`Canvas.kt` layer pass, `buildGlScene` layers below the active one) never replay strokes: a ready set is drawn, a set that only lacks the newest strokes is drawn as it is, a missing / must-clear / building set is skipped; the rebuild runs on `OnionWarmup` (new urgent queue `tile-build`, no 1.5 s cool-down) and the canvas / GL scene redraw through `OnionWarmup.ready`. `TileSet.building` marks a replay in progress (the set lock is held for all of it, so drawing such a set would block the UI). (3) `TileCache.floorBytes` (set in `MainActivity.onCreate` = RAM / 12, 128-320 MB; 4 GB tablet -> 320 MB): the editing tile budget is `max(CelCache.budget + 32 MB, floor)`; was a fixed 128 MB. (4) `Editor.load`: Cut thumbnails render on a low-priority background thread instead of in `onCreate`.
- Reason: User report that large brush strokes, scrubbing and onion skin are laggy; diagnostics attached.
- Files: `Engine.kt`, `Tiles.kt`, `Canvas.kt`, `GlLayers.kt`, `Editor.kt`, `MainActivity.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, `gradle.properties`, manifest. File format unchanged (V16).
- Validation: source review, brace balance check, diff review. NOT compiled, NOT run. (1) is the main behaviour risk: threading of stamping, ordering with commit / undo / cancel, and visible trailing of the stroke behind the pen.
- Known limits / follow-up: (a) A frame whose cels are not built yet shows empty / partial until the build finishes (blank flash when opening a Cut or jumping far). (b) Per-dab cost of large soft brushes is unchanged (software Skia into 512 px tiles, many tiles per dab); it is now off the UI thread but a very large brush can still trail the pen. If it does, the next options are dab spacing for large sizes or batching. (c) Fill / wand / pen-down `TileCache.get` still rebuild synchronously when the set is missing. (d) Playback now draws a frame without cels that are not built yet instead of waiting. (e) Memory: the 320 MB floor raises the native heap ceiling on a 4 GB tablet (was PSS max 757 MB); watch for low-memory kills. (f) 57 s for 50 strokes means some strokes cost ~1 s to replay: find which brush settings (size / softness / texture / wet / capped) are involved with the `tiles` events and a stroke-level timer.
- Test: draw large soft strokes continuously (pen should stay responsive; stroke follows closely); scrub the timeline and flip frames with onion skin on (no multi-second freezes; cels fill in); open the app with several Cuts (list appears quickly, thumbnails fill in); undo / redo after big strokes; lift the pen quickly after a long stroke (nothing missing); check the HUD / event log for `rebuild-ui` events.

## 2026-11-15 — Performance: stalls found in the SM-P610 diagnostics report (build 0.4 / 143)

- AI model: Claude Sonnet 5.5
- Category: Performance
- Evidence (`superframe-performance-1794727303.zip`, scenario F label, 299 s): the recording contains NO playback (MediaSync: 0 ticks, 0 players, 0 seeks); it is an editing session with the GPU layer plane on. 3591 UI frames, 93.7 % over 16.67 ms. Stalls were on the UI thread: `input_ms` up to 1386 ms in bursts, `draw_ms` spikes of 1.3 s / 9.0 s / 68.9 s / 9.3 s (single frames up to 78 s). While idle (90-130 s, `input_ms` ~1) frames still came at ~20 / s with ~6-9 ms command issue + ~10 ms GPU each. Cold starts took 7.4 / 7.9 / 10.5 / 15.4 s between "Loading saved editor data" and "loaded" (warm `onCreate` 2 ms). Native heap sat at 455-538 MB, GC 121 collections / 13.5 s, process CPU avg 77 %, app main thread 57 % of a core, GL compositor itself cheap (commands avg 1.06 ms, swap 0.52 ms).
- Changes: (1) `Recovery.snapshot`: the cut is still serialized on the main thread (consistency rule kept) but into memory; file write, `fsync`, rename and pruning now run on a single background thread (`recovery-io`). Obsolete snapshots (normal save / clear happened after serialization) are dropped by the writer (`Recovery.invalidated`); a failed write makes the cut dirty again. Previously the whole write + fsync ran on the UI thread 2.5 s after every editing pause, which fits the `input_ms` bursts when the pen went down again. (2) `Canvas.kt`: the pen hover cursor (and the wand / fill gap label) moved from the big canvas draw lambda into its own small overlay `Canvas` (zIndex 3, no pointer input). Hover state was read inside the big draw, so every hover report redrew page, grid and all tiles. (3) `GlLayers.kt` `buildGlScene`: previous / following onion cels use `TileCache.peekReady` + `OnionWarmup.request` instead of `TileCache.get` (which rebuilt a whole cel synchronously on the UI thread); the scene re-runs when a warm-up finishes. Same rule the 2026-11-14 (4) fix applied to `Canvas.kt`. (4) `Store.read`: an embedded media clip is not rewritten when the app-owned copy `media_assets/<cut>_<layer>.media` already exists with the same length (new `skipExactly`); before, every cold start copied every embedded clip again. (5) Instrumentation only: `TileCache.get` reports tile-set rebuilds of 8 ms or more to the profiler event log (`tiles` / `rebuild-ui` or `rebuild-bg`, with tile memory vs budget); `Editor.load` logs the cold-start phases (brushes, cuts read, thumbnails, recovery check) to the application log.
- Reason: User asked to optimize the app from the diagnostics report.
- Files: `Recovery.kt`, `Canvas.kt`, `GlLayers.kt`, `Store.kt`, `Tiles.kt`, `Editor.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, `gradle.properties`, manifest. File format unchanged (V16). No project data layout change.
- Validation: source review, brace / parenthesis balance check, full diff review against the supplied ZIP. NOT compiled, NOT run (no Android SDK / Gradle here). Causes (1) and (2) are supported by the report plus the code path; (3) is a real UI-thread rebuild path but the report has no counter proving it fired; (4) is inferred from the code (cold-start time not split in the report); the instrumentation in (5) exists to settle this.
- Known limits / follow-up: (a) Serialization of the cut for a snapshot still runs on the main thread and grows with the cut. (b) The tile budget while editing is only 128 MB (`CelCache.budget` 96 MB + 32 MB, `Engine.kt`), while a single dense frame with several layers plus onion can exceed it, which would evict and rebuild cels on the UI thread (`TileCache.trim`). NOT changed: the new `rebuild-ui` events will show whether it happens. (c) The 9-69 s draw stalls were not attributed with certainty; check whether they disappear. (d) Cold start still reads every cut on the main thread in `onCreate`; moving it off-thread needs a loading state and was not done. (e) A same-length stale `media_assets` file with the same cut / layer id would now be reused instead of overwritten (not expected: the copies are written once and never edited). (f) This recording did not exercise playback; repeat Scenario F with playback running for playback numbers.
- Test: open the Cut list and note cold-start time (compare the new "Startup load" log line); draw, pause 3 s, draw again repeatedly (no hitch when the pen goes down); hover the pen without touching (cursor still follows, HUD frame rate idle should drop); turn onion skin on with the GPU plane and flip frames; close the app from recents with a Cut that has audio / video and reopen (clips present); force-stop the app after editing and check that the recovery dialog still offers the work; export a new Scenario F ZIP while really playing.

## 2026-11-14 (4) — Fixes: undo, onion skin, image import, flatten, media on reload, brush selection

- AI model: Claude Sonnet 5.5
- Category: Fixes / Performance
- Summary: (1) Undo lag: undoing a stroke removed it and made the tile set replay EVERY stroke of the cel on the next frame. New `StrokeBackup` (`Tiles.kt`): while a brush stroke is drawn, `TileSet.recorder` copies each tile on first write (`noteWrite` from `TileSink` and `compositeFrom`, `saveAbsent` for new tiles); `Cut.commit` keeps it (`StrokeAct.backup`, `UndoPixels` budget 80 MB, oldest dropped first); `Cut.undo` puts the old pixels back with `TileSet.restoreFrom` when the stroke is the newest of its cel and the set is in the expected state (`count == size + 1`), otherwise the old rebuild runs. Redo still replays one stroke. Fill / transform / shape strokes (`commitOp`) and strokes older than the budget keep the rebuild. (2) Onion skin lag: the canvas rebuilt neighbouring cels' tile sets on the UI thread. It now draws only sets that are already up to date (`TileCache.peekReady`) and asks `OnionWarmup` (one background thread) to prepare the others; the canvas redraws when one is ready (`OnionWarmup.ready`). Same for persistent reference cels. (3) Image sequence import and Flatten: `TileSet.stampImage` had been changed to read `Ink.data` only as an external reference-image path, but imported images and flattened layers (`imageInk`, `flattenDownImpl`) store PNG / JPEG bytes there, so nothing was drawn (and every rebuild turned megabytes of PNG into a path string, which could exhaust memory). `stampImage` now detects encoded images (PNG / JPEG / GIF / WEBP header) and decodes them through the new `InkImageCache` (identity-keyed LRU, 96 MB); anything else is still a reference path. (4) Video / audio layers empty after reload: `Store.read` built the layer with `apply { mediaUri = mediaUri; mediaDurationMs = mediaDurationMs; ... }`, where the local variables shadow the layer's properties, so the values were assigned to themselves and the layer kept its defaults. Now `this.mediaUri = mediaUri` etc. (5) Brush selection: `brushIdx` / `eraserIdx` were never saved; `Editor.selectBrush` saves them in `brushes.json` (`brushIdx`, `eraserIdx`), `saveAll` writes them too, `loadBrushes` reads them.
- Reason: User report of six defects (undo / redo lag, onion skin lag, broken image sequence import, Flatten w/ layer below not merging and sometimes crashing, video and audio layers empty after reopening, selected brush resetting).
- Files: `Tiles.kt`, `Engine.kt` (Session), `Editor.kt`, `Canvas.kt`, `Store.kt`, `Panels.kt` (one call), `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest. File format unchanged (V16).
- Validation: source review and a brace / parenthesis balance check. No compiler or device: NOT compiled, NOT run. The causes of (3), (4) and (5) were found by reading the code and are certain to be defects; the cause of (1) and (2) is the most likely one but was not profiled, so check the speed on the tablet.
- Known limits / follow-up: (a) A stroke's undo backup is kept only for the latest ~80 MB of strokes of the session (a few tiles per stroke); older strokes still redraw the whole cel on undo. (b) The first onion frame after a jump appears a moment late. (c) Flatten still renders each upper frame at full cut size in memory; very large cuts can still be heavy. (d) Cuts that were reopened and saved by the build with the media bug saved the audio / video layers EMPTY (no media path, default duration). The reader now repairs them when the unpacked copy `media_assets/<cut>_<layer>.media` from that first reload still exists: it links the file again and re-reads the duration (the clip starts at frame 0, the original start frame is lost). Please confirm with your existing cuts; if a clip is missing there, re-import it.
- Test: draw 10 strokes in a dense frame, undo and redo several times (should be instant); turn onion skin on with 3 previous and 3 next frames and flip through frames; import an image sequence and a single image, play and check the pictures; draw on two layers and Flatten w/ layer below, check it merges and undoes; add an audio and a video layer, close the app from recents and reopen (clips and audio must be there); pick the 5th brush, close the app from recents, reopen.

## 2026-11-14 (3) — Brush: imported tips and textures, tip rotation / roundness, flow and per-stroke opacity, wet pressure, new brush page (format V16)

- AI model: Claude Sonnet 5.5
- Category: Features / Format / UI
- Summary: (1) Imported stamps and textures (`BrushAssets.kt`, new): image -> grey-scale mask PNG, CRC-32 id, library in `filesDir/stamps`, picked with a + button in the brush page; `.inbrush` files carry them. (2) Tip rotation, direction-following and roundness; texture scale, invert and moving-with-stroke (`PathStamper`). (3) `Brush.flow` and `Brush.capped`: with capped on, opacity is a ceiling for the whole stroke (own tile set per stroke, composited at the end; live preview in `DrawingCanvas`; rebuild in `TileSet.stamp`). (4) `Brush.wetPressure` scales the wet mix with the pressure -> flow curve. (5) Brush detail page: tips and textures are picture buttons, the brush preview stays pinned while the settings scroll, settings are in collapsible sections (`Section`, `Editor.brushSections`); the opacity curve is now labelled pressure -> flow. (6) `.inb` V16: a second flag byte per stroke (tip shape, flow / cap, texture controls, stamp ids, wet pressure) and an asset table at the end of the cut; the reader still reads V1-V15. Built on the uploaded `Superframe_multiframe_transform_onion_skin.zip` (which already contained the previous brush engine batch and format V15).
- Reason: User request: brush batches A and B, picture tip previews, a preview that stays visible while scrolling, collapsible categories.
- Files: `BrushAssets.kt` (new), `Brush.kt`, `Engine.kt`, `Tiles.kt`, `Store.kt`, `Editor.kt`, `Canvas.kt`, `Panels.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest. Data format CHANGED on purpose (writer V16); older builds cannot open V16 files.
- Validation: source review and a brace / parenthesis balance check only. No Kotlin compiler, Gradle or device was available. NOT compiled, NOT run.
- Known limits / follow-up: (a) Capped strokes need one temporary tile set per stroke (up to a few MB for a long stroke) while drawing and while a cel is rebuilt; check drawing and playback speed with a capped brush on the tablet. (b) Wet mix reads the colour under the brush but still does not drag paint into empty canvas. (c) Wet pressure follows the pressure -> flow curve, so a flat curve gives no effect. (d) Forgetting an imported stamp does not touch existing strokes. (e) The rotation slider cannot type a minus sign if the numeric field has no minus key. (f) Texture preview pictures use the texture strength 100 %.
- Test: import a black-on-white stamp as a tip and draw; turn on "Tip follows the stroke direction" and draw curves; set Roundness 30 and Rotation 45. Import a paper-like photo as a texture, then try scale, invert and "moves with the stroke". Turn on "Opacity limits the whole stroke" with opacity 50 and scribble over the same area: it must stay one flat tone, live and after lifting the pen, and after saving and reopening the cut. Set Wet mix 80 and Pressure -> wetness +70 / -70 over another colour. Collapse and open the groups, scroll the page and watch the preview stay put. Save, reopen, check the stamps still draw; export a brush and import it again.

## 2026-11-14 (2) — Multi-frame selection transform and onion-skin depth controls
- AI model: GPT-6
- Category: Features / Canvas
- Summary: A transform started while the playhead cel is included in timeline multi-selection now lifts the same selected pixel region from every selected editable drawing cel and applies the same transform matrix to all of them. The playhead cel remains the primary gizmo reference; each selected cel retains its own source pixels and receives its own region-transform operation. Onion skin now has a detailed-options toggle for showing neighbouring drawings across all visible drawing layers or only the active drawing layer. Onion drawings progressively fade with distance from the playhead (each additional cel is rendered at 68% of the previous distance's opacity).
- Project format: unchanged (V15); multi-frame transforms use the existing region-transform operation. Onion layer scope is an editor preference and is not stored in the Cut.
- Validation: source-level review and archive integrity check only. Android/Gradle compilation and device validation were not available in this environment.
- Not completed in this batch: Warp and Puppet Warp deformation modes, transform-toolbar anchoring to the transformed selection, stroke-aware selection and the other previously listed outstanding items. No inactive placeholder controls were added.

## 2026-11-14 (1) — Timeline annotations, multi-pick mode, onion depth and eyedropper
- AI model: GPT-6
- Category: Features / Format
- Summary: Added per-cel persistent-canvas annotations and inbetween labels. Persistent cels render as a subdued red canvas reference and are marked red on the timeline; selected cels can be marked persistent or reset. Selected cels can be labelled as inbetweens using the preceding non-selected frame number plus a lower-case alphabetic suffix (for example `1a`, `1b`). Timeline selection mode now supports single-tap toggling and direct drag-marquee selection for non-contiguous frames and diamond keys; the existing double-touch marquee remains available when selection mode is off. Detailed onion-skin options now independently set the number of previous and following drawings (0–8). Added an eyedropper tool to the toolbar and fullscreen radial tool selector, plus an eyedropper shortcut button in the minimal fullscreen upper bar. Added F6 (rebindable) to toggle all reference-image layers together. `.inb` writer is now V15; V15 stores per-cel persistence and inbetween labels while retaining V1–V14 reading. Undo snapshots include both annotations.
- Not completed in this batch: multi-frame selection transforms; transform-widget-follow behavior; Warp and Puppet Warp; stroke-aware selection; fill algorithm improvements; detailed key double-tap-drag refinements beyond the existing marquee; and the reported main-menu image-sequence import defect. These need separate implementation and validation rather than placeholder UI.
- Build / device validation: Not run. The supplied environment has no Gradle executable or Gradle wrapper. Do not treat this ZIP as a verified build.

## 2026-11-13 (8) — Brush engine: spring stabilizer, velocity taper, dab jitter, wet mix (format V14)

- AI model: Claude Sonnet 5.5
- Category: Feature (drawing) / data format
- Summary: From the user's blueprint, adapted to the CPU stamping engine. (1) `Stabilizer` method 5 "Spring (string)": mass-spring-damper nib with a string dead zone, time-based substeps, and a settle step on pen lift (`Session.finish`). (2) `Brush.velSize`: velocity taper (log-normalized speed scales the width in `Session.emit`, baked into the stroke widths). (3) Dab jitter: `scatter`, `sizeJitter`, `angleJitter`, `opacityJitter` in `PathStamper.dab` with a seeded generator and `Ink.seed`; rotated shaped dabs use a larger scratch bitmap. (4) Wet mix: `wet`, `wetLength`, `DabSink.sample` (implemented in `TileSink`), carried colour with distance decay. (5) Seven new sliders in the brush detail page; `Brush` JSON gets the new keys (old files load with defaults). (6) `.inb` V14: per stroke one flag byte; when set, the jitter values + seed and the wet values follow. The reader still reads V1-V13.
- Reason: User request: brush engine upgrade (roadmap item after crash recovery, retiming removal, curve view, waveform and scrub).
- Files: `Brush.kt`, `Engine.kt`, `Tiles.kt`, `Store.kt`, `Canvas.kt` (seed on transformed and shape strokes), `Tools.kt` (seed on split strokes), `Panels.kt` (sliders), `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, input policy. Data format CHANGED on purpose: writer V14; older builds cannot open V14 files.
- Validation: hand review and a brace / parenthesis balance check. NOT compiled, NOT run on a device.
- Known limits / follow-up: (a) The blueprint's GL stamp shader, instanced draws and framebuffer smudge were not built (stamping is CPU, tiles are CPU bitmaps); wet mix is colour pickup, not paint dragging into empty canvas. (b) Pressure sensitivity / minimum size sliders were not added: the pressure -> size curve covers them. (c) Velocity taper is measured in document pixels per second, so it feels different at different zoom levels. (d) Previews in the brush list do not simulate speed or wet mixing. (e) The jitter of a live stroke and of its rebuilt version are the same pattern, but the rebuilt path is the simplified one, so dab positions can differ by a fraction of a pixel.
- Test: pick Spring (string) at 40 and draw circles, lift the pen: the line should finish smoothly without a jump. Velocity taper at 70: a quick flick should be visibly thinner than a slow line. Etched or square tip with Scatter, Size and Rotation jitter: strokes should look irregular and, after saving and reopening the cut, look the same. Wet mix at 80 over a stroke of another colour: colours should blend and fade back to the ink colour along bare canvas.

## 2026-11-13 (7) — Curve view after reload, channel list on the left, audio decode feedback

- AI model: Claude Sonnet 5.5
- Category: Fix / diagnostics
- Summary: (1) The curve view needed the expanded Camera / Transformation layer to be the SELECTED layer; a reloaded cut selects another layer but keeps `expanded`, so the old rows came back. `Timeline` now picks the selected expanded layer, else the first expanded one (`curveLayer`), and `selCam` / `selFolder` (used by the interpolation buttons) follow it; expanding a layer also selects it. (2) `ChannelPanel` moved to the left (240 dp, header as high as the ruler), like the old layer column. (3) Audio: `AudioPeaks` now exposes `status` (analyzing / "no waveform: reason") that `TimelineMediaTrack` appends to the clip label; `decode` throws readable reasons (no audio track, unsupported PCM encoding, decoder stopped, nothing decoded), supports float PCM, feeds input without blocking and has a stall watchdog (about 6 s without progress); `CrashDiagnostics` logs decode start (mime, rate, channels), result (samples, rate, time) or failure; `AudioScrub` logs once per 10 s when no decoded audio is ready.
- Reason: User report: curve view reverted to the old rows after reloading a cut; channel list should be on the left; waveform and audio scrub did nothing. The cause of the audio problem is NOT known yet: nothing in the code explains it, so this build adds the feedback needed to find it.
- Files: `Panels.kt`, `CurveView.kt`, `Waveform.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, `.inb` format (V13).
- Validation: hand review and a brace / parenthesis balance check. NOT compiled, NOT run on a device.
- Test: reload a cut with an expanded Camera: the curve view should open. Add or open an audio layer: the clip label should say "analyzing audio…", then the waveform appears; if it says "no waveform: ...", send the text and the diagnostics log. If the waveform appears but scrub is silent, check the speaker button and the volume, and send the log (look for "Audio scrub").

## 2026-11-13 (6) — Curve view for camera / transformation keys, waveform, audio scrub; Retime removed

- AI model: Claude Sonnet 5.5
- Category: Feature (timeline) / Removal
- Summary: (1) Removed the Retime button, dialog and `Retime.kt` (redundant with the duration pad and the interpolation buttons, user request). (2) New `CurveView.kt`: expanding a Camera layer or Transformation folder now replaces the layer column + track rows with a curve view modelled on the user's Calipeg screenshot: ruler on top, graph with one curve per channel (X red, Y green, scale blue, rotation white), orange playhead band, diamonds on the curves, and a channel panel on the right (< back button, eye, colour dot, key-at-playhead toggle, name, value at the playhead). `TimelineRuler` became `internal`; `evalChanF` (fractional-frame evaluation) was added to `Camera.kt`. Dragging a key moves it in time or in value (axis locks on the dominant direction) as one undo step; empty-space drag pans, tap moves the playhead. (3) New `Waveform.kt`: background decode of audio layers (`AudioPeaks`), waveform drawn inside the audio clip, and audio scrub (`AudioScrub`, `AudioScrubHost`, `Editor.audioScrub`, speaker toggle in the timeline bar) with pitch-preserving grains.
- Reason: User request after testing crash recovery (build with recovery + retiming): drop retiming, make the camera / transformation detail timeline look like Calipeg's, add waveform and audio scrub (roadmap item 1).
- Files: `CurveView.kt` (new), `Waveform.kt` (new), `Retime.kt` (deleted), `Panels.kt`, `Camera.kt`, `Editor.kt`, `MainActivity.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, `.inb` format (still V13; nothing new is saved), input policy.
- Validation: hand review plus a brace / parenthesis balance check of the touched files. NOT compiled (no Kotlin compiler in the sandbox), NOT run on a device. Expect to fix compile errors from the first CI log.
- Known limits / follow-up: (a) Channels use separate value ranges, so curves of different channels cannot be compared by height; an overlay mode on a shared axis is not built. (b) There is no Opacity channel (it is not part of the camera / folder data). (c) Values in the channel list are read-only; editing is by dragging keys or on the canvas. (d) Hold segments are drawn as steep lines. (e) Scrub: audio with a decode rate below 22050 Hz and non-16-bit PCM are not supported; very long audio is capped at 20 minutes; the decoded audio of a cut stays in memory (up to 96 MB total). (f) Scrub fires on any hand-made playhead change, including frame-step buttons and drawing-time frame changes. (g) The waveform sits inside the audio clip (44 dp tall); a taller audio row is not built.
- Test: add an audio layer, wait a moment, the waveform should appear in the clip; drag the ruler over it (speaker button on) and listen, also backwards. Expand a camera layer: curves, panel, drag a diamond sideways then up, undo each; tap the < button; tap the diamond button on a row to add / remove a key at the playhead.

## 2026-11-13 (5) — Crash recovery snapshots and range retiming

- AI model: Claude Sonnet 5.5
- Category: Feature (robustness + timeline editing)
- Summary: (1) Crash recovery: new `Recovery.kt`. While a cut is open and has unsaved edits (`Cut.edits` counter, bumped by commit / pushStruct / undo / redo / clear / reference matrix), a snapshot is written to `filesDir/recovery/<cutId>_<ms>.rec` (temp file, fsync, rename) once the user pauses 2.5 s, or 60 s after the first unsnapshotted edit, never while playing; the last 3 per cut are kept. `Store.write` got an optional `embedMedia` flag (default true, format unchanged): snapshots write media layers with size 0 so the reader keeps the app-owned `file://` copy. The normal save (`Editor.saveAll`, `Editor.close`) deletes the snapshots once the saved file exists and no `.tmp` is left; `Editor.remove` also clears them. On a fresh process start `Editor.load` computes `recoveryPending` (snapshot newer than `cut_<id>.inb`); `MainActivity` shows "Recover unsaved work?" (Restore / Discard) before the What's new window. Restore reads the newest readable snapshot, replaces or adds the cut and saves it as the normal file. (2) Range retiming: new `Retime.kt` plus `RetimeDialog` and a SwapHoriz button in the timeline toolbar (shown when 2+ frames on a layer or 2+ key times on a layer are selected). Length 25-400 %, ease -100 (slow end) .. +100 (slow start), reverse. Frames: reorder when reversed, redistribute durations (total follows the percentage, spacing keeps the old proportions then warps with the ease curve, each frame at least 1). Keys: one time map per layer for all four channels so they stay aligned; reversed segments flip smooth in / smooth out; selected keys that land on an unselected key replace it. One undo step via `cut.record`.
- Reason: User roadmap items 5 (snapshot versioning and crash recovery) and 3 (timing handles).
- Files: `Recovery.kt` (new), `Retime.kt` (new), `Editor.kt` (edits counter, save / remove hooks, recoveryPending), `Store.kt` (embedMedia flag), `Panels.kt` (dialog + button + icon import), `MainActivity.kt` (autosave driver, recovery dialog), `WhatsNew.kt` (note 5), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, `.inb` format version (still V13), input policy.
- Validation: the retiming math (`retimeDurations`, key time map) was mirrored in Python and checked for identity, proportional stretch, minimum 1 frame, exact totals and strictly increasing key times. The Kotlin was reviewed by hand only. NOT compiled, NOT run on a device (no Kotlin compiler in the sandbox).
- Known limits / follow-up: (1) Snapshots are serialized on the main thread (the cut is only mutated there); a very large cut may hitch for a moment when a snapshot is taken, so watch for it while drawing. (2) Only the newest snapshot is offered for restore; a version picker is not built. (3) Hold segments are approximate when keys are reversed. (4) Recovery covers foreground crashes and kills; background kills are covered by the existing onStop save. (5) Not done: the drag handle on the timeline selection (the dialog replaces it for now), and a live preview while the sliders move.
- Test: draw, wait 3 s, force-stop the app from Settings, relaunch: the dialog should list the cut and Restore should bring back the last stroke. Select 4+ frames with different durations: Retime at 200 % doubles the block, 50 % halves it, Reverse flips the drawing order, undo restores everything. Same on camera keys: channels must stay aligned.

## 2026-11-13 (4) — Playback no longer uses the GPU layer plane

- AI model: Claude Sonnet 5.5
- Category: Performance
- Summary: `gpuPlan()` (`GlLayers.kt`) now returns -1 while `cut.playing`, so playback draws every layer with the Compose renderer. Editing (drawing with layers below the active layer, onion skin) still uses the GPU plane. The unused `cut.layers.size` playing branch was removed.
- Evidence (two user diagnostics, SM-P610, build 134, scenario F, video layer 50%): compositor ON vs OFF: UI frame avg 28.19 vs 25.18 ms; GC 431 vs 14 collections; process CPU 154% vs 104%; HeapTaskDaemon 32.8% vs gone from the top threads; timeline tick max 446 ms vs 44.85 ms (avg 43.02 vs 41.67 ms). GC rate followed the GL frame rate, which points at the per-frame hardware Bitmap wrap (cause inferred, not proven). The same recordings show the video layer and its opacity are not the bottleneck.
- Files: `app/src/main/java/com/exemple/inbetween/GlLayers.kt` (`gpuPlan`), `WhatsNew.kt` (note 4), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, `.inb` serialization, `Canvas.kt`, `MediaSync.kt`.
- Validation: source review of the empty-scene path (`gpuPlan` -1 -> `buildGlScene` returns `GlScene.EMPTY` -> the renderer delivers a cleared frame, `shownK` becomes -1, the same path used when the active layer is the bottom layer). NOT compiled, NOT run on a device.
- Known limits / follow-up: (1) On play / stop the plane may show a stale frame for one or two frames while Compose draws all layers (existing double-draw design); check there is no visible flash or leftover. (2) Frame cost is still ~25 ms with the plane off; remaining suspects are listed in `PROJECT_MEMORY.md`. (3) Re-record scenario F with the switch ON to compare against build 134: GC should now be low in both settings. (4) Root-cause fix for the plane (cache the wrapped Bitmap per buffer, API 31+) was not attempted.

## 2026-11-13 (3) — Back out video opacity experiments (video went dark); add "What's new" window

- AI model: Claude Sonnet 5.5
- Category: Fix (rendering regression) / Feature
- Summary: (1) User screenshot: with the video layer at 25% opacity the video looked dark (its dark source content faded toward black, not toward the white page). Removed `TextureView.isOpaque = true` (added 2026-11-13, GPT-6) and `compositingStrategy = ModulateAlpha` (2026-11-13 (2), this model) from `Media3VideoReferenceLayer`; the layer is back to the default TextureView and default compositing with `alpha = layer.opacity`. (2) New startup "What's new" dialog shown once after an update (see `WhatsNew.kt`, hooked in `MainActivity` as `else -> { App(ed); WhatsNewHost() }`). It compares the highest `ReleaseNote.id` with the stored `seen_id`; first run shows the 3 newest notes. Seeded with three notes (Nov 11, 12, 13). `AI_WORKING_AGREEMENT.md` §8 now requires a note per user-visible change.
- Reason: User report (dark video at partial opacity) and request for an update changelog window.
- Diagnosis caveat: the cause is inferred, not reproduced. The darkening matches an opaque-flagged texture layer being modulated by alpha against black; the opaque flag is the prime suspect and `ModulateAlpha` was removed too so the state matches the earlier behaviour. Which of the two caused it was not isolated.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `WhatsNew.kt` (new), `MainActivity.kt` (one line), `PROJECT_MEMORY.md`, `AI_WORKING_AGREEMENT.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration (versionName stays 0.4), manifest, `.inb` serialization.
- Validation: source and diff review only. NOT compiled, NOT tested on a device.
- Known limits / follow-up: Test video opacity at 25 / 50 / 75 / 100% (should lighten toward white); if still dark, the cause is elsewhere (inspect GPU plane / page order). Test the dialog: appears on first launch after install, not again after "Got it", appears again after a new note id is added. Notes are hand-written, not generated from this file. There is no menu entry to reopen old notes yet.

## 2026-11-13 (2) — Video layer: apply partial opacity without an offscreen buffer

- AI model: Claude Sonnet 5.5
- Category: Performance / Android UI composition
- Summary: The video image's `graphicsLayer` in `Media3VideoReferenceLayer` now sets `compositingStrategy = CompositingStrategy.ModulateAlpha`. With the default `Auto` strategy Compose renders the layer into an offscreen buffer whenever `0 < alpha < 1`, i.e. a full extra copy of the video image every frame while the layer opacity is below 100%. The layer contains a single opaque TextureView, so modulating alpha on that one draw gives the same result without the buffer.
- Reason: Suspected RenderThread / GPU cost when the video layer opacity is below 100% (a candidate raised in a previous session). At 100% opacity `Auto` already uses no offscreen buffer, so this change has no effect there.
- Slider path reviewed, no change needed: `Layer.opacity` is a `mutableFloatStateOf`, read only inside the `graphicsLayer` block, so dragging the opacity slider does not recompose the video layer; `onOpacityChange` takes one `cut.snap()` per drag.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt` (one import, one property), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle / SDK / signing configuration, manifest, `.inb` serialization, `Panels.kt`, `MediaSync.kt`, input policy.
- Validation: source review and diff review only. Compose BOM 2024.09.00 (UI 1.7) provides `CompositingStrategy.ModulateAlpha`. NOT compiled and NOT tested on a device.
- Known limits / must test on the SM-P610: (1) correctness first: with the video layer at 25%, 50%, 75% the video must look the same as before (if it appears fully opaque, the TextureView ignores the modulated alpha: revert this line). (2) Check hidden / out-of-range clips, pan / zoom / rotate, drawing above the video. (3) Benefit only exists below 100% opacity: record scenario F at 50% opacity before and after; a recording at 100% will not show any difference.

## 2026-11-13 — Avoid unnecessary video TextureView transparency blending

- AI model: GPT-6
- Category: Performance / Android UI composition
- Summary: Marked the Media3 video `TextureView` as opaque. Decoded reference-video frames are opaque; per-layer opacity is still applied by the enclosing Compose `graphicsLayer`, so the visual opacity control is preserved. This lets HWUI avoid treating the video texture itself as a translucent surface.
- Reason: Recent profiler recordings show sustained RenderThread and GPU composition cost while the custom GL drawing compositor's command / swap times remain low. This is a narrow, reversible attempt to reduce the video texture's composition overhead without changing the TextureView architecture that keeps viewport transforms synchronized with Compose.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (the existing `main`, `master` and `beta` triggers remain intact), Gradle / SDK / signing configuration, `.inb` serialization, drawing input policy and MediaSync synchronization.
- Validation: source assertions confirm the TextureView is opaque and layer opacity remains applied by its parent graphics layer; verified the beta workflow trigger, changed-file scope and ZIP CRC integrity. NOT compiled and NOT tested on a device.
- Known limits: the gain is device / video-path dependent. Check first-frame appearance, hidden / inactive video clips, layer opacity values below 100%, and viewport pan / zoom / rotation on the SM-P610. If any transparent-video formats are intentionally supported later, revisit this assumption.

# Changelog

## 2026-11-12 — Adaptive grid level of detail

- AI model: GPT-6
- Category: Performance / Canvas rendering
- Summary: Added shared adaptive grid spacing for the main canvas and the video-mode Behind-grid plane. When zoomed out, the renderer skips increasingly fine subdivisions by selecting an exact multiple of the configured spacing (1, 2, 5, 10, 20...); it targets at least about 8 screen pixels between lines and roughly 180 lines per axis. Zooming in returns to the configured grid spacing. This replaces the previous behavior that stopped drawing the grid below 4 screen pixels per base interval or abandoned it when the visible span exceeded 500 intervals.
- Reason: The user observed higher reported FPS while interacting with the canvas, particularly when zooming out, and suspected dense grid rendering. The change reduces grid line count and avoids a sudden all-or-nothing grid disappearance at the old thresholds.
- Files: new `app/src/main/java/com/exemple/inbetween/Grid.kt`; changed `Canvas.kt`, `Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (the existing `main`, `master` and `beta` triggers remain intact), Gradle / SDK / signing configuration, `.inb` serialization and playback synchronization.
- Validation: inspected the original Markdown project guidance and current grid paths; verified both canvas grid renderers call the shared helper; checked changed-file scope, source structure and ZIP contents. NOT compiled and NOT tested on a device. The 8 px / 180-line thresholds are initial performance defaults and should be checked visually at several zoom levels.

## 2026-11-12 — Reduce per-frame video transform allocation

- AI model: GPT-6
- Category: Performance / Render-thread allocation
- Summary: The video reference layer now evaluates its Transformation-folder X, Y, scale and rotation channels directly inside the Compose graphics-layer block instead of calling `Cut.transformFor()` and allocating a temporary `GroupTransform` on every playback frame. The same `evalChan` interpolation functions and default values are retained. Updated the current architecture note to accurately describe the TextureView implementation and why a separate SurfaceView / GLSurfaceView is not used.
- Reason: The latest profiler still shows high RenderThread CPU and expensive ordinary frames, while GL command issue, swap and explicit GPU wait are low. This is a narrow allocation reduction in the per-frame video transform path; it is not expected to solve the broader Compose / TextureView composition cost by itself.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (already triggers on `main`, `master` and `beta`), Gradle scripts, `gradle.properties`, SDK / Java configuration and signing configuration. No `.inb` format or playback synchronization change.
- Validation: Source-level comparison confirms the same folder lookup, four channel evaluations, defaults, media transform composition, scale clamp and opacity behavior. Diff and ZIP structure reviewed. No Gradle / Android SDK build or on-device benchmark was available here; performance impact remains to be measured on the tablet.
- Known issues / follow-up: This removes one small per-frame object allocation only. The dominant RenderThread / HWUI cost may still come from TextureView composition and the Compose canvas/window rendering path. Keep TextureView unless a replacement is tested for viewport alignment, z-order and input behavior.

---

## 2026-11-12 (7) — Reduce playback scene and GL cache allocations
- AI model: GPT-6
- Category: Performance
- Summary: Reduced avoidable allocations in the GPU layer compositor hot path. `GlItem` now stores its affine transform as scalar fields instead of allocating a `FloatArray` per scene item. `buildGlScene()` no longer constructs a per-tick folder-transform `HashMap`, `GroupTransform` instances, onion `(index, tint)` pairs/lists, or default `ReferencePose` instances when Out of Peg is off; it resolves folder channels directly and pre-sizes the scene item list. GL texture creation/deletion reuses a GL-thread-owned one-element ID array. Texture-budget accounting is now incremental, and LRU eviction scans for the oldest eligible set without `sortedBy()` and its temporary sorted-entry list.
- Evidence: the 330 s Scenario F report showed HeapTaskDaemon at 37.81% of one core and 1,346 GC collections, while the current scene builder created several temporary collections / transforms on each timeline tick. The same report showed RenderThread at 33.49%, UI frame time avg 25.90 ms (80.46% over 16.67 ms), and GL commands / swap at 0.80 / 0.83 ms average. This patch targets demonstrable app-side allocation churn; it does not establish that all ART GC work originates here.
- Files: `app/src/main/java/com/exemple/inbetween/GlLayers.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (including the `beta` trigger), Gradle scripts / wrapper configuration, `gradle.properties`, manifest, SDK / Java configuration, signing configuration and `.inb` serialization. `Canvas.kt`, `MediaSync.kt` and the intentional playback input lock are unchanged.
- Validation: compared the new affine calculations with the prior `affineAbout()` equations; checked onion Out of Peg defaults, folder transforms, per-item clipping/tint, texture count accounting and LRU eligibility by source review. ZIP structure and CRC integrity checked after packaging. No Android SDK / Gradle build or tablet validation was available; compilation and runtime equivalence remain unverified.
- Follow-up: build on `beta`, then repeat Scenario F for 3–5 minutes with the same media. Compare HeapTaskDaemon CPU, GC collection/time deltas, RenderThread CPU, GL frame count and frame-time p50/p95. If GC remains high, collect an Android Studio allocation recording to identify call sites before further pooling or broad changes. Investigate TextureView / RenderThread composition separately; do not replace the video surface path without a viewport/lifecycle regression plan.

## 2026-11-12 (6) — Playback rendering optimization (drawing lock preserved)
- AI model: GPT-6
- Category: Performance
- Summary: Retained the Compose-side optimization that avoids rebuilding folder-transform maps, clipping checks and walking drawing layers when the GPU compositor has already presented every drawing layer. Compose still renders layers not yet present in the GPU frame and active selection / fill overlays. S Pen input remains intentionally disabled while playback is active; the previous draft's attempt to enable live drawing was contrary to the established interaction design and has been reverted.
- Evidence: Scenario F (231 s) showed 98.25% of UI frames over the 16.67 ms budget, 31.96 ms average frame time, RenderThread at 47.87% of one core and HeapTaskDaemon at 35.22%. The diagnostic was a playback stress test, not a request to draw during playback.
- Files: `Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (including the `beta` trigger), Gradle scripts / wrapper configuration, `gradle.properties`, manifest, SDK / Java configuration, signing configuration and `.inb` serialization.
- Validation: restored the original playback input guards from the preceding performance ZIP and reviewed the remaining Compose rendering diff. No Android SDK / Gradle build or tablet validation was available.
- Follow-up: test ordinary playback and confirm stylus input remains blocked during playback; compare Scenario F frame timing and GC metrics against the preceding build. Obtain allocation-site data before broad GC-related changes.


## 2026-11-12 (5) — First media/render performance optimization pass
- AI model: GPT-6
- Category: Performance / Diagnostics
- Summary: Added best-effort per-thread CPU sampling from `/proc/self/task` to the profiler, including a top-thread summary and `thread-statistics.csv` export (profiler format v2). Reduced audio drift-correction thrashing: audio now requires 3 consecutive samples beyond 300 ms and has a 5 s correction cooldown; video requires 2 samples beyond 120 ms and has a 2 s cooldown. Removed the synchronous per-frame `glFinish()` from the ImageReader EGL producer path; `eglSwapBuffers` now relies on EGL buffer-queue completion fences.
- Evidence: the supplied 98 s Scenario D report showed process CPU averaging 131.55% of one core (so not single-core-only), UI frame time averaging 24.41 ms with 92.17% over budget, GL compositor commands / wait / swap averaging 0.68 / 0.42 / 0.68 ms, and 86 audio drift-correction seeks. Audio player position remained near -190 ms behind the wall clock despite repeated seeks, and each seek caused buffering and stop/start events. Audio underruns and decoder-dropped video frames were both zero.
- Files: changed `PerformanceMetrics.kt`, `PerformanceProfiler.kt`, `MediaSync.kt`, `GlLayers.kt`, `PerformanceHud.kt`, `CrashDiagnostics.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (including the `beta` trigger), Gradle scripts / wrapper configuration, `gradle.properties`, manifest, SDK / Java configuration, signing configuration and `.inb` serialization.
- Validation: reviewed the relevant source paths and diagnostic CSVs; checked patch consistency and performed Kotlin parser-level checks where possible. No Android SDK / Gradle build was available, so this is NOT a verified Android compile. No device test was performed.
- Known risks / follow-up: verify that removing `glFinish()` preserves ImageReader delivery / timestamp-to-viewport pairing on the SM-P610. Verify audible A/V alignment: the persistent audio `currentPosition` offset may be output latency or genuine playback lag; the new threshold prevents repeated disruptive seeks but does not prove or correct the underlying offset. Run scenarios A-F and inspect `thread-statistics.csv` before further thread distribution, tile upload or buffer changes.

## 2026-11-12 (4) — Performance profiler
- AI model: Claude Sonnet 5.5
- Category: Feature (diagnostics)
- Summary: Added the profiler described in the "Superframe - Performance Profiler" plan. HUD (toolbar Speed button) with FPS, frame time / p95, jank vs. the real refresh-rate budget, dropped frames, CPU, RAM, GC, GL prepare / commands / glFinish / swap, audio underruns, video dropped frames, tick and drift numbers; Record / Stop / Reset / scenario A-F / budget choice; persistent REC indicator; export through the existing diagnostic ZIP with `performance-summary.txt`, `performance-events.csv`, `frame-statistics.csv`, `media-statistics.csv`, `gl-statistics.csv`, `system-statistics.csv` (extra), `test-session.txt`. Hooks are observation-only and skipped when the HUD is hidden and no recording runs.
- Files: new `PerformanceMetrics.kt`, `PerformanceProfiler.kt`, `PerformanceHud.kt`; changed `MediaSync.kt`, `GlLayers.kt`, `CrashDiagnostics.kt`, `MainActivity.kt`, `README.md`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files and the project file format untouched.
- Validation: bracket-balance check and read-through only. NOT compiled, NOT run on a device. Plan phase 7 (all scenarios, overhead with profiler on / off, extended recording, release build) is NOT done.
- Known limits: the Media3 AnalyticsListener overrides are written against media3 1.4.1 from memory and may need adjusting if CI reports a signature mismatch. GPU frame time needs Android 12. CPU is per-process (percent of one core), not device load. Ring / log sizes are fixed; see README.

## 2026-11-12 (3) — Build fix: CurveEditor lost @Composable
- AI model: Claude Sonnet 5.5
- Category: Fix (build)
- Summary: CI failed in `Panels.kt` (lines 449-455: "@Composable invocations can only happen from the context of a @Composable function"). The curve-editor rewrite dropped the `@Composable` annotation above `CurveEditor`. Restored.
- Files: `Panels.kt`, `CHANGELOG.md`. Protected files untouched.
- Validation: the log listed only these errors (all one cause); not rebuilt here (no Gradle/SDK). Other errors could appear once these clear.

## 2026-11-12 (2) — Split crash, ruler anchors, curve tangents, softer AA slider
- AI model: Claude Sonnet 5.5
- Category: Fixes / Features
- Summary: (1) Split-frame crash (bug report: "Cannot coerce value to an empty range: maximum 0 is less than minimum 1"): `timelineCutFrame` clamped the cut with `coerceIn(1, dur - 1)` before checking `dur >= 2`; on a one-frame cell the range is empty. The check now comes first. (2) Ruler: levels of detail drop out as you zoom out (frame ticks, then half-second ticks, then seconds thin to every 2/5/10/15/30 s, then minutes and hours), so there are always marks; frame numbers are anchored to the seconds (12, 24, 36, 48 ... then 24, 48, 72 ..., then every 2/5/10 s, then none); second numbers on top thin out the same way and switch to m:ss; minimum zoom lowered so frames can go down to 1 px. (3) Brush curves behave like the Callipeg example: smooth through the points, tap a point to select it (tangent handle + menu: Delete / Tangents set to medium / Tangents set to linear), drag the handle ends to change the tangent, hold a point to delete it, tap empty space to create a point there; presets Linear / Ease in / Ease out / S curve are tangent-based. Brush JSON keeps the old x,y pairs and adds `sizeCurveT` / `opacityCurveT` (x, y, tangent). (4) Anti-aliasing slider: 0 = aliased, then extra feather grows cubically (0.5 -> 0.4 px, 1 -> 3 px) instead of jumping.
- Files: `Panels.kt`, `Editor.kt`, `Brush.kt`, `Engine.kt`, `CHANGELOG.md`, `PROJECT_MEMORY.md`. Protected files untouched.
- Validation: bracket-balance check and read-through only. NOT compiled, NOT run on a device.
- Known limits: half-second and finer ticks are the first to go; whether the thresholds feel right needs a device check. Curve menu position is approximate (anchored at the editor, not the point).

## 2026-11-12 — Build fix: drawIntoCanvas import
- AI model: Claude Sonnet 5.5
- Category: Fix (build)
- Summary: CI failed in `Panels.kt` with "Unresolved reference 'drawIntoCanvas'" (and a follow-on type-inference error). The import pointed at `androidx.compose.ui.graphics.drawIntoCanvas`; the function lives in `androidx.compose.ui.graphics.drawscope`. Import corrected, as already used in `Canvas.kt`.
- Files: `Panels.kt`, `CHANGELOG.md`. Protected files untouched.
- Validation: the log showed these three errors as the only compile errors; the fix is not rebuilt here (no Gradle/SDK). Further errors could appear after this one clears.

## 2026-11-11 (2) — Fixes after on-device screenshots
- AI model: Claude Sonnet 5.5
- Category: Fixes / Features
- Summary: (1) Shaped / textured / feathered brush dabs are now drawn at their real size into a scratch bitmap (vector edges) instead of scaling one 96 px bitmap, which caused the aliasing; new Anti-aliasing slider (0 = hard, 0.5 = normal, 1 = extra soft). (2) Textures: applied per dab by multiplying the dab with a document-anchored 128 px tile (the earlier paint-shader route was unreliable). Stroke storage now keeps tip / texture / strength / anti-aliasing (`.inb` V13; before, they were lost on save, reload and tile rebuild). (3) Pressure curves interpolate smoothly (monotone cubic) instead of straight lines between points. (4) Palette: one button in the minimal timeline opens the palette dialog; palettes can be deleted (also the last one). (5) Clone frames are light green. (6) Letter naming ignores layers whose name is not a single letter. (7) Ruler is a single canvas: frame/second numbers thin out with zoom and never wrap, minor ticks only where they have room; window brackets become plain vertical lines when frames are narrow; frame labels hide when too narrow; the blank tail is drawn as empty frame slots (grouped when narrow). (8) Frame split: pixel-based tolerance (the old fraction-of-a-frame tolerance made it impossible on thin frames), and the right half is now a regular frame, not a clone. (9) During playback the camera frame animation (frame + shading outside) is shown on every layer; on the camera layer the shading is stronger.
- Validation: bracket-balance check only; NOT compiled, NOT run on a device.
- Known limits: Strokes drawn before this update keep the old rendering until their tiles are rebuilt. The camera is NOT applied as a true view-through-camera during playback (video and GPU planes have their own transforms); only its animated frame is shown. Split could not be tested on a device; the cause (tolerance too tight when zoomed out) is a best guess.
- Files: `Engine.kt`, `Brush.kt`, `Store.kt`, `Editor.kt`, `Panels.kt`, `Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.

## 2026-11-11 — Timeline, brush, palette and camera batch (19 requested items)
- AI model: Claude Sonnet 5.5
- Category: Features / Fixes
- Summary: (1) Layer "Select all" + chronometer now retimes every frame. (2) Colored, manipulable interpolation lines between diamond keys in camera and Transformation channel rows (tap = cycle type, vertical drag = ease strength, one undo step). (3) Brush: Ease in / Ease out curve presets next to Linear / S curve; selectable tips (circle, triangle, square, etched); selectable textures (paper, canvas, stipple, hatch) with strength; the old texture slider is now labelled Grain. (4) Brush size slider and the active palette's swatches in the fullscreen minimal timeline next to Opacity. (5) Radial menu opens by dragging out of the puck circle (no long press). (6) Clone frame (shared drawing, own duration, not unique in "Unique frames" export; saved in .inb V12). (7) Camera frame shade drawn as four rectangles so only the outside is darkened. (8) Main-menu "Image sequence" creates a cut from images or a ZIP. (9) Empty frame button (keeps timing, undoable). (10) Dynamic single-letter layer names A, B, C... by drawing-layer position (bottom = A); any other name is left alone, renaming to one letter re-enables it. (11) Rewind (button and shortcut) pans the timeline to frame 0. (12) Blank tail can grow to a full minute. (13) Ripple toggle (arrow + bar) under the timeline. (14) Fit-to-brackets button. (15) Grid button highlighted only when the grid is in front. (16) Rasterized camera path: joining lines are thin and dotted. (17) Palette tool (toolbar button, palettes.json). (18) Double-touch + short vertical drag across a frame splits it (down = both halves keep the drawing, the right one a clone; up = right half empty). (19) Video layer widget bar: rotation magnet and reset; the magnet now snaps video rotation.
- Reason: User feature list.
- Files: `Editor.kt`, `Store.kt`, `Export.kt`, `Engine.kt`, `Brush.kt`, `Camera.kt`, `Canvas.kt`, `Panels.kt`, `MainActivity.kt`, `Keys.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: none beyond a bracket-balance check of each changed file and a read-through of the diff. NOT compiled, NOT run on a device (no Gradle/SDK here).
- Known limits / unverified: A8-mask + BitmapShader texture on soft or shaped tips relies on Skia honouring the paint shader (falls back to untextured color if not). Camera-darkening cause during playback not reproduced; the fix removes the even-odd path. Existing documents keep their layer names (only single-letter names move). Video layers have no flip. "Thin frame" for splitting is read as any frame of 2+ cells.

## 2026-11-10 — GPU layer plane: frames drawn through the live viewport (fix for rotation jitter)
- AI model: Claude Sonnet 5.5
- Category: Rendering / Performance (fix)
- Summary: Rotating showed occasional jitter on the layers drawn by the GPU compositor: a TextureView is presented a frame behind the Compose Canvas, so the viewport baked into the GL output trailed the ink. The GL thread now renders into an ImageReader and every finished frame is handed to Compose as a hardware Bitmap together with the viewport it was rendered with (paired through the EGL presentation time / `Image.timestamp`). The plane's Canvas draws it through "viewport of the frame -> viewport now" in the draw phase, so pan / rotate / zoom is exact in the same frame as the Canvas; GL only supplies pixels. The GL surface is 10 % larger than the workspace on each side so the plane keeps covering the screen while a frame is a little stale. Android 10+ only; older devices, any EGL / GL / frame-wrap failure, or the Cut settings switch use the Compose renderer.
- Reason: User report of occasional jitter when rotating the non-active (GPU-drawn) layers.
- Files: `GlLayers.kt` (renderer, plane, `ScreenView` / `FrameInfo` / `PlaneFrame`; TextureView removed), `PROJECT_MEMORY.md`, `CHANGELOG.md`. `Canvas.kt`, `Tiles.kt`, `Panels.kt`, `Media3Video.kt` unchanged in this step.
- Protected files untouched: all Gradle scripts, `gradle.properties`, `.github/workflows/**`, manifest, signing configuration, SDK / Java configuration. No `.inb` format change.
- Validation: source and diff review, brace balance check. Not compiled, not run (no Gradle / Android SDK here). `ImageReader.newInstance(..., usage)`, `Bitmap.wrapHardwareBuffer`, `Image.hardwareBuffer`, `EGLExt.eglPresentationTimeANDROID` are from memory of the Android API and must be confirmed by the CI build.
- Known issues / follow-up: (1) If the presentation time is not passed through by a driver, frames are paired with the newest known viewport, which can show a one-frame offset during very fast gestures. (2) The plane is slightly soft for the frame or two between a gesture and its re-render. (3) Very fast pans can expose the plane edge for a frame (margin is 10 %). (4) Texture upload per changed `TileSet`, no playback prefetch yet.

## 2026-11-10 — GPU layer compositor for the layers below the active layer (GL stage 3)
- AI model: Claude Sonnet 5.5
- Category: Performance / Rendering
- Summary: Added an OpenGL ES 2.0 compositor (`GlLayers.kt`) that draws the layers below the active layer, and every drawing layer during playback, plus onion skins, into a TextureView plane between the page / video and the drawing Canvas. Compose keeps drawing the active layer, the layers above it, the live stroke, selection and all overlays. Cel tiles (`TileSet`) are uploaded as 512x512 GL textures (cached, LRU 128 MB), composited with layer opacity, folder transforms, onion tint, out-of-peg pose and Bleed clipping. New switch "GPU layer compositor" in Cut settings; EGL / GL failure falls back to the Compose renderer automatically. A visible reference-image layer also forces the Compose path.
- Reason: User asked for a real GL compositor for the layers below the active one, with the best performance during playback with audio + video. Those layers are static while drawing, so they no longer cost a Canvas redraw of every tile on every pen event / frame.
- Files: `GlLayers.kt` (new), `Canvas.kt` (plan values, page / grid planes, plane call, skip of GPU-drawn layers and onion), `Media3Video.kt` (`hidden` parameter so footage stays hidden outside its range while the page is a plane), `Tiles.kt` (`TileSet.forEachTile`), `Panels.kt` (settings switch), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: all Gradle scripts, `gradle.properties`, `.github/workflows/**`, manifest, signing configuration, SDK / Java configuration. No `.inb` format change; exports, thumbnails and CelCache are unchanged (CPU path).
- Validation: source and diff review, brace balance check, signatures checked against `Tiles.kt`, `Editor.kt`, `Canvas.kt`. No Kotlin compiler, Gradle or Android SDK here: not compiled, not run on a device. EGL14 / GLES20 / GLUtils usage is from memory of the Android API and must be confirmed by the CI build.
- Known issues / follow-up: (1) The plane bakes the viewport and re-renders per change, so during rotate / pinch it may still trail Compose by a frame; if the tablet shows it, switch to applying the viewport with `graphicsLayer` and re-rendering on settle. (2) Switching the active layer or starting / stopping playback can double-draw a semi-transparent layer for one frame. (3) A changed `TileSet` re-uploads all its tiles; playback uploads each cel's tiles on first use (no prefetch yet), so the first loop may stutter on large cels. (4) Tiles are sampled with plain bilinear filtering like the Canvas; seams at fractional zoom match the Canvas behaviour. (5) Test: layers / opacity / folders / onion / Bleed identical with the switch on and off, drawing, layer switching, playback with audio + video, scrubbing.

## 2026-11-10 — Back out GL SurfaceView video output; TextureView with Compose transforms
- AI model: Claude Sonnet 5.5
- Category: Performance / Rendering (regression fix)
- Summary: Canvas rotation / pinch lagged (worst zoomed out) whenever a video layer was visible and was snappy with it hidden. The video layer is a Media3 TextureView again, placed in the Compose tree with the same workspace-sized viewport `graphicsLayer` as the page background and a second `graphicsLayer` for folder / media transform and opacity, so the video moves in the same frame as the ink. Compose draws the Behind grid and white page again. The per-frame values are read in the layer blocks, so neither gestures nor playback ticks recompose the video layer. `MediaSync` (audio / video preroll and start, drift check) from the previous entry is unchanged and still used.
- Reason: A SurfaceView (the GL video surface) is presented independently of the Compose window, so its baked-in viewport transform trailed the drawing, and it punched a hole that erased the page and grid; the earlier backdrop fix (this day) moved page and grid into GL and made that worse. The earlier entries of this day describing the GL backdrop and the per-frame GL quad are superseded for the video layer.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt` (rewritten video layer on TextureView; grid and page composables as in the 2026-11-10 baseline), `PROJECT_MEMORY.md`, `CHANGELOG.md`. `Canvas.kt` and `GlVideoSurface.kt` are byte-identical to the uploaded baseline (`GlVideoSurface.kt` is no longer referenced).
- Protected files untouched: all Gradle scripts, `gradle.properties`, `.github/workflows/**`, manifest, signing configuration, SDK / Java configuration. No `.inb` format change.
- Validation: source and diff review only; not compiled, not run (no Gradle / Android SDK here). Behaviour is expected to match the earlier TextureView layout (viewport pivot, video fit inside the page, opacity), but that was reconstructed from the surviving page-background code and must be checked on the tablet.
- Known issues / follow-up: Test rotate / pinch zoomed out with the video visible, video opacity 0-100 %, video media transform and folder transform, Behind and Front grid, video out of range, and playback with audio + video. Video is still beneath all drawing layers (TextureView plane). A future GL compositor must not be a separate SurfaceView; see `PROJECT_MEMORY.md`.

## 2026-11-10 — Audio/video playback sync coordinator and per-frame GL cost (GL stage 2a)
- AI model: Claude Sonnet 5.5
- Category: Performance / Playback synchronization
- Summary: Added `MediaSync` as the single owner of audio and video clip start / stop / seek. Before the timeline clock starts, every clip under the playhead is seeked while paused and started together once all are ready (bounded wait of 700 ms), instead of each player starting cold on its own and sounding / showing 100-300 ms late and at different times. Clips about to begin are primed half a second ahead; a running clip is re-seeked only when it drifts more than 120 ms (checked about once a second). Players now use a short-read-ahead load control. The video layer no longer recomposes every playback frame (quad computed in the `AndroidView` update block; Behind-grid segments cached), and the GL surface skips unchanged input instead of requesting extra frames.
- Reason: User asked for the best-performing path for the GL migration, especially with an audio and a video layer playing together. Code review found the audio and video players started independently on the first clock tick and were never realigned, and the video composable rebuilt grid / quad arrays every frame.
- Files: `app/src/main/java/com/exemple/inbetween/MediaSync.kt` (new), `MainActivity.kt` (clock effect, `Media3AudioLayerPlayback`), `Media3Video.kt`, `GlVideoSurface.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: all Gradle scripts, `gradle.properties`, `.github/workflows/**`, manifest, signing configuration, SDK / Java configuration. No `.inb` format change; `Canvas.kt` not changed in this step.
- Validation: source and full diff review, brace balance check. No Kotlin compiler, Gradle or Android SDK here: not compiled, not run on a device. Media3 API use (`DefaultLoadControl.Builder.setBufferDurationsMs`, `playbackState`, `currentPosition`) is from memory of Media3 1.4.1 and must be confirmed by the CI build.
- Known issues / follow-up: Test on the tablet with audio + video together: start alignment, drift after a minute, loop wrap (re-seek is still a cold seek), clip boundaries, scrub then play, hiding layers while playing. Preroll adds up to 0.7 s before the playhead moves on a slow decoder. Next GL stages: single GL scene for page / grid / layers, cel tiles as textures, z-ordered compositing, exports through the same path.

## 2026-11-10 — Fix black canvas when a video layer is visible (GL backdrop)
- AI model: Claude Sonnet 5.5
- Category: Fixed / Rendering
- Summary: With a video layer visible the whole workspace turned black. `GlVideoSurfaceView` is a SurfaceView; it punches a hole through the window over its full bounds and erases the Compose workspace colour, Behind grid and white page drawn before it. The transparent GL clear then showed the black window behind. The first visible video surface now paints the opaque workspace colour, the Behind grid and the white Cut page in GL, below the video. Blending is premultiplied so framebuffer alpha stays 1; the video quad is not drawn until the first frame has been decoded.
- Reason: User screenshots (video visible vs hidden) and diagnosis of the SurfaceView hole-punch behaviour against `Canvas.kt` draw order.
- Files: `app/src/main/java/com/exemple/inbetween/GlVideoSurface.kt`, `Media3Video.kt` (removed `Media3VideoGridBackground` and `Media3VideoPageBackground`; added backdrop/grid/page NDC computation and a `drawBackdrop` parameter), `Canvas.kt` (call site only), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: all Gradle scripts, `gradle.properties`, `.github/workflows/**`, manifest, signing configuration, SDK / Java configuration. `README.md` untouched. No `.inb` format change.
- Validation: source and full git-diff review only; transform maths checked by hand against `DrawingCanvas.base()`. No Kotlin compiler, Gradle or Android SDK is available here, so nothing was compiled or run on a device.
- Known issues / follow-up: Verify on the tablet: workspace/page/grid appearance with video visible, Behind vs Front grid, zoom/rotate/flip alignment of page against drawing, video opacity 0–100 %, video out of its time range, and hiding/showing the layer. Multiple visible video layers: only the first owns the backdrop; z-order of several SurfaceViews is untested. Video orientation/size was not changed; check it separately if it still looks wrong.

## 2026-11-10 — Independent video-layer transforms and canvas clipping (V11)
- Video layers now have independent X/Y offset, scale, and rotation properties; these are changed only when that video layer is selected and unlocked.
- Stylus drag and one-/two-finger gestures on a selected video transform the footage without changing the drawing viewport. Selecting a drawing layer keeps ordinary canvas navigation and drawing behaviour.
- Video visibility no longer disables the drawing canvas clip-to-document rule, preventing strokes outside the page from appearing as though they were fitted to the video.
- Drawing coordinate conversion no longer applies a selected video's folder transform to drawing input.
- Added V11 serialization for video transforms while retaining V1–V10 read compatibility. Undo/redo snapshots include the new transform values.

[2026-11-10] — Decouple media playback from drawing-cache stalls

- AI model: GPT-6
- Category: Performance / Playback synchronization
- Summary: Changed the animation preview clock from strict cache-ready sequential stepping to a monotonic real-time clock. Drawing prefetch remains asynchronous and the status badge now reports cache misses as “Preparing drawing…” without freezing the playhead. Audio clip playback no longer interprets ordinary dropped timeline frames as user seeks, and video playback reacts to timeline frame changes through snapshotFlow instead of a 16 ms polling loop. Disabled TextureView touch/focus and raised the Compose drawing canvas above the media plane in sibling z-order.
- Reason: User reported severe audio lag, video intercepting input, and grid/video stacking errors. The previous playback loop blocked timeline time until raster cache preparation completed, while audio restarted whenever frame numbers jumped by more than one.
- Impact: Preview can skip late drawing frames under load; audio/video remain on real time. Behind grid remains below the page and video plane; Front grid remains in the foreground. Video remains a passive reference layer and is still composited beneath all drawing artwork; arbitrary interleaving with drawing layers and independent video transform handles are not implemented by this patch.
- Files: `app/src/main/java/com/exemple/inbetween/MainActivity.kt`, `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `app/src/main/java/com/exemple/inbetween/Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: all Gradle scripts and dependency versions, `.github/workflows/**`, `gradle.properties`, Android SDK / Java configuration, manifest and signing configuration. `README.md` untouched.
- Validation: Reviewed the changed playback effects, timeline clock arithmetic, Compose z-index ordering and input flags. ZIP structure and source diff checks performed. No Android SDK / Gradle executable is available in this environment, so no application compile or device runtime test was performed.
- Known issues / follow-up: Verify on the Android 13 tablet with multiple overlapping audio clips, slow drawing cels, scrubbing, clip boundaries and both grid modes. The video TextureView remains a separate composition plane; a true arbitrary-z-order GPU compositor requires a dedicated shared GL renderer and migration of the drawing renderer, which is not claimed as completed here.

---

[2026-11-10] — Fix Media3 video layer sizing and opacity

- AI model: GPT-6
- Category: Fixed / Rendering
- Summary: Corrected video reference compositing so the media page is laid out using its fitted screen-space width and height before canvas zoom / rotation / flips are applied. The prior layout used raw document dimensions inside a viewport-constrained Compose Box; Compose could clamp the child before graphics transforms, producing a mismatched page/video size and apparent canvas distortion. Video content is aspect-fitted using the decoded video pixel aspect ratio. Removed an unintended `0.588f` alpha multiplier; video layer opacity now maps directly to the layer's 0–1 opacity, so 100% is fully opaque.
- Reason: User screenshots showed the canvas/page geometry changing when video visibility was enabled, stretched-looking video, and a video layer that could not reach full opacity.
- Impact: Media3 decoding, audio playback, `.inb` V10 serialization, media embedding and export behavior are unchanged.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: all Gradle scripts and version configuration, `.github/workflows/**`, `gradle.properties`, manifest, signing configuration and `README.md`.
- Validation: Compared the screenshot geometry with the actual Compose layout and reviewed the changed modifiers / transform math. ZIP and source diff checks performed. No Android SDK / Gradle build environment is available here; no compile or tablet runtime test was performed.
- Known issues / follow-up: Build with GitHub Actions and verify portrait and landscape reference videos, canvas fit at several zoom levels, pan / rotation / flips, Transformation-folder offsets, and opacity at 0%, 50% and 100%.

---

[2026-11-10] — Replace laggy media preview with Media3 playback

- AI model: GPT-6
- Category: Performance / Media playback
- Summary: Replaced frame-by-frame MediaMetadataRetriever video preview and manually synchronized MediaPlayer audio clips with AndroidX Media3 ExoPlayer 1.4.1. Video uses TextureView output and MediaCodec hardware decoding where supported; audio clips use continuous ExoPlayer playback. Media players seek at clip entry, pause/resume and timeline jumps instead of seeking on every playback tick. Players pause while the drawing timeline is buffering.
- Reason: User reported severe audio and video playback lag and approved adding the free, Apache-2.0 Media3 dependency.
- Impact: Adds `androidx.media3:media3-exoplayer:1.4.1`. `.inb` V10 media embedding and export serialization remain unchanged. Video TextureViews are composited beneath drawing artwork so the canvas can draw transparently over reference footage; video layers therefore currently behave as background guides beneath all drawing layers, rather than retaining arbitrary interleaved z-order. Audio remains excluded from preview when its layer is hidden; video remains reference-only and excluded from exports.
- Files: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/exemple/inbetween/Media3Video.kt` (new), `Canvas.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, root Gradle plugin versions, `gradle.properties`, SDK / Java configuration, signing configuration and README.
- Validation: Reviewed Media3 1.4.1 as the version compatible with this project’s compileSdk 34 and checked the Player TextureView API. Kotlin parser pass produced no syntax diagnostics. No Gradle wrapper/executable or Android SDK is available here, so no Android compile, tablet playback test, codec verification or performance benchmark was possible. Hardware decoder selection is device-dependent and must be verified on the target tablet.
- Known issues / follow-up: Run GitHub Actions build. Test H.264 and HEVC sources, audio with multiple simultaneous clips, pause/scrub/clip-boundary sync, app background/foreground, and low-memory behavior on the Android 13 tablet. If TextureView composition is still too costly, investigate a single shared SurfaceView / custom GL canvas renderer; TextureView is chosen here because it can be transformed and alpha-composited with the editor canvas.

---

[2026-11-10] — Fix media persistence, timeline crash and video preview buffering

- AI model: GPT-6
- Category: Fixed / Performance / Media storage
- Summary: Imported audio/video is copied into app-owned storage; V10 `.inb` documents now embed each media file and restore it when loaded or imported. Video preview decodes scaled frames (up to 1280×720) and buffers up to three upcoming frames. Timeline cell width automatically shrinks for long media clips to keep Compose measurement constraints within a safe range. Audio playback now polls less often and only seeks to correct larger drift. MP4 audio export handles app-owned `file://` media sources as well as legacy provider URIs.
- Reason: The user reported laggy video playback, audio-layer crashes, and video references disappearing after app restart. The supplied Android 13 report shows a fatal Compose constraint exception (`width 287508`, `height 0`), not an audio-decoder exception.
- Impact: `.inb` writer advances to V10; V1–V9 remain readable. New imports are self-contained when saved/exported. Existing V9 content URIs are migrated on load when Android still grants access, then embedded on the next save. If a V9 provider grant was already lost, reimport that source. Video remains reference-only and excluded from all exports.
- Files: `app/src/main/java/com/exemple/inbetween/Media.kt`, `Store.kt`, `Editor.kt`, `Panels.kt`, `Canvas.kt`, `MainActivity.kt`, `Export.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts, `gradle.properties`, Android SDK / Java configuration and signing configuration. `README.md` intentionally left unchanged.
- Validation: Inspected the full supplied bug report and relevant project sources. A standalone Kotlin compiler invocation had no Android / Compose classpath, so it could not type-check the application; it emitted no parser `expecting`/syntax diagnostics. No Gradle wrapper/executable or Android SDK is available here. No Android build, `.inb` round-trip, media playback or tablet runtime test was performed.
- Known issues / follow-up: Run the GitHub Actions build. Test long audio imports (including the previously crashing duration), close/reopen and export/import of a cut with both media types, video playback smoothness, audio sync and MP4 audio muxing on the tablet. Existing V9 media references are not automatically migrated into embedded files.

---

[2026-11-10] — Add audio and video reference timeline layers

- AI model: GPT-6
- Category: Added / Media timeline
- Summary: Added importable Audio (kind 6) and Video (kind 7) layers. Both store a persistable Android document-provider URI and source duration, are placed at the playhead, and appear as duration-sized timeline clips that can be dragged horizontally. Audio clips play during timeline playback; video clips are decoded as semi-transparent reference frames and drawn in layer order. MP4 export now offers “Include visible audio layers” and muxes compatible source audio tracks with their timeline offsets.
- Reason: User request to bring audio and video onto the timeline, with video as reference-only and an optional audio-in-export setting.
- Impact: `.inb` writer advances from V8 to V9; readers V1–V8 are retained. Media bytes are not embedded in project files. Video is excluded from canvas artwork exports, thumbnails and MP4 output. Visible audio tracks are included as separate MP4 tracks, not mixed down; unsupported source codecs may fail MP4 export and require transcoding in a future update. Video reference preview refreshes approximately every 50 ms during playback.
- Files: `Editor.kt`, `Store.kt`, `Panels.kt`, `Canvas.kt`, `MainActivity.kt`, `Export.kt`, `Media.kt` (new), `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts, `gradle.properties`, Android SDK / Java configuration and signing configuration. `README.md` intentionally left unchanged, per the instruction to update it only when specifically requested.
- Validation: Inspected the full project agreement, project memory, latest changelog entries and relevant source callers. Kotlin parser invocation produced no syntax diagnostics. No Gradle wrapper/executable or Android SDK is available in this environment; no Android compile, GitHub Actions build, device test, `.inb` round-trip test or media codec export test was performed.
- Known issues / follow-up: Build with GitHub Actions first. Test URI permission persistence across app restarts and project import/export; audio/video seek and clip movement; simultaneous audio playback; video reference frame timing / memory use; MP4 export with AAC and unsupported audio formats. MP4 audio tracks are separate rather than mixed down.

---

[2026-11-10] — Pinch-reset balance, rebindable shortcuts, keyframe flipping, Bleed options and button icons

- AI model: Claude Sonnet 5.5
- Category: Input / Usability / Editor
- Summary:
  1. Canvas pinch-reset proximity set to 112 dp (new constant `RESET_PINCH_DP`), between the too-hard 80 dp and the too-easy 160 dp.
  2. Keyboard shortcuts are now action-based and rebindable (`KeyAction`, `KeyBind`, `KeyBindings` in `Keys.kt`). Settings > Keyboard shortcuts edits a draft: tap a key to change it, + adds another key, × removes one; Save stores `keybinds.json` in the app files directory, Reset restores the app defaults immediately and deletes the stored file; leaving with unsaved edits asks first. A combination already used by another action is moved. Default keys are unchanged. Added actions Previous / Next keyframe (default Ctrl+Left / Ctrl+Right): diamond keys on a Camera layer / Transformation folder, drawings on a drawing layer.
  3. Bleed button now uses a logo (frame with a stroke running past its edge) instead of text; hold it for Bleed options: the draw-outside toggle and "Delete bleed ink" with scope This frame / This layer / Whole Cut. Strokes that start inside the Cut keep the part inside; fills are clipped; selection moves and imported images are left alone; locked layers are skipped; one undo step.
  4. Rotation magnet button redrawn as an icon in the same box, corner radius and colours as the other transform-bar buttons.
- Reason: User requests.
- Impact: Shortcut handling now goes through the stored map instead of a hard-coded `when`; the old read-only `SHORTCUT_GROUPS` list is removed. Delete bleed ink replaces the touched cels with trimmed copies (same pathway as other structural edits, so undo restores them); Out of Peg poses are carried over to the new cels. A brush-width rim of ink at the Cut edge is kept deliberately so the inside does not change. No `.inb` format change. `keybinds.json` is a new app-private file.
- Files: `Keys.kt`, `Tools.kt`, `Canvas.kt`, `Editor.kt`, `Panels.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts, `gradle.properties`, signing configuration.
- Validation: Reviewed the diff; checked that every changed file keeps balanced braces/parentheses as before and that no `SHORTCUT_GROUPS` reference remains; ported the stroke-trimming geometry to a standalone script and checked the exit, re-entry, fully-outside and crossing-both-edges cases. No Gradle/Android SDK build and no tablet test were available here, so none of the Kotlin / Compose changes have been compiled or run.
- Known issues / follow-up: Compile via GitHub Actions first. On the tablet test: pinch reset at 112 dp versus ordinary pinch zoom; rebinding with a physical keyboard (capture dialog focus, Save / Reset / discard prompt, saved keys after restarting the app); Ctrl+Left / Right keyframe flipping on Camera, Transformation and drawing layers; Delete bleed ink on partly-outside strokes, fills, eraser strokes and with undo/redo; the new Bleed and magnet icons. The rim kept at the Cut edge is up to half a brush width of ink outside the frame.

---

[2026-11-10] — Fix timeline two-finger zoom/pan compile error

- AI model: Claude Sonnet 5.5
- Category: Build fix (source code)
- Summary: GitHub Actions `:app:compileReleaseKotlin` failed with "Restricted suspending functions can invoke member or extension suspending functions only on their restricted coroutine scope" at `Panels.kt` lines 1560 and 1565. The new timeline two-finger zoom/pan handler runs inside `awaitEachGesture` (a restricted suspend scope) and called the suspending `ScrollState.scrollBy`. Replaced both calls with the non-suspending `ScrollState.dispatchRawDelta`, which applies the same scroll delta immediately.
- Reason: Demonstrated by the build log; the failure was in Kotlin source, not in the workflow, Gradle or signing configuration.
- Impact: No behavior change intended; the handler still keeps the pinch centroid anchored and pans horizontally. `dispatchRawDelta` bypasses scroll-mutex/fling cancellation, so an in-progress one-finger fling may not stop the instant two fingers land; verify on device. No `.inb` format change.
- Files: `app/src/main/java/com/exemple/inbetween/Panels.kt` (two lines), `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts, `gradle.properties`, signing configuration.
- Validation: Read the build log; the only two compile errors were those lines. Verified by inspection that `hs` is a `ScrollState` and that no other `scrollBy` calls sit inside a restricted scope (`vs.scrollBy` at ~1688 is in a separate scope and was not reported). No Gradle/Android SDK build or tablet test was available here.
- Known issues / follow-up: Re-run the workflow and inspect the next log independently. Test timeline pinch zoom anchoring and two-finger pan on the tablet.

---

[2026-11-10] — Make two-finger canvas reset easier to activate

- AI model: GPT-6
- Category: Input / Usability
- Summary: Increased the two-finger proximity threshold that arms the canvas-view reset gesture from 48 dp to 80 dp. The existing purple activation cue and release-to-reset behavior are unchanged. Bumped the app version name to 0.3; CI continues to assign monotonically increasing version codes from the GitHub Actions run number.
- Reason: The previous 48 dp threshold was too difficult to reach reliably with fingers on a tablet.
- Impact: The reset gesture can arm with the fingers farther apart. Two-finger pinch zoom / rotate and two-finger tap Undo retain their existing handling, but the larger proximity zone may make reset easier to arm during a pinch; verify on device. No `.inb` format change.
- Files: `Canvas.kt`, `app/build.gradle.kts`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Confirmed the threshold is changed at the single reset-arming check, checked the version-name and project-memory updates, and reviewed the diff. No Gradle / Android SDK build or tablet test was available here.
- Known issues / follow-up: Test reset with different finger spacing, ordinary pinch zoom/rotation, two-finger tap Undo, and gestures on Camera / Transformation layers. If accidental resets occur, refine the gesture to require a clear inward pinch rather than simply entering the proximity zone.

---

[2026-11-10] — Add persistent release APK signing for in-place updates

- AI model: GPT-6
- Category: Build / Release
- Summary: Added an environment-driven release signing configuration, a GitHub Actions path that builds and uploads a signed release APK when all signing secrets are present, a clearly labelled debug fallback when they are absent, and automatic CI version-code assignment from the workflow run number. Added a PowerShell helper to create a permanent keystore and Base64 secret file, and ignored local keystore/secret files.
- Reason: Debug APKs generated by ephemeral GitHub runners can have changing signing certificates, causing Android package/signature conflicts during installation over an existing app.
- Impact: The application ID remains `com.example.inbetween`; no source package, document format or runtime feature was changed. A one-time uninstall may be required to move from the previous signing certificate to the new permanent release key. After that, builds signed with the same key can update in place.
- Files: `app/build.gradle.kts`, `.github/workflows/build.yml`, `.gitignore`, `scripts/prepare-release-signing.ps1`, `PROJECT_MEMORY.md`, `CHANGELOG.md`, `RELEASE_SIGNING_SETUP.md`.
- Validation: Reviewed Gradle Kotlin DSL, workflow conditions, secret/environment variable names, version-code wiring, PowerShell script and ignore rules; checked the patch and ZIP structure. No Gradle executable/Android SDK or GitHub repository secrets are available in this environment, so no Android build, signed APK generation, GitHub Actions run or device installation test was performed.
- Known issues / follow-up: Run `scripts/prepare-release-signing.ps1` once on a computer with JDK/keytool, add its output and the passwords as the four documented GitHub Actions repository secrets, back up the `.jks` securely, then run the workflow. Uninstall the old app once before installing the first signed release. Do not lose or replace the keystore.

---

[2026-11-10] — Consolidate project documentation and resolve naming notes

- AI model: GPT-6
- Category: Documentation
- Summary: Removed duplicated and malformed sections from `PROJECT_MEMORY.md`, separated current-state reference material from historical changelog entries, corrected section numbering and Markdown tables, and clarified the distinction between the `com/exemple/` source directory and the `com.example.inbetween` Kotlin package / Android namespace. Removed duplicated changelog template/rules material while preserving dated historical entries and their original attributions.
- Reason: The project memory had duplicated source maps and UI sections, a corrupted keyboard-shortcut bullet, duplicated changelog content, and an ambiguous package-directory note.
- Impact: The three documents now have distinct responsibilities and consistent naming guidance. No application code, package declaration, namespace, application ID or build configuration was changed.
- Files: `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Compared the documentation against the supplied source archive, including `app/build.gradle.kts`, `MainActivity.kt` and the actual `com/exemple/inbetween/` path; checked Markdown headings, changelog entry order and archive integrity. No Gradle build or device test was run because no source code or build configuration changed.
- Known issues / follow-up: The feature inventory remains a snapshot of the supplied archive and should be checked against the live repository before future code changes.

---

[2026-11-09] — Canvas reset build correction

- AI model: Unknown — attribution not verified
- Category: Build
- Summary: Added the missing `androidx.compose.ui.unit.dp` import in `Canvas.kt`, required by the two-finger canvas-reset proximity threshold.
- Reason: The source required the `dp` extension for the reset gesture threshold.
- Impact: Resolves the missing import in the affected source file; no document-format change.
- Files: `Canvas.kt`.
- Validation: This correction is recorded from the supplied changelog note; independent build validation was not performed during this documentation cleanup.
- Known issues / follow-up: Confirm the fix is present in the current repository and verify with the normal CI build.


---

[2026-11-09] — Isolate timeline composables and refresh Android bytecode toolchain

- AI model: GPT-6
- Category: Fixed / Build
- Summary: Extracted timeline toolbar, curve controls and dialogs from the large `Timeline` composable into dedicated composables. Updated Android Gradle Plugin from 8.5.2 to 8.7.3 and Kotlin / Compose compiler plugin from 2.0.20 to 2.0.21. Changed CI to run `clean assembleDebug` with Gradle build-cache reuse disabled.
- Reason: The supplied Android 13 bug report repeatedly records `java.lang.VerifyError` while verifying `PanelsKt.Timeline`, specifically DEX instruction `0x4E63` (`copy-reference` receiving an `IntegerConstant`). The failure happens before the main menu can render. Smaller composable methods reduce generated-method complexity; the newer Android build toolchain refreshes the Kotlin/Compose and D8/R8 pipeline.
- Impact: Timeline behavior is intended to remain unchanged. The build uses a newer but compatible AGP/Kotlin toolchain; CI starts the APK build from clean outputs.
- Files: `Panels.kt`, root `build.gradle.kts`, `.github/workflows/build.yml`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Reviewed the Android bug report and exact stack trace; reviewed the source diff and helper call sites; verified the ZIP structure and configuration versions. No Gradle executable/wrapper or Android SDK is available in this environment, so no Android build, CI run or tablet runtime test was performed.
- Known issues / follow-up: The changes are a targeted remediation, not yet a confirmed fix. Run the GitHub Actions workflow and install the resulting APK on the Android 13 tablet. If the same verifier error persists, capture the new APK's DEX disassembly and further split the grid/layer-row content or test a later Kotlin/AGP patch release.

[2026-11-08] — Timeline key button, pinned ruler, sharp color triangle, quicker frame drag, keyboard support

- AI model: Claude Sonnet 5.5
- Category: Added / Changed / Fixed
- Summary: Transformation folders are now named "Transformation"; legacy names are renamed on load. Added a key-diamond toolbar button for Transformation folders and Camera layers. Folder rows can be selected and renamed. Color triangle rendering now uses its real pixel size with anti-aliased edges. Frame drag hold reduced to approximately 0.2 seconds ("CEL_HOLD_MS"). Retime popup opens above the frame. Timeline ruler is pinned above vertical scrolling. Added hardware keyboard support and Settings > Keyboard shortcuts.
- Reason: User requests for folder naming/key controls, sharper color triangle, shorter frame hold, improved retime popup placement, pinned ruler and keyboard controls.
- Impact: No ".inb" format change. Legacy Transformation Folder names change on load. Timeline ruler and layer rows have separate layouts.
- Files: "Panels.kt", "Editor.kt", "Camera.kt", "Canvas.kt", "Store.kt", "MainActivity.kt", "Keys.kt" (new), "PROJECT_MEMORY.md".
- Validation: Static review and script check for balanced braces/parentheses. No Kotlin compiler, Gradle or device test.
- Known issues / follow-up: Run Superframe CI. Verify color triangle, frame hold, retime popup, ruler behavior, key creation/Undo and Bluetooth keyboard behavior on device.

[2026-11-08] — Fix restricted pointer-event scope in timeline cel drag

- AI model: GPT-6
- Category: Fixed
- Summary: Removed redundant nested "awaitPointerEventScope" from the long-press cel-drag handler and awaited "PointerEventPass.Initial" events directly in the existing "awaitEachGesture" scope.
- Reason: Kotlin compilation failed because a restricted suspending function was invoked from an already restricted pointer-event scope.
- Impact: Preserves intended Initial-pass pointer ownership and movement consumption while correcting coroutine-scope usage.
- Files: "Panels.kt", "PROJECT_MEMORY.md".
- Validation: Static inspection and ZIP integrity check. No Gradle build or device test.
- Known issues / follow-up: Re-run Superframe GitHub Actions build and inspect any subsequent compiler errors.

[2026-11-08] — Give timeline frame stretching priority over horizontal panning

- AI model: GPT-6
- Category: Fixed / Changed
- Summary: Changed post-long-press cel drag to consume pointer movement at the Initial pointer-event pass. Renamed Java source directory from "com/example/" to "com/exemple/" in the delivered archive; Kotlin package declarations and Android namespace/application ID remained "com.example.inbetween".
- Reason: Cel gesture recognition and ancestor horizontal scrolling competed to own horizontal movement.
- Impact: After a cel hold is recognized, its handler consumes subsequent movement before parent horizontal scrolling. Edge stretching and middle-of-cel repositioning retain existing duration and undo logic.
- Files: "Panels.kt" (recorded as renamed from "com/example/inbetween/" to "com/exemple/inbetween/"), "PROJECT_MEMORY.md".
- Validation: Static source inspection and targeted checks. Kotlin parser reported no syntax errors, but Android/Compose dependencies were unavailable. No real build, CI or device interaction test.
- Known issues / follow-up: Verify edge stretching, middle repositioning and ordinary empty-space panning on device.

[2026-11-08] — Align Transformation Folder animation with Camera and repair timeline range/stretch

- AI model: GPT-6
- Category: Fixed / Changed
- Summary: Reworked Transformation Folder animation to use independent X/Y/Scale/Rotation keyframe channels matching Camera. Added camera-style interpolation/easing and per-channel timeline markers. Reworked cel long-press recognition and reduced edge hit zones proportionally. Allowed playback end marker to extend up to 120 frames into blank timeline tail. Advanced ".inb" persistence to V8 while retaining V1–V7 readers and migrating older combined transform keys.
- Reason: Folder transforms used a combined key list and linear interpolation. Stretch zones could overlap on narrow frames. Long-press movement handling could cancel before hold recognition. Playback end was clamped to final cel.
- Impact: Independent folder channels and easing; improved frame stretch zones; blank playback tail; V8 storage with older readers retained.
- Files: "Editor.kt", "Camera.kt", "Panels.kt", "Store.kt", "Export.kt", "PROJECT_MEMORY.md".
- Validation: Static review, source searches, Kotlin parser pass with unresolved Android/Compose dependencies and archive inspection. No Gradle build, CI or device test.
- Known issues / follow-up: Verify V8 save/load and V1–V7 compatibility, Undo/Redo, channel editing, blank-tail playback/export and gestures on device.

[2026-11-08] — Repair timeline gestures and add transformation folders

- AI model: GPT-6
- Category: Added / Fixed
- Summary: Changed two-finger timeline pinch to horizontal time-scale zoom. Repaired cel drag destination math and first-cel left-edge stretching. Retained long-press repositioning for finger and stylus. Added Transformation Folder groups with X/Y offset, uniform scale and rotation keys, interpolation, undo/redo snapshots, V6 persistence, canvas/onion-skin rendering and export rendering.
- Reason: Timeline pinch changed panel height. Reordering used incorrect indices after removing the dragged cel. Transformation folders previously grouped layers without animation.
- Impact: Horizontal timeline zoom, frame repositioning, first-cel stretch and animated transformation folders. ".inb" writer advanced to V6; V1–V5 readers retained.
- Files: "Editor.kt", "Panels.kt", "Canvas.kt", "Export.kt", "Store.kt", "PROJECT_MEMORY.md".
- Validation: Static source inspection and diff checks only. No Gradle, CI or device test.
- Known issues / follow-up: Verify stylus/finger hold, pinch focal point, V6 save/load and older migration, Undo/Redo, onion skin and exports.

[2026-11-08] — Fix color-wheel drag gesture compilation

- AI model: GPT-6
- Category: Fixed
- Summary: Updated the color-wheel pointer loop to call the imported "drag" extension through its "dragPointer" alias and gave the pointer-change lambda an explicit parameter name.
- Reason: CI reported unresolved references to "drag", "consume" and "position". The source imported "androidx.compose.foundation.gestures.drag as dragPointer", but the call site used the unaliased name.
- Impact: Preserves color-wheel drag behavior while resolving the import/call-site mismatch.
- Files: "Panels.kt", "PROJECT_MEMORY.md".
- Validation: Static check confirmed the call site matched the alias. No Gradle build.
- Known issues / follow-up: Re-run "gradle assembleDebug" in GitHub Actions. The source-folder spelling difference between reviewed ZIP and CI log was not the reported compiler failure.

[2026-11-08] — Initial source audit and project memory document

- AI model: GPT-6
- Category: Documentation
- Summary: Inspected supplied source archive and created initial feature inventory, source map, maintenance notes and changelog protocol.
- Reason: Establish durable project reference and record future changes across sessions and AI models.
- Impact: Contributors can identify feature areas, likely implementation locations and high-risk regression areas.
- Files: "PROJECT_MEMORY.md".
- Validation: Read Kotlin source, Gradle configuration, Android manifest and GitHub Actions workflow from supplied ZIP. No Gradle build, automated test or device test.
- Known issues / follow-up: Feature inventory is based on static source inspection. Verify UI behavior and build status in the normal Android environment.
---

[2026-11-09] — Fullscreen layer button: up/down layer flip buttons, working drag-to-reorder in the unfolded list

- AI model: Claude Sonnet 5.5
- Category: Changed / Fixed
- Summary: In the fullscreen top bar the small arrow under the current-layer button is removed. Two buttons (up / down arrows) now sit to the left of the layer button and flip the current layer to the layer above / below it in the list (`MiniLayerButton`, `Panels.kt`). Tapping or holding the layer name unfolds / folds the list. Drag-to-reorder moved from the layer button into the unfolded list: hold any row and drag it; the drop indicator and `Cut.moveLayer` call are unchanged. Previously the hold started on the button while the dragged row was a different copy of the layer inside a separate pop-up window, so the finger and the dragged row were not the same element and the pop-up opening interrupted the gesture.
- Reason: User request and screenshot showing the held layer on the timeline and in the unfolded menu at once, with dragging not working.
- Impact: No `.inb` change. Reordering is now started from the unfolded list rather than from the button; the list no longer opens with a single arrow tap.
- Files: `Panels.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: Source inspection and diff review; bracket / parenthesis counts of the changed function are balanced. No compiler, Gradle or device was available, so nothing was compiled or run.
- Known issues / follow-up: Verify on the tablet: up / down flipping through groups and at the first / last layer (does nothing), the list unfolding on tap and hold, long-press drag of a row with finger and S Pen (including scrolling a long list and dropping into a folder), and that the focusable pop-up does not cancel the row drag.

---

[2026-11-09] — Fix ruler/playhead drift, gap-fill-only wand/fill drag, per-frame Out of Peg, edge scrub bump, deliberate new frame

- AI model: Claude Sonnet 5.5
- Category: Fixed / Changed / Added
- Summary: (1) Ruler / playhead: the timeline cell width was a fractional pixel value; tick and cel boxes are measured at whole pixels while the playhead and window markers are placed at `cell * frame`, so they drifted apart by the fraction per frame. `Timeline` now rounds the cell width to whole pixels and derives the Dp width from it. (2) Magic Wand and Fill: the threshold slider is removed and colour tolerance is fixed (`WAND_FILL_TOL` 24 %). The horizontal drag now only adjusts gap fill, 1 px per 3 screen px up to 60 px (was ~1 px per 75 px up to 4 px); `gapFillMask` uses a separable square dilation (`dilateSquare`) so the cost does not depend on the radius. Vertical grow / shrink is unchanged. The hover label shows Gap fill and Grow. (3) Out of Peg: poses are stored per adjacent drawing (`Editor.referencePoses` keyed by `Cel`, `Layer.adjacentCel`) instead of per layer + side, so adjusting the previous / following drawing no longer moves every onion skin on every frame. A cyan dot at the top-right of a timeline cel marks a cel with an Out of Peg pose and disappears when it is reset. (4) Finger scrub feedback: the bottom-centre dot is removed; each cel / key step bumps a purple-to-white gradient on the left or right canvas edge (direction of the scrub). (5) Scrubbing past the last frame now builds a growing purple pull on the right edge and arms the new frame only after `NEW_FRAME_PULL` (168 screen px, about 3.5 steps); scrubbing back cancels it.
- Reason: User requests and on-device screenshot showing the playhead off the ruler.
- Impact: No `.inb` change. `Editor.wandTol` / `fillTol` replaced by `wandGap` / `fillGap` (session state, not serialized). Out of Peg poses stay session-only. Stored Out of Peg poses of old per-layer keys do not exist across sessions, so nothing migrates.
- Files: `Panels.kt`, `Canvas.kt`, `Tools.kt`, `Editor.kt`, `Gizmo.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files (workflow, Gradle, gradle.properties) untouched.
- Validation: Read all three .md files and the affected sources; diff review against the supplied archive; script bracket-balance check (balanced in `Canvas.kt`, `Editor.kt`, `Tools.kt`, `Gizmo.kt`; the script cannot parse the string literals of `Panels.kt` / `MainActivity.kt`, so those two were reviewed line by line in the diff). No Kotlin compiler, Gradle or device was available, so nothing was compiled, run or measured.
- Known issues / follow-up: Run GitHub Actions first. Verify on the tablet: playhead on the ruler tick at several zoom levels after scrolling; wand / fill gap drag speed and the preview at 0-60 px (large windows with Bleed on); Out of Peg on frame N does not change other frames, the cyan dot appears and disappears on reset, and a cel shows its pose as the onion of both neighbours; the edge bump on both scrub directions and on Camera / Transformation key scrubbing; the pull distance for a new frame (tune `NEW_FRAME_PULL`). Square dilation closes diagonal gaps slightly wider than the old diamond growth. Pinch zoom of the timeline now steps in whole pixels.

---

[2026-11-09] — Clarify visible-layer sampling option for Magic Wand and Fill

- AI model: GPT-6
- Category: Changed / Documentation
- Summary: Renamed the shared detailed sampling control to “All visible drawing layers” and clarified that disabling it samples only the selected drawing layer. The option remains enabled by default and is shared by Magic Wand and Fill.
- Reason: Make the scope of region detection explicit and let users choose between composite visible artwork and the active drawing layer.
- Impact: No algorithm or document-format change; sampling behavior already supported the shared all-visible/current-layer switch.
- Files: `app/src/main/java/com/exemple/inbetween/Panels.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Source inspection confirmed `refAll` defaults to true, is shared by both tools, and the all-layer path composites visible layers while skipping hidden layers. ZIP integrity checked. No Android/Compose build or device test was performed.
- Known issues / follow-up: Verify the detailed option appears for both tools and that toggling it changes sampling as described.

---

[2026-11-09] — Keep timeline ruler and playhead scroll geometry synchronized

- AI model: GPT-6
- Category: Fixed
- Summary: Made the timeline track-row scroll content use the same explicit frame width as the ruler.
- Reason: The ruler and track rows shared one horizontal ScrollState but had different measured content widths. Compose could clamp the shared scroll offset to different maximums, making the playhead/cels drift from ruler ticks.
- Impact: Ruler, cel columns, playhead and playback-window markers now share a consistent horizontal scroll extent.
- Files: `app/src/main/java/com/exemple/inbetween/Panels.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Static source inspection and ZIP integrity check. No Android/Compose build or device test was run.
- Known issues / follow-up: Verify alignment at minimum and maximum zoom, after horizontal scrolling, and while dragging playback-window markers on device.

---

[2026-11-09] — Correct grid front-mode coverage and toolbar toggle

- AI model: GPT-6
- Category: Fixed / Changed
- Summary: In Front mode, render the grid across the full drawing workspace rather than clipping it to the Cut page. Keep all artwork above the grid in both modes. Remove the Behind/In Front switch from the detailed Grid popup; tapping the toolbar grid button is the mode switch, while its long-press popup retains density adjustment.
- Reason: Front mode previously drew the grid inside the Cut-page clip, and the mode switch was duplicated in detailed options.
- Impact: Behind mode remains masked by the opaque white page; In Front mode shows grid lines both on and outside the page. Artwork always renders above grid lines.
- Files: `Canvas.kt`, `Panels.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Inspected the prior ZIP and relevant Compose draw order; source patch applied and ZIP integrity checked. No Gradle build or Android device test was performed.
- Known issues / follow-up: Verify both modes, artwork stacking, toolbar tap and long-press density popup on the Android tablet.

[2026-11-09] — Keep artwork above the grid in both depth modes

- AI model: GPT-6
- Category: Fixed
- Summary: Confirmed and documented the draw order so artwork is always rendered after the grid. Behind mode draws the grid before the opaque white Cut page (therefore masked on the page); In Front mode draws it over the page, but still before artwork.
- Reason: User clarified that regardless of grid depth setting, artwork must remain visually above grid lines.
- Impact: Grid depth changes only its relationship to the white page/workspace, never its relationship to artwork.
- Files: `app/src/main/java/com/exemple/inbetween/Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Static draw-order inspection and ZIP integrity check. No Android build or tablet test performed.

[2026-11-09] — Mask the grid behind the opaque Cut page

- AI model: GPT-6
- Category: Fixed
- Summary: Moved the Behind-mode grid draw pass ahead of the white Cut-page draw. The opaque page now masks the grid while the surrounding workspace retains the grid. In Front mode remains drawn over the page and artwork.
- Reason: User clarified that Behind mode must not show grid lines over the white canvas.
- Impact: Grid depth now has a true visual distinction: Behind is masked by the page; In Front overlays it. Grid remains enabled in both modes.
- Files: `app/src/main/java/com/exemple/inbetween/Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Static draw-order inspection and source diff review; ZIP integrity checked. No Android/Compose build or tablet interaction test was performed.
- Known issues / follow-up: Verify both grid modes on device, including with Bleed enabled.

[2026-11-09] — Correct Cut resize input routing, grid depth and ruler scrolling

- AI model: GPT-6
- Category: Fixed
- Summary: Allowed the shared gizmo hit test to run while a Cut settings draft is open; kept reference-image auto-picking disabled during that draft. Anchored the Cut settings panel at the canvas top-left so it cannot cover the right/bottom resize handles. Moved the playhead and playback-window markers into the ruler's horizontally scrolling content so they remain aligned with frame ticks after scrolling. Reversed the grid depth mapping so the depth control matches its intended front/behind behavior while keeping the grid visible on the Cut page.
- Reason: On-device screenshots showed that the ruler playhead did not follow the frame ruler and that the grid depth button behaved opposite to the requested front/behind direction. Cut resize handles remained unusable despite the previous hit-area adjustment; the open panel could cover their hit targets, and the handle routing must stay enabled while the draft is active.
- Impact: Cut settings handles can receive stylus and finger drags while settings are open, with the panel kept away from resize edges. Ruler and playhead use the same scroll coordinate space. The depth control now maps its two states to the intended grid-over-artwork and grid-under-artwork draw passes.
- Files: `Canvas.kt`, `Panels.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Static source inspection and targeted diff review; ZIP integrity checked. No Android/Compose build or tablet interaction test was performed.
- Known issues / follow-up: Verify all three Cut resize handles with finger and S Pen, grid depth in both modes, and playhead alignment while horizontally scrolling and changing timeline zoom.

[2026-11-09] — Repair Cut resize hit targets, persistent grid depth, absolute transform snapping, adaptive ruler and threshold-driven gap fill

- AI model: GPT-6
- Category: Fixed / Changed
- Summary: Increased the Cut-settings resize-handle hit area while preserving the fixed top-left page origin and bottom-right/right/bottom resize behavior. The grid now defaults on at 30 document pixels and the grid toolbar button toggles its draw order (behind or in front of the artwork); the old keyboard toggle no longer turns it off. New Cuts default to 24 fps and 144 DPI. Gizmo rotation snapping now uses absolute 30° multiples from 0° for selection, Camera, Transformation folders, Reference images and Out of Peg poses. Timeline ruler frame and second labels thin out based on the current zoom so they do not overlap the playhead's frame spacing. Wand and Fill threshold values now control both colour tolerance and a barrier-expansion gap-closing radius; the live blue preview and committed mask use the same combined result, and the preview label displays the active gap-fill radius.
- Reason: User reported unresponsive Cut corner handles, requested a persistent front/behind grid, new Cut defaults, absolute rotation snapping, zoom-safe ruler labels and threshold-controlled gap filling visible in the preview.
- Impact: No `.inb` format change. Existing Cuts retain their saved frame rate and DPI. Grid is always shown; its depth can be switched without disabling it. Wand and Fill remain independently adjustable but each tool's one threshold value controls both tolerance and gap closing. Fill's existing Grow / shrink control remains separate.
- Files: `app/src/main/java/com/exemple/inbetween/Canvas.kt`, `Editor.kt`, `Gizmo.kt`, `Keys.kt`, `MainActivity.kt`, `Panels.kt`, `Tools.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Read the complete working agreement and project memory plus the latest relevant changelog entries. Reviewed the affected source paths and changed-file diff; verified no `.inb` serialization or protected build configuration changes. Static source inspection only. A Kotlin compiler parse attempt reported no syntax/`expecting` diagnostics, but unresolved Android/Compose dependencies prevented compilation. No Gradle/Android build, CI run or tablet interaction test was performed. ZIP integrity passed.
- Known issues / follow-up: Verify Cut resize handles with finger and S Pen; check grid depth in both modes; test absolute snapping from pre-rotated states; inspect ruler at very low and high zoom; test gap closure on narrow line gaps and confirm preview/committed fill match. Gap closing expands threshold-excluded barrier pixels before reflooding from the seed; verify the radius curve on narrow and thick line art to avoid closing intended openings.

---

[2026-11-09] — Unbounded drawing: sparse tiled cel cache, real-time Bleed, selection/fill beyond the frame

- AI model: Claude Sonnet 5.5
- Category: Performance / Changed / Added
- Summary: Diagnosis: with Bleed on, a layer that already had out-of-frame ink was drawn from `BleedCache`, which does not contain the live stroke (only `CelCache` and a small 64–128 px `Session.bleedBmp` did, and that overlay was clipped to outside the frame), so the whole stroke appeared on pen release; release also invalidated and replayed the whole cel into a bitmap of up to 20 MP. Replaced `BleedCache` and the page-sized live stamping with `Tiles.kt`: sparse 512 px tiles per cel (`TileCache` / `TileSet`) in unbounded document coordinates. Live strokes stamp straight into the tiles they touch (`PathStamper` now writes to a `DabSink`; `TileSink` forwards each dab to every overlapped tile; erasers only touch existing tiles). The canvas, onion skin and playback draw only the tiles in view; Bleed off clips to the Cut rectangle instead of dropping ink. `CelCache` is now a Cut-cropped bitmap derived from the tiles (version-checked) for export, thumbnails, flatten and gizmo bounds, so exports match the canvas inside the Cut. `Cut.commit`, undo and redo no longer invalidate caches; tile sets catch up through `count`. Selection transform lifts/draws pixels through tiles (`liftRuns` / `drawLiftTiles`) and pins the set while live. `Sel` got a document-space window (`ox`, `oy`); with Bleed on, lasso/rectangle selections, wand, fill, invert and the all-layers source (`renderWindow`) work beyond the Cut (capped to 8 MP); with Bleed off they are unchanged. `Session.release()` unpins on pen up/cancel.
- Reason: User asked for real-time Bleed drawing and truly unbounded drawing, and for all stages (display tile cache, normal drawing on tiles, fill/wand/selection beyond the frame) to be done.
- Impact: No `.inb` change (strokes were already stored in unbounded coordinates; fills and region transforms still store Cut-window run coordinates, now possibly outside the Cut when made with Bleed on). Exports and thumbnails stay cropped to the Cut, so a moving Camera does not show ink outside the Cut in exports. Ink more than ~40960 px from the origin is stored but not rasterized. Memory: tile sets are evicted LRU against `CelCache.budget` + 32 MB and rebuilt by replaying strokes. Fill is bounded by its window, not infinite. Flatten Down still flattens only the Cut area.
- Files: `Tiles.kt` (new), `Engine.kt`, `Canvas.kt`, `Editor.kt`, `Tools.kt`, `Gizmo.kt`, `Keys.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files (workflow, Gradle, gradle.properties, manifest) untouched.
- Validation: Read all three .md files and the affected sources; script bracket-balance check of every `.kt` file (identical residual counts to the supplied archive); grep review of every former `BleedCache` / `CelCache` / `Sel` / `runsOf` / `selFromPath` / `Xform` call site; diff review against the supplied archive. No Kotlin compiler, Gradle, profiler or device was available, so nothing was compiled, run or measured.
- Known issues / follow-up: Run GitHub Actions first; least certain to compile: `Tiles.kt` (public inline `eachTile`/`eachTileF` with `continue`, expression-bodied `synchronized` with `return@synchronized`), `Engine.kt` (`CelCache.budget` custom setter, `PathStamper` secondary constructor), `Canvas.kt` (`nc.clipBounds`, `viewRect`, fill window block). Verify on tablet: live stroke visible in real time inside and beyond the frame with Bleed on and off (also on a cel that already has out-of-frame ink), no hitch on release, undo/redo, eraser across tile borders, tile seams under zoom, view rotation and Transformation-folder transforms (bilinear sampling at tile edges may show faint lines), onion skin/Out of Peg, playback with heavy cels (memory/eviction), selection transform and Cancel (pixels return), lasso/rect/wand/fill/invert with Bleed on and off beyond the frame, 4K Cuts, export/GIF/MP4 matching the canvas, Save/reopen. Folder content-bounds and Flatten Down still use the Cut-cropped `CelCache`. Not done: unbounded Flatten Down, infinite flood fill (by design), camera exports revealing out-of-Cut ink.

---

[2026-11-09] — Optimize Bleed, make playback strictly sequential, and add URI-backed Reference layers

- AI model: GPT-6
- Category: Added / Fixed / Performance
- Summary: Fixed a compile error in Canvas coordinate conversion (the inverse transform referenced a parameter that exists only in the forward conversion). Reference layers now store only a persistable Android document URI and transform matrix in `.inb`; image pixels are never embedded. Missing or inaccessible references (including legacy embedded-image references from the earlier build) are silently discarded during load. Reference images are excluded from project thumbnails and exports. Retained the shared corner-scale and exterior-corner rotation handles across selection, camera, Transformation folders and Reference layers; corrected Cut settings to resize from the bottom-right/right/bottom using ungrouped document coordinates. Reworked Bleed overscan to allocate margins based on actual ink overhang rather than allocating 1024–1536 px for every affected cel. Playback pre-renders a short rolling window and waits for cache-ready frames; it never intentionally skips a timeline tick. Frames too large to fit the cache are rendered layer-by-layer in order and may slow playback under load.
- Reason: Reference assets should remain external to `.inb`; Bleed caused excessive bitmap allocation; the previous playback timeout could advance before buffering completed; Cut resizing could use transformed layer coordinates.
- Impact: Reference links survive project save/load only while the Android document URI remains accessible. Reference images are guide-only and do not appear in exports or thumbnails. Overscan allocations are smaller and adaptive. If a frame is too large to fit in cache, it is drawn layer-by-layer on demand without dropping it; playback can slow while buffering.
- Files: `Editor.kt`, `Engine.kt`, `Store.kt`, `Panels.kt`, `Canvas.kt`, `MainActivity.kt`, `Export.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Source-level review and targeted reference-path, export-exclusion, Bleed and playback searches. Kotlin parser reported no syntax diagnostics, but Android/Compose dependencies were unavailable and dependency-resolution errors prevent treating this as a compile. ZIP integrity passed. Protected Gradle/build/manifest/workflow files were byte-for-byte unchanged. No Gradle build, CI build, profiler run, or Android/S Pen device test was available.
- Known issues / follow-up: Verify OpenDocument persistable permission behavior with the tablet's file provider; verify URI availability after app restart and after source deletion/movement. Measure Bleed memory and frame time on-device. Verify large-frame buffering does not stall indefinitely due a rendering failure. Confirm bottom-right Cut resizing and transform/rotation handles with both finger and S Pen.

[2026-11-09] — Add persistent Reference image layers, sequential playback buffering and reduce Bleed overhead

- AI model: GPT-6
- Category: Added / Performance / Fixed
- Summary: Added a persistent Reference layer type (kind 5) that stores imported images as image Ink with a document-space matrix, supports independent visibility/opacity/locking/z-order, auto-selects on tap, and supports finger/S Pen move, corner scale, side stretch, exterior-corner rotation, flip, aspect lock and rotation magnet. Reused the existing V8 layer/Ink serialization, so no `.inb` version bump or migration is needed. Changed playback to pre-render upcoming cels and advance strictly in order, waiting for required cels instead of dropping late frames, with a Buffering indicator and a 750 ms on-demand fallback to avoid indefinite waits. Optimized Bleed by caching per-cel overflow detection, avoiding overscan allocations for ordinary cels, and lazily allocating live-stroke overscan only after a stroke crosses the page edge. Corrected Cut-settings gizmo coordinate conversion so active layer-group transforms do not move the Cut resize box.
- Reason: The user requested Reference image layers, corner/rotation transform handles, lower Bleed overhead and the sequential buffering approach that preserves every preview frame.
- Impact: Reference images persist with the document and remain independent; preview playback may slow while waiting for a prepared frame rather than skipping it; after 750 ms it renders that frame on demand in sequence. GIF export remains separate and renders every frame. Bleed still uses bounded overscan and can fall back on large Cuts. Out of Peg pose data is retained when the Out of Peg toggle is turned off; source drawings are not modified.
- Files: `Editor.kt`, `Engine.kt`, `Canvas.kt`, `Gizmo.kt`, `MainActivity.kt`, `Panels.kt`, `Export.kt`, `Store.kt`, `PROJECT_MEMORY.md`.
- Validation: ZIP/source inspection and static targeted checks. The Kotlin compiler was invoked without Android/Compose dependencies; it produced dependency-related unresolved-reference/type-inference diagnostics and no parser-level `expecting` errors in the changed files. No Gradle/Android build or device test was available; do not treat this as a successful compile.
- Known issues / follow-up: Run GitHub Actions. On device verify Reference image import, independent transforms, layer visibility, save/reopen, undo/redo, reference image sequence export, S Pen/finger handles, Cut resizing from bottom-right, Bleed performance with and without overflow, and playback under heavy frames. The playback buffer caches cel bitmaps rather than complete composited frames; a complex frame that exceeds the memory budget may still cause playback to wait or slow down.

[2026-11-09] — Radial options, frame-duration keypad, Out of Peg gizmo, transform handles and expanded Bleed

- AI model: GPT-6
- Category: Fixed / Changed
- Summary: Re-layered the fullscreen radial menu and detailed options so the options panel receives taps above the radial dismiss surface. Moved frame-duration entry to the timeline toolbar and restored the numeric keypad; the button is available for the current drawing cel even when only one frame is active, and the old floating timer popup above a cel is removed. Reworked Out of Peg into an independent timeline toggle with separate Previous / Following adjustment buttons; the active onion reference can be moved, scaled, rotated, flipped horizontally / vertically, aspect-locked and rotation-snapped on the canvas. Apply leaves the reference pose in place and exits adjustment mode; disabling Out of Peg hides all reference adjustments and restores the original onion positions. Added selection-interior move support to the selection gizmo and enabled Cut-draft resizing from all edges / corners, respecting dimension and aspect locks. Increased Bleed overscan from a fixed 256 px to an adaptive 1024–1536 px preference, reduced as needed to keep each expanded bitmap near 64 MiB; overscan is disabled for Cuts where even a 64 px border would exceed that budget; exports remain cropped to the Cut.
- Reason: Correct the radial-menu tap interception, restore the former frame-duration keypad workflow, provide direct canvas-based Out of Peg alignment, repair unresponsive selection / Cut gizmos, and substantially expand drawing bleed without unbounded bitmap allocation.
- Impact: No `.inb` format migration and no protected build configuration changes. Out of Peg remains session-only and does not modify source cels. Bleed remains a display/editing overscan cache over document-space strokes; standard export and thumbnails remain Cut-cropped. Frame duration updates use `Cut.record` for undo/redo.
- Files: `app/src/main/java/com/exemple/inbetween/Editor.kt`, `Engine.kt`, `Canvas.kt`, `Gizmo.kt`, `Panels.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Source-level inspection and ZIP integrity checks only. The environment has no Android SDK / Compose classpath or Gradle wrapper/executable, so no Gradle build, CI run or tablet interaction test has been performed. User must run GitHub Actions before installing.
- Known issues / follow-up: Verify the radial menu remains open while using detailed options; verify numeric keypad from the toolbar changes one selected cel and multiple selected cels and supports undo; verify Out of Peg move / scale / rotate / flip / lock / magnet / Apply and global disable; verify selection interior move and all Cut resize handles with locks; draw beyond all four edges at several Cut resolutions and confirm no memory pressure, undo/redo and export crop. The adaptive overscan is still bounded by bitmap-memory constraints and may be smaller on very high-resolution Cuts.

---

[2026-11-09] — Out of Peg references, transform magnet, layer auto-scroll, fullscreen radial tools and canvas bleed

- AI model: GPT-6
- Category: Added / Changed
- Summary: Added Out of Peg controls to the detailed onion-skin popup. Previous and following onion references for the selected drawing layer can be offset, scaled and rotated independently; reset is available per side or per Cut. These are reference-only transforms and do not alter source drawings. Added a 30-degree rotation magnet toggle to selection, Camera and Transformation-folder transform bars; on-canvas transform rotation and two-finger Camera / selection rotation snap when enabled. Added automatic vertical timeline scrolling while a layer or selected layer set is dragged near the layer-list viewport edges, with scroll displacement included in drop placement. Added a selection chronometer button to the timeline, showing selected frame range, frame count and duration at the Cut FPS. Added a fullscreen floating radial tool puck: long-press and drag outward to unfold Brush / Eraser / Selection / Fill / Shapes, release toward a tool to select it, tap a tool again for its detailed options, and tap away to close. Added a Bleed toggle in the upper bar: a 256-document-pixel overscan cache displays and records strokes beyond the Cut bounds; turning Bleed off clips those strokes from view without deleting them. Normal export remains cropped to the established Cut frame.
- Reason: Complete the remaining requested workflow features after the timeline refactor was confirmed to build successfully.
- Impact: No `.inb` format migration and no protected build configuration changes. Out of Peg reference adjustments are session-only (retained while the current Editor ViewModel remains alive, but not serialized into `.inb`). Bleed strokes are stored as ordinary document-space stroke coordinates; canvas display uses a separate overscan bitmap cache, while export and thumbnails continue to use the Cut-sized renderer. Overscan is limited to 256 document pixels beyond each edge.
- Files: `app/src/main/java/com/exemple/inbetween/Editor.kt`, `Engine.kt`, `Canvas.kt`, `Gizmo.kt`, `Panels.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Inspected the latest source archive and `AI_WORKING_AGREEMENT.md`; reviewed all changed call sites and source diffs; checked ZIP structure and protected-file hashes; ran a Kotlin parser attempt, which could not complete within the available runtime and no Android / Compose classpath or Gradle/SDK is installed. No Gradle build, CI run or tablet interaction test has been performed.
- Known issues / follow-up: Build with GitHub Actions and test on the tablet before relying on the new interactions. Verify Out of Peg previous/following alignment and resets; layer auto-scroll and multi-layer drops; 30-degree magnet on selection / Camera / Transformation; fullscreen radial selection and detailed options; Bleed drawing outside all four edges, hide/reveal without data loss, undo/redo, and export crop. Bleed preview for freehand strokes appears live through a dedicated overscan session bitmap; complex region-transform strokes are reconstructed with overscan-aware source offsets. Very large Cut dimensions may require lowering the overscan cache budget if memory pressure is observed.

---

[2026-11-09] — Substantially split the Timeline composable after Android 13 DEX verification failure

- AI model: GPT-6
- Category: Fixed / Refactored
- Summary: Replaced the monolithic `Timeline` composable with smaller independently compiled composables for layer entries (channel, folder and normal layer rows), ruler, track rows, individual track types, drawing tracks and individual cel cells. Moved timeline selection, landing-index and shared UI state into small top-level helpers / a dedicated state holder. Preserved the existing toolbar, curve controls, selection, cel drag/stretch, key drag, layer reorder, ruler scrub and dialog behavior.
- Reason: Android 13 repeatedly rejected `PanelsKt.Timeline` with `java.lang.VerifyError` (`copy-reference` using an `IntegerConstant`) before the main menu could render. A previous smaller extraction left the large timeline body largely intact.
- Impact: The original ~40 KB `Timeline` body is now a short coordinator (~6.7 KB of source); the heaviest gesture logic is isolated in `TimelineCelCell`, with layer and key rendering in separate composables. No document model or `.inb` serialization changes.
- Files: `app/src/main/java/com/exemple/inbetween/Panels.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Inspected the latest Android bug report and confirmed the exact repeated failure at `PanelsKt.Timeline` / DEX offset `0x520A`. Ran the local Kotlin parser pass on `Panels.kt`; no syntax diagnostics were reported, but Android / Compose dependencies were unavailable, so this is not a successful Kotlin/Gradle build. Reviewed the extracted function boundaries and retained behavior against the supplied source. No CI or device test has been run.
- Known issues / follow-up: Run the repository's clean GitHub Actions build (`gradle clean assembleDebug --no-build-cache --stacktrace`) and install the resulting APK on the Android 13 tablet. Confirm that the startup VerifyError is gone, then verify timeline zoom, ruler scrubbing, multi-selection, key/cel dragging and layer reorder. If the verifier still fails, capture the new exact class/method/DEX offset rather than assuming this refactor fixed it.

[2026-11-09] — GIF palette/timing, playback clock and timeline range/key controls

- AI model: GPT-6
- Category: Fixed / Performance / Added
- Summary: Replaced the fixed GIF color cube with per-frame adaptive median-cut palettes and local color tables; GIF frame delays now use cumulative centisecond rounding to avoid 24/30 fps drift. Playback now derives the playhead from monotonic elapsed time and skips late frames instead of letting camera animation slow under rendering load. Reset playback range now includes visible drawing cels and visible Camera / Transformation key times; Transformation-folder key times contribute to timeline extent. Added Set playback window to selection, selected diamond-key duplicate/delete controls, second numbers on the ruler, camera rotation keys in summary-row selection, zoom-aware range-marker pointer handling, and rename-only-when-selected behavior. Canvas three-finger scrubbing now steps through Camera / Transformation diamond-key times with a purple bump on key hits. Finger contacts show touch rings; touching two fingers together arms a purple canvas tint, and releasing them resets the view (replacing quick-pinch reset). Hover descriptions now open below the upper bar and above the timeline / right toolbar. Canvas view rotation snaps to 30-degree increments at the end of a rotation gesture.
- Reason: User reported GIF artifacts, inconsistent camera pacing, ruler/range misalignment and missing timeline selection/key workflows; also requested 30-degree canvas rotation and selected-layer-only renaming.
- Impact: No `.inb` format change and no protected build configuration changes. GIF color tables are now frame-local; timing remains based on the document FPS. Diamond-key duplicate places the selected key block immediately after its selected time span, replacing any key already at a destination time. Blank cels are included in playback-window Reset because they are timeline keys.
- Files: `app/src/main/java/com/exemple/inbetween/Export.kt`, `MainActivity.kt`, `Editor.kt`, `Camera.kt`, `Panels.kt`, `Canvas.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Source inspection, Kotlin compiler parser pass with expected unresolved Android / Compose / JSON dependencies because no Android classpath is available, and ZIP/diff checks. No Android Gradle build, GIF playback validation, device playback measurement or tablet interaction test was possible.
- Known issues / follow-up: Verify GIF playback and visual quality in multiple viewers, especially 24 fps timing and gradients; verify camera playback at 24/30/60 fps on tablet. Advanced requests still pending: Out-of-Peg / Shift-and-Trace, layer auto-scroll while dragging, magnet widgets for all transform tools, timeline chronometer, fullscreen radial tool selector, and non-destructive draw-beyond-frame clipping. The canvas rotation currently snaps automatically on rotation gestures; per-tool magnet toggle is not yet added.

---

Inbetween — Changelog

«Historical record of project changes. Entries are reverse chronological. Do not delete old entries or rewrite their original AI attributions. Current architecture and feature descriptions belong in "PROJECT_MEMORY.md".»

[2026-11-09] — Add local diagnostics and startup recovery

- AI model: GPT-6
- Category: Added / Reliability
- Summary: Added a local diagnostics subsystem with a process-wide uncaught-exception handler, timestamped log rotation (256 KB maximum), startup-in-progress marker, startup checkpoint logging and ZIP report export. Added a Diagnostics action to the Cut-list screen, a recovery screen for a previous incomplete launch, and a recovery screen when saved editor data fails to load. Reports include app/device version details and diagnostic logs only; they do not include project or drawing files.
- Reason: The user reported that the app returned to Android before showing the main menu and requested a built-in bug-report function that can be used directly on the tablet.
- Impact: A crash before the main UI can now leave a local stack trace for the next launch. Startup data-load exceptions are shown in a recovery UI with an export path; `onStop` skips saving if the initial load did not complete, avoiding overwriting stored data with a partial state. No document format, editor data, Gradle configuration, manifest or build workflow changes.
- Files: `app/src/main/java/com/exemple/inbetween/CrashDiagnostics.kt` (new), `app/src/main/java/com/exemple/inbetween/MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Validation: Static source review and diff/ZIP checks only. No Gradle executable/wrapper, Android SDK build environment or device was available; compilation, GitHub Actions and on-device crash/export behavior remain unverified.
- Known issues / follow-up: Run the normal GitHub Actions debug build, install on the tablet, verify the Diagnostics dialog and ZIP export, then reproduce the launch failure and share the report. The uncaught handler captures Java/Kotlin process exceptions; native-process termination or power loss may only be indicated by the startup marker without a stack trace.

---

[2026-11-09] — Scrub lag fix, Cut settings panel + canvas handles, fullscreen minimal timeline, onion skin Fade out / Turn off when scrubbing

- AI model: Claude Sonnet 5.5
- Category: Performance / Changed / Added
- Summary: (1) Scrub lag with a Transformation folder selected: `DrawingCanvas` recomputed the folder's content bounds (a sampled full-bitmap scan) on every frame change because the cache key contained `cut.time`. Bounds are now scanned once per cel on a background thread (`celContentBounds` in `Gizmo.kt`, cached per cel in `Canvas.kt`, cleared when the cut size changes); until a cel's scan finishes the last box (or the page) is shown. (2) Cut settings is now a side panel (`CutSettingsPanel`, opened by tapping the Cut name) instead of a dialog; values are pending (`CutDraft` in `Editor.kt`) and Confirm applies them and closes it; X closes without applying. While it is open the canvas shows cyan handles (right, bottom, bottom-right) that change the pending width / height (same locks and aspect lock as the fields). (3) Fullscreen minimal timeline in the top bar (hidden when fullscreen is off): compact timeline buttons (`Timeline(mode = 1)`, no Opacity label), a button unfolding a single-layer timeline (`Timeline(mode = 2)`: ruler + the current layer's cels / channel rows, same scrub and frame gestures), and the current-layer button (`MiniLayerButton`: name, visibility, lock, three dots, arrow underneath that unfolds the layer list; tap a layer to select it; hold the current layer to unfold the list and drag it above / under others, using the same `plan` / `moveLayer` as the timeline). In fullscreen the Cut name in the bar is replaced by this strip. (4) Onion skin: hold the onion button for options. "Fade out": the onion skin stays visible while scrubbing and fades out over 0.5 s after the scrub ends. "Turn off when scrubbing": hidden while scrubbing, back when it stops. Both: hidden while scrubbing, appears when it stops, then fades out. A scrub ends when the frame has not changed for 220 ms.
- Reason: User requests (scrub lag on animated transform layers, cut settings panel + transformable canvas bounds, fullscreen minimal timeline, middle ground between flipping frames and onion skin).
- Impact: No `.inb` change. Onion options are session state (not saved). Cut settings no longer apply on each ✓; they apply on Confirm.
- Files: `Canvas.kt`, `Gizmo.kt`, `Editor.kt`, `Panels.kt`, `MainActivity.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: Read all three .md files and the affected sources; script check that braces / parentheses balance in all `.kt` files (all zero); diff review. No Kotlin compiler, Gradle or device here: nothing was compiled or run, and the lag fix is not measured.
- Build fix (same day): CI failed in `:app:compileDebugKotlin` with "Platform declaration clash" on `CutDraft` (the `w` / `h` / `dpi` / `fps` properties generate `setW(int)` etc., which my `setW(v)` functions duplicated). The functions were renamed `changeW` / `changeH` / `changeAspect` / `changeDpi` / `changeFps` (as on `Cut`) and their callers in `Canvas.kt` and `Panels.kt` updated. Not rebuilt locally.
- Known issues / follow-up: Run CI first; least certain to compile: `MiniLayerButton` (`Popup` + drag gesture), the `toolbarContent` / `curveContent` lambdas in `Timeline`, `folderRectNow` in `Canvas.kt`. Verify on device: scrub with a folder selected, long-press drag in the layer list while the popup opens, Slider height in the compact bar. Canvas handles only resize from the right / bottom because the canvas is anchored at its top-left and content is not shifted. The cut-name tap is not available in fullscreen. With Fade out the onion skin is gone while drawing after the fade.

[2026-11-08] — Playback optimization pass (camera / Transformation folder lag)

- AI model: Claude Sonnet 5.5
- Category: Performance
- Summary: Found by code inspection that every playback frame (`cut.time` change) recomposed far more than needed: (1) `Timeline` read the playhead in composition in many places (ruler numbers, every cel's highlight, every key diamond, the expanded X/Y/Scale/Rotation value texts, both playhead lines, the curve-button highlight), so the whole timeline re-ran each frame, worst with expanded Camera / Transformation rows; (2) the Camera / Transformation reset pop-ups read the playhead directly in the editor screen composable, recomposing the entire screen (timeline included, as `Editor`/`Cut` are unstable parameters) each frame while a camera or folder was selected; (3) my new folder handle code in `DrawingCanvas` recomputed the folder content bounds (bitmap scan) on every frame while a folder was selected. Fixes: playhead lines use a layout-phase `offset { }`; ruler numbers (`RulerNum`), channel values (`ChanValue`) and key diamonds (`KeyMark` takes `isNow: () -> Boolean` with `derivedStateOf`) are separate scopes; cel highlight is decided in `drawBehind`; the curve highlight stops following the playhead while playing; the camera / folder pop-ups moved into `TransformBars` (hidden while playing); folder content bounds are cached and never computed while playing; the canvas draw looks up each folder's transform once per draw (`folderTr`) instead of scanning all layers per layer; `evalChan` uses binary search (assumes key lists sorted by time, as `setKey` keeps them).
- Reason: User report of laggy playback with Transformation layers and camera animation.
- Impact: No `.inb` change, same visuals. While playing, the camera aspect-lock / Reset pop-ups and folder Reset pop-up are hidden, and the timeline curve-button highlight does not follow the playhead.
- Files: `Panels.kt`, `MainActivity.kt`, `Canvas.kt`, `Camera.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files untouched.
- Validation: Static inspection of the playback loop, canvas draw and timeline; script bracket check of the new code; diff review. No profiler, compiler, Gradle or device here, so the speed-up is NOT measured and nothing was compiled or run.
- Known issues / follow-up: Run CI, then compare playback with a camera and an expanded Transformation folder before/after. Possible next steps if still laggy: prefetch / bitmap cost of large cels (`CelCache`), drawing rotated full-page bitmaps for folders (cache a pre-transformed bitmap or use a `graphicsLayer`), and the `Timeline` still re-running on structural changes. If key lists can ever be unsorted, `evalChan` would need a sort guard.

[2026-11-08] — On-canvas transform handles (selection, camera, Transformation folder), aspect lock, Reset

- AI model: Claude Sonnet 5.5
- Category: Added
- Summary: New `Gizmo.kt` with a shared transform box: square handles on the four corners (scale), on the four sides (stretch; a pen or finger held anywhere along a side), rotation by dragging just outside a corner, and (pen only) move by dragging inside the Transformation folder box. Used by: (1) the selection (handles show around the selected pixels with the Selection tool; grabbing one starts the transform automatically; handles stay on the live transform), (2) the Camera frame (corners scale the frame and write the scale key, sides stretch the frame's own width/height like the Camera frame panel, outside a corner rotates and writes the rotation key, X/Y keys follow the fixed opposite corner/side), (3) Transformation folders (selecting a folder now shows its content box with a tinted preview and handles; corners/sides scale uniformly, outside a corner rotates, the pen moves it by dragging inside; keys are written at the playhead). A lock widget (padlock) keeps the aspect ratio for selection and camera handles (`Editor.xfLock`; the camera's own aspect lock is also honored). A Reset button was added to the selection transform pop-up, and the camera pop-up now always shows (padlock + Reset; Reset also restores a frame size changed by an on-canvas stretch). Transformation folders get a Reset pop-up when off their default transform.
- Reason: User request for corner/side/rotate handles on every transform feature, an aspect-ratio lock, a reset button and an on-canvas preview for a selected transform layer.
- Impact: No `.inb` format change. Camera and folder handle drags are one Undo step (structure snapshot); selection handles act on the live transform (apply/cancel as before). Fingers use handles only (no inside-move) so panning is unchanged; the pen can also move a folder from inside its box. Folders have a single uniform scale in the data model, so their side handles scale proportionally and the lock widget is not shown for them.
- Files: `Gizmo.kt` (new), `Canvas.kt`, `MainActivity.kt`, `Camera.kt`, `Editor.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`. Protected files (workflow, Gradle, gradle.properties) untouched. The stale duplicate `com/example/inbetween/` folder found again in the supplied archive was left out of the delivered archive (it redeclares the classes of `com/exemple/inbetween/`).
- Validation: Read all three .md files and the affected sources; script check that braces/parentheses balance (same residual counts as the supplied archive in `MainActivity.kt` and `Panels.kt`, which the script's string stripping does not parse exactly); diff review against the supplied archive. No Kotlin compiler, Gradle or device is available here, so no compile, CI build or on-device test was run.
- Known issues / follow-up: Run the GitHub Actions build first; least certain to compile: `Gizmo.kt` Matrix helpers, the handle code in `DrawingCanvas` (`gDown`/`gMove`/`gUp`) and the manual pointer loop in the finger gesture. Verify on device: handle sizes and hit areas with finger and S Pen, rotation ring vs. starting a new selection near an old one, camera corner scale / side stretch / rotate (and Undo), folder box over moving content and while scrubbing (content bounds are sampled every 4 px and recomputed when the frame or drawings change), default lock state (unlocked: corners and sides stretch freely). Not done: "cut settings" has no on-canvas transform in the code (it is the document size dialog), so no handles were added there; non-uniform folder stretch needs separate X/Y scale channels (a V9 `.inb` change and renderer/export changes) and was not started without approval.

[2026-11-08] — Pen-only exact ruler scrub, multi-select, shake between keys, key-only Rasterize, layer icons

- AI model: Claude Sonnet 5.5
- Category: Added / Changed / Fixed
- Summary: (1) Ruler scrubbing is S Pen only (mouse accepted like the pen); finger taps no longer set the playhead and fingers only pan. The pen handler now reads the live cell width (it used a stale value after pinch-zoom, so the playhead missed the pen) and floors to the frame under the pen. The cel hold/drag handler is also re-keyed on the cell width. (2) Double-touch + drag rectangle (finger or pen) selects frames and diamond keys on the grid, or layers on the name column; a tap-gap/distance/slop buffer keeps single taps, holds and pans unaffected. Hold + drag on a selected item moves the whole selection. (3) Curve buttons and ease strength act on selected keys. (4) New shake button next to the curve buttons: menu with Strength, Density (1 = key every frame, 2 = every second frame...), Horizontal lock, Vertical lock; applies between the first and last selected key time of each layer (X/Y only), creating linear keys. The old playhead-shake UI and `ed.camOpt == 2` panel in `CameraOverlays` were removed (`bakeShake`, `shakeSel`, `shakeLen`, `shakeAxis` stay in code unused). (5) Rasterize only at existing key times, plus red lines between matching corners. (6) Icons in the add-layer menu; every layer-column row has a type icon in the same slot (folder collapse arrow takes the eye's slot; name column widened 200 -> 240 dp). (7) "Select all" in the Camera menu and a new three-dot menu on Transformation folders. (8) Removed the stale duplicate source folder `com/example/inbetween/` from the archive (it redeclared every class of `com/exemple/inbetween/`).
- Reason: User requests for exact pen-only scrubbing, bulk editing of frames/layers/keys, bringing back shake, cleaner camera path, consistent layer-type visuals.
- Impact: No `.inb` format change (selection is UI state only). Undo/redo covers bulk moves, interpolation changes and shake. Camera path layers now only mark existing keys.
- Files: `Panels.kt`, `Camera.kt`, `Editor.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`; deleted `app/src/main/java/com/example/inbetween/*` (stale copy). Protected files (workflow, Gradle, gradle.properties) untouched.
- Validation: Read all three .md files and the affected sources; script check that braces/parentheses balance in all `.kt` files; diff review against the supplied archive. No Kotlin compiler, Gradle or device is available here, so no compile, CI build or on-device test was run.
- Known issues / follow-up: Run the GitHub Actions build first; least certain to compile: `marqueeSelect` and `KeyMark` pointer code, `DropdownMenuItem(leadingIcon=...)`, `Popup` with `PopupProperties`. Verify on device: pen scrub 1:1 at several zoom levels and finger-only pan on the ruler; marquee start with finger and pen (buffer feels right, no accidental pans/retime popups); bulk hold-drag of frames, keys and layers (and Undo); shake with different density/locks; Rasterize result; name-column width with "Transformation 2". Notes: the marquee has no auto-scroll at the edges; a quick tap clears the selection; shake creates keys with linear interpolation and no decay; frames move as a reordered block (no gaps); dropping selected layers onto another selected layer does nothing; the tip text for the shake button changed.

---

## 2026-11-10 — Timeline gesture responsiveness
- Increased the canvas two-finger pinch-to-reset activation distance from 80 dp to 160 dp.
- Timeline pinch zoom now keeps the content beneath the fingers anchored instead of zooming around the left edge.
- Two-finger horizontal movement now pans the timeline; one-finger scrolling remains available.
- Reduced the minimum pinch-scale change required to trigger a zoom update, filtering tiny gesture noise.
- Bumped the app version name to 0.4. The existing CI-managed version code remains unchanged.

## Media rendering and audio continuity fixes
- Kept the workspace grid visible when video reference layers are active; in video mode the grid is composited above the reference footage.
- Placed the video TextureView beneath the drawing canvas so it cannot cover artwork, grid overlays, or receive stylus/touch input intended for the canvas.
- Removed audio pause/resume coupling to transient cel-cache buffering. Audio playback now continues through short visual frame-preparation stalls, with drift correction only when the player differs from the timeline by more than 120 ms.


## 2026-11-10 — Media playback and video/grid stacking follow-up

- Removed periodic audio player-position polling and drift-correction seeks during continuous playback. Media3 audio players now seek at clip entry, explicit playhead discontinuities, and while scrubbing; normal playback is uninterrupted by synchronization seeks.
- Added a separate lower-plane grid pass for the Behind setting when video is active. The white page and video are composed above that grid; the Front setting remains in the foreground canvas pass.
- Made the video TextureView explicitly non-clickable, non-focusable, and non-accessible so it does not claim direct user interaction.
- No `.inb` format bump: this patch adds no serialized layer properties. Full arbitrary interleaving of video with individual drawing layers still requires a unified GPU compositor and is not claimed by this patch.


## 2026-11-10 — Video transform handles and behind-grid depth

- Added a dedicated transform context for a selected, visible, unlocked video layer. The video receives its own on-canvas transform box; move/scale/rotate handle gestures update the video layer's saved media transform and create one structural undo step.
- S Pen gestures on a selected video layer are routed to video manipulation instead of drawing. S Pen handle drags use the same transform path; drawing layers continue to receive normal stylus input.
- Finger gestures on selected video layers can manipulate the video, while transform handles are checked before the general gesture path.
- Corrected Behind-grid plane ordering for video: white page < Behind grid < video texture < drawing canvas. This makes the grid visible on the page but beneath opaque video pixels; Front grid remains in the foreground.
- No serialization change: video transforms already use the V11 mediaX/mediaY/mediaScale/mediaRotation fields.
- Validation: source-level diff and ZIP structure review only. No Android SDK/Gradle build or device test was available; CI build remains required.


[2026-11-10] — Shared workspace pivot for page, video and artwork

- Category: Fixed / Rendering
- Summary: Reworked the video page and Media3 TextureView layout so viewport pan, zoom, rotation and flips are applied by a workspace-sized Compose node. Previously, the drawing Canvas transformed around the workspace centre while the page/video views transformed around their smaller page/video centres, causing the footage/page/artwork to drift relative to one another during viewport gestures. The video’s mediaX/mediaY/mediaScale/mediaRotation and parent-folder transform remain local to the video image; the workspace viewport transform is now applied once by the common full-workspace parent. The white page uses the same workspace pivot.
- Compatibility: No `.inb` serialization changes; V1–V11 remain unchanged. Media3 dependency and playback logic unchanged.
- CI: Added `beta` to the existing GitHub Actions push branch filter, preserving `main`, `master`, and manual workflow dispatch.
- Validation: Reviewed the updated Compose transform hierarchy, source diff and workflow trigger; ZIP integrity checked. No Android SDK/Gradle build or on-device gesture test was available, so compilation and runtime behavior remain unverified.
- Follow-up: Build from `beta`; test viewport pinch/pan/rotate with a visible video, then draw with a drawing layer selected and verify page, artwork and video remain registered. Test video-layer local transform separately.


[2026-11-10] — GPU media rendering stage 1: external OES video output

- AI model: GPT-6
- Category: Added / Rendering architecture
- Summary: Replaced Media3 TextureView output with a dedicated transparent GLSurfaceView using OpenGL ES 2.0. Media3/MediaCodec now outputs decoded frames to a Surface backed by SurfaceTexture; the GL render thread samples the external OES texture with a shader and draws it into the transparent media surface. The existing workspace and video-local Compose transforms, clipping and opacity are retained.
- Reason: Begin the GPU compositor migration with a contained video-output path that avoids TextureView's view-hierarchy texture composition and establishes a dedicated GL rendering thread.
- Impact: Video decode remains hardware-decoded by MediaCodec where supported. Audio path, drawing renderer, `.inb` format and export path are unchanged. This is not a unified compositor: drawing, grid and page remain in Compose, and arbitrary drawing/video layer interleaving is not implemented. SurfaceView transparency, z-order, transforms, alpha and lifecycle require on-device validation.
- Files: `app/src/main/java/com/exemple/inbetween/GlVideoSurface.kt` (added), `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle scripts and dependency versions, `.github/workflows/**`, `gradle.properties`, manifest, SDK/Java configuration and signing configuration. `README.md` untouched. The existing workflow's `beta` trigger was checked and already present.
- Validation: source diff and Kotlin parser diagnostics; ZIP structure/integrity. No Android SDK / Gradle executable or device available, so no compile, CI or runtime performance claim is made.
- Known issues / follow-up: Build with beta CI and test transparent video compositing over the page/grid, drawing above video, 100% and partial opacity, local video transforms, viewport gestures, playback lifecycle and surface recreation. Benchmark dropped frames and CPU/GPU frame times against the prior TextureView version before claiming a performance improvement.


[2026-11-10] — Corrected OpenGL video texture orientation

- Category: Fixed / GPU rendering
- Summary: Changed the external-OES quad to use canonical, unflipped texture coordinates. The SurfaceTexture producer matrix is now the sole source of decoder texture orientation, avoiding an additional hard-coded vertical flip. Canvas and video-layer rotations remain separate geometry transforms and are not used to compensate for texture-coordinate inversion.
- Compatibility: No project format, dependency, playback, or export changes.
- CI: Preserved the existing GitHub Actions triggers, including `beta`.
- Validation: Source diff and ZIP integrity checked. No Android SDK/Gradle build or device test was available; verify orientation and rotation on device.


[2026-11-10] — GPU video geometry follows canvas and layer transforms

- Category: Fixed / GPU rendering
- Summary: Removed reliance on Compose graphicsLayer transforms for the GLSurfaceView. The video view now occupies the workspace viewport, while its quad corners are calculated in document coordinates and converted to screen-space OpenGL NDC coordinates. The calculation applies video aspect-fit sizing, Transformation-folder offset/scale/rotation, per-video media offset/scale/rotation, canvas zoom/pan/rotation and horizontal/vertical flips. The SurfaceTexture producer matrix remains responsible only for decoder texture orientation.
- Reason: GLSurfaceView is backed by a separate SurfaceView surface; parent Compose transforms were not reliably applied to it. Correcting texture UV orientation therefore made the video upright but left its geometry fixed during canvas and video-layer rotations.
- Compatibility: No `.inb` format or dependency changes. Existing V1–V11 documents remain compatible.
- Files: `app/src/main/java/com/exemple/inbetween/GlVideoSurface.kt`, `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (including the `beta` trigger), Gradle scripts and dependency versions, `gradle.properties`, manifest, SDK/Java and signing configuration. `README.md` untouched.
- Validation: Reviewed the coordinate mapping against `DrawingCanvas.base()` and the existing video-layer transform model; ZIP integrity and source-level structure checks. No Android SDK / Gradle executable or device is available here, so compilation and runtime rotation tests remain unverified.
- Known follow-up: Verify canvas rotation, pan, zoom and flips; verify video-layer and Transformation-folder transforms independently; check page-edge clipping and transparent composition on device.


[2026-11-10] — GPU video quad orientation and transparent composition regression fix

- AI model: GPT-6
- Category: Fixed / GPU rendering
- Summary: Corrected the screen/document Y-axis mismatch when constructing the GL video quad. GL's bottom-left-first vertex order is now paired with document-space bottom corners, preventing the image from being inverted by geometry mapping while leaving the SurfaceTexture orientation matrix untouched. Restored transparent composition requirements on the GLSurfaceView (transparent background, alpha blending and layer-opacity uniform) so the surface does not intentionally paint black over the Compose page; video layer opacity is passed into the shader.
- Files: `app/src/main/java/com/exemple/inbetween/Media3Video.kt`, `app/src/main/java/com/exemple/inbetween/GlVideoSurface.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**` (the `beta` push trigger remains included), Gradle scripts/dependency versions, `gradle.properties`, manifest, SDK/Java and signing configuration. `README.md` untouched.
- Validation: Compared the current GPU transform implementation with the previously oriented version; reviewed NDC-vs-document coordinate ordering; ZIP integrity verified. No Android SDK/Gradle build or device test available. Transparent SurfaceView composition, video orientation, opacity and transforms still require beta CI and on-device validation.
- Known issues / follow-up: If the canvas still renders black, inspect the actual EGL config selected and Android SurfaceView composition on the tablet; if clipping at rotated page edges is required, implement polygon/page clipping in the GL pass rather than relying on Compose clipping of a separate SurfaceView.


## 2026-11-12 (3) — Fix profiler JVM setter clash
- AI model: GPT-6
- Category: Build fix
- Summary: Renamed the profiler control function `Perf.setHudVisible(Boolean)` to `Perf.setHudVisibility(Boolean)`. The Kotlin property `hudVisible` already generates a JVM setter named `setHudVisible(boolean)`, so the explicit function had the same JVM signature and prevented Kotlin compilation.
- Files: `app/src/main/java/com/exemple/inbetween/PerformanceProfiler.kt`, `app/src/main/java/com/exemple/inbetween/PerformanceHud.kt`, `CHANGELOG.md`, `PROJECT_MEMORY.md`.
- Protected files untouched: `.github/workflows/**`, Gradle scripts and dependency versions, `gradle.properties`, manifest, SDK/Java and signing configuration.
- Validation: Confirmed the change removes the duplicate JVM name at both reported source locations; searched all Kotlin sources for old/new call sites; ZIP integrity and source diff reviewed. No local Android Gradle build or device test was available, so a successful build is not claimed.
- Known follow-up: Run the GitHub Actions build. If compilation advances to further diagnostics, address those independently; then exercise the HUD, recording indicator, frame metrics, media/GL instrumentation and diagnostic ZIP export on device.


## 2026-11-16 — Stronger, persistent perspective brush magnetism

- AI model: GPT-6
- Category: Improved / Drawing interaction
- Summary: Increased the perspective magnet capture radius from 28 to 42 screen pixels. Each brush stroke now latches onto the first horizon, perpendicular or vanishing-point path it approaches within about 25° and enters the capture radius, then remains projected onto that same path until pen-up rather than dropping the attraction whenever the nib moves outside the capture radius. Curvilinear vanishing-point paths use denser sampling for smoother projection.
- Pressure and tilt: Magnetism changes only sample positions; the existing pressure / tilt values continue through the brush engine unchanged.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `app/src/main/java/com/exemple/inbetween/Canvas.kt`, `app/src/main/java/com/exemple/inbetween/WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: `.github/workflows/**`, Gradle wrapper / build scripts, `gradle.properties`, manifest, SDK / Java and signing configuration.
- Validation: Reviewed the source diff against the supplied ZIP; confirmed the existing `KeyboardOptions` import remains `androidx.compose.foundation.text.KeyboardOptions` and `KeyboardType` remains unchanged; ZIP integrity checked. No Gradle wrapper / executable or Android SDK is present in this workspace, so no compile, CI or tablet runtime test was performed.
- Known follow-up: Verify capture feel and path persistence on the S Pen, including strokes that cross from one guide's vicinity toward another; confirm expected behavior for very short strokes and curved vanishing-point paths.


## 2026-11-16 — Horizon-stable curvilinear rays, focal-length VP scaling and hover-gated magnetism

- AI model: GPT-6
- Category: Improved / Perspective tools and stylus input
- Curvilinear mode now leaves the guide axis (including the horizon) perfectly straight and draws an adjustable radial fan from each vanishing point. Rays parallel to the guide stay flat; curvature increases progressively for rays angled farther away from the horizon and as each ray extends away from its VP. One VP is sufficient.
- Added a per-guide ray-count button and slider (8–48, even counts; default 24). Ray counts are saved per guide; V22 also stores physical focal length directly.
- Focal length now scales all vanishing-point offsets around the optical centre by the focal-length ratio, so lens changes visibly reposition the VPs. The horizontal FOV remains mathematically linked to focal length for the 36 mm sensor model.
- Brush magnetism now uses the S Pen hover cursor's recent movement direction as intent. It only acquires a guide or ray when hover direction and initial stroke direction align with that candidate; without a recent hover trajectory, ordinary brush strokes are not magnetized. The per-stroke latch remains.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle wrapper/build scripts, `gradle.properties`, `.github/workflows/**`, manifest, SDK/Java and signing configuration.
- Validation: Source inspection, focused static checks and ZIP integrity only. No Android SDK / Gradle executable is present, so no compile or tablet runtime test is claimed.
- Follow-up: Test the hover direction gate on the actual S Pen, confirm ray density and shape at multiple zoom levels, and check focal-length changes with vanishing points on both guides.


## 2026-11-16 — Vanishing-point ray authority and line-tool magnetism

- AI model: GPT-6
- Category: Fixed / Perspective magnetism
- Curvilinear ray families now connect adjacent vanishing points along each guide. The two central rays coincide with the straight guide; circular-arc curvature increases quadratically away from the horizon and each curve terminates at the neighbouring VP. A single VP retains a radial fallback fan.
- Added a persisted V23 toggle to choose between vanishing-point ray magnetism and horizon/perpendicular guide-line magnetism. In VP mode, VP paths are authoritative and the two guide axes do not compete; if no VP exists, guide axes are a safe fallback.
- Restored line-tool magnetization. The straight-line shape tool uses the same hover-guided latch as brush/eraser and follows the selected curved path when curvilinear mode is active.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `Canvas.kt`, `Store.kt`, `WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle wrapper/build scripts, `gradle.properties`, `.github/workflows/**`, manifest, SDK/Java and signing configuration.
- Validation: Static source and persistence review only; no Android Gradle build or tablet test was available.
- Follow-up: Validate actual S Pen hover capture and line/brush magnet behavior on device, including multiple VP arrangements.


## 2026-11-16 — Direction-aware and averaged perspective magnetism (V24 source revision)
- AI model: GPT-6
- Category: Fixed / Perspective magnetism
- Summary: Magnet acquisition now selects the VP family aligned with the initial stroke or recent hover direction, so horizontal strokes prefer the horizon family and vertical strokes prefer the perpendicular family. Curvilinear candidates are now the pointwise average paths between adjacent rays in each neighbouring-VP family, preventing the magnet from latching directly onto a single nearest ray.
- Persistence: No format change; V23 project storage remains current and older files remain compatible.
- Files: `app/src/main/java/com/exemple/inbetween/Camera.kt`, `app/src/main/java/com/exemple/inbetween/WhatsNew.kt`, `PROJECT_MEMORY.md`, `CHANGELOG.md`.
- Protected files untouched: Gradle wrapper/build scripts, `gradle.properties`, `.github/workflows/**`, manifest, SDK/Java and signing configuration.
- Validation: Reviewed the modified selection and curve-generation logic; Kotlin parser checks and focused geometric harness checks are pending. No Android SDK / Gradle executable is available in this workspace, so no full Android build or tablet test is claimed.
- Follow-up: Verify horizontal and vertical line-tool strokes and averaged curved brush strokes on the S Pen, including rotated guides, single-VP and multi-VP setups.
