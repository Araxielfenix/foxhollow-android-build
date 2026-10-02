# Foxhollow

Foxhollow is a fully native port of **Star Fox Adventures** for modern platforms, built on the
[SFA decompilation](https://github.com/zcanann/SFA-Decomp) and
[Aurora](https://github.com/encounter/aurora).

This port attempts to stay as true to the original game as possible (bugs included). The port is built using Aurora
which gives us support for Mac, Windows, Linux, iOS and Android. Foxhollow has been tested and is fully playable for
Mac, Windows and Linux. There are currently no plans to release for iOS or Android.

You will need a genuine, legally obtained copy of Star Fox Adventures to run Foxhollow (any copy is fine). Foxhollow
does not distribute any original game assets.

## Android

### Installation

1. Download the latest APK from [Releases](https://github.com/Araxielfenix/foxhollow-android-build/releases)
2. On Android 14+ (MIUI/HyperOS), enable "Install via USB" in Developer Options
3. Install via ADB:
   ```bash
   adb push foxhollow-android-<build>.apk /data/local/tmp/fh.apk
   adb shell pm install -r /data/local/tmp/fh.apk
   ```

### Game Disc (ISO)

**The game ISO is NOT included.** You must provide your own legally obtained copy of Star Fox Adventures.

On first launch, the app will prompt you to select the ISO file using the system file picker. The ISO is copied to the app's external files directory:
```
/sdcard/Android/data/dev.encounter.foxhollow/files/Star Fox Adventures.iso
```

On subsequent launches, the game starts instantly without prompting.

### Mods

Mods are loaded from `/sdcard/foxhollow_mods/` on the device.

#### Installing Mods

1. Create the mods directory on your device:
   ```bash
   adb shell mkdir -p /sdcard/foxhollow_mods
   ```

2. Push mod folders to the device:
   ```bash
   # Single mod
   adb push /path/to/mod_folder /sdcard/foxhollow_mods/
   
   # Or all mods at once
   adb push /path/to/mods/* /sdcard/foxhollow_mods/
   ```

3. Launch the app with the mods path:
   ```bash
   adb shell am start -n dev.encounter.foxhollow/.FoxhollowActivity --es mod_path /sdcard/foxhollow_mods
   ```

#### Mod Format

Each mod must be a folder containing a `mod.json` manifest:

```json
{
  "id": "com.example.mymod",
  "name": "My Mod Name",
  "version": "1.0.0",
  "author": "Author Name",
  "description": "Description of what this mod does",
  "enabled": true
}
```

#### Supported Mod Types

| Type | Format | Notes |
|------|--------|-------|
| Texture replacements | `.dds` files | Replaces game textures by filename match |
| Audio replacements | `.wav`, `.ogg` | Replaces game audio |
| Code patches | Native lib (`.so`) | **Must be compiled for `arm64-v8a`** |

**Important:** Desktop mod binaries (`.dll`, `.so` for x86_64, macOS `.dylib`) **will not work** on Android. Mods must provide `arm64-v8a` native libraries.

#### Mod Structure Example

```
com.example.mymod/
├── mod.json
├── textures/
│   └── SomeTexture.dds
├── audio/
│   └── SomeSound.wav
└── lib/
    └── arm64-v8a/
        └── libmymod.so
```

#### Loading Mods

Mods can be loaded in three ways:

1. **Auto-load** (recommended): Place mods in `/sdcard/foxhollow_mods/` and launch with:
   ```bash
   adb shell am start -n dev.encounter.foxhollow/.FoxhollowActivity --es mod_path /sdcard/foxhollow_mods
   ```

2. **Intent**: Open a mod folder from a file manager with "Open with Foxhollow"

3. **Settings menu** (future): Will add a "Load Mods" button in the in-game settings

#### Known Mods

- **`com.thatbran.sfa-hd-textures`** - HD texture pack (~2 GB, DDS textures) - Works on Android
- **`dev.foxhollow.reflections`** - Reflections mod - **Desktop only** (no arm64 build)

## How to run (Desktop)

If you're looking to play Foxhollow, please go to https://foxhollow.dev and download the official launcher to play. The
launcher keeps your game up to date, allows you to manage your saves and also offers a mod library. The instructions
below are for people who are improving contributing to the port or building mods.
