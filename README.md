# Sapphirine

An emulator frontend for Android, to start new emulators from. It is the app of
[Aegirine](https://github.com/kiraponz/Aegirine) (library, settings, touch controls, save states,
disc changes) with the PS1 core taken out: in its place is a stub core written in Kotlin, which
"boots" any file and draws a test picture with the pad state, so every screen can be tried without
a real core.

## Features

- Game library from a folder you pick, with favorites, search, play time and multi-disc games
- Global settings with per-game overrides
- Touch controls you can rearrange and resize, and physical controllers
- Save states, rewind, fast forward, memory cards, cheats, memory search, disc changes
- Backup and restore of settings and saves
- Optional: ask Google Gemini about the current screen

## Starting a new emulator from it

1. Clone this repository under the new emulator's name, and change `applicationId`,
   `rootProject.name` and `app_name`.
2. Put the core in `:emucore`: a native build in `emucore/build.gradle.kts`, and the stub's
   functions in `NativeCore.kt` made `external`. `EmulatorCore` stays the app's only way into the
   core, so the screens keep working.
3. Replace what is specific to the PS1: the pad's buttons (`PadButton`), the settings
   (`emucore/settings/`, `SettingsCatalog.kt`), the memory cards (`data/memcard/`), the BIOS check,
   the cheat format and the game file extensions (`LibraryRepository`).
4. List what the APK now contains in `OpenSourceComponents.kt`.

Fixes to the frontend can move between the emulators with `git cherry-pick`, since they share
this history.

## Building

Requires Android Studio (for its JDK).

```bash
./gradlew :app:assembleDebug
```

From the command line, point `JAVA_HOME` at Android Studio's JDK (on macOS,
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

## License

Sapphirine is free software under the [GNU General Public License v3.0](LICENSE).
