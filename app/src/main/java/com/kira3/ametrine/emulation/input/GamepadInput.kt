package com.kira3.ametrine.emulation.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emucore.PadButton
import com.kira3.ametrine.emucore.PadStick
import com.kira3.ametrine.emucore.settings.ControlsSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a physical controller's key and motion events into presses on the emulated pad.
 * Call from the activity's dispatchKeyEvent / dispatchGenericMotionEvent, on the UI thread.
 *
 * A pad can report the same control twice (the D-pad as keys and as a HAT axis, L2/R2 as
 * keys and as trigger axes), so each source is tracked separately and merged; the core
 * only hears about changes. The standard Saturn pad has no stick, so the left stick also works
 * the D-pad; a 3D Control Pad boot ([padType3d]) takes the stick as the pad's analog stick
 * instead, and the analog triggers' values as its analog L and R.
 */
class GamepadInput(
    private val core: EmulatorCore,
    private val port: Int = 0,
    /** True while the boot the input serves runs a 3D Control Pad (EmulationActivity reads the pad-type setting). */
    private val padType3d: () -> Boolean = { false },
) {
    /** Called when someone deliberately uses a controller (not just resting stick noise). */
    var onControllerUsed: (() -> Unit)? = null

    /** The device that last sent input, for rumble. -1 until a controller is used. */
    @Volatile
    var lastDeviceId: Int = -1
        private set

    private val keysHeld = BooleanArray(BUTTONS.size)
    private var hatX = 0f
    private var hatY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f
    private val sentPressure = FloatArray(BUTTONS.size)

    private val sentStick = Array(PadStick.entries.size) { FloatArray(2) }

    @Volatile
    private var keyMap: Map<Int, PadButton> = DEFAULT_KEY_MAP

    /** The buttons the analog triggers press: whatever the L2 and R2 keys are assigned to. */
    private var leftTriggerButton: PadButton? = DEFAULT_KEY_MAP[KeyEvent.KEYCODE_BUTTON_L2]
    private var rightTriggerButton: PadButton? = DEFAULT_KEY_MAP[KeyEvent.KEYCODE_BUTTON_R2]
    private var deadzone = DEFAULT_DEADZONE
    private var triggerThreshold = DEFAULT_TRIGGER_THRESHOLD

    private val rightStickAxisCache = HashMap<Int, Pair<Int, Int>>()

    /** Takes the button assignments, dead zone and trigger threshold from the settings. */
    fun configure(controls: ControlsSettings) {
        val map = controls.buttonMap
        val assigned = mapOf(
            PadButton.Up to map.up, PadButton.Down to map.down, PadButton.Left to map.left,
            PadButton.Right to map.right, PadButton.A to map.a, PadButton.B to map.b,
            PadButton.C to map.c, PadButton.X to map.x, PadButton.Y to map.y, PadButton.Z to map.z,
            PadButton.L to map.l, PadButton.R to map.r, PadButton.Start to map.start,
        )
        val newMap = HashMap<Int, PadButton>()
        for ((button, key) in assigned) if (key != KeyEvent.KEYCODE_UNKNOWN) newMap[key] = button
        leftTriggerButton = newMap[KeyEvent.KEYCODE_BUTTON_L2]
        rightTriggerButton = newMap[KeyEvent.KEYCODE_BUTTON_R2]
        deadzone = (controls.stickDeadzone / 100f).coerceIn(0f, 0.9f)
        triggerThreshold = (controls.triggerThreshold / 100f).coerceIn(0.01f, 0.95f)
        if (newMap != keyMap) {
            // A key held under the old assignments must not stay pressed.
            keyMap = newMap
            releaseAll()
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromGamepad()) return false
        val button = keyMap[event.keyCode] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                keysHeld[button.ordinal] = true
                controllerUsed(event.deviceId)
            }
            KeyEvent.ACTION_UP -> keysHeld[button.ordinal] = false
            else -> return true
        }
        flushButtons()
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return false
        }

        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        leftTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        rightTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )

        val (rightX, rightY) = rightStickAxes(event.deviceId)
        val leftMag = sendStick(
            PadStick.Left,
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
        )
        val rightMag = sendStick(
            PadStick.Right,
            event.getAxisValue(rightX),
            event.getAxisValue(rightY),
        )
        // After the sticks: the left one presses the D-pad.
        flushButtons()

        val deliberate = hatX != 0f || hatY != 0f ||
            max(leftTrigger, rightTrigger) > DELIBERATE_THRESHOLD ||
            max(leftMag, rightMag) > DELIBERATE_THRESHOLD
        if (deliberate) controllerUsed(event.deviceId)
        return true
    }

    /** Lets go of everything, e.g. when the activity loses focus mid-press. */
    fun releaseAll() {
        keysHeld.fill(false)
        hatX = 0f
        hatY = 0f
        leftTrigger = 0f
        rightTrigger = 0f
        flushButtons()
        for (stick in PadStick.entries) sendStick(stick, 0f, 0f)
    }

    private fun controllerUsed(deviceId: Int) {
        lastDeviceId = deviceId
        onControllerUsed?.invoke()
    }

    private fun flushButtons() {
        for (button in BUTTONS) {
            val pressure = pressureOf(button)
            if (pressure != sentPressure[button.ordinal]) {
                sentPressure[button.ordinal] = pressure
                core.setPadButton(port, button, pressure > 0f, pressure)
            }
        }
    }

    private fun pressureOf(button: PadButton): Float {
        val fromKey = if (keysHeld[button.ordinal]) 1f else 0f
        val fromHat = when (button) {
            PadButton.Up -> if (hatY < -HAT_THRESHOLD) 1f else 0f
            PadButton.Down -> if (hatY > HAT_THRESHOLD) 1f else 0f
            PadButton.Left -> if (hatX < -HAT_THRESHOLD) 1f else 0f
            PadButton.Right -> if (hatX > HAT_THRESHOLD) 1f else 0f
            else -> 0f
        }
        val (stickX, stickY) = sentStick[PadStick.Left.ordinal]
        // With a 3D Control Pad the left stick is the pad's stick, not the D-pad's second face.
        val fromStick = if (!padType3d()) when (button) {
            PadButton.Up -> if (stickY < -STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Down -> if (stickY > STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Left -> if (stickX < -STICK_DPAD_THRESHOLD) 1f else 0f
            PadButton.Right -> if (stickX > STICK_DPAD_THRESHOLD) 1f else 0f
            else -> 0f
        } else {
            0f
        }
        val fromTrigger = max(
            if (button == leftTriggerButton) triggerPressure(leftTrigger) else 0f,
            if (button == rightTriggerButton) triggerPressure(rightTrigger) else 0f,
        )
        return maxOf(fromKey, fromHat, fromStick, fromTrigger)
    }

    private fun triggerPressure(value: Float): Float =
        if (value > triggerThreshold) value.coerceAtMost(1f) else 0f

    /** Applies a radial deadzone, sends the stick if it moved, and returns its magnitude. */
    private fun sendStick(stick: PadStick, rawX: Float, rawY: Float): Float {
        val mag = hypot(rawX, rawY)
        val (x, y) = if (mag <= deadzone) {
            0f to 0f
        } else {
            val scale = ((mag - deadzone) / (1f - deadzone)).coerceAtMost(1f) / mag
            rawX * scale to rawY * scale
        }
        val sent = sentStick[stick.ordinal]
        if (abs(x - sent[0]) > STICK_EPSILON || abs(y - sent[1]) > STICK_EPSILON ||
            (x == 0f && y == 0f && (sent[0] != 0f || sent[1] != 0f))
        ) {
            sent[0] = x
            sent[1] = y
            core.setPadStick(port, stick, x, y)
        }
        return mag
    }

    /**
     * Most pads put the right stick on Z/RZ, but some use RX/RY: Nintendo pads, and pads
     * whose RZ is a trigger (it rests at 0 rather than spanning -1..1).
     */
    private fun rightStickAxes(deviceId: Int): Pair<Int, Int> = rightStickAxisCache.getOrPut(deviceId) {
        val device = InputDevice.getDevice(deviceId)
        fun has(axis: Int) = device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
        val hasRxRy = has(MotionEvent.AXIS_RX) || has(MotionEvent.AXIS_RY)
        val rz = device?.getMotionRange(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK)
        val rzIsTrigger = rz != null && rz.min >= 0f
        val useRxRy = hasRxRy && (
            device?.vendorId == VENDOR_NINTENDO || rzIsTrigger ||
                (!has(MotionEvent.AXIS_Z) && !has(MotionEvent.AXIS_RZ))
            )
        if (useRxRy) {
            MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        } else {
            MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        }
    }

    private fun KeyEvent.isFromGamepad(): Boolean =
        isFromSource(InputDevice.SOURCE_GAMEPAD) || isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            isFromSource(InputDevice.SOURCE_DPAD)

    private companion object {
        val BUTTONS = PadButton.entries

        /** As ButtonMapSettings' defaults. */
        val DEFAULT_KEY_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadButton.A,
            KeyEvent.KEYCODE_BUTTON_B to PadButton.B,
            KeyEvent.KEYCODE_BUTTON_R1 to PadButton.C,
            KeyEvent.KEYCODE_BUTTON_X to PadButton.X,
            KeyEvent.KEYCODE_BUTTON_Y to PadButton.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to PadButton.Z,
            KeyEvent.KEYCODE_BUTTON_L2 to PadButton.L,
            KeyEvent.KEYCODE_BUTTON_R2 to PadButton.R,
            KeyEvent.KEYCODE_BUTTON_START to PadButton.Start,
            KeyEvent.KEYCODE_DPAD_UP to PadButton.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to PadButton.Down,
            KeyEvent.KEYCODE_DPAD_LEFT to PadButton.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadButton.Right,
        )

        const val VENDOR_NINTENDO = 0x057E
        const val DEFAULT_DEADZONE = 0.15f
        const val DEFAULT_TRIGGER_THRESHOLD = 0.08f
        const val STICK_EPSILON = 0.004f
        const val HAT_THRESHOLD = 0.5f
        /** How far (after the dead zone) the left stick must lean to press a D-pad direction. */
        const val STICK_DPAD_THRESHOLD = 0.5f
        const val DELIBERATE_THRESHOLD = 0.5f
    }
}
