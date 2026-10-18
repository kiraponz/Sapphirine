package com.kira3.ametrine.emucore.settings

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/**
 * Every emulator setting the app stores, global or per game. The core's settings are a chosen
 * part of Mednafen's (the ss module's and a few general ones); the rest keep Mednafen's
 * defaults, or are pinned in [CoreKeys].
 *
 * Stored as JSON (see [SettingsJson]), nested by group: `{"display": {"aspectRatio": "4:3"}, ...}`.
 * Choice values are the core's own names where it has them ("extram4", "weave"), so they stay
 * valid as options are added. Each field's comment names the setting it maps to; fields marked
 * "App side" never reach the core.
 */
@Serializable
data class EmulatorSettings(
    val console: ConsoleSettings = ConsoleSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val frameLimit: FrameLimitSettings = FrameLimitSettings(),
    val audio: AudioSettings = AudioSettings(),
    val osd: OsdSettings = OsdSettings(),
    val system: SystemSettings = SystemSettings(),
    val output: OutputSettings = OutputSettings(),
    val rewind: RewindSettings = RewindSettings(),
    val controls: ControlsSettings = ControlsSettings(),
    val gemini: GeminiSettings = GeminiSettings(),
    val memoryEditor: MemoryEditorSettings = MemoryEditorSettings(),
)

/**
 * The console: its region, its clock, the cart in its slot and the pad in its connectors. All
 * apply on the next boot.
 */
@Serializable
data class ConsoleSettings(
    /** ss.region_autodetect and ss.region_default: "auto" (from the disc), "jp", "na" or "eu". */
    val region: String = "auto",
    /** ss.cart: "auto" (what the game needs), "none", "backup", "extram1" or "extram4". */
    val cart: String = "auto",
    /** Which pad the two connectors take together: "standard" or "3d" (the 3D Control Pad). Reaches the core as Input/PadType. */
    val padType: String = "standard",
    /** ss.smpc.autortc: sets the console's clock to the phone's at boot. */
    val autoRtc: Boolean = true,
    /** ss.smpc.autortc.lang: the BIOS language, "english", "german", "french", "spanish", "italian" or "japanese". */
    val language: String = "english",
)

/** How the picture is made and fitted to the screen. */
@Serializable
data class DisplaySettings(
    /** The picture's shape: "4:3", "16:9" or "Stretch" (the whole screen). App side (android_host.cpp). */
    val aspectRatio: String = "4:3",
    /** video.deinterlacer: "weave", "bob", "bob_offset" or "blend", for the few interlaced screens. */
    val deinterlacer: String = "weave",
    /** ss.h_overscan: shows the picture's left and right edges, which a TV hid. Applies on the next boot. */
    val hOverscan: Boolean = true,
    /** ss.h_blend: blurs neighbouring pixels together, as a TV did for dithered transparency. Applies on the next boot. */
    val hBlend: Boolean = false,
    /** ss.slstart: the first line shown of an NTSC picture (0..239). Applies on the next boot. */
    val lineStart: Int = 0,
    /** ss.slend: the last line shown of an NTSC picture (0..239). Applies on the next boot. */
    val lineEnd: Int = 239,
    /** ss.slstartp: the first line shown of a PAL picture (-16..271). Applies on the next boot. */
    val lineStartPal: Int = 0,
    /** ss.slendp: the last line shown of a PAL picture (-16..271). Applies on the next boot. */
    val lineEndPal: Int = 255,
)

/** Fast forward, and the buttons over the game. */
@Serializable
data class FrameLimitSettings(
    /** Main/FastForwardSpeed: fast forward as a multiple of full speed (2..10), or 0 for as fast as possible. */
    val fastForwardSpeed: Int = 0,
    /** Show the fast-forward button over the game. App side. */
    val fastForwardButton: Boolean = true,
    /** Show a screenshot button over the game. Off by default. App side. */
    val screenshotButton: Boolean = false,
    /** Show a macro button over the game. Off by default. App side. */
    val macroButton: Boolean = false,
)

/** Audio output (AAudio). */
@Serializable
data class AudioSettings(
    /** Audio/OutputVolume: 0..200 percent. */
    val volume: Int = 100,
    /** Audio/FastForwardVolume: 0..200 percent. */
    val fastForwardVolume: Int = 100,
    /** Audio/OutputMuted. */
    val muted: Boolean = false,
    /** Audio/BufferMS: the sound kept ready ahead of the speaker. More is steadier, less is quicker. */
    val bufferMs: Int = 100,
    /** ss.scsp.resamp_quality: the quality of the conversion to the phone's rate (0..10). */
    val resampleQuality: Int = 4,
)

/** What the app shows over the game. App side. */
@Serializable
data class OsdSettings(
    /** The frame rate, in a corner. */
    val showFps: Boolean = false,
)

/** The per-game BIOS and the backup memory. Apply on the next boot. */
@Serializable
data class SystemSettings(
    /** BIOS file (in the BIOS folder) for this game, or "" to let the core pick one for the game's region. */
    val biosFilename: String = "",
    /** filesys.fname_sav: "PerGame" (a backup memory file for each game) or "Shared" (one for every game, as on the console). */
    val backupRam: String = "PerGame",
    /** The backup memory file (in the card folder) every game uses when [backupRam] is "Shared". */
    val backupRamFile: String = "shared.bkr",
)

/** The play screen. App side, apart from [customDriverId]. */
@Serializable
data class OutputSettings(
    /** Output surface size: 0 = screen, else n x 448 lines on the short side. */
    val hwScaler: Int = 0,
    /** "auto", "2560x1440", "1920x1080" or "1280x720". */
    val screenResOverride: String = "auto",
    /** Play screen orientation: 0 device setting, 1 landscape, 2 portrait, 3 auto-rotate. */
    val orientation: Int = 0,
    /** In portrait, align the picture to the top (true) instead of centering it. */
    val portraitRenderTop: Boolean = true,
    /** Installed custom Vulkan driver (e.g. Turnip) to load, or "" for the system driver. Applies on the next boot. */
    val customDriverId: String = "",
    /** Turnip's UBWC flag hint (FD_DEV_FEATURES), for glitches on Adreno 740. Applies on the next boot. */
    val turnipUbwcHint: Boolean = false,
)

/** The on-screen pad and physical controllers. App side. */
@Serializable
data class ControlsSettings(
    /** 0 = sticks drawn at a fixed place, 1 = sticks that appear where a finger touches an empty part of the screen. */
    val stickMode: Int = 0,
    /** How opaque the pad is, as a percent (20..100). */
    val padOpacity: Int = 100,
    /** Vibrate when a button is pressed. */
    val padHaptics: Boolean = true,
    /** Seconds without a touch after which the pad and the function buttons fade (1..30); above 30 they never do. */
    val padIdleSeconds: Int = 15,
    /** Stick dead zone of a physical controller, as a percent of the stick's travel. */
    val stickDeadzone: Int = 15,
    /** How far a physical controller's analog trigger must go to press its button, as a percent. */
    val triggerThreshold: Int = 8,
    /** Which key of a physical controller presses each pad button. */
    val buttonMap: ButtonMapSettings = ButtonMapSettings(),
)

/**
 * The Android key code (KeyEvent.KEYCODE_*) that presses each pad button; 0 leaves the button
 * without a key. The D-pad's HAT axis and the left stick work as the D-pad whatever is set here,
 * and the analog triggers press the buttons set to L2 and R2.
 *
 * The defaults put the Saturn pad on a modern controller as most Saturn ports do: A, B, C along
 * the bottom row (A, B, R1), X, Y, Z along the top (X, Y, L1), and L, R on the triggers.
 */
@Serializable
data class ButtonMapSettings(
    val up: Int = KeyEvent.KEYCODE_DPAD_UP,
    val down: Int = KeyEvent.KEYCODE_DPAD_DOWN,
    val left: Int = KeyEvent.KEYCODE_DPAD_LEFT,
    val right: Int = KeyEvent.KEYCODE_DPAD_RIGHT,
    val a: Int = KeyEvent.KEYCODE_BUTTON_A,
    val b: Int = KeyEvent.KEYCODE_BUTTON_B,
    val c: Int = KeyEvent.KEYCODE_BUTTON_R1,
    val x: Int = KeyEvent.KEYCODE_BUTTON_X,
    val y: Int = KeyEvent.KEYCODE_BUTTON_Y,
    val z: Int = KeyEvent.KEYCODE_BUTTON_L1,
    val l: Int = KeyEvent.KEYCODE_BUTTON_L2,
    val r: Int = KeyEvent.KEYCODE_BUTTON_R2,
    val start: Int = KeyEvent.KEYCODE_BUTTON_START,
)

/** Rewind: points the core keeps in memory as the game runs, for the player to go back to. Not in the core yet. */
@Serializable
data class RewindSettings(
    /** Main/RewindEnable. Off by default: each point stalls the game for a moment. */
    val enabled: Boolean = false,
    /** Main/RewindFrequency: seconds of play between points (1..60). */
    val intervalSeconds: Int = 10,
    /** Main/RewindSaveSlots: how many points to keep (1..20). */
    val keep: Int = 6,
)

/** Asking Gemini about the game screen. App side only: none of these reach the core. The API key is kept apart from settings. */
@Serializable
data class GeminiSettings(
    /** Show the AI button over the game. */
    val enabled: Boolean = false,
    /** Gemini model name, as the API takes it. */
    val model: String = "gemini-2.5-flash",
    /** Sent as the system instruction with every question. Empty sends none. */
    val systemPrompt: String = "",
    /** Show the saved memos over the game. */
    val memosVisible: Boolean = true,
)

/** Writing to the game's memory from the memory search. App side only: none of these reach the core. */
@Serializable
data class MemoryEditorSettings(
    /** Let the memory search change the values it lists. */
    val enabled: Boolean = false,
)

