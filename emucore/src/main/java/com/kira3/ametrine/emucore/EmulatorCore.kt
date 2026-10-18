package com.kira3.ametrine.emucore

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import android.view.Surface
import com.kira3.ametrine.emucore.settings.CoreKeys
import com.kira3.ametrine.emucore.settings.EmulatorSettings
import com.kira3.ametrine.emucore.settings.SettingsEffects
import com.kira3.ametrine.emucore.settings.SettingsJson
import com.kira3.ametrine.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's only way into the emulation core. Wraps [NativeCore] (a Kotlin stub in this
 * template, JNI with a real core) so the core boundary stays inside this module.
 *
 * One instance per process (the core itself is a process-wide singleton). Call
 * [initialize] once before anything else.
 *
 * The API is the one the app's screens use; a new core keeps it and fills in [NativeCore].
 */
class EmulatorCore(context: Context) {
    private val appContext = context.applicationContext

    /** Memory cards, save states, settings and the core's resources live here. */
    val dataDir: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir

    /**
     * The folder the core loads the BIOS from. The core opens the BIOS with plain file
     * APIs, so a BIOS picked through SAF has to be copied in here.
     */
    val biosDir: File = File(dataDir, "bios")

    /** Where the core keeps its memory cards (128 KB `.mcd` files). */
    val memoryCardsDir: File = File(dataDir, "memcards")

    /** The save states (`<serial>_<slot>.sav`) and the suspend states (`<serial>_resume.sav`). */
    val saveStatesDir: File = File(dataDir, "savestates")

    /** Null when the native core loaded; otherwise a reason it cannot run on this device. */
    val unavailableReason: String? get() = NativeCore.loadError ?: initError

    /** Why [initialize] failed, if it did. */
    @Volatile
    private var initError: String? = null

    private val _vmState = MutableStateFlow(VmState.Stopped)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    /**
     * Called on the CPU thread when the game drives a pad's motors (0..255 each). Zeros when the
     * game stops them, and when the VM pauses or stops; a resume sends the levels again.
     */
    @Volatile
    var rumbleHandler: ((pad: Int, largeMotor: Int, smallMotor: Int) -> Unit)? = null

    /** Opens the games for the core, which only takes paths. */
    private val files = SafFiles(appContext.contentResolver)

    private val initMutex = Mutex()
    private var initialized = false

    /**
     * Installs the core's resources and initializes the core. Safe to call more than once;
     * only the first call does the work. Throws [IllegalStateException] if the core is
     * unavailable ([unavailableReason]).
     */
    suspend fun initialize() = initMutex.withLock {
        if (initialized) return@withLock
        unavailableReason?.let { throw IllegalStateException(it) }

        withContext(Dispatchers.IO) {
            biosDir.mkdirs()
            CoreResources.install(appContext, dataDir)

            NativeCore.files = files
            NativeCore.listener = object : NativeCore.Listener {
                override fun onVmPausedChanged(paused: Boolean) {
                    val next = if (paused) VmState.Paused else VmState.Running
                    // A late callback after the VM stopped must not revive it.
                    _vmState.update { if (it == VmState.Stopped) it else next }
                }

                override fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
                    rumbleHandler?.invoke(pad, largeMotor, smallMotor)
                }
            }
            NativeCore.initialize(dataDir.absolutePath)?.let { error ->
                initError = error
                throw IllegalStateException(error)
            }
        }
        initialized = true
    }

    val buildVersion: String get() = NativeCore.getBuildVersion()

    // ---- Settings ----

    /**
     * Writes one value to the core's base settings layer (the same section/key names as
     * DuckStation's INI). Changes apply on the next [commitSettings] or VM boot.
     */
    fun setSetting(section: String, key: String, value: Boolean) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: Int) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: Float) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: String) =
        NativeCore.setSetting(section, key, value)

    /** Applies the settings written since the last commit to a running VM. */
    fun commitSettings() = NativeCore.commitSettings()

    private val applyMutex = Mutex()

    /** The effective settings the running VM booted with; null when none is running. */
    @Volatile
    private var bootSettings: EmulatorSettings? = null

    /**
     * Hands the settings to the core: [global], with [overrides] for [serial] (the game about
     * to boot, or the one running). Call it before [runVm], and again whenever settings change
     * while a game runs: the running game takes them straight away, except those in
     * [SettingsEffects.appliesOnNextBoot], which keep what it booted with.
     *
     * The game's BIOS ([com.kira3.ametrine.emucore.settings.SystemSettings.biosFilename]) is
     * dropped when its file is gone, so the core picks one for the game's region instead.
     */
    suspend fun applySettings(
        global: EmulatorSettings,
        overrides: SettingsOverrides,
        serial: String?,
    ) = withContext(Dispatchers.IO) {
        applyMutex.withLock {
            var effective = SettingsJson.merge(global, overrides)
            val bios = effective.system.biosFilename
            if (bios.isNotEmpty() && !File(biosDir, bios).isFile) {
                Log.w(TAG, "BIOS $bios is not installed; the core picks one")
                effective = effective.copy(system = effective.system.copy(biosFilename = ""))
            }

            val running = NativeCore.hasActiveSystem()
            val boot = bootSettings
            if (!running || boot == null) {
                bootSettings = effective
            } else {
                // Changing these under a running game is not safe (the renderer) or does
                // nothing until a reset (the region, the BIOS).
                effective = SettingsJson.copyPaths(effective, boot, SettingsEffects.appliesOnNextBoot)
            }

            for (key in CoreKeys.pinned + CoreKeys.of(effective)) NativeCore.setSetting(key.section, key.key, key.value)
            portraitRenderTop = effective.output.portraitRenderTop
            NativeCore.setDisplayLayout(portraitRenderTop, portraitTopInset)
            if (running) commitSettings()
        }
    }

    // ---- Fast forward ----

    private val _fastForward = MutableStateFlow(false)

    /** True while the running game is fast-forwarded. Every boot starts at normal speed. */
    val fastForward: StateFlow<Boolean> = _fastForward.asStateFlow()

    /** Fast-forwards the running game, or returns it to normal speed. */
    fun setFastForward(on: Boolean) {
        if (!NativeCore.hasActiveSystem()) return
        _fastForward.value = on
        NativeCore.setFastForward(on)
    }

    // ---- BIOS and games ----

    /**
     * Reads BIOS metadata from [uri], or null if it is not a 512 KB PS1 BIOS. A BIOS the core's
     * list does not know has [BiosRegion.Unknown]. Blocking I/O.
     */
    fun readBiosMetadata(uri: Uri): BiosMetadata? {
        val fd = openFd(uri) ?: return null
        val info = NativeCore.getBiosInfoFromFd(fd) ?: return null
        val region = when (info.substringBefore('\t').toIntOrNull()) {
            1 -> BiosRegion.Japan
            2 -> BiosRegion.Usa
            3 -> BiosRegion.Europe
            else -> BiosRegion.Unknown
        }
        return BiosMetadata(region = region, description = info.substringAfter('\t'))
    }

    /**
     * Reads the serial (e.g. "SLUS-00067") from a disc image, or null if it can't. The GameDB's
     * serial when it knows the disc. Needs [initialize]. Blocking I/O.
     */
    fun readGameSerial(uri: Uri): String? {
        val path = files.pathFor(uri) ?: return null
        return NativeCore.getGameSerial(path)
    }

    /** The GameDB's name for [serial], or null when the database doesn't know it. Needs [initialize]. */
    fun gameDbTitle(serial: String): String? = NativeCore.getGameDbTitle(serial)

    /**
     * The cheats to run: the [Cheat.enabled] ones of [cheats] go into the running game now, and into
     * every game booted after it. Codes the core doesn't understand are skipped.
     */
    fun setCheats(cheats: List<Cheat>) {
        val enabled = cheats.filter { it.enabled }
            .map { it.name to GameShark.check(it.code).lines.joinToString("\n") }
            .filter { it.second.isNotEmpty() }
        NativeCore.setCheats(enabled.map { it.first }.toTypedArray(), enabled.map { it.second }.toTypedArray())
    }

    /**
     * The cheats in a cheat file's [text] (the PCSXR, libretro and ePSXe formats), all off, each
     * with an id from [newId]. Empty if it has none.
     */
    fun parseCheatFile(text: String, newId: () -> String): List<Cheat> =
        NativeCore.parseCheats(text).toCheats(newId)

    /** The cheat database's cheats for [serial], all off, each with an id from [newId]. */
    suspend fun databaseCheats(serial: String, newId: () -> String): List<Cheat> {
        initialize()
        return withContext(Dispatchers.IO) { NativeCore.getDatabaseCheats(serial).toCheats(newId) }
    }

    private fun Array<String>?.toCheats(newId: () -> String): List<Cheat> =
        orEmpty().toList().chunked(2).filter { it.size == 2 }.map { (name, code) -> Cheat(newId(), name, code.trim()) }

    /** Opens [uri] and detaches the fd; the core takes ownership and closes it. */
    private fun openFd(uri: Uri): Int? = try {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (e: Exception) {
        Log.w(TAG, "Could not open $uri", e)
        null
    }

    // ---- VM ----

    /**
     * The Vulkan driver the next boot loads: [driver], or the system driver when null.
     * [ubwcFlagHint] turns on Turnip's UBWC flag hint, which recent Turnip builds need on
     * Adreno 740 to draw correctly; other drivers ignore it. Call before [runVm].
     *
     * The core only uses the system driver; the custom driver is not offered.
     */
    @Suppress("UNUSED_PARAMETER")
    fun useVulkanDriver(driver: VulkanDriver?, ubwcFlagHint: Boolean) {
        // Mesa reads its options from the environment each time it creates a device.
        runCatching {
            if (ubwcFlagHint) Os.setenv("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1", true)
            else Os.unsetenv("FD_DEV_FEATURES")
        }
    }

    /**
     * Boots [gameUri] (a content:// URI the app holds read permission for, or "" for the BIOS
     * alone) and runs it until the VM shuts down. Blocks; call it on a dedicated emulation
     * thread.
     *
     * A game from a SAF tree can reach the files beside it (a .cue's tracks, .sbi and .ppf files);
     * one opened on its own cannot.
     *
     * [serial] names the game's save states; a game without one has none.
     *
     * Returns null when the game ran and exited normally, or the reason it failed to boot.
     */
    fun runVm(gameUri: String, serial: String?): String? {
        check(initialized) { "EmulatorCore.initialize() has not completed" }
        val path = if (gameUri.isEmpty()) "" else files.pathFor(Uri.parse(gameUri)) ?: return "Could not open the game file"
        val saveState = bootSaveState?.absolutePath.orEmpty()
        bootSaveState = null
        runningSerial = serial
        _vmState.value = VmState.Starting
        // The core boots at the speed the settings give it.
        _fastForward.value = false
        return try {
            NativeCore.runSystem(path, saveState)
        } finally {
            bootSettings = null
            _vmState.value = VmState.Stopped
        }
    }

    fun pause() = NativeCore.pause()
    fun resume() = NativeCore.resume()

    /** Asks the VM to stop; [runVm] returns once it has. */
    fun shutdown() = NativeCore.shutdown()

    val hasActiveVm: Boolean get() = NativeCore.hasActiveSystem()

    /**
     * From the activity's onStop (true) and onStart (false). Nothing to do for this core: the
     * activity pauses the VM while hidden, and a pause stops the audio and the rumble.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setAppBackgrounded(backgrounded: Boolean) = Unit

    // ---- Save states ----

    /** The serial [runVm] was given, which names the running game's state files. */
    @Volatile
    private var runningSerial: String? = null

    /** The file of [slot] (-1 for the suspend state) of the game with [serial], named as the core names it. */
    fun saveStateFile(serial: String, slot: Int): File =
        File(saveStatesDir, if (slot < 0) "${serial}_resume.sav" else "${serial}_$slot.sav")

    /** Saves the running game to [slot]. Blocks until the state is written. */
    fun saveState(slot: Int): Boolean = saveStateTo(slot)

    /** Loads [slot] into the running game. Blocks until it is loaded. */
    fun loadState(slot: Int): Boolean {
        val file = runningSerial?.let { saveStateFile(it, slot) }?.takeIf { it.isFile } ?: return false
        return NativeCore.loadState(file.absolutePath).logError("Loading ${file.name}")
    }

    /**
     * Saves the suspend state: where the game stands, for [suspendStateFile] to find the next
     * time the game starts.
     */
    fun saveSuspendState(): Boolean = saveStateTo(-1)

    private fun saveStateTo(slot: Int): Boolean {
        val file = runningSerial?.let { saveStateFile(it, slot) } ?: return false
        saveStatesDir.mkdirs()
        return NativeCore.saveState(file.absolutePath).logError("Saving ${file.name}")
    }

    /** True for null, else logs [this] as why [what] failed. */
    private fun String?.logError(what: String): Boolean {
        if (this != null) Log.w(TAG, "$what failed: $this")
        return this == null
    }

    /** The suspend state [serial] left behind, if any. Blocking I/O. */
    fun suspendStateFile(serial: String): File? = saveStateFile(serial, -1).takeIf { it.isFile }

    /** Serials that have a suspend state to resume from. Blocking I/O. */
    fun resumableSerials(): Set<String> = saveStatesDir.list().orEmpty()
        .filter { it.endsWith(RESUME_SUFFIX) }
        .mapTo(HashSet()) { it.removeSuffix(RESUME_SUFFIX) }

    @Volatile
    private var bootSaveState: File? = null

    /** Makes the next [runVm] start from the save state at [file], or from the beginning for null. */
    fun setBootSaveState(file: File?) {
        bootSaveState = file
    }

    /** True while the memory card is being written; a save state is refused then. */
    val isMemcardBusy: Boolean get() = NativeCore.isSavingMemoryCards()

    /** What [slot] holds for the running game, or null when it is empty. Blocking I/O. */
    fun saveStateInfo(slot: Int): SaveStateInfo? {
        val file = runningSerial?.let { saveStateFile(it, slot) }?.takeIf { it.isFile } ?: return null
        return SaveStateInfo(slot, file.lastModified(), readSaveState(file)?.screenshot)
    }

    /** What the header of the state file [file] says, or null if it is not a save state. Blocking I/O. */
    fun readSaveState(file: File): SaveStateHeader? {
        val info = NativeCore.getSaveStateInfo(file.absolutePath) ?: return null
        return SaveStateHeader(serial = (info[0] as? String).orEmpty(), screenshot = info[1] as? ByteArray)
    }

    // ---- Rewind ----

    /**
     * The points rewind can go back to for the running game, newest first. [SaveStateInfo.slot] is
     * the place in the history (1 is the newest) and [SaveStateInfo.savedAt] is when it was saved,
     * counting only the time the game ran. Blocks while the core reads them.
     */
    fun rewindPoints(): List<SaveStateInfo> {
        val points = NativeCore.getRewindPoints()
        val ages = points[0] as LongArray
        @Suppress("UNCHECKED_CAST")
        val thumbnails = points[1] as Array<ByteArray?>
        val now = System.currentTimeMillis()
        return ages.indices.map { i -> SaveStateInfo(i + 1, now - ages[i], thumbnails[i]) }
    }

    /** Goes back to the rewind point [n] (see [rewindPoints]). The newer points are dropped. */
    fun rewindTo(n: Int): Boolean = NativeCore.loadRewindPoint(n - 1)

    // ---- Discs ----

    /** The discs of a playlist (.m3u) game: their [titles] and the [current] one's index. */
    class DiscPlaylist(val titles: List<String>, val current: Int)

    /** The running game's playlist, or null when it didn't start from a .m3u. Blocks while the core answers. */
    fun discPlaylist(): DiscPlaylist? {
        val info = NativeCore.getDiscPlaylist() ?: return null
        return DiscPlaylist(titles = info.drop(1), current = info[0].toIntOrNull() ?: 0)
    }

    /** Puts disc [index] of [discPlaylist] in the drive. */
    fun switchDisc(index: Int): Boolean = NativeCore.switchDisc(index)

    /** Puts the disc image at [uri] in the drive of the running game. Blocking I/O. */
    fun insertDisc(uri: Uri): Boolean {
        val path = files.pathFor(uri) ?: return false
        return NativeCore.insertDisc(path)
    }

    /** The discs that make up one game: the GameDB's [name] for the set and the [serials] of its discs in disc order. */
    class DiscSet(val name: String, val serials: List<String>)

    /**
     * The set [serial]'s disc belongs to, or null for a game on one disc or one the GameDB doesn't
     * know. Needs [initialize].
     */
    fun discSet(serial: String): DiscSet? {
        val info = NativeCore.getDiscSet(serial) ?: return null
        return DiscSet(name = info[0], serials = info.drop(1))
    }

    // ---- Screenshots ----

    /**
     * Captures what the running game shows to [png] (a path ending in .png). Returns straight
     * away; the core writes the file a moment later.
     */
    fun saveScreenshot(png: File) = NativeCore.saveScreenshot(png.absolutePath)

    val fps: Float get() = NativeCore.getFps()

    /** Frames the game has run (the core's own count). A load state sets it back. */
    val frameCount: Long get() = NativeCore.getFrameNumber()

    /** Why the custom driver set by [useVulkanDriver] did not open, once the VM is up; null if it did or none was set. */
    val customDriverFailure: String? get() = null

    /** The unsigned little-endian number of [size] (1, 2 or 4) bytes at [address], or null if it cannot be read. */
    fun readValue(address: Int, size: Int): Long? {
        val bytes = readMemory(address, size) ?: return null
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        return v
    }

    /**
     * [length] bytes of main RAM from [address], or null with no game or out of range. Blocks for up
     * to a frame, until the core is between frames.
     */
    fun readMemory(address: Int, length: Int): ByteArray? = NativeCore.readMemory(address, length)

    /** Writes [value] as [size] (1, 2 or 4) little-endian bytes at [address]. False with no game or out of range. */
    fun writeValue(address: Int, size: Int, value: Long): Boolean =
        size in setOf(1, 2, 4) && NativeCore.writeMemory(address, size, value.toInt())

    // ---- Surface (from SurfaceHolder.Callback) ----

    fun onSurfaceCreated() = Unit
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) =
        NativeCore.setSurface(surface, width, height)

    /** Returns once the core has stopped rendering to the surface. */
    fun onSurfaceDestroyed() = NativeCore.setSurface(null, 0, 0)
    fun setDisplayRefreshRate(hz: Float) = NativeCore.setDisplayRefreshRate(hz)

    @Volatile
    private var portraitRenderTop = true

    @Volatile
    private var portraitTopInset = 0

    /**
     * How far to shift a top-aligned portrait render down so it clears the display cutout, in
     * surface (buffer) pixels.
     */
    fun setPortraitRenderTopInset(pixels: Int) {
        portraitTopInset = pixels.coerceAtLeast(0)
        NativeCore.setDisplayLayout(portraitRenderTop, portraitTopInset)
    }

    /** Keeps the on-screen messages and the core's overlays clear of the display cutout. Surface pixels. */
    fun setOsdSafeAreaInsets(left: Int, top: Int, right: Int, bottom: Int) =
        NativeCore.setOsdSafeArea(left, top, right, bottom)

    // ---- Input ----

    /** Hears every press and stick move sent below, on the thread that sent it. For recording. */
    interface PadListener {
        fun onButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float)
        fun onStick(port: Int, stick: PadStick, x: Float, y: Float)
    }

    @Volatile
    var padListener: PadListener? = null
    // Call these directly from input handlers, not through a ViewModel, to keep latency low.

    /**
     * Presses or releases [button] on [port] (0 = player 1, 1 = player 2). [pressure] (0..1) is
     * how hard a pressed button is pushed; the Saturn pad does not sense it, so only [pressed]
     * reaches the game.
     */
    fun setPadButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float = 1f) {
        padListener?.onButton(port, button, pressed, pressure)
        NativeCore.setPadBind(port, button.code, if (pressed) 1f else 0f)
    }

    /**
     * Moves [stick] on [port] to ([x], [y]), each -1..1 with +x right and +y down. Shape the
     * input (deadzone) before calling; this sends it as is. The core does not take sticks until
     * the 3D Control Pad is connected, so for now this only reaches [padListener].
     */
    fun setPadStick(port: Int, stick: PadStick, x: Float, y: Float) {
        padListener?.onStick(port, stick, x, y)
        NativeCore.setPadStick(port, stick == PadStick.Right, x, y)
    }

    companion object {
        /**
         * Size of the main RAM the memory search covers. With the 8 MB RAM setting, [readMemory]
         * reaches further, but games made for the PS1 only use the first 2 MB.
         */
        const val MAIN_RAM_BYTES = 2 * 1024 * 1024

        private const val RESUME_SUFFIX = "_resume.sav"

        /** The save-state slots, as the core numbers them. */
        val SAVE_STATE_SLOTS: IntRange = 1..10

        private const val TAG = "EmulatorCore"
    }
}
