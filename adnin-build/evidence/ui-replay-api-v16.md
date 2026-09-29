# v16 UI, Replay identity and API policy

## Scope

The settings panel uses OS Segoe UI with logical-font fallback for Unicode,
antialiased cached glyphs, rounded controls, consistent spacing, vector icons,
time-based opening/closing and switch animations, interpolated navigation and
scrolling, and a single red close control. Rendering uses real nested scissor
clips. Input applies the same scale, animation offset and visible scroll as the
drawing. The fixed Overlay header cannot also activate a hidden row.

The window scales to fit the available GUI viewport. All seven pages retain
their existing settings and native ABI. Font resources are generated locally;
no commercial font is distributed. Unicode text uses a bounded 32-entry texture
cache. The raw GL scope restores driver attributes, matrix mode, active texture,
pixel-unpack configuration and parent clips. Minecraft FontRenderer is never
called within that raw scope, including the font-failure path.

## Replay identity

Tab display components, or the scoreboard team's prefix/profile/suffix when a
component is absent, supply the full recorded account. Valid formatting codes
inside names are removed without inserting spaces. Team markers and trailing
health are treated separately. Multiple conflicting account tokens are rejected.
No missing character is guessed through fuzzy name completion.

The reported XiaoShu_SKY2026 versus XiaoShu_SKY202 case is covered with color
changes, team suffixes, health suffixes, old truncated-name NICK cache entries,
alias/full-name cache reuse, native profile callbacks and the actual Replay to
Anticheat adapter/engine/alert path. Real Nick handling remains separate from
pending or failed account lookup. Actor UUIDs and GameProfiles are not rewritten.

## Credential policy

Two recovered stats branches implicitly selected a proxy when the Hypixel key
was blank, despite the visible proxy toggle being off. Both runtime profiles now
require explicit proxy selection. Direct lookup rejects empty or ASCII-whitespace
keys. The independent proxy ping lookup requires the same toggle.

Number's separate Hypixel key was compared with an already-updated shared shadow,
which could skip its setter when only the key changed. Four pinned operands now
compare against Number's actual private copy, so replacement and clearing reach
the existing setter only on change. A request-entry guard also rejects a worker's
previous copy after the current main key length becomes zero. No new lock is
introduced and existing original cleanup and setter paths remain intact.

The settings page reports the currently selected source, or a missing direct
key, from the same visible fields. It is not an authentication-success indicator.
Existing results can remain cached. A request already accepted before settings
synchronization can complete; no cancellation barrier is claimed.

All default API keys and the personal Bot URL are blank. The packaging gate
scans source, reconstructed native db data, EXE/DLL bytes and every compiled Java
class. It validates every Base64 helper chunk in bootstrap owners and ties scanned
entrypoint classes to the exact DLL byte ranges. Personal runtime configuration
files are excluded. Public service endpoints remain necessary program code.

## Verification boundary

The optional tests/test_ui_render.py renders the real Gui4 implementation in a
hidden LWJGL2 Pbuffer. It creates screenshots of every page at the top and bottom,
checks fractional viewport rendering, nested clipping, nondefault GL state
restoration and Unicode rendering/cache bounds. It does not run a game client.

No personal settings file, live API key, public chat message or WDR command was
used for this work. No v16 DLL has been loaded into an existing game process.
The final verification status and artifact hashes are in the root verification
document and release manifest. Automated fixtures do not establish live API
availability, cheat-detection accuracy or real gameplay frame times.

## Scaffold behavior retained

Scaffold requires block-in-hand, swing, pitch >= 70, 20 consecutive ticks with
max(abs(deltaX), abs(deltaZ)) >= 0.07, no sneak for 30 ticks, no absolute vertical
movement >= 0.1 for 20 ticks, and four loaded air blocks from feet minus 2 to 5.
Legit Scaffold correlates a fresh sneak edge with a new swing cycle within one
tick, while holding a block at pitch >= 70. Three matched cycles trigger a
decision; failed swing correlation or invalid conditions reset the counter.
Neither check directly confirms a successful placement packet. Replay seeks or
discontinuous sampling reset evidence; Replay never produces a WDR command.
