package com.kira3.ametrine.emulation

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kira3.ametrine.R
import com.kira3.ametrine.data.GameRecordRepository
import com.kira3.ametrine.data.GpuDriver
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.ui.theme.AmetrineTheme
import com.kira3.ametrine.emucore.Cheat
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emucore.PadButton
import com.kira3.ametrine.emucore.PadStick
import com.kira3.ametrine.emucore.VmState
import com.kira3.ametrine.emulation.input.ControllerRumble
import com.kira3.ametrine.emulation.input.GamepadInput
import com.kira3.ametrine.emulation.input.PadSink
import com.kira3.ametrine.data.GeminiApi
import com.kira3.ametrine.data.GeminiError
import com.kira3.ametrine.emulation.macro.MacroPlayer
import com.kira3.ametrine.emulation.macro.MacroRecorder
import com.kira3.ametrine.emulation.search.MemorySearch
import com.kira3.ametrine.data.macro.Macro
import com.kira3.ametrine.data.macro.MemoryCondition
import com.kira3.ametrine.data.GeminiChat
import com.kira3.ametrine.data.GeminiMessage
import com.kira3.ametrine.data.memo.Memo
import com.kira3.ametrine.emulation.input.PadEditor
import com.kira3.ametrine.emulation.input.TouchPad
import com.kira3.ametrine.data.PadLayoutEdits
import com.kira3.ametrine.emulation.input.gameAspectOf
import com.kira3.ametrine.emucore.settings.EmulatorSettings
import com.kira3.ametrine.emucore.settings.SettingsJson
import com.kira3.ametrine.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Gameplay. Owns the VM for its lifetime: boots the game on a dedicated thread, hands the
 * core its surface, pauses while hidden, and shuts the VM down when the player leaves.
 *
 * Takes only the game's URI and serial from its Intent and reads everything else from
 * storage, so it can later move to its own process.
 */
class EmulationActivity : ComponentActivity() {
    private val container by lazy { (application as AmetrineApplication).container }
    private val core: EmulatorCore by lazy { container.emulatorCore }

    private var emulationThread: Thread? = null

    /** True when onStop paused the VM, so onStart knows to resume it. */
    private var pausedWhileHidden = false

    /** The suspend state onStop is writing, which onStart waits for before it lets the game go on. */
    private var suspendSave: Job? = null

    /** The suspend state this boot started from, until the game is up and it has been used. */
    private var suspendFile: File? = null

    // Input goes straight to the core from the input callbacks, not through a ViewModel.
    private val gamepad by lazy { GamepadInput(core) }
    private val rumble by lazy { ControllerRumble { gamepad.lastDeviceId } }
    private val touchSink by lazy {
        object : PadSink {
            override fun onButton(button: PadButton, pressed: Boolean) =
                core.setPadButton(PORT, button, pressed)

            override fun onStick(stick: PadStick, x: Float, y: Float) =
                core.setPadStick(PORT, stick, x, y)
        }
    }

    /** The touch pad hides while a controller is in use and comes back on a touch. */
    private var touchPadVisible by mutableStateOf(true)

    /** The user's changes to the touch pad layout, and the editor while they are being made. */
    private var padEdits by mutableStateOf(PadLayoutEdits())
    private var padEditor by mutableStateOf<PadEditor?>(null)

    /** Started from the settings to edit the pad layout: no game runs, and leaving the editor finishes the activity. */
    private var editPadOnly = false

    /** What the game runs with, once read. The fast-forward button and the menu show from it. */
    private var settings by mutableStateOf<EmulatorSettings?>(null)

    private var serial: String? = null

    /** Key of this game in [GameRecordRepository]; null for the BIOS menu. */
    private var recordKey: String? = null
    private var visibleSince = 0L

    /** Layout size of the game view; the output buffer is scaled from this. */
    private var viewWidth = 0
    private var viewHeight = 0
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceView: SurfaceView? = null

    /** Live buffer size (post setFixedSize); the cutout inset is scaled to this. */
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** Holds Triangle+Cross through boot while auto progressive scan is on. */

    private val screenshots by lazy { Screenshots(applicationContext, core) }

    // ---- In-game menu ----

    private var menuOpen by mutableStateOf(false)

    /** True when opening the menu paused the VM, so closing it resumes. */
    private var pausedByMenu = false
    private var saveSlots by mutableStateOf<List<SaveSlot>?>(null)
    private var selectedSlot by mutableStateOf(EmulatorCore.SAVE_STATE_SLOTS.first)

    /** The discs the menu offers: a playlist's, or the other discs of this game in the library. Empty for a one-disc game. */
    private var discChoices by mutableStateOf<List<DiscChoice>>(emptyList())

    /** The image in the drive when the game didn't start from a playlist. */
    private var currentDiscUri: Uri? = null

    /** A save or load is running. */
    private var stateBusy by mutableStateOf(false)

    private var rewindOpen by mutableStateOf(false)
    private var rewindSlots by mutableStateOf<List<SaveSlot>?>(null)
    private var rewindNow by mutableLongStateOf(0L)

    private var cheatsOpen by mutableStateOf(false)
    private var waitDialogOpen by mutableStateOf(false)
    private var cheatList by mutableStateOf<List<Cheat>?>(null)

    // ---- Macros ----

    private val macroRecorder by lazy { MacroRecorder(core, PORT) }
    private val macroPlayer by lazy { MacroPlayer(core, PORT, lifecycleScope) }
    private var macroList by mutableStateOf<List<Macro>>(emptyList())
    private var memoList by mutableStateOf<List<Memo>>(emptyList())
    private var macrosOpen by mutableStateOf(false)
    private var searchOpen by mutableStateOf(false)
    private val memorySearch by lazy { MemorySearch(core) }

    /** Seconds the running recording has gone on, or -1 when none is. */
    private var recordingSeconds by mutableLongStateOf(-1L)
    private var recordingJob: Job? = null

    // ---- Gemini ----
    private var aiShot by mutableStateOf<AiShot?>(null)
    private var aiState by mutableStateOf<AiState>(AiState.Idle)
    private var aiMessages by mutableStateOf<List<GeminiMessage>>(emptyList())
    private var aiChatId = UUID.randomUUID().toString()
    /** False for a conversation opened from the history: its screenshot is gone. */
    private var aiHasImage by mutableStateOf(true)
    private var aiHistory by mutableStateOf<List<GeminiChat>>(emptyList())
    /** The memo being written or changed by hand; [NewMemo] for a new one. */
    private var memoEdit by mutableStateOf<Memo?>(null)
    private var aiBusy = false
    private var aiJob: Job? = null

    private val overlayOpen get() = menuOpen || padEditor != null || rewindOpen || cheatsOpen || macrosOpen || searchOpen || waitDialogOpen || memoEdit != null || aiShot != null

    /** Waits for the game's CRC, then gives the core its cheats. */

    /** Set by Reset: when the VM stops, boot the game again instead of finishing. */
    private var restartRequested = false

    /** The custom driver the current boot uses, or null for the system driver. */
    private var customDriverInUse: GpuDriver? = null

    /** Boots in this activity use the system driver: a boot failed with a custom one, see [onVmStopped]. */
    private var systemDriverForced = false

    /** How long the :emu process stays alive after the game ends, so a Toast can be seen. */
    private var exitDelayMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameUri = IntentCompat.getParcelableExtra(intent, EXTRA_GAME_URI, Uri::class.java) ?: intent.data
        if (gameUri == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        serial = intent.getStringExtra(EXTRA_SERIAL)
        editPadOnly = intent.getBooleanExtra(EXTRA_EDIT_PAD_ONLY, false)
        // The BIOS menu (empty URI) isn't a game.
        if (gameUri != Uri.EMPTY) recordKey = GameRecordRepository.keyOf(serial, gameUri.toString())
        systemDriverForced = intent.getBooleanExtra(EXTRA_SYSTEM_DRIVER, false)
        intent.getStringExtra(EXTRA_FAILED_DRIVER)?.let { name ->
            Toast.makeText(this, getString(R.string.emulation_driver_failed, name), Toast.LENGTH_LONG).show()
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                padEditor != null -> closePadEditor(null)
                menuOpen -> if (!stateBusy) closeMenu()
                rewindOpen -> if (!stateBusy) closeRewind()
                cheatsOpen -> closeCheats()
                macrosOpen -> closeMacros()
                searchOpen -> closeSearch()
                aiShot != null -> closeAi()
                recordingSeconds >= 0 -> stopRecording()
                core.hasActiveVm -> openMenu()
                // Still booting, or the boot failed: nothing to pause.
                else -> exit()
            }
        }

        touchPadVisible = !isControllerConnected()
        gamepad.onControllerUsed = { touchPadVisible = false }
        core.rumbleHandler = { pad, large, small -> if (pad == PORT) rumble.rumble(large, small) }

        setContent {
            AmetrineTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(surfaceCallback)
                                surfaceView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize().onSizeChanged {
                            viewWidth = it.width
                            viewHeight = it.height
                            applyOutputScale()
                        },
                    )
                    TouchPad(
                        sink = touchSink,
                        visible = touchPadVisible,
                        onShowRequest = { touchPadVisible = true },
                        fastForwardButton = if (editPadOnly || settings?.frameLimit?.fastForwardButton == true) {
                            {
                                val fastForward by core.fastForward.collectAsState()
                                FastForwardButton(active = fastForward, onActiveChange = core::setFastForward)
                            }
                        } else {
                            null
                        },
                        rewindButton = if (editPadOnly || (settings?.rewind?.enabled == true && serial != null)) {
                            { RewindButton(onClick = ::openRewind) }
                        } else {
                            null
                        },
                        floatingSticks = settings?.controls?.stickMode == 1,
                        gameAspect = settings?.display?.let(::gameAspectOf),
                        opacity = (settings?.controls?.padOpacity ?: 100) / 100f,
                        haptics = settings?.controls?.padHaptics != false,
                        edits = padEdits,
                        editor = padEditor,
                        idleSeconds = settings?.controls?.padIdleSeconds?.takeIf { it in 1..30 } ?: 0,
                        screenshotButton = if (editPadOnly || settings?.frameLimit?.screenshotButton == true) {
                            { ScreenshotButton(onClick = ::takeScreenshot) }
                        } else {
                            null
                        },
                        macroButton = if (editPadOnly || (settings?.frameLimit?.macroButton == true && serial != null && recordingSeconds < 0)) {
                            {
                                val playing by macroPlayer.playing.collectAsState()
                                MacroButton(macroList, playing != null, macroActions)
                            }
                        } else {
                            null
                        },
                        aiButton = if (editPadOnly || settings?.gemini?.enabled == true) {
                            { AiButton(onClick = ::openAi) }
                        } else {
                            null
                        },
                    )
                    if (settings?.osd?.showFps == true) FpsOverlay { core.fps }
                    if (serial != null && settings?.gemini?.memosVisible == true && memoList.isNotEmpty()) {
                        MemoOverlay(memoList, memoActions)
                    }
                    val gameAspect = settings?.display?.let(::gameAspectOf)
                    val playingMacro by macroPlayer.playing.collectAsState()
                    if (recordingSeconds >= 0) {
                        MacroStatus(
                            stringResource(R.string.macro_recording, durationLabel(recordingSeconds * 1000)),
                            onStop = ::stopRecording,
                            recording = true,
                            gameAspect = gameAspect,
                            onWait = if (waitDialogOpen) null else ::openWaitDialog,
                        )
                    } else if (playingMacro != null) {
                        val waiting by macroPlayer.waiting.collectAsState()
                        MacroStatus(
                            stringResource(
                                if (waiting) R.string.macro_waiting else R.string.macro_playing,
                                playingMacro?.name.orEmpty(),
                            ),
                            onStop = macroPlayer::stop,
                            recording = false,
                            gameAspect = gameAspect,
                        )
                    }
                    val current = settings
                    if (rewindOpen) {
                        RewindMenu(rewindSlots, rewindNow, stateBusy, ::rewindTo, ::closeRewind)
                    }
                    if (cheatsOpen) {
                        CheatsMenu(cheatList, ::toggleCheat, ::closeCheats)
                    }
                    if (waitDialogOpen) {
                        ConditionDialog(
                            kind = ConditionKind.Wait,
                            current = null,
                            suggestions = memorySearch.state.hits.map { it.address to memorySearch.state.size },
                            onDismiss = ::closeWaitDialog,
                            onSave = { condition, seconds, _ ->
                                if (condition != null) {
                                    macroRecorder.addWait(condition, seconds?.let(::secondsToFrames))
                                    showMessage(getString(R.string.macro_wait_added))
                                }
                                closeWaitDialog()
                            },
                        )
                    }
                    if (searchOpen) {
                        MemorySearchMenu(memorySearch, current?.memoryEditor?.enabled == true, ::closeSearch)
                    }
                    if (macrosOpen) {
                        MacrosMenu(
                            macroList,
                            macroActions,
                            memorySearch.state.hits.map { it.address to memorySearch.state.size },
                            ::closeMacros,
                        )
                    }
                    padEditor?.let { PadEditorBar(it, serial != null, ::closePadEditor) { closePadEditor(null) } }
                    val panelShot = aiShot
                    AiPanelHost(
                        panelShot?.let { AiPanelData(it, aiHasImage, aiMessages, aiState, aiHistory, serial != null) },
                        aiPanelActions,
                    )
                    memoEdit?.let { target ->
                        MemoDialog(
                            initial = target.takeIf { it !== NewMemo },
                            onSave = { title, text -> saveMemoEdit(target, title, text) },
                            onDismiss = ::closeMemoEditor,
                        )
                    }
                    if (menuOpen && current != null) {
                        InGameMenu(current, saveSlots, selectedSlot, stateBusy, serial != null, discChoices, menuActions)
                    }
                }
            }
        }

        lifecycleScope.launch {
            padEdits = container.padLayoutRepository.load(serial)
            if (editPadOnly) {
                val repository = container.settingsRepository
                val overrides = serial?.let { repository.loadOverrides(it) } ?: SettingsOverrides()
                settings = SettingsJson.merge(repository.loadGlobal(), overrides)
                touchPadVisible = true
                padEditor = PadEditor(padEdits)
            }
        }
        serial?.let { serial ->
            lifecycleScope.launch { container.macroRepository.macros(serial).collect { macroList = it } }
            lifecycleScope.launch { container.memoRepository.memos(serial).collect { memoList = it } }
            lifecycleScope.launch { container.geminiHistoryRepository.chats(serial).collect { aiHistory = it } }
        }
        if (!editPadOnly) lifecycleScope.launch { boot(gameUri) }
    }

    private suspend fun boot(gameUri: Uri) {
        try {
            core.initialize()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Core unavailable", e)
            finishWithMessage(getString(R.string.library_message_core_unavailable))
            return
        }

        // A home screen shortcut outlives the access to the game folder.
        if (gameUri != Uri.EMPTY && !withContext(Dispatchers.IO) { canRead(gameUri) }) {
            finishWithMessage(getString(R.string.emulation_game_unreadable))
            return
        }

        if (!startVm(gameUri, resume = true)) return

        // Settings changed during play (from anywhere in this process) reach the running game.
        val settings = container.settingsRepository
        lifecycleScope.launch {
            settings.changes.drop(1).collect {
                if (!core.hasActiveVm) return@collect
                val current = serial?.let { settings.loadOverrides(it) } ?: SettingsOverrides()
                apply(settings.loadGlobal(), current)
                applyEmulationOrientation()
                applyOutputScale()
            }
        }
    }

    /**
     * Reads the settings and boots the game on a new emulation thread. False if it cannot.
     * With [resume], a suspend state the game left behind is booted into instead of the start.
     */
    private suspend fun startVm(gameUri: Uri, resume: Boolean = false): Boolean {
        // Settings are read fresh on every boot rather than shared in memory with
        // MainActivity, so this activity can move to its own process.
        val settings = container.settingsRepository
        val overrides = serial?.let { settings.loadOverrides(it) } ?: SettingsOverrides()
        val global = settings.loadGlobal()
        // The core picks a BIOS for the game's region (or the game's own, from its settings).
        if (withContext(Dispatchers.IO) { container.biosRepository.installed().isEmpty() }) {
            finishWithMessage(getString(R.string.emulation_bios_missing))
            return false
        }
        apply(global, overrides)
        applyEmulationOrientation()
        applyOutputScale()
        val suspended = serial?.takeIf { resume }?.let { withContext(Dispatchers.IO) { core.suspendStateFile(it) } }
        suspendFile = suspended
        core.setBootSaveState(suspended)
        customDriverInUse = useVulkanDriver(global, SettingsJson.merge(global, overrides))

        // Every game the core runs gets this game's cheats, so a game without a serial gets none.
        core.setCheats(serial?.let { container.cheatRepository.load(it) }.orEmpty())

        currentDiscUri = gameUri
        Log.i(TAG, "Booting $gameUri (serial $serial)" + if (suspended != null) " from ${suspended.name}" else "")
        emulationThread = thread(name = "Emulation") {
            val error = core.runVm(gameUri.toString(), serial)
            runOnUiThread { onVmStopped(gameUri, error) }
        }
        customDriverInUse?.let { driver ->
            lifecycleScope.launch {
                while (!core.hasActiveVm && emulationThread != null) delay(VM_POLL_MS)
                // The Vulkan library falls back to the system loader without saying so.
                val failure = core.customDriverFailure ?: return@launch
                Log.w(TAG, "Driver ${driver.id} did not open: $failure")
                Toast.makeText(this@EmulationActivity, getString(R.string.emulation_driver_not_loaded, driver.name, failure), Toast.LENGTH_LONG).show()
            }
        }
        suspended?.let { file ->
            // Used once the VM is up: it has loaded the state by then. Kept until then so a
            // driver fallback can boot into it again.
            lifecycleScope.launch {
                while (suspendFile === file && !core.hasActiveVm) delay(VM_POLL_MS)
                discardSuspendState()
            }
        }
        return true
    }

    /** Deletes the suspend state this boot used, so a later start begins at the start. */
    private fun discardSuspendState() {
        val file = suspendFile ?: return
        suspendFile = null
        thread { file.delete() }
    }

    private fun onVmStopped(gameUri: Uri, error: String?) {
        val failedDriver = customDriverInUse
        when {
            // The custom driver loaded but the game still could not start (e.g. the GPU could
            // not create a Vulkan device with it). Its library stays loaded in this process,
            // so try again in a new one with the system driver.
            error != null && failedDriver != null && !systemDriverForced -> {
                Log.w(TAG, "Boot failed with driver ${failedDriver.id} ($error); retrying with the system driver")
                relaunch(gameUri, failedDriver.name)
            }
            error != null -> {
                Log.e(TAG, "Boot failed: $error")
                discardSuspendState()
                finishWithMessage(getString(R.string.emulation_boot_failed, error))
            }
            restartRequested && !isDestroyed -> {
                restartRequested = false
                lifecycleScope.launch { startVm(gameUri) }
            }
            else -> {
                discardSuspendState()
                finish()
            }
        }
    }

    /**
     * Points the core at the custom Vulkan driver this game uses. A game whose driver has been
     * deleted follows the global driver, and the system driver is used when neither is installed
     * (or after a boot failed with the custom driver, see [systemDriverForced]).
     * Returns the custom driver set, or null for the system driver.
     */
    private suspend fun useVulkanDriver(global: EmulatorSettings, effective: EmulatorSettings): GpuDriver? {
        val installed = container.driverRepository.list()
        fun find(id: String) = installed.firstOrNull { it.id == id }
        val id = effective.output.customDriverId
        val driver = if (id.isEmpty() || systemDriverForced) null else find(id) ?: find(global.output.customDriverId)
        if (id.isNotEmpty() && driver?.id != id) Log.w(TAG, "Driver $id is not installed, using ${driver?.id ?: "the system driver"}")
        core.useVulkanDriver(driver?.toVulkanDriver(), effective.output.turnipUbwcHint)
        return driver
    }

    private suspend fun apply(global: EmulatorSettings, overrides: SettingsOverrides) {
        core.applySettings(global, overrides, serial)
        settings = SettingsJson.merge(global, overrides)
        settings?.let { gamepad.configure(it.controls) }
    }

    /**
     * Locks the play screen's orientation. Only assigns when it changes: writing
     * requestedOrientation makes Android re-evaluate even an identical value, and each
     * change rebuilds the Vulkan swapchain (ARMSX2 hit multi-second picture freezes).
     */
    private fun applyEmulationOrientation() {
        val effective = settings ?: return
        val requested = when (effective.output.orientation.coerceIn(0, 3)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != requested) requestedOrientation = requested
    }

    /** Pins the output buffer size from hwScaler/screenResOverride; see OutputScale.kt. */
    private fun applyOutputScale() {
        val holder = surfaceHolder ?: return
        val effective = settings ?: return
        val buffer = outputBufferSize(viewWidth, viewHeight, effective.output.hwScaler, effective.output.screenResOverride)
        if (buffer == null) {
            bufferWidth = viewWidth
            bufferHeight = viewHeight
            holder.setSizeFromLayout()
        } else {
            bufferWidth = buffer.first
            bufferHeight = buffer.second
            holder.setFixedSize(buffer.first, buffer.second)
        }
        pushDisplayCutoutInset()
    }

    /**
     * Shifts a top-aligned portrait render below the display cutout (punch-hole /
     * notch camera). Top-aligning put the picture directly under the camera, which
     * sat on the game. Sent on creation and every rotation/resize, in the surface
     * pixels the renderer works in.
     */
    private fun pushDisplayCutoutInset() {
        val view = surfaceView ?: return
        // Landscape: the cutout is on a side, so shifting vertically would not help.
        val inset = if (viewHeight > viewWidth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { view.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        core.setPortraitRenderTopInset(scaleInsetToBuffer(inset, viewHeight, bufferHeight))

        // The on-screen messages are drawn over the whole surface, so keep them off the cutout on any side.
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { view.rootWindowInsets?.displayCutout }.getOrNull()
        } else {
            null
        }
        core.setOsdSafeAreaInsets(
            scaleInsetToBuffer(cutout?.safeInsetLeft ?: 0, viewWidth, bufferWidth),
            scaleInsetToBuffer(cutout?.safeInsetTop ?: 0, viewHeight, bufferHeight),
            scaleInsetToBuffer(cutout?.safeInsetRight ?: 0, viewWidth, bufferWidth),
            scaleInsetToBuffer(cutout?.safeInsetBottom ?: 0, viewHeight, bufferHeight),
        )
    }

    private fun takeScreenshot() {
        lifecycleScope.launch {
            val saved = screenshots.capture(serial)
            showMessage(getString(if (saved) R.string.emulation_screenshot_saved else R.string.emulation_screenshot_failed))
        }
    }

    private fun openMenu() {
        gamepad.releaseAll()
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedByMenu = true
        }
        menuOpen = true
        saveSlots = null
        discChoices = emptyList()
        lifecycleScope.launch { saveSlots = readSaveSlots() }
        lifecycleScope.launch { discChoices = readDiscs() }
    }

    /** Opens the rewind menu over a paused game, from the button over the game. */
    private fun openRewind() {
        if (menuOpen || rewindOpen || cheatsOpen || stateBusy) return
        gamepad.releaseAll()
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedByMenu = true
        }
        showRewind()
    }

    private fun showRewind() {
        rewindOpen = true
        rewindSlots = null
        rewindNow = System.currentTimeMillis()
        lifecycleScope.launch {
            rewindSlots = withContext(Dispatchers.IO) { core.rewindPoints() }.map { point ->
                val thumbnail = point.screenshot?.let { png ->
                    BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
                }
                SaveSlot(point.slot, point.savedAt, thumbnail)
            }
        }
    }

    private fun closeRewind() {
        rewindOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    /** Goes back to the rewind point [n], then returns to the game. */
    private fun rewindTo(n: Int) {
        stateBusy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { core.rewindTo(n) }
            stateBusy = false
            when {
                ok -> closeRewind()
                core.isMemcardBusy -> showMessage(getString(R.string.emulation_state_memcard_busy))
                else -> showMessage(getString(R.string.rewind_failed))
            }
        }
    }

    private fun showCheats() {
        cheatsOpen = true
        cheatList = null
        lifecycleScope.launch { cheatList = serial?.let { container.cheatRepository.load(it) }.orEmpty() }
    }

    private fun closeCheats() {
        cheatsOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private fun toggleCheat(id: String, enabled: Boolean) {
        val serial = serial ?: return
        // The switch follows the tap at once; the core catches up when it has reloaded.
        cheatList = cheatList?.map { if (it.id == id) it.copy(enabled = enabled) else it }
        lifecycleScope.launch {
            container.cheatRepository.update(serial) { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
            val cheats = container.cheatRepository.load(serial)
            core.setCheats(cheats)
        }
    }

    /**
     * Captures the screen while the game still runs, then pauses it and opens the question panel.
     * The frame is kept in memory only: the temporary file is deleted at once.
     */
    private fun openAi() {
        if (overlayOpen || aiBusy || recordingSeconds >= 0) return
        if (container.geminiKeyStore.get().isEmpty()) {
            showMessage(getString(R.string.ai_no_key))
            return
        }
        aiBusy = true
        gamepad.releaseAll()
        lifecycleScope.launch {
            val shot = screenshots.captureTemporary()?.let { png ->
                withContext(Dispatchers.IO) { prepareAiShot(png).also { png.delete() } }
            }
            aiBusy = false
            if (shot == null) {
                showMessage(getString(R.string.ai_capture_failed))
                return@launch
            }
            if (core.vmState.value == VmState.Running) {
                core.pause()
                pausedByMenu = true
            }
            aiState = AiState.Idle
            newChat()
            aiShot = shot
        }
    }

    private fun closeAi() {
        aiJob?.cancel()
        aiShot = null
        aiState = AiState.Idle
        aiMessages = emptyList()
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private fun askGemini(question: String) {
        val shot = aiShot ?: return
        val gemini = settings?.gemini ?: return
        val asked = aiMessages + GeminiMessage(GeminiMessage.USER, question)
        val chatId = aiChatId
        aiState = AiState.Loading
        aiJob?.cancel()
        aiJob = lifecycleScope.launch {
            val result = GeminiApi.ask(
                container.geminiKeyStore.get(), gemini.model, gemini.systemPrompt,
                shot.jpeg.takeIf { aiHasImage }, asked,
            )
            result.fold(
                onSuccess = { answer ->
                    // A question and its answer join the conversation together: a failed one leaves the box as it was.
                    val messages = asked + GeminiMessage(GeminiMessage.MODEL, answer)
                    if (chatId == aiChatId) {
                        aiMessages = messages
                        aiState = AiState.Idle
                    }
                    serial?.let { container.geminiHistoryRepository.save(it, GeminiChat(chatId, System.currentTimeMillis(), messages)) }
                },
                onFailure = {
                    aiState = AiState.Error(
                        when (it) {
                            is GeminiError.NoKey -> getString(R.string.ai_no_key)
                            is GeminiError.Rejected -> getString(R.string.ai_error_rejected, it.message.orEmpty())
                            is GeminiError.Empty -> getString(R.string.ai_error_empty, it.message.orEmpty())
                            else -> getString(R.string.ai_error_network)
                        },
                    )
                },
            )
        }
    }

    /** Starts a conversation about the screenshot in the panel. */
    private fun newChat() {
        aiJob?.cancel()
        aiChatId = UUID.randomUUID().toString()
        aiMessages = emptyList()
        aiHasImage = true
        aiState = AiState.Idle
    }

    private val aiPanelActions = object : AiPanelActions {
        override fun ask(question: String) = askGemini(question)
        override fun saveMemo(question: String, answer: String) = this@EmulationActivity.saveMemo(question, answer)

        override fun openChat(chat: GeminiChat) {
            aiJob?.cancel()
            aiChatId = chat.id
            aiMessages = chat.messages
            aiHasImage = false
            aiState = AiState.Idle
        }

        override fun deleteChat(chat: GeminiChat) {
            val serial = serial ?: return
            lifecycleScope.launch { container.geminiHistoryRepository.delete(serial, chat.id) }
        }

        override fun newChat() = this@EmulationActivity.newChat()
        override fun close() = closeAi()
    }

    /** Opens the memo dialog over a paused game, to write [memo] (null: a new one) by hand. */
    private fun openMemoEditor(memo: Memo?) {
        if (serial == null || memoEdit != null) return
        gamepad.releaseAll()
        if (core.vmState.value == VmState.Running && !pausedByMenu) {
            core.pause()
            pausedByMenu = true
        }
        memoEdit = memo ?: NewMemo
    }

    private fun closeMemoEditor() {
        memoEdit = null
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private fun saveMemoEdit(target: Memo, title: String, text: String) {
        if (target === NewMemo) {
            saveMemo(title, text)
            // A memo that cannot be seen would look like nothing happened.
            if (settings?.gemini?.memosVisible == false) {
                updateSetting("gemini.memosVisible") { it.copy(gemini = it.gemini.copy(memosVisible = true)) }
            }
        } else {
            val serial = serial ?: return
            lifecycleScope.launch {
                container.memoRepository.update(serial) { list ->
                    list.map { if (it.id == target.id) it.copy(question = title, text = text) else it }
                }
            }
        }
        closeMemoEditor()
    }

    /** Keeps an answer as a memo, a little lower than the last one so they do not pile up. */
    private fun saveMemo(question: String, answer: String) {
        val serial = serial ?: return
        lifecycleScope.launch {
            container.memoRepository.update(serial) { memos ->
                val step = memos.size % MEMO_CASCADE
                memos + Memo(
                    id = UUID.randomUUID().toString(),
                    question = question,
                    text = answer,
                    x = 0.04f + 0.03f * step,
                    y = 0.08f + 0.06f * step,
                    createdAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private val memoActions = object : MemoActions {
        override fun move(memo: Memo, x: Float, y: Float) = change(memo) { it.copy(x = x, y = y) }
        override fun setCollapsed(memo: Memo, collapsed: Boolean) = change(memo) { it.copy(collapsed = collapsed) }
        override fun edit(memo: Memo) = openMemoEditor(memo)

        override fun delete(memo: Memo) {
            val serial = serial ?: return
            lifecycleScope.launch { container.memoRepository.update(serial) { list -> list.filter { it.id != memo.id } } }
        }

        private fun change(memo: Memo, transform: (Memo) -> Memo) {
            val serial = serial ?: return
            lifecycleScope.launch {
                container.memoRepository.update(serial) { list -> list.map { if (it.id == memo.id) transform(it) else it } }
            }
        }
    }

    private fun closeSearch() {
        searchOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private fun showMacros() {
        macrosOpen = true
    }

    private fun closeMacros() {
        macrosOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    /** Pauses the game while a wait point is set during a recording. */
    private fun openWaitDialog() {
        if (waitDialogOpen) return
        gamepad.releaseAll()
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedByMenu = true
        }
        waitDialogOpen = true
    }

    private fun closeWaitDialog() {
        waitDialogOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    /** Lets the game go, then records what is pressed until [stopRecording]. */
    private fun startRecording() {
        macroPlayer.stop()
        macrosOpen = false
        menuOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
        gamepad.releaseAll()
        macroRecorder.start()
        recordingSeconds = 0
        val startedAt = SystemClock.elapsedRealtime()
        recordingJob?.cancel()
        recordingJob = lifecycleScope.launch {
            while (isActive) {
                delay(RECORDING_TICK_MS)
                recordingSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            }
        }
    }

    private fun stopRecording() {
        if (recordingSeconds < 0) return
        recordingJob?.cancel()
        recordingSeconds = -1
        val serial = serial
        val names = macroList.map { it.name }.toSet()
        val name = generateSequence(macroList.size + 1) { it + 1 }
            .map { getString(R.string.macro_default_name, it) }.first { it !in names }
        val macro = macroRecorder.stop(UUID.randomUUID().toString(), name)
        if (macro == null || serial == null) {
            showMessage(getString(R.string.macro_nothing_recorded))
            return
        }
        lifecycleScope.launch {
            container.macroRepository.update(serial) { it + macro }
            showMessage(getString(R.string.macro_saved, name))
        }
    }

    private fun updateMacro(id: String, transform: (Macro) -> Macro?) {
        val serial = serial ?: return
        lifecycleScope.launch {
            container.macroRepository.update(serial) { list ->
                list.mapNotNull { if (it.id == id) transform(it) else it }
            }
        }
    }

    private val macroActions = object : MacroActions {
        override fun play(macro: Macro) {
            if (macrosOpen) closeMacros()
            macroPlayer.play(macro)
        }

        override fun stop() = macroPlayer.stop()

        override fun record() = startRecording()

        override fun rename(macro: Macro, name: String) {
            updateMacro(macro.id) { it.copy(name = name) }
        }

        override fun setLoop(macro: Macro, loop: Boolean) {
            if (macroPlayer.playing.value?.id == macro.id) macroPlayer.stop()
            updateMacro(macro.id) { it.copy(loop = loop) }
        }

        override fun setStartCondition(macro: Macro, condition: MemoryCondition?) {
            if (macroPlayer.playing.value?.id == macro.id) macroPlayer.stop()
            updateMacro(macro.id) { it.copy(startWhen = condition) }
        }

        override fun setStopCondition(macro: Macro, condition: MemoryCondition?) {
            if (macroPlayer.playing.value?.id == macro.id) macroPlayer.stop()
            updateMacro(macro.id) { it.copy(stopWhen = condition) }
        }

        override fun replace(macro: Macro) {
            if (macroPlayer.playing.value?.id == macro.id) macroPlayer.stop()
            updateMacro(macro.id) { macro }
        }

        override fun delete(macro: Macro) {
            if (macroPlayer.playing.value?.id == macro.id) macroPlayer.stop()
            updateMacro(macro.id) { null }
        }

        override fun manage() {
            // The game stays paused while the macros are up.
            if (core.vmState.value == VmState.Running) {
                core.pause()
                pausedByMenu = true
            }
            gamepad.releaseAll()
            showMacros()
        }
    }

    /** Closes the layout editor, storing the edits for [save]'s games if given; the game goes on. */
    private fun closePadEditor(save: PadLayoutScope?) {
        val editor = padEditor ?: return
        padEditor = null
        if (save != null) {
            padEdits = editor.edits
            val repository = container.padLayoutRepository
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    when {
                        save == PadLayoutScope.Game && serial != null -> repository.save(serial, editor.edits)
                        else -> {
                            // The layout every game shares; this game follows it, whatever it had of its own.
                            repository.save(null, editor.edits)
                            serial?.let { repository.clearGame(it) }
                        }
                    }
                }
                // Finishing ends the process (onDestroy), so only after the layout is written.
                if (editPadOnly) finish()
            }
        } else if (editPadOnly) {
            finish()
        }
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private fun closeMenu() {
        menuOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private suspend fun readSaveSlots(): List<SaveSlot> = withContext(Dispatchers.IO) {
        EmulatorCore.SAVE_STATE_SLOTS.map { slot ->
            val info = core.saveStateInfo(slot)
            val thumbnail = info?.screenshot?.let { png ->
                BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
            SaveSlot(slot, info?.savedAt, thumbnail)
        }
    }

    private suspend fun readDiscs(): List<DiscChoice> = withContext(Dispatchers.IO) {
        core.discPlaylist()?.let { playlist ->
            return@withContext playlist.titles.mapIndexed { i, title ->
                DiscChoice(title.ifBlank { getString(R.string.menu_disc_number, i + 1) }, i == playlist.current, index = i)
            }
        }
        val serial = serial ?: return@withContext emptyList()
        val discs = runCatching { container.libraryRepository.discSet(serial) }.getOrDefault(emptyList())
        if (discs.size < 2) return@withContext emptyList()
        discs.map { DiscChoice(it.discTitle, it.uri == currentDiscUri, uri = it.uri) }
    }

    private val menuActions = object : InGameMenuActions {
        override fun resume() = closeMenu()

        override fun selectSlot(slot: Int) {
            selectedSlot = slot
        }

        override fun saveState() = runStateAction(
            action = core::saveState,
            done = R.string.emulation_state_saved,
            failed = R.string.emulation_state_save_failed,
        )

        override fun loadState() = runStateAction(
            action = core::loadState,
            done = R.string.emulation_state_loaded,
            failed = R.string.emulation_state_load_failed,
        )

        override fun screenshot() {
            // The core captures the next frame it presents, which a paused game never does.
            closeMenu()
            takeScreenshot()
        }

        override fun editPadLayout() {
            // The game stays paused (by the menu) while the layout is edited.
            menuOpen = false
            touchPadVisible = true
            padEditor = PadEditor(padEdits)
        }

        override fun hideTouchPad() {
            touchPadVisible = false
            closeMenu()
        }

        override fun macros() {
            // The game stays paused (by the menu) while the macros are up.
            menuOpen = false
            showMacros()
        }

        override fun addMemo() {
            // The game stays paused (by the menu) while the memo is written.
            menuOpen = false
            openMemoEditor(null)
        }

        override fun setShowMemos(show: Boolean) = updateSetting("gemini.memosVisible") {
            it.copy(gemini = it.gemini.copy(memosVisible = show))
        }

        override fun setMacroButton(show: Boolean) = updateSetting("frameLimit.macroButton") {
            it.copy(frameLimit = it.frameLimit.copy(macroButton = show))
        }

        override fun setScreenshotButton(show: Boolean) = updateSetting("frameLimit.screenshotButton") {
            it.copy(frameLimit = it.frameLimit.copy(screenshotButton = show))
        }

        override fun reset() {
            menuOpen = false
            pausedByMenu = false
            restartRequested = true
            core.shutdown()
        }

        override fun changeDisc(disc: DiscChoice) {
            stateBusy = true
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    when {
                        disc.index != null -> core.switchDisc(disc.index)
                        disc.uri != null -> core.insertDisc(disc.uri)
                        else -> false
                    }
                }
                stateBusy = false
                if (ok) {
                    disc.uri?.let { uri ->
                        currentDiscUri = uri
                        // The library starts this disc next time.
                        recordKey?.let { key ->
                            withContext(Dispatchers.IO) {
                                runCatching { container.gameRecordRepository.setLastDiscBlocking(key, uri.toString()) }
                            }
                        }
                    }
                    showMessage(getString(R.string.emulation_disc_changed, disc.title))
                    closeMenu()
                } else {
                    showMessage(getString(R.string.emulation_disc_change_failed))
                }
            }
        }

        override fun exit() {
            menuOpen = false
            pausedByMenu = false
            this@EmulationActivity.exit()
        }

        override fun rewind() {
            // The game stays paused (by the menu) while the rewind menu is up.
            menuOpen = false
            showRewind()
        }

        override fun memorySearch() {
            // The game stays paused (by the menu) while the search is up.
            menuOpen = false
            searchOpen = true
        }

        override fun cheats() {
            // The game stays paused (by the menu) while the cheats are up.
            menuOpen = false
            showCheats()
        }

        override fun exitAndResumeLater() {
            stateBusy = true
            lifecycleScope.launch {
                if (saveSuspendState()) {
                    menuOpen = false
                    pausedByMenu = false
                    this@EmulationActivity.exit()
                } else {
                    stateBusy = false
                    showMessage(getString(R.string.emulation_suspend_failed))
                }
            }
        }

        override fun setShowFps(show: Boolean) = updateSetting("osd.showFps") {
            it.copy(osd = it.osd.copy(showFps = show))
        }

        override fun setFastForwardButton(show: Boolean) = updateSetting("frameLimit.fastForwardButton") {
            it.copy(frameLimit = it.frameLimit.copy(fastForwardButton = show))
        }

        override fun setFastForwardSpeed(speed: Int) = updateSetting("frameLimit.fastForwardSpeed") {
            it.copy(frameLimit = it.frameLimit.copy(fastForwardSpeed = speed))
        }
    }

    /**
     * Writes the suspend state with the game paused. A memory card that is mid-write refuses it,
     * and only finishes while the game runs, so the game gets short turns until it is not busy.
     */
    private suspend fun saveSuspendState(): Boolean = withContext(Dispatchers.IO) {
        repeat(SUSPEND_SAVE_TRIES) {
            if (core.saveSuspendState()) return@withContext true
            if (!core.isMemcardBusy) return@withContext false
            core.resume()
            delay(SUSPEND_SAVE_RETRY_MS)
            core.pause()
        }
        false
    }

    /** Saves or loads the selected slot, then returns to the game. */
    private fun runStateAction(action: (Int) -> Boolean, done: Int, failed: Int) {
        val slot = selectedSlot
        stateBusy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { action(slot) }
            stateBusy = false
            when {
                ok -> {
                    showMessage(getString(done, slot))
                    closeMenu()
                }
                // The card only finishes writing while the game runs, so waiting in the menu cannot help.
                core.isMemcardBusy -> showMessage(getString(R.string.emulation_state_memcard_busy))
                else -> showMessage(getString(failed, slot))
            }
        }
    }

    /**
     * Changes a setting where it is set (this game's settings or the global ones). The change
     * reaches the game through the settings collector in [boot].
     */
    private fun updateSetting(path: String, transform: (EmulatorSettings) -> EmulatorSettings) {
        lifecycleScope.launch { container.settingsRepository.updateWhereSet(serial, path, transform) }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceHolder = holder
            core.onSurfaceCreated()
            applyOutputScale()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Before onSurfaceChanged: the core reads it when it (re)acquires the window.
            displayRefreshRate()?.let(core::setDisplayRefreshRate)
            core.onSurfaceChanged(holder.surface, width, height)
            // width/height are the live buffer size; keep the cutout inset scaled to it.
            bufferWidth = width
            bufferHeight = height
            pushDisplayCutoutInset()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfaceHolder === holder) surfaceHolder = null
            core.onSurfaceDestroyed()
        }
    }

    override fun onStart() {
        super.onStart()
        visibleSince = SystemClock.elapsedRealtime()
        if (emulationThread == null) return
        core.setAppBackgrounded(false)
        lifecycleScope.launch {
            suspendSave?.join()
            // Back before the process was lost: the game goes on from here, not from that state.
            suspendSave = null
            serial?.let { withContext(Dispatchers.IO) { core.suspendStateFile(it)?.delete() } }
            if (pausedWhileHidden) {
                pausedWhileHidden = false
                core.resume()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the menu is open a controller navigates it: the D-pad moves focus, A clicks, B goes back.
        if (overlayOpen) return super.dispatchKeyEvent(menuKeyEvent(event))
        return gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Unhandled hat motion comes back as D-pad key events, which the menu navigates by.
        if (overlayOpen) return super.dispatchGenericMotionEvent(event)
        return gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun menuKeyEvent(event: KeyEvent): KeyEvent {
        val keyCode = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return event
        }
        return KeyEvent(
            event.downTime, event.eventTime, event.action, keyCode, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Key-up events go to whatever has focus, so a button held while focus leaves would
        // stay pressed.
        if (!hasFocus) gamepad.releaseAll()
    }

    override fun onStop() {
        super.onStop()
        recordPlayTime()
        rumble.stop()
        stopRecording()
        if (emulationThread == null) return
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedWhileHidden = true
        }
        core.setAppBackgrounded(true)
        // Android may end this process while it is hidden. The state lets the next start go on
        // from here; coming back first discards it (onStart).
        if (serial != null && core.hasActiveVm && !stateBusy) {
            suspendSave = lifecycleScope.launch { saveSuspendState() }
        }
    }

    /** Adds the time since [onStart] to the game's play time, and marks it as just played. */
    private fun recordPlayTime() {
        val key = recordKey ?: return
        if (visibleSince == 0L) return
        val seconds = (SystemClock.elapsedRealtime() - visibleSince) / 1000
        visibleSince = 0L
        runCatching { container.gameRecordRepository.addPlayTimeBlocking(key, seconds) }
            .onFailure { Log.w(TAG, "Could not record play time", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        core.rumbleHandler = null
        // configChanges keeps rotation from recreating this activity, so being destroyed
        // means the game is over.
        if (emulationThread != null && core.hasActiveVm) core.shutdown()
        if (isFinishing) exitProcess()
    }

    /** Stops the VM; the emulation thread finishes the activity once it has shut down. */
    private fun exit() {
        when {
            emulationThread == null -> finish()
            core.hasActiveVm -> core.shutdown()
            // Still booting: the core ignores a stop request until the VM is up.
            else -> Unit
        }
    }

    private fun canRead(uri: Uri): Boolean =
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    private fun finishWithMessage(message: String) {
        // Application context: this can run after the activity is already destroyed.
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        exitDelayMs = TOAST_VISIBLE_MS
        finish()
    }

    /** Starts the game again with the system driver in a fresh process, and ends this one. */
    private fun relaunch(gameUri: Uri, failedDriverName: String) {
        val target = intent(this, gameUri, serial)
            .putExtra(EXTRA_SYSTEM_DRIVER, true)
            .putExtra(EXTRA_FAILED_DRIVER, failedDriverName)
        startActivity(RelaunchActivity.intent(this, target))
        finish()
        // Not left to onDestroy: RelaunchActivity is translucent, so this activity stays
        // visible below it and would be destroyed only after the new one has started in
        // this very process.
        exitProcess()
    }

    /**
     * Ends the :emu process once the VM thread is done. Anything a driver or the core loaded
     * stays in a process until it dies, so a game never shares one with the next.
     */
    private fun exitProcess() {
        val vmThread = emulationThread
        thread(name = "ProcessExit") {
            vmThread?.join(VM_STOP_WAIT_MS)
            if (exitDelayMs > 0) Thread.sleep(exitDelayMs)
            Process.killProcess(Process.myPid())
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun isControllerConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        !device.isVirtual && device.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }

    private fun displayRefreshRate(): Float? {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return display?.refreshRate
    }

    companion object {
        private const val TAG = "EmulationActivity"

        /** Player 1. Both the touch pad and controllers play as player 1 for now. */
        private const val PORT = 0

        private const val EXTRA_GAME_URI = "com.kira3.ametrine.extra.GAME_URI"
        private const val EXTRA_SERIAL = "com.kira3.ametrine.extra.SERIAL"
        private const val EXTRA_EDIT_PAD_ONLY = "com.kira3.ametrine.extra.EDIT_PAD_ONLY"
        private const val EXTRA_SYSTEM_DRIVER = "com.kira3.ametrine.extra.SYSTEM_DRIVER"
        private const val EXTRA_FAILED_DRIVER = "com.kira3.ametrine.extra.FAILED_DRIVER"
        private const val VM_STOP_WAIT_MS = 5_000L
        private const val VM_POLL_MS = 200L
        private const val RECORDING_TICK_MS = 250L

        /** Memos saved one after the other are offset in this many steps before starting over. */
        private const val MEMO_CASCADE = 6

        /** Stands for "a memo not written yet" in [memoEdit]. */
        private val NewMemo = Memo("", "", "", 0f, 0f)
        private const val SUSPEND_SAVE_TRIES = 10
        private const val SUSPEND_SAVE_RETRY_MS = 250L
        private const val TOAST_VISIBLE_MS = 3_500L

        /**
         * Intent for a home screen shortcut. A shortcut's extras can only hold simple values,
         * so the URI travels as the intent's data instead of [EXTRA_GAME_URI].
         */
        fun shortcutIntent(context: Context, gameUri: Uri, serial: String?): Intent =
            Intent(Intent.ACTION_VIEW, gameUri, context, EmulationActivity::class.java)
                .putExtra(EXTRA_SERIAL, serial)

        /** Opens only the pad layout editor, with no game: for [serial]'s game, or for every game when null. */
        fun padLayoutIntent(context: Context, serial: String?): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_GAME_URI, Uri.EMPTY)
                .putExtra(EXTRA_SERIAL, serial)
                .putExtra(EXTRA_EDIT_PAD_ONLY, true)

        fun intent(context: Context, gameUri: Uri, serial: String?): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_GAME_URI, gameUri)
                .putExtra(EXTRA_SERIAL, serial)
    }
}
