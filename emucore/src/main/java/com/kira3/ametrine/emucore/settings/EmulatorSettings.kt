package com.kira3.ametrine.emucore.settings

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/**
 * Every emulator setting the app stores, global or per game. The core's settings are a
 * chosen part of aegirine-core's INI keys (docs/CORE_SETTINGS.md); the rest of the core's keys
 * keep their defaults, or are pinned in [CoreKeys].
 *
 * Stored as JSON (see [SettingsJson]), nested by group: `{"gpu": {"resolutionScale": 2}, ...}`.
 * Choice values are the core's own names ("Recompiler", "Nearest"), so they stay valid as
 * options are added. Each field's comment names the core key it maps to; fields marked "App
 * side" never reach the core.
 */
@Serializable
data class EmulatorSettings(
    val console: ConsoleSettings = ConsoleSettings(),
    val cpu: CpuSettings = CpuSettings(),
    val gpu: GpuSettings = GpuSettings(),
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

/** The console itself and its CD-ROM drive. */
@Serializable
data class ConsoleSettings(
    /** Console/Region: "Auto" (from the disc), "NTSC-J", "NTSC-U" or "PAL". Applies on the next boot. */
    val region: String = "Auto",
    /** Console/Enable8MBRAM: the 8 MB of the development units. Few games use it. Applies on the next boot. */
    val enable8MbRam: Boolean = false,
    /** BIOS/PatchFastBoot: skips the BIOS intro. Applies on the next boot. */
    val fastBoot: Boolean = true,
    /** CDROM/ReadSpeedup: 1 is the console's speed, 2..10 times faster, 0 as fast as possible. */
    val cdromReadSpeedup: Int = 1,
    /** CDROM/SeekSpeedup: as [cdromReadSpeedup]. */
    val cdromSeekSpeedup: Int = 1,
    /** CDROM/LoadImageToRAM: reads the whole disc into memory at boot. Applies on the next boot. */
    val loadImageToRam: Boolean = false,
)

/** The R3000A and its recompiler. */
@Serializable
data class CpuSettings(
    /** CPU/ExecutionMode: "Interpreter", "CachedInterpreter", "Recompiler" or "NewRec". */
    val executionMode: String = "Recompiler",
    /** CPU/OverclockEnable, OverclockNumerator/Denominator: the clock as a percent of the console's (100 = off). */
    val overclockPercent: Int = 100,
    /** CPU/RecompilerICache: emulates the instruction cache. Slower; few games need it. */
    val recompilerICache: Boolean = false,
    /** CPU/FastmemMode: "Disabled", "MMap" or "LUT". */
    val fastmemMode: String = "MMap",
)

/** The GPU renderer and its enhancements. */
@Serializable
data class GpuSettings(
    /** GPU/Renderer: "auto", "opengl", "vulkan" or "software". Applies on the next boot. */
    val renderer: String = "auto",
    /** GPU/ResolutionScale: 1 native (1x), up to 8; 0 follows the window size. */
    val resolutionScale: Int = 1,
    /** GPU/Multisamples: 1 (off), 2, 4 or 8. */
    val multisamples: Int = 1,
    /** GPU/PerSampleShading: supersampling instead of multisampling. */
    val perSampleShading: Boolean = false,
    /** GPU/TrueColor: 24-bit color instead of the console's 15-bit with dithering. */
    val trueColor: Boolean = true,
    /** GPU/ScaledDithering: dithering follows the resolution scale. */
    val scaledDithering: Boolean = true,
    /** GPU/Debanding. */
    val debanding: Boolean = false,
    /** GPU/TextureFilter: "Nearest", "Bilinear", "BilinearBinAlpha", "JINC2", "JINC2BinAlpha", "xBR" or "xBRBinAlpha". */
    val textureFilter: String = "Nearest",
    /** GPU/LineDetectMode: "Disabled", "Quads", "BasicTriangles" or "AggressiveTriangles". */
    val lineDetectMode: String = "Disabled",
    /** GPU/DownsampleMode: "Disabled", "Box" or "Adaptive". */
    val downsampleMode: String = "Disabled",
    /** GPU/DisableInterlacing: draws interlaced frames whole. */
    val disableInterlacing: Boolean = true,
    /** GPU/ForceNTSCTimings: runs PAL games at 60 Hz. */
    val forceNtscTimings: Boolean = false,
    /** GPU/WidescreenHack: widens 3D scenes. */
    val widescreenHack: Boolean = false,
    /** GPU/ChromaSmoothing24Bit: smooths 24-bit movies. */
    val chromaSmoothing24Bit: Boolean = false,
    /** GPU/AccurateBlending. */
    val accurateBlending: Boolean = false,
    /** GPU/PGXPEnable: precise geometry, which removes the wobble of 3D. */
    val pgxpEnable: Boolean = false,
    /** GPU/PGXPCulling. */
    val pgxpCulling: Boolean = true,
    /** GPU/PGXPTextureCorrection. */
    val pgxpTextureCorrection: Boolean = true,
    /** GPU/PGXPColorCorrection. */
    val pgxpColorCorrection: Boolean = false,
    /** GPU/PGXPVertexCache. */
    val pgxpVertexCache: Boolean = false,
    /** GPU/PGXPCPU: PGXP for every CPU instruction. Slow; fixes some games. */
    val pgxpCpu: Boolean = false,
    /** GPU/PGXPPreserveProjFP. */
    val pgxpPreserveProjFp: Boolean = false,
    /** GPU/PGXPDepthBuffer. */
    val pgxpDepthBuffer: Boolean = false,
    /** GPU/UseThread: the software renderer on its own thread. */
    val useThread: Boolean = true,
    /** GPU/ThreadedPresentation (Vulkan). */
    val threadedPresentation: Boolean = true,
)

/** How the picture is fitted to the screen. */
@Serializable
data class DisplaySettings(
    /** Display/AspectRatio: "Auto (Game Native)", "Auto (Match Window)", "Custom", "4:3", "16:9", "19:9", "20:9" or "PAR 1:1". */
    val aspectRatio: String = "Auto (Game Native)",
    /** Display/CustomAspectRatioNumerator, used with "Custom". */
    val customAspectNumerator: Int = 16,
    /** Display/CustomAspectRatioDenominator. */
    val customAspectDenominator: Int = 9,
    /** Display/CropMode: "None", "Overscan" or "Borders". */
    val cropMode: String = "Overscan",
    /** Display/DeinterlacingMode: "Disabled", "Weave", "Blend" or "Adaptive". */
    val deinterlacingMode: String = "Adaptive",
    /** Display/Scaling: "Nearest", "NearestInteger", "BilinearSmooth", "BilinearSharp" or "BilinearInteger". */
    val scaling: String = "BilinearSmooth",
    /** Display/Force4_3For24Bit: movies at 4:3 whatever the aspect ratio. */
    val force43For24Bit: Boolean = false,
    /** Display/VSync. */
    val vsync: Boolean = false,
    /** Display/OptimalFramePacing. */
    val optimalFramePacing: Boolean = false,
    /** Main/SyncToHostRefreshRate: runs at the screen's rate when it is close to the game's. */
    val syncToHostRefreshRate: Boolean = false,
    /** Display/SkipPresentingDuplicateFrames. */
    val skipDuplicateFrames: Boolean = false,
)

/** Speed, and the buttons over the game. */
@Serializable
data class FrameLimitSettings(
    /** Main/EmulationSpeed is 0 (unlimited) when off. */
    val frameLimitEnable: Boolean = true,
    /** Main/EmulationSpeed as a percent of full speed (10..1000). */
    val nominalSpeedPercent: Int = 100,
    /** Main/FastForwardSpeed as a multiple of full speed (2..10), or 0 for as fast as possible. */
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
    /** Audio/StretchMode: "Off", "Resample" or "TimeStretch". */
    val stretchMode: String = "TimeStretch",
    /** Audio/BufferMS. */
    val bufferMs: Int = 100,
    /** Audio/OutputLatencyMS. */
    val outputLatencyMs: Int = 20,
    /** Audio/OutputLatencyMinimal: the smallest buffer the device allows, ignoring [outputLatencyMs]. */
    val outputLatencyMinimal: Boolean = false,
    /** CDROM/MuteCDAudio: silences CD audio tracks and XA. */
    val muteCdAudio: Boolean = false,
)

/** What the core draws over the game (Display/Show*). */
@Serializable
data class OsdSettings(
    /** Display/ShowFPS. */
    val showFps: Boolean = false,
    /** Display/ShowSpeed. */
    val showSpeed: Boolean = false,
    /** Display/ShowResolution. */
    val showResolution: Boolean = false,
    /** Display/ShowCPU. */
    val showCpu: Boolean = false,
    /** Display/ShowGPU. */
    val showGpu: Boolean = false,
    /** Display/ShowFrameTimes. */
    val showFrameTimes: Boolean = false,
    /** Display/ShowGPUStatistics. */
    val showGpuStats: Boolean = false,
    /** Display/ShowInputs. */
    val showInputs: Boolean = false,
    /** Display/ShowEnhancements: lists the enhancements in use. */
    val showEnhancements: Boolean = false,
    /** Display/ShowOSDMessages: notifications such as "state saved". */
    val showMessages: Boolean = true,
    /** Display/ShowStatusIndicators: icons for pause, fast forward and the like. */
    val showStatusIndicators: Boolean = true,
    /** Display/OSDScale: 25..500 percent. */
    val scale: Int = 100,
)

/** The memory cards and the per-game BIOS. */
@Serializable
data class SystemSettings(
    /** BIOS file (in the BIOS folder) for this game, or "" to let the core pick one for the game's region. Applies on the next boot. */
    val biosFilename: String = "",
    /** MemoryCards/Card1Type: "None", "Shared", "PerGame", "PerGameTitle", "PerGameFileTitle" or "NonPersistent". */
    val memoryCard1Type: String = "PerGameTitle",
    /** MemoryCards/Card1Path: the card (in the card folder) slot 1 holds when it is "Shared". */
    val memoryCard1File: String = "shared_card_1.mcd",
    /** MemoryCards/Card2Type. */
    val memoryCard2Type: String = "None",
    /** MemoryCards/Card2Path. */
    val memoryCard2File: String = "shared_card_2.mcd",
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

/** The controller in port 1 ([padType] and the two after it), and the on-screen pad (app side). */
@Serializable
data class ControlsSettings(
    /** Pad1/Type: "AnalogController" (DualShock) or "DigitalController". */
    val padType: String = "AnalogController",
    /** Pad1/ForceAnalogOnReset: start in analog mode. The GameDB turns it off for games that need digital. */
    val forceAnalogOnReset: Boolean = true,
    /** Pad1/AnalogDPadInDigitalMode: the left stick works as the D-pad in digital mode. */
    val analogDpadInDigitalMode: Boolean = true,
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

/** Rewind: points the core keeps in memory as the game runs, for the player to go back to. */
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

