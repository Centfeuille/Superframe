package com.example.inbetween

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In-app release notes shown once after an update.
 *
 * MAINTENANCE RULE: every accepted change that a user can notice adds a [ReleaseNote] at the TOP of
 * [WhatsNew.notes] with the next (higher) [ReleaseNote.id]. The window appears on startup whenever the
 * highest id is greater than the id the user last dismissed. Never reuse or renumber ids.
 * Write plain language; do not claim a fix that has not been verified on a device.
 */
data class ReleaseNote(val id: Int, val date: String, val title: String, val items: List<String>)

object WhatsNew {
    const val PREFS = "whats_new"
    const val KEY_SEEN = "seen_id"

    /** Newest first. */
    val notes: List<ReleaseNote> = listOf(
        ReleaseNote(
            77, "2026-11-23", "Ghost tracing: open a closed ghost line, close an open one",
            listOf(
                "Tracing a closed (ring) ghost line with an open line now keeps the ghost's anchor numbers: the line starts at the ghost's lowest number and ends at its highest, and the numbers in between are paired with your bends in order. The ring is opened where its numbers wrap, so where you draw and how far you are from the ghost do not matter.",
                "Tracing an open ghost line with a line that closes (with Merge, or a closed shape) now keeps the ghost's numbers too: the ghost's two end numbers become ordinary tips of the ring and everything is paired round the ring in the ghost's number order.",
                "Open-over-open and ring-over-ring traces are unchanged. Not compiled or tested on a device yet."
            )
        ),
        ReleaseNote(
            76, "2026-11-23", "Tracing a closed ghost line that self-merges",
            listOf(
                "With Merge on, a line you trace over a closed (ring) ghost line and close onto itself now takes the ghost's anchor numbers, like any other trace, instead of getting brand-new numbers. Everything else about anchors and ghost tracing is unchanged.",
                "Not compiled or tested on a device yet. If it still invents new numbers, tell me whether the ghost was a ring or an open line."
            )
        ),
        ReleaseNote(
            75, "2026-11-23", "Extending a line no longer fuses it with a neighbour; ghost traces of extended lines",
            listOf(
                "With Merge on, only the end of the line you just drew joins other lines. If the line you extend merely touches a third line at its far end, that line is no longer pulled into the merge: the extension joins the one line and nothing else. The same goes for snapping an end onto another line's body.",
                "Tracing a ghost line that was extended from its first end: the traced line now takes the ghost's lowest number at the end where you start, so it comes out 1 to 6 in drawing order instead of reversed.",
                "Not compiled or tested on a device yet."
            )
        ),
        ReleaseNote(
            74, "2026-11-23", "Interpolation drag: Smooth slider",
            listOf(
                "New Smooth button in the Interpolation drag bar opens a slider that sets the smoothness of the picked preview lines (all lines when none is picked). It evens out the curves between the line's points without adding any: the points only shift a little, and the ends, numbered anchors and joint points stay where they are. The Reset button also puts the smoothness back.",
                "The smoothness is part of the in-between you create (it is not saved on its own). Not compiled or tested on a device yet."
            )
        ),
        ReleaseNote(
            73, "2026-11-23", "Tracing a ghost line: numbered in drawing order, wherever it is drawn",
            listOf(
                "A line you trace is numbered in drawing order: the end where you start takes the ghost's first number, the end where you finish its last. How far your line is from the ghost, or which ghost end it is near, no longer matters, and neither does its shape. Only a line drawn directly on top of the ghost still takes its direction from the way it runs along the ghost.",
                "Not compiled or tested on a device yet. If a trace still comes out reversed, tell me where the ghost and your line started."
            )
        ),
        ReleaseNote(
            72, "2026-11-23", "Interpolation drag: Shift handles, Select, joints that follow the preview; Displace on the preview",
            listOf(
                "Shift now shows transform handles around the preview: drag inside to move, the corners and sides to scale, and outside a corner to rotate (the rotation magnet and the aspect lock of the selection tools apply). A new Reset button puts the preview, or the picked lines, back where the interpolation puts them.",
                "New Select button in the Interpolation drag bar: tap a line of the preview to pick it, or drag a rectangle to pick several (picked lines turn orange). With Shift on, the handles then move, scale and rotate only the picked lines, independently of the others. Tap on nothing to clear.",
                "Guide joints are now attached to their line: moving, scaling or rotating the preview carries the joints along instead of warping the interpolation.",
                "Displace (selection tool): once a joint warps the selection, tap the orange preview to add the next joint, not the original line underneath it.",
                "After joints are applied (interpolation and Displace) the line is thinned again with the same curve-aware fit used for lines you draw, so it does not keep the many extra points the warp needs. Joint points, anchors and ends are kept.",
                "Not compiled or tested on a device yet. The .inb format is unchanged; Shift transforms and picked lines are not saved."
            )
        ),
        ReleaseNote(
            71, "2026-11-22", "Auto anchors rewritten: ends and curve tips, working with the ghost line",
            listOf(
                "A new line gets an anchor on each end and on the apex of each bend. A line traced over a ghost line takes the ghost's two end numbers (the end where you start is matched by where you start along the ghost) and its in-between numbers at its own apexes, in order.",
                "Draw a trace in several pieces with Merge on: the end where you started stays, the other end moves to the new end as pieces join, and the apexes follow the merged line. A ghost line from an older version that only had end anchors gets its tips the first time you trace it.",
                "Not tested on a device yet. If anchors land in the wrong place, tell me which case it was."
            )
        ),
        ReleaseNote(
            70, "2026-11-22", "Interpolation drag: Joints, Fixed and Shift; selection tool: Displace",
            listOf(
                "The guide joints now live in the Interpolation drag bar (the Joints button); the separate guide-joint button and bar are gone. In that bar, Fixed keeps the in-between at the position of the previous keyframe while its points still move relative to each other, and Shift lets you drag the preview to another position like any selection (the refresh button puts it back). The created in-between includes the shift.",
                "New Displace button in the selection tool: with lines or points selected, tap a selected line to add a joint and drag it; the selection warps with the same smooth even pull as guide joints. The check button applies it in one undo step, the refresh button removes the joints, and turning Displace off cancels it. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            69, "2026-11-22", "Guide joints warp the line evenly",
            listOf(
                "A guide joint now pulls the line smoothly: the line is exactly on the joint, and between two joints it changes evenly from one joint's offset to the next, with no sharp kinks at the attached points. Away from a joint the pull fades out gradually.",
                "The line is also given more points before it is warped, so a line with few points no longer shows corners. Your joints and their positions are unchanged, and the .inb format is unchanged. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            68, "2026-11-22", "Ghost line stays visible with the selection tool, with a framing window",
            listOf(
                "With Ghost on, the ghost line and its anchors now stay visible when you switch from the brush to the selection tool, so you can see where to place your anchor points.",
                "When you zoom or pan and the ghost line is partly or completely out of the visible canvas, a small window appears in the upper left. It frames the whole ghost line in the same orientation as the canvas, with its anchors, and an amber rectangle shows the part of the page you are looking at. The window disappears as soon as the ghost line is fully visible. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            67, "2026-11-22", "Ghost tracing keeps only the end anchors",
            listOf(
                "When you trace a ghost line, the new line now takes only the ghost's two end anchors. Anchors between the ends on the ghost are ignored, so nothing is placed or piled up between the ends of the line you draw.",
                "Drawn in several pieces, the first piece gets the ghost's start anchor and the last piece its end anchor. Closed lines are unchanged. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            66, "2026-11-22", "Tracing a ghost piece by piece no longer piles anchors up",
            listOf(
                "When the drawing has moved away from the ghost and you trace the ghost in several pieces, each piece now takes the next ghost anchors at the ghost's own spacing, starting where the previous piece stopped. Before, the first short piece received every in-between anchor squeezed into a few pixels, and anchors 1 and 3 could end up swapped.",
                "The direction of the ghost is now read from the ghost's own shape near the piece (short pieces) or from comparing the two shapes (a full line), instead of from the straight line between the line's two ends. Anchors that do not fit in a piece stay free for the next piece. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            65, "2026-11-22", "No more automatic in-between anchors on open lines",
            listOf(
                "Open lines now get automatic anchors only on their two end points. Anchors between the ends are no longer detected automatically; they are inherited from the ghost when you trace it, or placed by you.",
                "Automatic in-between anchors that older drawings already contain are removed the next time that line is refreshed (for example after a merge). Closed lines are unchanged. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            64, "2026-11-22", "Ghost curve anchors follow the traced contour",
            listOf(
                "When tracing a ghost, automatic intermediate anchors now use ordered projection onto the actual drawn path, so curve apexes follow the corresponding contour feature even when the trace is stretched or compressed.",
                "Overlap detection now considers all reference anchors, including ones already claimed by earlier traced pieces, so a partial trace is not mistaken for a complete trace. Existing anchor numbers and order are preserved. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            63, "2026-11-22", "Guide joints shape interpolated in-betweens",
            listOf(
                "Turn on Guide joints and tap the interpolated ghost curve to add a joint. Drag a joint to reshape the in-between locally; select it and use Delete to remove it. The joints are separate from correspondence anchors and do not modify either original keyframe.",
                "Guide joints are stored on the interpolation span and are included in live previews, dragged in-betweens and baked interpolation. New .inb files use V27 only when guide joints are present; older V1–V26 files remain readable. Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            62, "2026-11-22", "Ghost smoothing preserves curve apexes",
            listOf(
                "Ghost-traced strokes now use one continuous curve fit instead of fitting separately between anchors. Correspondence anchors remain non-removable, but the fitter keeps the neighbouring tangent context around each anchor so an apex is not treated as an artificial endpoint or corner.",
                "Structural endpoints and merge endpoint aliases remain protected. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            61, "2026-11-22", "Ghost-traced vector lines keep anchors with fewer points",
            listOf(
                "When drawing over a ghost onion-skin line, the new vector stroke reuses the same curve-aware point fitting as ordinary vector brush strokes. Correspondence anchors are retained as protected samples during fitting.",
                "Structural endpoints and merge endpoint aliases are also protected. No .inb format change. Full Android build and tablet behavior remain unverified."
            )
        ),
        ReleaseNote(
            60, "2026-11-22", "Curve anchors follow the actual curve extremes",
            listOf(
                "Automatic curve anchors now target the apex of each curve lobe: the point furthest from the chord between that lobe's boundaries. Inflection points split S-curves into separate lobes, while sharp corners remain independently detectable.",
                "When drawing with the ghost onion-skin guide, inherited automatic curve anchors are mapped to the corresponding detected extremes on the new stroke by their order along the path, instead of simply snapping to the nearest point. This is intended to keep each curve's extreme point paired during interpolation. No .inb format change. Full Android build and device behavior remain unverified."
            )
        ),
        ReleaseNote(
            59, "2026-11-22", "Adaptive anchors follow the shape of each curve",
            listOf(
                "Automatic intermediate anchors are now detected from meaningful shape features: pronounced bends, curvature peaks and S-curve inflections. Simple curves receive fewer anchors; complex contours can receive more, up to a safety limit of 24.",
                "The existing tip-sensitivity control still affects feature detection. Automatic anchors avoid crowding inherited or manually placed correspondence anchors, while structural endpoints and ghost anchor identities remain protected. No .inb format change. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            58, "2026-11-22", "Endpoint anchors are protected from curve-tip renumbering",
            listOf(
                "Structural endpoint anchors and automatic curve-tip anchors now use separate identity pools. Curve tips can only be reassigned to intermediate curve targets; they can no longer take an endpoint's number.",
                "When two anchored stroke ends are collapsed into a merge join, both endpoint identities are carried through the join until refresh places them on the outer ends. Ghost-correspondence endpoints are preferred over unrelated new endpoint numbers. No .inb version bump; endpoint metadata uses additional sparse anchor entries. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            57, "2026-11-22", "Automatic curve anchors restored alongside ghost correspondence",
            listOf(
                "Open vector lines once again receive automatic anchors at sharp curve tips as well as at their two outer endpoints. The tip-sensitivity setting continues to control how gentle a turn can be before it receives an anchor.",
                "When lines are merged, automatic curve-tip anchors are recalculated while structural endpoints keep their correspondence identities and move to the outer ends of the complete merged line. When tracing a ghost, its interior curve anchors are inherited with their original numbers and order, alongside the endpoints. No .inb format change. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            56, "2026-11-22", "Ghost anchor numbers survive merging traced strokes",
            listOf(
                "When a ghost-traced line is extended by merging another stroke, its structural endpoint anchor numbers from the ghost now take priority over unrelated automatic numbers brought in by the other stroke. The ghost numbers move to the new outer ends.",
                "Numbers shared with neighbouring keyframes are still avoided when genuinely new automatic anchors must be created. Existing correspondence identities are preserved through the merge. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            55, "2026-11-22", "Ghost-traced lines keep the reference anchor numbers",
            listOf(
                "A line traced from a ghost now keeps the available anchor numbers from that reference line, even when another piece of the same line has already used some of them. The remaining anchors still map in their original order and by their relative position when the new drawing is offset from the ghost.",
                "A ghost-traced stroke no longer receives unrelated fresh endpoint numbers when some reference anchors are already in use. This preserves the correspondence that the ghost guide is meant to provide. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            54, "2026-11-22", "Vector interpolation anchors stay ordered through Merge",
            listOf(
                "Open vector strokes now use automatic anchors only at the two outer ends. Curve-tip anchors are no longer generated on open strokes; add intermediate correspondence anchors where you need to guide interpolation.",
                "When separate strokes are merged, their internal join points are not treated as endpoints. The two structural endpoint anchors are refreshed onto the outer ends of the complete merged stroke, while intermediate anchors keep their identities and point order.",
                "Ghost-traced endpoint anchors are marked as structural so they can follow the outer ends when traced pieces are merged. This changes anchor behavior only; the .inb format is unchanged. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            53, "2026-11-20", "Auto anchors and the line ghost",
            listOf(
                "Auto anchors (new anchor icon in the vector draw bar, on by default): a new line gets numbered anchors at its two ends and at the tips of its curves. The Tune button next to it sets how gentle a turn counts as a tip.",
                "Merging keeps them up to date: when a line is merged into another (drawing, or Merge points) or points are deleted, its automatic anchors are redone. A join that is no longer an end or tip loses its anchor, new ends get one, and the points that stay keep their numbers. Anchors you set by hand are never touched.",
                "Ghost (sparkle icon): the next line of the previous keyframe shimmers on the canvas with its numbered anchors. Draw over it and your line takes its anchor numbers, placed along your line in the same order (drawn the other way round is fine), so it matches that line when you interpolate. The line you trace disappears from the ghost and the next one shows. A line you draw closer to another ghost line is matched with that one, and the skip button shows a different one.",
                "The arrow icon next to the ghost switches it between the previous and the next keyframe. If the ghost line has no anchors yet, it gets automatic ones first (its own undo step).",
                "A line drawn without the ghost gets numbers that the neighbouring keyframes do not use, so it never pairs with a line it was not meant to.",
                "Saved files stay compatible: automatic anchors are stored as extra entries that older versions ignore. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            52, "2026-11-20", "Keypad for every number, clone numbering, system bars stay hidden",
            listOf(
                "Cloned frames show the number of the frame they copy on the timeline. Frames are numbered by drawing, so a clone does not use up a number: the next new drawing takes the next one. Named inbetweens (3a...) keep their names, and Set inbetweens counts from the same numbers.",
                "Every number you type now uses the keypad of the frame-length chronometer instead of the Android keyboard: cut width, height, aspect, DPI and frame rate, the new cut form, folder transformation values, the perspective lens (FOV and focal length) and the number next to the sliders. Names are still typed with the normal keyboard.",
                "The keypad adds + , − , × and ÷ (and ± and a decimal point where the number can have one): tap a value, tap × then 2, then OK. The line shows the sum and a live \"= result\"; = collapses it.",
                "The number opens selected as a whole: tapping a digit replaces it at once, tapping an operator continues from it. Tap the number to select it again.",
                "Dialogs no longer bring the Android status and navigation bars back, and they stay hidden after the keyboard closes.",
                "Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            51, "2026-11-20", "Icons instead of text on the vector widget bar and the timeline Selection button",
            listOf(
                "The vector widget bar now uses icons: Merge (while drawing), Points, All layers, Lock weight, the Shift / Smoothen / Distort brushes, Falloff, Select all, Deselect, Delete, Merge points, Hide / Show, Smooth and Anchor. A toggle that is on is highlighted, a button that needs a selection is dimmed until you select something, and holding or hovering shows what each one does and its current state.",
                "The timeline Selection button is an icon too, highlighted while timeline selection mode is on.",
                "Nothing else changed: the buttons do what they did. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            50, "2026-11-20", "Copy and Paste for the selection",
            listOf(
                "Copy button on the selection widget bar: on a drawing or background layer it copies the selected pixels (nothing is cleared); on a vector layer it copies the selected lines, or in Points mode only the selected stretches of lines.",
                "Paste button next to it, pressable on the same layer and frame or on any other layer, frame or cut. Drawing pixels paste onto drawing and background layers, lines onto vector layers; on a layer that cannot take what you copied it tells you why.",
                "With the Selection tool on and nothing selected on a drawing or background layer, a small Paste widget appears at the bottom right as soon as something is copied.",
                "Pasting back onto the frame you copied from shifts the copy a little so it does not hide the original. Pasted pixels end up selected (tap Transform to move or scale them); pasted lines stay selected. A pasted line keeps its id on another keyframe (so interpolation still sees the same line) and gets a new one on a clash. Live fills are pasted as baked fills.",
                "The copy is kept for the session only (it is not saved in the project). Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            49, "2026-11-20", "Interpolation drag only touches the selected lines",
            listOf(
                "With lines selected, the interpolation drag now blends only those lines of the last frame. Each one finds its partner in the first reference frame by its anchor numbers (the most shared numbers win), or by being the same line (same id). No other line of the first frame takes part and nothing fades in or out any more; a selected line with no anchor or same-line partner simply stays as it is.",
                "Three-frame mode: the selection is read for the last selected frame, even if you made it on another frame or moved the playhead afterwards (before, moving the playhead could drop the selection and every line was interpolated). If the selected line is not found in the last frame, nothing is interpolated and the bar says so.",
                "The created frame keeps the lines in their original stacking order (a blended line replaces its own line in place).",
                "Without a selection nothing changes. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            48, "2026-11-19", "Merge anywhere along a line, Merge on the point selection bar, interpolation drag on three frames",
            listOf(
                "Lines now merge anywhere along their length, not only at their ends: with Merge on, a line you draw that crosses another gets a shared point at the crossing, and an end that lands on the body of another line snaps onto it (as before).",
                "The Merge button moved to the point selection bar (Points on) and was rebuilt. Two selected points merge whatever the distance: end + end join the lines (the two ends of one line close it), an end + a point of another line puts the end on that point, two inner points meet in the middle. One selected point merges with the nearest other line within the Merge radius (an end joins an end, otherwise it snaps onto the line). Lines selected as whole lines only merge their ends, within the radius.",
                "The shimmer no longer shows where lines have already been merged (a shared point at a crossing, an end sitting on a point of the other line). Two lines that only meet end to end still shimmer, since Merge would join them into one.",
                "Interpolation drag with exactly three frames selected in the timeline: the first and last selected frames are the references and the in-between lands in the middle selected frame when you press the check mark. No frame is created. Selected lines limit it to those lines. With the playhead on the middle frame you see a preview. With two, or four or more, frames selected the bar asks for three; with none or one it works as before.",
                "Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            47, "2026-11-19", "Shimmer where lines are not joined, hide points, interpolation drag on the selection, numbered anchors",
            listOf(
                "Selection tool: places where separate lines cross, or where the end of a line touches another line, shimmer so you can see they are not joined (use Merge to join them). The reach follows the Merge radius. It pauses while you drag.",
                "Point selection: a Hide button makes the selected points invisible and Show brings them back. A hidden point has no width: the line tapers out toward it and a stretch between two hidden points is not drawn. The point stays editable (drawn as a hollow ring) and keeps interpolating. Older builds show hidden points as anchors.",
                "Interpolation drag: with lines selected, only those lines are interpolated (the bar says how many); the other lines stay as they are. With nothing selected it interpolates everything, as before.",
                "Anchors are numbers now: the number is written outside the point and pairs it with the same number in the next keyframe. The colour is only a preview and changes with how many anchors the line has (three anchors: blue, red, yellow; more anchors blend between them). Up to 99 anchors per line. Old files keep their pairing (anchor 1 to 12 are numbers 1 to 12).",
                "Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            46, "2026-11-19", "Vector fills reach small corners",
            listOf(
                "A fill used to stop short of narrow, sharp corners, leaving a gap between the colour and the lines. The outline of the region is no longer smoothed inward, and the fill is drawn with crisp corners instead of rounded ones.",
                "This also applies to fills you already have (they are drawn that way now); tap Refill or All frames to rebuild the outline of a live fill with the new tracing. Rounded edges of curved regions stay smooth. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            45, "2026-11-19", "Merge in the Line and selection tools, sharper joins, eraser across layers, cleaner Duplicate layer, Copy frame copies the selection",
            listOf(
                "Selection tool with Points off (whole lines): a Merge button joins the selected lines end to end, closes a line whose ends meet, and snaps an end that lies on another selected line, using the Merge radius shown above the bar.",
                "The Line / shapes tool on a vector layer also has the Merge button (the same switch as the Brush tool, with its Radius): a line or shape you draw that ends on another line joins it. Closed shapes are not merged.",
                "Merge no longer rounds the joint so much: a new Sharp joins slider (in the Merge panel of the Brush, Line / shapes and selection tools) keeps the corner crisp. 0 is the old rounded look; the default is 70%. It applies to lines joined from now on, and to the whole joined line, so its other bends of more than about 30 to 55 degrees also stay corners. Older builds open these files but show them rounded.",
                "The vector Eraser now works on every visible, unlocked vector layer when All layers (selection tool) is on. Each layer is cut at its own crossings; it is one undo step.",
                "Duplicate layer keeps cloned frames as clones of each other in the copy (before, every clone became its own frame). Duplicate but empty is unchanged.",
                "Copy frame now copies all selected frames (or every frame after three-dot Select all), placed after the last selected one. With nothing selected it copies the current frame as before. The keyboard shortcut does the same.",
                "Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            44, "2026-11-19", "Line weight bar: stronger irregular weight, finer scale, bottom-left and smaller",
            listOf(
                "Irregular now goes up to 300% (it stopped at 100%). Past about 130% the thin parts reach the minimum width and the thick parts the maximum, so the line gets very uneven.",
                "Irreg. scale now goes down to 0.5 px (it stopped at 4 px), for a very fine, jittery variation. Lines saved with the old limits look the same.",
                "The Line weight bar now sits at the bottom left of the canvas and is 25% smaller (it is scaled, so it keeps all its controls). Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            43, "2026-11-19", "Line weight: irregular weight, anti-aliasing, light dial and live preview",
            listOf(
                "Irregular adds a hand-inked swelling of the width along the line, independent of its curves. Irreg. scale sets how long each swell is. The variation is tied to the line itself, so the same line looks the same on every frame.",
                "Anti-alias slider: left is Off (hard, pixel edge), the middle is the standard smooth edge, and the right part adds a soft feather of up to 3 px. It only changes the lines when you move it.",
                "The light direction is now a dial: drag or tap on the circle and the arrow points to where the light comes from (snaps every 15°). Light strength is the slider beside it.",
                "Preview shows the current settings on the canvas for the selected keyframes (or the current one) without changing the drawing. Nothing is saved or put on the undo list until you tap Apply or All frames, and it switches off when you close the bar. Exports always use the real lines.",
                "Limits: the preview does not cover in-between frames made by live interpolation; the soft anti-alias edge is not shown while a line is being drawn. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            42, "2026-11-19", "Line weight for vector lines",
            listOf(
                "New Line weight button on the timeline bar (vector layers). It designs the weight of lines the way modern anime inking does: Curvature makes tight bends heavier and straight stretches lighter, Light with Light from makes lines facing away from the light heavier and lit ones lighter, and Taper in / out run the width out at the start and end of each line.",
                "Apply sets it on the selected keyframes (or the current one), All frames on every keyframe of the layer, Off clears it. It is stored on each line and applied only when the line is drawn, so your points and widths are untouched, you can change it again at any time, and in-betweens from interpolation get the same weight. Fills are not affected.",
                "Limits: the weight is symmetric around the line (no thicker outer side), the light uses one direction you pick per Apply, and the effect is not previewed before Apply. Files saved with it open fine in older builds, but without the weight. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            41, "2026-11-19", "Clone with Select all, calmer Refill, interpolation drag takes time from the previous frame",
            listOf(
                "Clone frame now clones every frame of the layer after \"Select all\" from the three-dot menu, the same as selecting them by hand (or double-tap and drag).",
                "Refill and All frames no longer freeze the app on a long layer. The work is done one fill at a time so the screen keeps responding, the button shows the percentage while it runs, and the result is still one undo step.",
                "Creating a dragged in-between now takes its frame from the previous keyframe when that lasts more than one frame, so the current keyframe stays where it is. If the previous one is a single frame, the old behaviour applies (the retime toggle decides whether the current frame gives way or everything is pushed later).",
                "Not compiled or tested on a device after this change."
            )
        ),
        ReleaseNote(
            40, "2026-11-18", "Vector Fill tool, and lighter vector transforms",
            listOf(
                "The Fill tool now works on vector layers. A tap fills the closed area around it (using the same gap closing and Grow as the raster Fill) with a smooth, editable colour shape. The fill sits under all lines and is grown under them, so there is no gap between colour and ink. You can select, move, transform and point-edit it like any other line, and it interpolates between keyframes.",
                "Live fill (on by default) remembers how it was made. After you change the lines, Refill recomputes it for the selected keyframes (or the current one), and All frames does it for every keyframe of the layer. With Live fill off the fill is a plain baked shape.",
                "Across layers makes the lines of every visible drawing layer count as walls, so colour can live on its own layer under the line art. Tapping inside an existing fill of the same area recolours it.",
                "Moving, transforming and point-editing vector lines is lighter: only the lines that change are redrawn, and moves are limited to one update per frame. Unselected lines are no longer redrawn on every pen move. When a moved line was below another line, the order is corrected when you lift the pen.",
                "Limits: a fill covers the outer outline of its area (islands inside are covered, lines drawn over them still show), in-between frames from live interpolation morph the fill instead of recomputing it (bake them, then Refill), and Refill runs on the main thread. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            39, "2026-11-18", "Vector lines take more of the brush's look",
            listOf(
                "New vector lines now follow the selected brush's Hardness (a soft brush gives a feathered edge), Texture (the chosen texture, strength, scale and invert, laid over the line and anchored to the page), tip shape (triangle and square change the width of the line), Roundness, Tip angle and Follow direction (a flat, calligraphy-style nib).",
                "Lines drawn before this change look as before. Interpolated in-betweens keep the look of the keyframe they come from.",
                "While you draw, the live preview shows the tip shape but not the soft edge or the texture; they appear when you lift the pen. The Etched tip is drawn as a circle, the Grain slider and the moving-texture option are not used for vector lines, and tips and caps stay round at the ends. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            38, "2026-11-18", "Vector wobble per keyframe, interpolation button back on the timeline",
            listOf(
                "New Wobble button on the vector timeline strip. It opens a bar at the top of the canvas to give the selected keyframes (or the current one) an organic boil: Amount (down to 0.05 px) and Scale (down to 2 px) sliders with fine steps at the low end, Apply, New look (a different wobble) and Off. Every keyframe and every line gets its own wobble, so lines are not read as the same line moved. Your drawn points are not changed; Off brings the clean line back.",
                "In-between frames created by live interpolation have no wobble of their own. Bake them into real keyframes (interpolation settings > Bake) or create them with the drag button, then set Wobble on each frame.",
                "The Interpolation settings button (lines, curve, bake) is back on the vector timeline strip, next to Interpolation drag and Cutter. It works with any vector tool. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            37, "2026-11-18", "Rounder vector turns, brushes that keep your points, selection buttons",
            listOf(
                "Sharp turns in vector lines are now rounded like a pen turning, instead of a straight, chamfered corner. The roundness follows the line width. A full reversal gets a round tip. This also applies to lines you drew before.",
                "The Shift, Smoothen and Distort brushes now only move the points a line already has (the line is a smooth curve through them). A point is added only where the brush touches a stretch of line with no point inside its radius, and it is placed on the curve so the shape does not jump.",
                "With the Selection tool on a vector layer, the regular selection buttons now sit above the vector options: add, erase, invert, lock aspect ratio, rotation magnet and done. The magnet and the aspect lock also work on the vector selection handles.",
                "Rectangles and other shapes drawn on a vector layer get slightly rounded corners. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            36, "2026-11-18", "Vector: Lock weight, anchor shortcuts, brush anti-aliasing",
            listOf(
                "New Lock weight switch in the vector bar (Selection tool). When on, moving, scaling or rotating the selection keeps the line weight; with it off, lines whose points are all selected scale their weight as before.",
                "New keyboard shortcuts for vector points: A sets the anchor on the selected points, Shift+A clears it. They work with the Selection tool on a vector layer and can be changed in Settings > Keyboard shortcuts.",
                "Vector lines now follow the anti-aliasing setting of the brush they were drawn with. Lines drawn before this change stay anti-aliased. The brush's edge softness is not applied to vector lines, only whether the edge is anti-aliased. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            35, "2026-11-18", "Vector tools merged into the Brush and Selection tools",
            listOf(
                "The Vector draw and Point edit buttons are gone from the timeline bar. On a vector layer the regular Brush tool draws vector lines and the regular Selection tool selects and edits points. Interpolation drag and Cutter stay on the timeline bar; tap them again to go back to drawing.",
                "The vector options still sit in their own bar at the bottom of the canvas, separate from the regular tool options: Merge and its new Radius slider with the Brush tool; Points, All layers, the Shift / Smoothen / Distort brushes, Raise, Falloff, Select all, Deselect, Delete, Smooth and Anchor with the Selection tool.",
                "Selection tool: the Points switch is on by default and works like the old Point edit (tap or lasso single points, drag them). Switch it off to select and transform whole lines as before.",
                "Merge has a Radius option (4 to 80 screen pixels) used for the ring and for the join.",
                "A line can now merge with itself: bring its end back to its own start (after drawing some distance) within the Merge radius and it closes into a ring. A ring shows where. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            34, "2026-11-18", "Vector Merge is clearer and more reliable",
            listOf(
                "The Merge switch on the vector draw bar is now a filled \"Merge: On\" / grey \"Merge: Off\" button, so you can see its state.",
                "While you draw with Merge on, a ring appears where the start or the end of your line will join another line. If there is no ring, nothing will be joined.",
                "The reach is the same for the ring and for the join: about 14 screen pixels (or half the line width, if larger), measured from the line's start or end to another line's end or body. Lines that only cross are not joined. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            33, "2026-11-18", "Warmer vector lines, Uniform timing, Smooth on selection",
            listOf(
                "Vector lines are now drawn as a smooth curve through their points, so slight curves no longer show straight segments between points. Sharp corners stay sharp. This also applies to vector lines you drew before. New lines are fitted with few points that follow your hand.",
                "The line you are drawing with the vector brush now looks like the finished line (same width, curve and opacity), so nothing changes when you lift the pen.",
                "Canvas flicker with vector layers (not yet verified on a device): after some vector edits the layer could blank for a moment while it was redrawn, and interpolated frames could jump to the key pose after a stroke. The frame is now prepared before it is shown, and the previous picture stays up until the new one is ready.",
                "Folder three-dot menu: Uniform timing. Drawing and vector layers in the folder take the timing of the layer with the most frames, and from then on a change to the frames or timing of one of them is applied to the others. Layers put in the folder later are not attached automatically.",
                "Layer three-dot menu: Detach timing (for a layer that follows Uniform timing) and Attach timing (for a detached layer in a Uniform timing folder; it takes the timing of the others).",
                "Drawing layer three-dot menu: Duplicate Empty Vector makes an empty vector layer with the same frames and timing.",
                "Smooth now only adds points between the selected points of a line, not along the whole line.",
                "Cuts that use Uniform timing are saved as format V26; other cuts are still saved as V25. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            32, "2026-11-17", "Vector tools: Merge, point brushes, eraser, pen feel",
            listOf(
                "Crash fix (not yet verified on a device): the vector layer's live interpolation built its in-between frames while the screen was drawing, which Android reported as \"Cannot modify a state object in a read-only snapshot\". It now builds them outside the draw pass.",
                "New Merge toggle on the vector draw bar: a new line that ends on another line's end joins it into one line; ending on the middle of a line snaps onto it and shares a point.",
                "Point edit now has Shift, Smoothen and Distort brushes. Each shows a Radius and a Strength slider above the bar. Shift drags points under the brush, Smoothen relaxes a wobbly line, Distort adds a wobble.",
                "The Eraser now works on vector layers: it removes the part of a line under the pen, up to where other lines cross it.",
                "Vector lines now use the selected brush's pressure curve, start/end taper and stabilization.",
                "Bezier curve: the second handle only appears after the first handle has been placed, so it no longer bends the line before you set the first handle.",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            31, "2026-11-16", "Vector selection across layers and Smooth",
            listOf(
                "The Selection tool and Point edit now select, move, transform, delete and anchor points across all visible vector layers when \"All visible drawing layers\" is on. Hold the Point edit button to open that toggle.",
                "Fixed: the Anchor button stayed disabled until the raise toggle was changed.",
                "New Smooth button on the widget bar: a slider adds points where the selected lines bend (more on harder curves, none on straight stretches).",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            30, "2026-11-16", "Bezier curves, automatic anchors and line pairing",
            listOf(
                "Line tool: new Bezier curve shape for drawing and vector layers. Drag the end points, then place and tap each of the two handles.",
                "The Anchor button now assigns the next colour in order by itself; tap it again on an established anchor to open the colour menu.",
                "Interpolation detects same or different lines automatically, pairing strokes by anchor colour first. Line weight interpolates along with the shape.",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            29, "2026-11-16", "Interpolation drag and vector widget bar",
            listOf(
                "New Interpolation drag button: drag on the canvas to place an in-between of the previous and the current vector keyframe, then confirm on the widget bar to create it as a frame.",
                "With the retime toggle on, the new frame takes its time from the current frame; otherwise the current frame and the ones after it are pushed later.",
                "The timeline keeps only Vector draw, Point edit, Interpolation drag and Cutter. Falloff, raise / lower, select, delete, Anchor and interpolation settings moved to a widget bar at the bottom centre of the canvas.",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            28, "2026-11-16", "Reference anchors",
            listOf(
                "Select points on a vector stroke and press Anchor to mark them as colour-coded anchors: primaries first (blue, red, yellow), then secondaries (green, orange, purple), then the six tertiary colours.",
                "Points of the same colour in the next keyframe are interpolated toward each other, so more anchors guide the in-betweens more closely. Baked frames keep the anchors.",
                "Saving now uses format V25. Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            27, "2026-11-16", "Selection, transform and shapes on vector layers",
            listOf(
                "The Selection tool now selects whole vector strokes (rectangle, lasso, polygon, or Magic Wand = the stroke under the pen).",
                "Selected strokes get the transform handles: scale from corners, stretch from sides, rotate outside the corners, drag inside to move.",
                "Line, rectangle, circle and ellipse draw editable vector strokes. Select all / Deselect / Delete appear on the timeline bar.",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            26, "2026-11-16", "Live vector interpolation",
            listOf(
                "Apply an interpolation to a vector keyframe and the frames until the next key now animate live, in the canvas, playback and exports, without baking.",
                "Bake still writes real keyframes; baked keys become plain holds.",
                "Not compiled or tested after this change."
            )
        ),
        ReleaseNote(
            25, "2026-11-16", "Vector layers",
            listOf(
                "New layer type: Add layer > Vector layer. Strokes drawn with the Brush tool stay editable vector lines (simplified with Ramer-Douglas-Peucker); each frame of the layer is a keyframe.",
                "The timeline bar gains a vector toolset: Vector draw, Point edit (tap or lasso points, drag to move; optional raise/lower and falloff radius) and Cutter (draw across strokes to slice them).",
                "Interpolation menu on selected keyframes: Hold, Same line or Different lines, curve and strength, and Bake on 1s, 2s, 3s or a custom step.",
                "Saving now uses format V24; older versions of the app cannot open cuts saved with it.",
                "Not built yet: the OpenGL ribbon renderer (vector strokes are drawn by the existing tile renderer), live interpolation preview without baking, and a custom Bezier editor. Not compiled or tested on a device."
            )
        ),
        ReleaseNote(
            24, "2026-11-16", "Directional and averaged perspective snapping",
            listOf(
                "Horizontal strokes now select the horizontal-guide VP family, and vertical strokes select the perpendicular-guide VP family, instead of snapping to the crosswise family just because it is closer.",
                "Curvilinear magnetism now targets intermediate average paths between neighbouring rays rather than an individual nearest ray, producing smoother, more parallel-feeling curves.",
                "This is a source-level correction based on the reported behavior; build and S Pen testing are still required."
            )
        ),
        ReleaseNote(
            23, "2026-11-16", "Vanishing-point ray magnetism and line-tool snapping",
            listOf(
                "Curvilinear ray families now connect neighbouring vanishing points on the same guide. The horizon paths stay perfectly straight, while curves bow progressively farther away from the horizon and meet both vanishing points exactly.",
                "The brush and eraser magnet follow the displayed curved VP paths. The straight-line shape tool now uses the same hover-guided path selection and can draw along the curved path.",
                "A new perspective control switches magnetism between vanishing-point rays and the horizon/perpendicular guide lines. When VP mode is enabled, VP paths take priority instead of competing with the guide axes.",
                "The magnetism mode is saved in V23 project files. V1–V22 project files remain readable. Source changes are not yet built or tested on a tablet."
            )
        ),
        ReleaseNote(
            22, "2026-11-16", "Horizon-stable curvilinear rays and lens perspective",
            listOf(
                "Curvilinear mode keeps the selected guide perfectly straight. Each vanishing point now emits an adjustable fan of rays; rays parallel to the horizon stay flat, while rays progressively curve farther away from it.",
                "A new ray-count control opens a slider to add or reduce rays per vanishing point. Ray density and physical focal length are saved in V22 projects.",
                "Changing focal length now scales vanishing-point offsets around the optical centre: longer focal lengths move them outward and shorter focal lengths move them inward.",
                "When a perspective layer is visible, brush and eraser strokes are constrained to its guides or vanishing-point rays; they are never left as ordinary freehand strokes.",
                "S Pen hover direction helps select the intended path before contact. If hover data is unavailable, the first contact movement selects the path. Every subsequent sample stays on that path.",
                "V22 stores physical focal length directly. V20/V21 FOV values are converted on load without scaling their already lens-adjusted VP positions; V1–V21 projects remain readable. This source change has not yet been built or tested on a tablet."
            )
        ),
        ReleaseNote(
            21, "2026-11-16", "Curvilinear rays, camera lens and rotation locks",
            listOf(
                "Curvilinear perspective now draws circular ray families between neighbouring vanishing points; every ray meets both endpoint vanishing points exactly.",
                "The brush magnet follows the same curved rays shown on the canvas and stays on the selected ray for the rest of the stroke.",
                "The camera lens control links horizontal field of view and focal length using a 36 mm full-frame sensor model; blue rays show the horizontal view cone without rotating the perspective guides.",
                "Guide rotation and 30-degree snapping are separate toggles. Unlock rotation with 30-degree snapping off for freeform rotation.",
                "The straight-line tool no longer snaps to perspective axes or vanishing-point directions. This source change has not yet been built or tested on the tablet.",
                "Projects are saved as V20. This build reads V1–V20; older app builds may not open V20 projects.",
            )
        ),
        ReleaseNote(
            20, "2026-11-16", "Perspective controls and smoother magnetism",
            listOf(
                "Fixed: the perspective control bar is layered above the drawing surface so its buttons can receive taps.",
                "Changed: only one guide's controls are shown at a time; the bar follows the horizon or perpendicular guide when that guide or one of its vanishing points is touched.",
                "Improved: perspective magnetism eases into a captured path over a short distance instead of abruptly shifting the stroke at the moment of capture.",
                "The changes are source-reviewed but have not yet been built or tested on the tablet.",
            )
        ),
        ReleaseNote(
            19, "2026-11-16", "Adjustable curvilinear strength",
            listOf(
                "New: each perspective guide has its own Curvilinear strength control, from 0% (straight) through 100% (default) to 200% (stronger fisheye curvature).",
                "Changed: the selected guide's curvature strength is saved in the project and restored independently; Reset returns it to 100%.",
                "Older V1–V18 project files remain supported. Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            18, "2026-11-16", "Stronger perspective brush magnetism",
            listOf(
                "Improved: perspective magnetism captures guide and vanishing-point paths from farther away.",
                "Improved: once a brush stroke approaches a horizon, perpendicular or vanishing-point path closely enough, it stays on that path for the rest of the stroke. Pressure and tilt continue to control the brush as before.",
                "Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            17, "2026-11-16", "Perspective layer: live magnetism and guide controls",
            listOf(
                "New: brush strokes are magnetically guided as you draw, not reshaped after lifting the pen. Pen pressure and tilt still drive the existing brush controls.",
                "New: horizon and perpendicular guides have separate icon bars for rotation unlock, 30° rotation, vanishing-point spacing lock, field of view, Curvilinear and Reset. S Pen hover shows each button's description.",
                "New: the intersection of the guides is the optical centre. Curvilinear mode bends the guide and the live brush-magnet path in a fisheye-like way.",
                "Cuts saved by this version use file format V18; older builds cannot open them. Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            16, "2026-11-15", "Selection and fullscreen timeline refinements",
            listOf(
                "Changed: completed selections are shown by the animated outline only, without a blue tint over the selected pixels.",
                "Changed: Magic wand is now an independent toggle; it can be switched on and off without changing the selected Rectangle, Lasso or Point-based tool.",
                "Changed: in the fullscreen minimal timeline, the current-layer bar is at the far left, with the layer up / down buttons immediately to its right.",
                "Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            15, "2026-11-15", "Reference images: easier tap-out, magnet for fingers",
            listOf(
                "Changed: tapping empty canvas with the pen to leave a reference layer now forgives pen jitter (a small wobble no longer counts as a drag), and a tap just outside the picked image (in its handle area) also leaves.",
                "Changed: with the magnet on, two-finger rotation of reference images now snaps in 30° steps while you turn: one image to an absolute angle around its centre, several images by their total turn.",
                "Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            14, "2026-11-15", "Reference images: several per layer",
            listOf(
                "New: pick several images at once when adding a reference; they go into one reference layer.",
                "New: with a reference layer selected, the timeline bar has an Add reference button to put more images in the same layer.",
                "New: tap an image to pick it; the bar then shows Delete reference, which removes only that image (the last one takes the empty layer with it). Undo brings it back.",
                "New: Relative position (reference bar) pins the images to the screen, so they stay where they are however you pan, zoom or rotate the canvas. Press it again to release them. The pin is not saved with the Cut yet.",
                "New: with a reference layer selected, two fingers pinch / rotate / drag the image they are on, or all images of the layer when they are on empty space. This replaces zooming the canvas while a reference layer is selected.",
                "New: tapping empty canvas with the pen finishes the reference edit and returns to the layer you came from.",
                "Fixed: Flip H / Flip V on a reference image mirrored around the wrong point (they now mirror around the image centre, or around all images together when none is picked).",
                "Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            13, "2026-11-15", "Selection, Warp, Puppet Warp and a smarter Fill",
            listOf(
                "New: the selection bar now follows the selection (and its transform) instead of sitting at the top. It has Add / Erase, Invert, Fill and Transform; Shift adds and Alt / Ctrl erases when a keyboard is attached.",
                "New: Warp splits the transformed selection into a grid (Increments slider) and lets you nudge it point by point (Strength slider sets how far the neighbours follow).",
                "New: Puppet Warp: tap to place pins, drag a pin to bend the drawing around the others, tap a pin to remove it.",
                "New: the selection outline shimmers. Rectangle, Lasso and Point selections follow the drawn strokes (Stroke-aware, switchable). Auto fill fills a finished selection at once.",
                "New: with a selection, brush, eraser, shapes and fill only change the pixels inside it (switchable).",
                "Improved: Fill closes small gaps in the outline by itself, no longer leaves a band along the lines when closing gaps, and a tap on a line fills the open area next to it.",
                "Cuts saved by this version use file format V17 and cannot be opened by older builds. Not tested on a device yet.",
            )
        ),
        ReleaseNote(
            12, "2026-11-15", "Less lag: big brushes, scrubbing, onion skin",
            listOf(
                "Changed: brush dabs are now painted on a background thread, so the pen no longer waits for big or soft brushes. The stroke may trail the pen slightly on very large brushes.",
                "Changed: changing frames or scrubbing no longer waits for a drawing to be rebuilt. A frame whose drawing is not ready yet can show empty or incomplete for a moment and then fills in.",
                "Changed: the drawing memory kept for editing now scales with the tablet's RAM (up to 320 MB, was 128 MB), so frames and onion skins are rebuilt far less often.",
                "Changed: the Cut list thumbnails are made in the background, so the app opens sooner (they appear one by one).",
                "These changes come from a diagnostics report and have not been measured on the tablet yet.",
            )
        ),
        ReleaseNote(
            11, "2026-11-15", "Smoother drawing: fewer freezes, faster start",
            listOf(
                "Changed: the automatic crash-recovery backup no longer writes to storage while you draw, which should remove the short freezes that appeared when you put the pen down again after a pause.",
                "Changed: hovering the pen over the canvas no longer redraws the whole canvas, which should reduce the constant background load.",
                "Changed: onion skin with the GPU layers no longer waits for neighbouring drawings; they appear a moment later instead.",
                "Changed: opening the app no longer rewrites every embedded audio / video clip, which should shorten the start when a Cut contains media.",
                "These changes are based on a diagnostics report and have not been measured on the tablet yet; the next report will show whether they helped.",
            )
        ),
        ReleaseNote(
            10, "2026-11-14", "Fixes: undo, onion skin, imports, flatten, media, brush choice",
            listOf(
                "Fixed: undoing a brush stroke is much faster. The pixels the stroke covered are put back instead of redrawing the whole frame (the most recent strokes of the session; older ones still redraw).",
                "Fixed: onion skin no longer makes a frame change wait while neighbouring drawings are prepared; they appear a moment later instead.",
                "Fixed: image sequence and image imports show their pictures again, and Flatten w/ layer below merges the layers again. These were the same bug, and it could also run the app out of memory.",
                "Fixed: video and audio layers keep their clip when the app is reopened. Layers that already came back empty are re-linked when the media file is still on the device (the clip starts at frame 0).",
                "Fixed: the selected brush and eraser are remembered after the app was closed by the system.",
            )
        ),
        ReleaseNote(
            9, "2026-11-14", "Brush tips, textures, flow and a tidier brush page",
            listOf(
                "Added: import any image as a brush stamp (tip) or as a texture with the + button. Transparent shapes or dark-on-light images both work. Hold an imported picture to remove it from the library; cuts keep a copy of the stamps they use.",
                "Added: the tip can be rotated, can follow the stroke direction, and can be squashed with Roundness. Textures have scale, invert, and a choice between fixed to the page or moving with the stroke.",
                "Added: Flow, and an option to make Opacity a limit for the whole stroke, so overlaps no longer build up darker. The old pressure → opacity curve is now called pressure → flow.",
                "Added: Pressure → wetness for the wet mix.",
                "Changed: tips and textures are shown as pictures, the brush preview stays at the top while you scroll the settings, and the settings are in collapsible groups.",
                "Note: files saved from this version use a new format and cannot be opened by older versions.",
            )
        ),
        ReleaseNote(
            8, "2026-11-13", "Brush engine upgrade",
            listOf(
                "Added: Spring (string) stabilization. The brush tip follows the pen on a springy string and finishes its swing when you lift. Pick it in the brush's stabilization methods.",
                "Added: Velocity taper. Faster strokes get thinner, smoothly.",
                "Added: Dab jitter: Scatter, Size, Rotation (triangle, square and etched tips) and Opacity, so strokes lose the beaded look.",
                "Added: Wet mix. The brush takes on the colour already under it and fades back to your ink colour over a distance you set.",
                "Note: files saved from this version use a new format and cannot be opened by older versions.",
            )
        ),
        ReleaseNote(
            7, "2026-11-13", "Curve view fixes and audio feedback",
            listOf(
                "Fixed: the curve view now opens for any expanded Camera or Transformation layer, also after you reload a cut (before, it only showed when that layer was selected).",
                "Changed: the channel list (X, Y, scale, rotation) is on the left again, where the layer names were.",
                "Audio clips now say \"analyzing audio…\" while the waveform is being prepared, or \"no waveform: reason\" if it cannot be decoded, and the diagnostics log records the steps.",
            )
        ),
        ReleaseNote(
            6, "2026-11-13", "Curve view, waveform and audio scrub",
            listOf(
                "Changed: opening a Camera or Transformation layer (the arrow on its row) now shows a curve view: one curve per channel (X, Y, scale, rotation), a channel list with eye, key and value, and the frame ruler. Drag a key sideways to retime it, up or down to change its value; tap a row to make that channel the one the vertical axis shows. The < button goes back to the layers.",
                "Added: audio layers show their waveform inside the clip.",
                "Added: audio scrub. Moving the playhead by hand (ruler drag, frame step) plays a short piece of the audio at normal pitch, backwards when you scrub backwards. The speaker button in the timeline bar turns it on or off.",
                "Removed: the Retime button added in the previous update.",
            )
        ),
        ReleaseNote(
            5, "2026-11-13", "Crash recovery",
            listOf(
                "Added: crash recovery. While you edit, Inbetween keeps recovery snapshots of the open cut. If the app closes unexpectedly, it offers to restore your unsaved work the next time you start it.",
            )
        ),
        ReleaseNote(
            4, "2026-11-13", "Playback rendering",
            listOf(
                "Playback now draws all layers with the standard renderer instead of the GPU compositor. In our diagnostics this removed most of the garbage-collection load and about a third of the CPU use. The GPU compositor is still used while you draw.",
            )
        ),
        ReleaseNote(
            3, "2026-11-13", "Video opacity and this window",
            listOf(
                "Added: this What's new window, shown once after each update.",
                "Video layers: removed two rendering experiments that made the video go dark when its opacity was below 100%. At partial opacity it should fade toward the page again.",
            )
        ),
        ReleaseNote(
            2, "2026-11-12", "Performance tools and grid",
            listOf(
                "Added: performance profiler (speedometer button in the top bar) with HUD, recording and a diagnostic ZIP export.",
                "Grid: when zoomed out it now draws a coarser grid instead of disappearing.",
                "Playback: audio and video re-sync less aggressively, to avoid repeated stutters.",
            )
        ),
        ReleaseNote(
            1, "2026-11-11", "Timeline, brushes and frames",
            listOf(
                "Added: clone frames, empty frame, split a frame, ripple toggle, fit-to-brackets and a longer blank tail.",
                "Added: brush tips and textures, anti-aliasing slider, tangent-based pressure curves, palettes.",
                "Added: coloured interpolation lines between camera / Transformation keys.",
            )
        ),
    )

    val latestId: Int get() = notes.firstOrNull()?.id ?: 0

    /** Notes the user has not seen. First run (nothing stored): the three newest. */
    fun unseen(seenId: Int): List<ReleaseNote> =
        if (seenId <= 0) notes.take(3) else notes.filter { it.id > seenId }.take(6)
}

@Composable
fun WhatsNewHost() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(WhatsNew.PREFS, Context.MODE_PRIVATE) }
    val seen = remember { prefs.getInt(WhatsNew.KEY_SEEN, 0) }
    var show by remember { mutableStateOf(seen < WhatsNew.latestId) }
    if (!show) return
    val unseen = remember { WhatsNew.unseen(seen) }
    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "v${pi.versionName} (build $code)"
        }.getOrDefault("")
    }
    val close = {
        prefs.edit().putInt(WhatsNew.KEY_SEEN, WhatsNew.latestId).apply()
        show = false
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (version.isEmpty()) "What's new" else "What's new — $version") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                unseen.forEach { n ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(n.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(n.date, fontSize = 11.sp, color = Color.Gray)
                        n.items.forEach { Text("•  $it", fontSize = 13.sp, lineHeight = 17.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("Got it") } }
    )
}
