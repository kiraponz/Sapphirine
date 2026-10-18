package com.kira3.ametrine.emucore

import android.util.Log
import android.view.Surface

/**
 * JNI boundary to libemucore.so (emucore/src/main/cpp/jni.cpp), the Sega Saturn core (Mednafen
 * 1.32.1's "ss" module). Native code finds this class by name to call the callbacks at the
 * bottom, so its name and members must not change (consumer-rules.pro keeps them).
 *
 * The methods this step of the port does not have yet (the 3D Control Pad's stick, cheat files and
 * the cheat database, the disc-set GameDB) still answer with their "not available" values, so the
 * frontend screens keep working until they are wired up.
 *
 * Use [EmulatorCore] instead of calling this directly.
 */
internal object NativeCore {
    private const val TAG = "NativeCore"

    /** Null when the core library loaded; otherwise why it did not. */
    @JvmStatic
    val loadError: String? = try {
        System.loadLibrary("emucore")
        null
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load emucore", e)
        e.message ?: "Failed to load the emulator core"
    }

    /** Receives the core's callbacks. Set by EmulatorCore. */
    @Volatile
    @JvmStatic
    var listener: Listener? = null

    /** Opens the core's /saf/ paths. Set by EmulatorCore before [initialize]. */
    @Volatile
    @JvmStatic
    var files: SafFiles? = null

    interface Listener {
        fun onVmPausedChanged(paused: Boolean)
        fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int)
    }

    // ---- Setup ----

    /** Once per process. Null on success, or why the core cannot run. */
    @JvmStatic external fun initialize(dataDir: String): String?
    @JvmStatic external fun getBuildVersion(): String

    // ---- Settings ----

    /** Writes one value to the base settings layer. A running VM applies it on [commitSettings]. */
    @JvmStatic external fun setSetting(section: String, key: String, value: String)
    @JvmStatic external fun commitSettings()

    // ---- Surface ----

    /** The surface to render to, or null for none. Blocks until the core has let go of the previous one. */
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int)

    @JvmStatic external fun setDisplayRefreshRate(hz: Float)

    /**
     * Where the picture sits: at the top of a portrait surface when [portraitTop], [topMargin]
     * surface pixels down (clear of the display cutout), else in the middle.
     */
    @JvmStatic external fun setDisplayLayout(portraitTop: Boolean, topMargin: Int)

    /** The edges of the surface the on-screen messages and overlays keep clear of, in surface pixels. */
    @JvmStatic external fun setOsdSafeArea(left: Int, top: Int, right: Int, bottom: Int)

    // ---- VM lifecycle ----

    /**
     * Boots [path] (empty for the BIOS alone) and runs it until the VM shuts down, from the save
     * state at [saveState] unless it is empty. Blocks the calling thread. Null when it booted, or
     * why it did not.
     */
    @JvmStatic external fun runSystem(path: String, saveState: String): String?
    @JvmStatic external fun hasActiveSystem(): Boolean
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()

    @JvmStatic external fun getFps(): Float
    @JvmStatic external fun getFrameNumber(): Long

    // ---- Input and speed ----

    /** Presses ([value] > 0.5) or releases the button [index] (a [PadButton.code]) of the pad in [port] (0 or 1). */
    @JvmStatic external fun setPadBind(port: Int, index: Int, value: Float)

    /** Runs as fast as it can while [enabled], at the fast-forward volume. */
    @JvmStatic external fun setFastForward(enabled: Boolean)

    // ---- Discs ----

    /**
     * The product number in the Saturn disc header of the image at [path] (e.g. "T-4507G"), or null
     * when it is no Saturn disc or cannot be read. Blocking I/O; needs [initialize] for /saf/ paths.
     */
    @JvmStatic external fun getGameSerial(path: String): String?

    /**
     * The index of the disc in the drive (as a string), then the titles of the discs of the loaded
     * playlist (.m3u); null when the loaded image is not a playlist.
     */
    @JvmStatic external fun getDiscPlaylist(): Array<String>?

    /**
     * Puts disc [index] of the loaded game in the drive: the tray opens at the next frame and closes
     * on the disc about a second of play later. False for an index out of range.
     */
    @JvmStatic external fun switchDisc(index: Int): Boolean

    /** Puts the disc image at [path] in the drive in place of the disc there, the same way. Blocking I/O. */
    @JvmStatic external fun insertDisc(path: String): Boolean

    // ---- States and screenshots ----

    /** Saves the running game to [path]. Blocks. Null when it did, or why not. */
    @JvmStatic external fun saveState(path: String): String?

    /** Loads the state at [path] into the running game. Blocks. Null when it did, or why not. */
    @JvmStatic external fun loadState(path: String): String?

    /** True while a memory card is waiting to be written, which refuses a save state. */
    @JvmStatic external fun isSavingMemoryCards(): Boolean

    /** [serial, PNG screenshot or null] from the header of the state at [path]; null if it is not a state. */
    @JvmStatic external fun getSaveStateInfo(path: String): Array<Any?>?

    /** [ages in ms (LongArray), PNG thumbnails (Array<ByteArray?>)] of the rewind points, newest first. */
    @JvmStatic external fun getRewindPoints(): Array<Any>

    /** Goes back to the rewind point [index] (in [getRewindPoints]). Blocks. */
    @JvmStatic external fun loadRewindPoint(index: Int): Boolean

    /** Writes what is on screen to [path] as a PNG, on a thread of the core's own. */
    @JvmStatic external fun saveScreenshot(path: String)

    // ---- Not available yet: these keep the frontend compiling until their task comes up ----

    /** Moves the left or [right] stick of the pad in [port] to ([x], [y]), each -1..1 with +y down. Not used until the 3D Control Pad. */
    @JvmStatic fun setPadStick(port: Int, right: Boolean, x: Float, y: Float) = Unit

    /** Alternating names and code lines of the cheats in a cheat file's [text]; null if it has none. */
    @JvmStatic fun parseCheats(text: String): Array<String>? = null

    /** Alternating names and code lines of the cheat database's cheats for [serial]; null if it has none. Needs [initialize]. */
    @JvmStatic fun getDatabaseCheats(serial: String): Array<String>? = null

    /** The disc set's name, then the serials of its discs in disc order, for [serial]'s game in the GameDB; null when it lists no set. */
    @JvmStatic fun getDiscSet(serial: String): Array<String>? = null

    /** The GameDB's title for [serial], or null. */
    @JvmStatic fun getGameDbTitle(serial: String): String? = null

    // ---- Cheats ----

    /** The cheats to run in every game from now on: [names] with their code lines in [codes]. Lines the core doesn't take are left out. */
    @JvmStatic external fun setCheats(names: Array<String>, codes: Array<String>)

    // ---- Memory search ----

    /**
     * [length] bytes of main RAM from [address] (an offset into [EmulatorCore.MAIN_RAM_BYTES], as
     * the memory is laid out for the app: the High work RAM first, then the Low), big-endian. Null
     * with no game or when it does not fit. Blocks until the core is between frames.
     */
    @JvmStatic external fun readMemory(address: Int, length: Int): ByteArray?

    /**
     * Writes [value] as [size] (1, 2 or 4) big-endian bytes at [address] of main RAM (an offset, see
     * [readMemory]). False with no game, out of range, or when [size] is none of those (or a 2/4-byte
     * write stands at an odd address). Blocks until the core is between frames.
     */
    @JvmStatic external fun writeMemory(address: Int, size: Int, value: Int): Boolean

    /**
     * "<region>\t<description>" for a Saturn BIOS image, or null if it is none. The region is
     * 1 Japan, 2 North America and Europe, or -1 for an image the core does not know; the
     * description is the image's usual file name ("sega_101.bin"). Takes ownership of [fd].
     */
    @JvmStatic external fun getBiosInfoFromFd(fd: Int): String?

    // ---- Called from native ----
    // These run on core threads; nothing may throw out of them.

    @JvmStatic
    fun onVmPausedChanged(paused: Boolean) {
        runCatching { listener?.onVmPausedChanged(paused) }
            .onFailure { Log.e(TAG, "onVmPausedChanged", it) }
    }

    @JvmStatic
    fun safOpen(path: String): Int = runCatching { files?.open(path) }
        .onFailure { Log.e(TAG, "safOpen", it) }.getOrNull() ?: -1

    @JvmStatic
    fun safStat(path: String): LongArray? = runCatching { files?.stat(path) }
        .onFailure { Log.e(TAG, "safStat", it) }.getOrNull()

    @JvmStatic
    fun safList(path: String): Array<String>? = runCatching { files?.list(path) }
        .onFailure { Log.e(TAG, "safList", it) }.getOrNull()

    /** The game set [pad]'s motors (0..255 each); zeros when it stops them or the VM pauses. */
    @JvmStatic
    fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
        runCatching { listener?.onPadRumble(pad, largeMotor, smallMotor) }
            .onFailure { Log.e(TAG, "onPadRumble", it) }
    }
}
