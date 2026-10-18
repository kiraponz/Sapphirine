package com.kira3.ametrine.emulation.input

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import com.kira3.ametrine.data.ControlEdit
import com.kira3.ametrine.data.PadLayoutEdits
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import com.kira3.ametrine.emucore.PadButton
import com.kira3.ametrine.emucore.PadStick
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the touch pad sends its input. Called on the UI thread, straight from touch handling. */
interface PadSink {
    fun onButton(button: PadButton, pressed: Boolean)
    fun onStick(stick: PadStick, x: Float, y: Float)
}

/**
 * The state of the layout editor: the edits being made and the control being dragged or sized.
 * While one is given to [TouchPad], the pad does not play; touches move and select its controls.
 */
class PadEditor(initial: PadLayoutEdits) {
    var edits by mutableStateOf(initial)
        private set

    /** The id of the selected control (see TouchPadLayout.kt), or null. */
    var selected by mutableStateOf<String?>(null)
        internal set

    /** Editing the function buttons as they sit while the pad is hidden, instead of the pad. */
    var controllerMode by mutableStateOf(false)
        private set

    fun editController(controller: Boolean) {
        controllerMode = controller
        selected = null
    }

    internal fun move(landscape: Boolean, id: String, dx: Float, dy: Float) = change(landscape, id) {
        it.copy(x = it.x + dx, y = it.y + dy)
    }

    fun setScale(landscape: Boolean, id: String, scale: Float) = change(landscape, id) { it.copy(scale = scale) }

    fun scaleOf(landscape: Boolean, id: String): Float = edits.of(landscape, controllerMode)[id]?.scale ?: 1f

    /** Puts every control of this orientation (or of the controller layout) back where the built-in layout has it. */
    fun reset(landscape: Boolean) {
        edits = edits.with(landscape, emptyMap(), controllerMode)
    }

    private fun change(landscape: Boolean, id: String, transform: (ControlEdit) -> ControlEdit) {
        val current = edits.of(landscape, controllerMode)
        edits = edits.with(landscape, current + (id to transform(current[id] ?: ControlEdit())), controllerMode)
    }
}

/**
 * The on-screen pad, drawn over the game. Each finger is tracked on its own, so several
 * controls can be held at once; a finger can slide between buttons and across the D-pad.
 *
 * While hidden ([visible] false) it draws nothing, and a touch anywhere calls [onShowRequest].
 *
 * With [floatingSticks] the drawn sticks are replaced by ones that appear where a finger touches an
 * empty part of the screen (left half: left stick, right half: right stick) and go when it lifts.
 * Held upright, a floating stick does not start on the picture, which the core draws at the top
 * with the shape [gameAspect] (width / height; null when it fills the screen).
 * [opacity] fades the whole pad; [haptics] turns off the vibration on a press.
 *
 * After [idleSeconds] without a touch (0 = never) the pad and the function buttons turn more transparent,
 * until the next touch.
 *
 * [fastForwardButton], if given, is placed where the layout leaves room for it, and stays
 * while the pad is hidden, moved to the top left (or wherever the user put it). [edits] are the user's changes to the layout; with an [editor] they are
 * the editor's, and the pad is drawn for editing instead of playing.
 */
@Composable
fun TouchPad(
    sink: PadSink,
    visible: Boolean,
    onShowRequest: () -> Unit,
    modifier: Modifier = Modifier,
    fastForwardButton: (@Composable () -> Unit)? = null,
    rewindButton: (@Composable () -> Unit)? = null,
    screenshotButton: (@Composable () -> Unit)? = null,
    macroButton: (@Composable () -> Unit)? = null,
    aiButton: (@Composable () -> Unit)? = null,
    floatingSticks: Boolean = false,
    gameAspect: Float? = null,
    opacity: Float = 1f,
    haptics: Boolean = true,
    edits: PadLayoutEdits = PadLayoutEdits(),
    editor: PadEditor? = null,
    idleSeconds: Int = 0,
) {
    val view = LocalView.current
    val currentHaptics by rememberUpdatedState(haptics)
    val tracker = remember(sink) {
        TouchTracker(sink) { if (currentHaptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }
    DisposableEffect(tracker, visible) {
        onDispose { tracker.releaseAll() }
    }
    val currentOnShowRequest by rememberUpdatedState(onShowRequest)

    // Whether a finger is down, sent from every touch the pad area gets; it fades after a while without one.
    val touches = remember { Channel<Boolean>(Channel.CONFLATED) }
    var idle by remember { mutableStateOf(false) }
    val editing = editor != null
    LaunchedEffect(idleSeconds, editing) {
        idle = false
        if (idleSeconds <= 0 || editing) return@LaunchedEffect
        var down = false
        while (true) {
            // A held finger keeps the pad awake, as does the time before the first timeout.
            val next = if (down || idle) touches.receive() else withTimeoutOrNull(idleSeconds * 1000L) { touches.receive() }
            if (next == null) {
                idle = true
            } else {
                idle = false
                down = next
            }
        }
    }
    val idleFactor by animateFloatAsState(if (idle) IDLE_ALPHA else 1f, tween(600), label = "padIdle")

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .pointerInput(touches) {
                // Only watches, on the way down, so every control still gets its touch.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touches.trySend(event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        val maxUnit = with(LocalDensity.current) { MAX_UNIT.toPx() }
        val area = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val landscape = area.width >= area.height
        // Without the pad (a controller is in use) only the function buttons are shown, in a layout of their own.
        val controllerMode = editor?.controllerMode ?: !visible
        val activeEdits = (editor?.edits ?: edits).of(landscape, controllerMode)
        val layout = remember(constraints.maxWidth, constraints.maxHeight, maxUnit, floatingSticks, activeEdits, controllerMode) {
            (if (controllerMode) controllerLayout(area, maxUnit) else touchPadLayout(area, maxUnit))
                .let { if (floatingSticks) it.withoutSticks() else it }
                .edited(activeEdits, area)
        }
        val currentLayout by rememberUpdatedState(layout)
        val extraRadius = with(LocalDensity.current) { EXTRA_BUTTON_SIZE.toPx() / 2 }
        val labels = buttonLabels()
        val textMeasurer = rememberTextMeasurer()

        Canvas(
            Modifier
                .fillMaxSize()
                .alpha(opacity * idleFactor)
                .pointerInput(editor, layout.takeIf { editor == null }, visible, floatingSticks, gameAspect) {
                    if (editor != null) {
                        // Editing: the overlay below takes the touches.
                    } else if (visible) {
                        tracker.layout = layout
                        tracker.floating = floatingSticks
                        tracker.areaWidth = size.width.toFloat()
                        tracker.gameBottom =
                            if (gameAspect != null && size.height > size.width) size.width / gameAspect else 0f
                        try {
                            awaitPointerEventScope {
                                while (true) tracker.handle(awaitPointerEvent().changes)
                            }
                        } finally {
                            tracker.releaseAll()
                        }
                    } else {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.changedToDown() }) {
                                    event.changes.forEach { it.consume() }
                                    currentOnShowRequest()
                                }
                            }
                        }
                    }
                },
        ) {
            if (visible || editor != null) drawPad(layout, tracker, labels, textMeasurer)
            editor?.selected?.let { id -> selectionOf(layout, id, extraRadius)?.let { drawSelection(it) } }
        }
        val extraButtons = mapOf(
            PadExtra.FastForward to fastForwardButton,
            PadExtra.Rewind to rewindButton,
            PadExtra.Screenshot to screenshotButton,
            PadExtra.Macro to macroButton,
            PadExtra.Ai to aiButton,
        )
        for ((extra, button) in extraButtons) {
            val center = layout.extras[extra] ?: continue
            if (button == null) continue
            val scale = layout.extraScales[extra] ?: 1f
            Box(Modifier.centeredAt(center)) {
                Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = idleFactor }) { button() }
            }
        }
        // While editing, the buttons are only to be moved, not pressed: this covers them and takes every touch.
        if (editor != null) {
            Box(
                Modifier.fillMaxSize().pointerInput(editor) {
                    awaitPointerEventScope {
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            when {
                                change.changedToDown() -> {
                                    editor.selected = editorHit(currentLayout, change.position, extraRadius)
                                    dragging = editor.selected != null
                                    change.consume()
                                }
                                !change.pressed -> dragging = false
                                dragging -> {
                                    val id = editor.selected
                                    val center = id?.let { currentLayout.centerOf(it) }
                                    if (id != null && center != null) {
                                        // The center stays on the screen, so a control cannot be lost off its edge.
                                        val target = center + change.positionChange()
                                        val clamped = Offset(
                                            target.x.coerceIn(0f, size.width.toFloat()),
                                            target.y.coerceIn(0f, size.height.toFloat()),
                                        )
                                        val moved = clamped - center
                                        editor.move(landscape, id, moved.x / size.width, moved.y / size.height)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** How much of its opacity the pad keeps while it is idle. */
private const val IDLE_ALPHA = 0.3f

/** The extra buttons are drawn at this size by the caller. */
private val EXTRA_BUTTON_SIZE = 44.dp

/** The id of the control under [position] for the editor: the one whose own size the point is closest to the middle of. */
private fun editorHit(layout: TouchPadLayout, position: Offset, extraRadius: Float): String? {
    var best: String? = null
    var bestRatio = Float.MAX_VALUE
    fun consider(id: String, center: Offset, radius: Float) {
        val ratio = (position - center).getDistance() / radius
        if (ratio <= 1.2f && ratio < bestRatio) {
            best = id
            bestRatio = ratio
        }
    }
    for (control in layout.controls) when (control) {
        is TouchControl.Button -> consider(control.id, control.bounds.center, max(control.bounds.width, control.bounds.height) / 2)
        is TouchControl.DPad -> consider(control.id, control.center, control.radius)
        is TouchControl.Stick -> consider(control.id, control.center, control.radius)
    }
    for ((extra, center) in layout.extras) consider(extra.id, center, extraRadius * (layout.extraScales[extra] ?: 1f))
    return best
}

/** The area to outline around the control with this [id]. */
private fun selectionOf(layout: TouchPadLayout, id: String, extraRadius: Float): Rect? {
    layout.controls.firstOrNull { it.id == id }?.let { control ->
        return when (control) {
            is TouchControl.Button -> control.bounds
            is TouchControl.DPad -> Rect(control.center, control.radius)
            is TouchControl.Stick -> Rect(control.center, control.radius)
        }
    }
    val extra = PadExtra.entries.firstOrNull { it.id == id } ?: return null
    val center = layout.extras[extra] ?: return null
    return Rect(center, extraRadius * (layout.extraScales[extra] ?: 1f))
}

private fun DrawScope.drawSelection(bounds: Rect) {
    val pad = 6.dp.toPx()
    drawRoundRect(
        SelectionColor,
        bounds.topLeft - Offset(pad, pad),
        Size(bounds.width + pad * 2, bounds.height + pad * 2),
        CornerRadius(pad * 2),
        style = Stroke(width = 2.5.dp.toPx()),
    )
}

private val SelectionColor = Color(0xFFFFD54F)

/** Places the content with its center at [center]. */
private fun Modifier.centeredAt(center: Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(
            (center.x - placeable.width / 2f).roundToInt(),
            (center.y - placeable.height / 2f).roundToInt(),
        )
    }
}

@Composable
private fun buttonLabels(): Map<PadButton, String> = mapOf(
    PadButton.A to stringResource(R.string.pad_a),
    PadButton.B to stringResource(R.string.pad_b),
    PadButton.C to stringResource(R.string.pad_c),
    PadButton.X to stringResource(R.string.pad_x),
    PadButton.Y to stringResource(R.string.pad_y),
    PadButton.Z to stringResource(R.string.pad_z),
    PadButton.L to stringResource(R.string.pad_l),
    PadButton.R to stringResource(R.string.pad_r),
    PadButton.Start to stringResource(R.string.pad_start),
)

/** Which control each finger is on, and what the pad as a whole is holding. */
private class TouchTracker(
    private val sink: PadSink,
    private val onPress: () -> Unit,
) {
    var layout: TouchPadLayout? = null

    /** Sticks that appear under a finger instead of sitting at a fixed place. */
    var floating = false
    var areaWidth = 0f

    /** Floating sticks cannot start above this (the picture); 0 = anywhere. */
    var gameBottom = 0f

    /** Where each floating stick was put down; absent while it is not held. */
    val floatingOrigins = mutableStateMapOf<PadStick, Offset>()

    var pressed by mutableStateOf(emptySet<PadButton>())
        private set
    val sticks = mutableStateMapOf<PadStick, Offset>()

    private val fingers = HashMap<PointerId, Finger>()

    private sealed interface Finger {
        /** On a button; it may slide to another one, or off all of them ([button] null). */
        class OnButton(var button: PadButton?) : Finger
        class OnDPad(val dpad: TouchControl.DPad, var directions: Set<PadButton>) : Finger
        class OnStick(val stick: TouchControl.Stick, var value: Offset) : Finger
        class OnFloatingStick(val stick: PadStick, val origin: Offset, var value: Offset) : Finger
    }

    fun handle(changes: List<PointerInputChange>) {
        val controls = layout?.controls ?: return
        for (change in changes) {
            val id = change.id
            when {
                change.changedToDown() -> {
                    val hit = hitTest(controls, change.position)
                    fingers[id] = when (hit) {
                        is TouchControl.Button -> Finger.OnButton(hit.button)
                        is TouchControl.DPad -> Finger.OnDPad(hit, dpadDirections(hit, change.position))
                        is TouchControl.Stick -> Finger.OnStick(hit, stickValue(hit, change.position))
                        null -> {
                            val stick = if (change.position.x < areaWidth / 2) PadStick.Left else PadStick.Right
                            val taken = fingers.values.any { it is Finger.OnFloatingStick && it.stick == stick }
                            if (!floating || taken || change.position.y < gameBottom) continue
                            Finger.OnFloatingStick(stick, change.position, Offset.Zero)
                        }
                    }
                    change.consume()
                }

                !change.pressed -> {
                    if (fingers.remove(id) != null) change.consume()
                }

                else -> {
                    when (val finger = fingers[id] ?: continue) {
                        is Finger.OnButton -> finger.button = controls
                            .filterIsInstance<TouchControl.Button>()
                            .firstOrNull { it.contains(change.position) }?.button
                        is Finger.OnDPad ->
                            finger.directions = dpadDirections(finger.dpad, change.position)
                        is Finger.OnStick ->
                            finger.value = stickValue(finger.stick, change.position)
                        is Finger.OnFloatingStick -> {
                            val v = (change.position - finger.origin) / floatingRadius()
                            val distance = v.getDistance()
                            finger.value = if (distance > 1f) v / distance else v
                        }
                    }
                    change.consume()
                }
            }
        }
        publish()
    }

    fun releaseAll() {
        fingers.clear()
        publish()
    }

    private fun publish() {
        val nowPressed = buildSet {
            for (finger in fingers.values) when (finger) {
                is Finger.OnButton -> finger.button?.let(::add)
                is Finger.OnDPad -> addAll(finger.directions)
                is Finger.OnStick, is Finger.OnFloatingStick -> Unit
            }
        }
        if (nowPressed != pressed) {
            for (button in pressed - nowPressed) sink.onButton(button, false)
            val added = nowPressed - pressed
            for (button in added) sink.onButton(button, true)
            pressed = nowPressed
            if (added.isNotEmpty()) onPress()
        }

        for (stick in PadStick.entries) {
            val floatingFinger = fingers.values.firstNotNullOfOrNull {
                (it as? Finger.OnFloatingStick)?.takeIf { f -> f.stick == stick }
            }
            if (floatingFinger != null) floatingOrigins[stick] = floatingFinger.origin else floatingOrigins.remove(stick)
            val value = floatingFinger?.value ?: fingers.values.firstNotNullOfOrNull {
                (it as? Finger.OnStick)?.takeIf { f -> f.stick.stick == stick }?.value
            } ?: Offset.Zero
            if (value != (sticks[stick] ?: Offset.Zero)) {
                sticks[stick] = value
                sink.onStick(stick, value.x, value.y)
            }
        }
    }

    private fun hitTest(controls: List<TouchControl>, position: Offset): TouchControl? =
        controls.firstOrNull {
            when (it) {
                is TouchControl.Button -> it.contains(position)
                is TouchControl.DPad -> (position - it.center).getDistance() <= it.radius * 1.15f
                is TouchControl.Stick -> (position - it.center).getDistance() <= it.radius * 1.4f
            }
        }

    private fun TouchControl.Button.contains(position: Offset): Boolean {
        // A little larger than drawn, since a fingertip covers more than its center.
        val slop = min(bounds.width, bounds.height) * 0.2f
        return if (round) {
            (position - bounds.center).getDistance() <= bounds.width / 2 + slop
        } else {
            bounds.inflate(slop).contains(position)
        }
    }

    /** Eight-way: a diagonal covers the 45 degrees between two directions. */
    private fun dpadDirections(dpad: TouchControl.DPad, position: Offset): Set<PadButton> {
        val v = position - dpad.center
        val distance = v.getDistance()
        if (distance < dpad.radius * 0.2f) return emptySet()
        val nx = v.x / distance
        val ny = v.y / distance
        return buildSet {
            if (nx > DIAGONAL) add(PadButton.Right)
            if (nx < -DIAGONAL) add(PadButton.Left)
            if (ny > DIAGONAL) add(PadButton.Down)
            if (ny < -DIAGONAL) add(PadButton.Up)
        }
    }

    /** How far a floating stick's finger travels for full tilt. */
    fun floatingRadius(): Float = (layout?.unit ?: 0f) * FLOATING_RADIUS_UNITS

    private fun stickValue(stick: TouchControl.Stick, position: Offset): Offset {
        val v = (position - stick.center) / stick.radius
        val distance = v.getDistance()
        return if (distance > 1f) v / distance else v
    }

    private companion object {
        /** cos(67.5°): beyond this a direction counts, so diagonals get 45 degrees. */
        const val DIAGONAL = 0.383f
    }
}

private val MAX_UNIT = 56.dp

/** The travel of a floating stick, in layout units (a fixed stick is 1.15). */
private const val FLOATING_RADIUS_UNITS = 1.3f

private val Fill = Color.White.copy(alpha = 0.16f)
private val FillPressed = Color.White.copy(alpha = 0.45f)
private val Outline = Color.White.copy(alpha = 0.5f)
private val Label = Color.White.copy(alpha = 0.85f)

private fun DrawScope.drawPad(
    layout: TouchPadLayout,
    tracker: TouchTracker,
    labels: Map<PadButton, String>,
    textMeasurer: TextMeasurer,
) {
    val stroke = Stroke(width = 1.5.dp.toPx())
    val pressed = tracker.pressed
    val labelStyle = TextStyle(
        color = Label,
        fontSize = (layout.unit * 0.3f).toSp(),
        fontWeight = FontWeight.Medium,
    )

    for ((stick, origin) in tracker.floatingOrigins) {
        val radius = tracker.floatingRadius()
        val value = tracker.sticks[stick] ?: Offset.Zero
        val knobRadius = radius * 0.48f
        drawCircle(Fill, radius, origin)
        drawCircle(Outline, radius, origin, style = stroke)
        val knob = origin + value * (radius - knobRadius)
        drawCircle(FillPressed, knobRadius, knob)
        drawCircle(Outline, knobRadius, knob, style = stroke)
    }

    for (control in layout.controls) when (control) {
        is TouchControl.Button -> {
            val fill = if (control.button in pressed) FillPressed else Fill
            val b = control.bounds
            if (control.round) {
                drawCircle(fill, b.width / 2, b.center)
                drawCircle(Outline, b.width / 2, b.center, style = stroke)
            } else {
                val corner = CornerRadius(b.height / 2)
                drawRoundRect(fill, b.topLeft, b.size, corner)
                drawRoundRect(Outline, b.topLeft, b.size, corner, style = stroke)
            }
            val label = labels[control.button]
            if (label != null) {
                val text = textMeasurer.measure(label, labelStyle.copy(fontSize = labelStyle.fontSize * control.scale))
                drawText(
                    text,
                    topLeft = b.center - Offset(text.size.width / 2f, text.size.height / 2f),
                )
            }
        }

        is TouchControl.DPad -> drawDPad(control, pressed, stroke)

        is TouchControl.Stick -> {
            val value = tracker.sticks[control.stick] ?: Offset.Zero
            val knobRadius = control.radius * 0.48f
            drawCircle(Fill, control.radius, control.center)
            drawCircle(Outline, control.radius, control.center, style = stroke)
            val knob = control.center + value * (control.radius - knobRadius)
            drawCircle(if (value != Offset.Zero) FillPressed else Fill, knobRadius, knob)
            drawCircle(Outline, knobRadius, knob, style = stroke)
        }
    }
}

private fun DrawScope.drawDPad(dpad: TouchControl.DPad, pressed: Set<PadButton>, stroke: Stroke) {
    val r = dpad.radius
    val arm = r * 0.64f
    val h = arm / 2
    val c = dpad.center
    // Arms and hub don't overlap, so the translucent fill stays even.
    val arms = listOf(
        PadButton.Up to Rect(c.x - h, c.y - r, c.x + h, c.y - h),
        PadButton.Down to Rect(c.x - h, c.y + h, c.x + h, c.y + r),
        PadButton.Left to Rect(c.x - r, c.y - h, c.x - h, c.y + h),
        PadButton.Right to Rect(c.x + h, c.y - h, c.x + r, c.y + h),
    )
    for ((button, rect) in arms) {
        drawRect(if (button in pressed) FillPressed else Fill, rect.topLeft, rect.size)
    }
    drawRect(Fill, Offset(c.x - h, c.y - h), Size(arm, arm))
    // The outline of the whole cross, so the arms read as one D-pad.
    val outline = Path().apply {
        moveTo(c.x - h, c.y - r)
        lineTo(c.x + h, c.y - r)
        lineTo(c.x + h, c.y - h)
        lineTo(c.x + r, c.y - h)
        lineTo(c.x + r, c.y + h)
        lineTo(c.x + h, c.y + h)
        lineTo(c.x + h, c.y + r)
        lineTo(c.x - h, c.y + r)
        lineTo(c.x - h, c.y + h)
        lineTo(c.x - r, c.y + h)
        lineTo(c.x - r, c.y - h)
        lineTo(c.x - h, c.y - h)
        close()
    }
    drawPath(outline, Outline, style = stroke)
}

@Preview(widthDp = 800, heightDp = 380, backgroundColor = 0xFF202020, showBackground = true)
@Preview(widthDp = 400, heightDp = 860, backgroundColor = 0xFF202020, showBackground = true)
@Composable
private fun TouchPadPreview() {
    TouchPad(
        sink = object : PadSink {
            override fun onButton(button: PadButton, pressed: Boolean) = Unit
            override fun onStick(stick: PadStick, x: Float, y: Float) = Unit
        },
        visible = true,
        onShowRequest = {},
    )
}
