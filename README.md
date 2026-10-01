# AdninOverlay

A Windows x64 companion for Minecraft 1.8.9, focused on Hypixel player statistics, overlays, and client utilities. Adnin targets Lunar Client, Badlion Client, and Vanilla through a standalone executable with embedded runtime DLLs. No Forge installation is required.

Download the latest standalone [AdOverlay.exe](https://github.com/oAdr/AdninOverlay/releases/latest/download/AdOverlay.exe) from [Releases](https://github.com/oAdr/AdninOverlay/releases).

Adnin is based on the original **Frenchify v1.6** releases:

- `Frenchify Inject Badlion - Vanilla v1.6.zip`
- `Frenchify Inject Lunar v1.6.zip`

**Original author (Discord): `otigercqrnes`.** Credit for the original Frenchify / ChatReader foundation belongs to the original author.

## Features

- **Player overlays:** customizable Tab columns for Bed Wars, SkyWars, Duels, and Bed Wars Duels, plus chat overlays and a session statistics HUD.
- **Player information:** Seraph / Urchin tags and Skin, Number, and Bot denicker support for resolving nicked players where data is available.
- **Replay support:** player statistics, identity matching, and detection checks for eligible replay actors.
- **Client-side anticheat:** Autoblock, NoFall, NoSlow, Scaffold, and Legit Scaffold alerts, with optional reporting and party output.
- **Utilities and interface:** local block-placement sounds, quick-buy bindings, hitbox options, adjustable UI scale, and English, Simplified Chinese, and Traditional Chinese.

## What's New in v23

- Confirmed teammate identities remain cached through respawn, spectator changes, and temporary Tab disappearance within the current match.
- Light-gray (`§7`) players pause new statistics, Ping, denicker, anticheat, tag, and Output processing while preserving existing cached information. Light gray is no longer treated as a team color.
- Lunar Party queries complete after a validated `/locraw` response and recover from temporary failures. Waiting-room detection also handles font-proven invisible BMP symbols.
- Ping uses the Aurora v2 public endpoint without a Ping API key, independently of the Hypixel API Proxy setting. PingVar shows the range of returned average samples, not statistical variance.
- All clients share `%LOCALAPPDATA%/Adnin/config.properties`, including keys, feature switches, language, scale, and overlay columns. Existing settings migrate on first startup; changes save in the background.
- Includes the v22 input, Replay, Skin Denicker, Output, and local crash-diagnostic improvements.

Skin metadata identifies a texture owner and may not identify the player wearing a shared skin. Restart Minecraft before updating from an older payload.

## Usage

1. Start a supported **64-bit Minecraft 1.8.9** client and enter the game. Keep only one supported game instance open for automatic detection.
2. Run `AdOverlay.exe` and wait for the success message. Source builds name the same standalone executable `Adnin.exe`.
3. Enter `/config` in game chat to open the settings panel.
4. Add your own API keys under **Settings**. Direct statistics requests require a Hypixel key; **Vega Proxy** must be enabled explicitly to use the proxy. Configure other providers and the Bot URL as needed.
5. Choose your overlay columns, utilities, and alert settings. Party output is configured separately under **Chat Overlay**.

Anticheat, automatic reporting, and party output are off by default. Detection results are heuristic alerts. Use **1x playback** when evaluating replay detections; replay actors do not trigger automatic reports.

For multiple game instances, specify the target process:

```powershell
.\AdOverlay.exe --client lunar --pid 1234 --inject
.\AdOverlay.exe --help
```

Replace `1234` with the game process ID and use `lunar`, `badlion`, or `vanilla` as appropriate. Command-line runs default to a dry run unless `--inject` is supplied.

Keep `%LOCALAPPDATA%/Adnin/config.properties` and API keys private. Once the shared file exists, it takes precedence over legacy client settings; running clients do not live-reload external edits. Restart Minecraft before loading a different Adnin version. Compatibility is limited to the targeted runtimes; see the [v23 verification notes](adnin-build/verification.md) for testing coverage and remaining limitations.

## Build from Source

Requires Windows x64, LLVM-MinGW, CMake 3.21+, Ninja, NASM, JDK 17, and Python with `pefile==2024.8.26`.

The build also needs locally supplied, transformed Minecraft 1.8.9 / Lunar classes, their runtime libraries, and a Vanilla 1.8.9 client JAR. These are not bundled. The default tool paths are specific to the original development environment; replace all example paths below:

```powershell
cd adnin-build
& 'C:\tools\python\python.exe' -m pip install -r requirements.txt
$runtimeJars = @('C:\deps\lunar-1.8-local.jar') + @(
  Get-ChildItem 'C:\deps\libraries' -Filter '*.jar' -Recurse | ForEach-Object { $_.FullName }
)
.\Build.ps1 -BuildDirectory build `
  -Python 'C:\tools\python\python.exe' `
  -Toolchain 'C:\tools\llvm-mingw' `
  -CMake 'C:\tools\cmake\bin\cmake.exe' `
  -Ninja 'C:\tools\ninja\ninja.exe' `
  -Nasm 'C:\tools\nasm\nasm.exe' `
  -Jdk 'C:\tools\jdk-17' `
  -RuntimeClasspath ($runtimeJars -join ';') `
  -VanillaJar 'C:\deps\1.8.9.jar'
```

Output: `adnin-build/build/bin/Adnin.exe`. The build runs its verification suites. The codebase combines a C++20 injector, Java helpers, and recovered NASM native code.

## Credits and Licenses

### Contributors

- **oAdr** — project maintainer.
- **Codex (OpenAI)** — AI assistance with documentation, source review, and release preparation.
- **otigercqrnes (Discord)** — original Frenchify / ChatReader author.

### Third-Party Projects

| Project | Contribution | License |
| --- | --- | --- |
| [RavenBS-Plus-Plus](https://github.com/OlziYT/RavenBS-Plus-Plus) | Anticheat and PlayerData adaptations; reference commit `14b0a03` | [MIT](https://github.com/OlziYT/RavenBS-Plus-Plus/blob/14b0a03e8b3af4f109d7c05bc5d0b98d42470179/LICENSE) |
| [Mellow](https://github.com/Roxiun/Mellow) | Scaffold / Eagle detection and Skin Denicker adaptations; reference commit `17ef9b7` | [GPLv3](https://github.com/Roxiun/Mellow/blob/17ef9b7466754a33ee8c8ed87fa7ea717573d775/LICENSE) |
| [ClientSideSounds](https://github.com/letsgoawaydev/ClientSideSounds) by LetsGoAway, with original credit to ASDFCube | Behavior reference for independently implemented placement sounds | [MIT in the upstream repository](https://github.com/letsgoawaydev/ClientSideSounds/blob/main/LICENSE) |
| [pefile](https://github.com/erocarrera/pefile) | PE inspection and validation during builds | [MIT](https://github.com/erocarrera/pefile/blob/v2024.8.26/LICENSE) |
