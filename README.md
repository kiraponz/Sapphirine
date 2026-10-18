# Sapphirine

A Sega Saturn emulator for Android. The core is [Mednafen](https://mednafen.github.io)'s Saturn
module, built into the app from [a fork](https://github.com/kiraponz/sapphirine-core) that is kept
as a git submodule. The app is the frontend of [Aegirine](https://github.com/kiraponz/Aegirine)
(library, settings, touch controls, save states, disc changes) with the Saturn's own parts.

Sapphirine does not include any BIOS or games. Use the BIOS and the discs of your own Saturn.

## Features

- Games from CHD, CUE/BIN and M3U (multi-disc) images, read from a folder you pick
- Standard Control Pad and 3D Control Pad, on screen or with a physical controller
- Backup memory and expansion carts (including the KoF '95 and Ultraman ROM carts)
- Save states, rewind, fast forward, cheats (Action Replay), memory search, screenshots
- Global settings with per-game overrides
- Backup and restore of settings and saves
- Optional: ask Google Gemini about the current screen

## Building

Requires Android Studio (for its JDK) and the Android NDK. Clone with the core:

```bash
git clone --recurse-submodules https://github.com/kiraponz/Sapphirine.git
```

Or, in an existing clone, `git submodule update --init`. Then:

```bash
./gradlew :app:packageDebug
```

From the command line, point `JAVA_HOME` at Android Studio's JDK (on macOS,
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`). If the native code does not seem
to rebuild, add `--rerun`.

## License

Sapphirine is free software under the [GNU General Public License v3.0](LICENSE). The core is
Mednafen, under the GNU General Public License v2 or later; see
[sapphirine-core](https://github.com/kiraponz/sapphirine-core) for its changes.
