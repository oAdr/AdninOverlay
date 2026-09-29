# Replay overlay and lifecycle review for v12

This is source, binary-structure and isolated fixture evidence. It does not
establish live Lunar, Badlion, server Replay, or ReplayMod behavior.

## Sidebar observation

`AdninReplay.tick(Minecraft)` reads the current world's actual scoreboard.
It selects the local player's team-color sidebar slot when present, otherwise
display slot 1. It checks the displayed title and the final 15 visible sorted
score rows, excluding null names and names beginning with `#`. Each row uses
the actual team prefix, score name and suffix. Valid Minecraft formatting codes
are removed and `replay` is matched case-insensitively using `Locale.ROOT`.

The detector publishes one volatile boolean after each observation, avoiding a
temporary false value during scanning. A missing client/world/player, missing
sidebar, failure, module stop, or subsequent ordinary sidebar clears the state.
It performs no HTTP, chat, packet transmission or native game-state mutation.
The observed state is exposed to the native predicates through
`AdninGui4.nativeReplayMode()I`.

The offline `AdninReplayTest` passed 34 checks using actual local scoreboard
classes. Its team fixtures populate the real team map directly to avoid Lunar's
live nametag-invalidation callback. Coverage includes title/row detection,
formatting within a word, team-color display-slot precedence, the 15-row window,
hidden entries, prefix/name/suffix composition, absent boards and explicit stop.
The test does not instantiate Minecraft or call a game server.

## Four local overlay predicates in each native profile

| Purpose | Lunar RVA | Compatibility RVA | JNIEnv source |
| --- | --- | --- | --- |
| `AdninTabOverlayNative.enabled` value | `0x19f49` | `0x1a111` | RDI |
| 300-ms overlay model refresh eligibility | `0x1a5e8` | `0x1a7b0` | RDI |
| JNI Tab renderer eligibility | `0x201be` | `0x205af` | RBX |
| Existing no-current-screen fallback renderer | `0x1d308` | `0x1d6b9` | caller RBP+0x28 |

Each replacement computes `original eligibility || observed Replay`, changing
only the existing predicate's ZF result. The master overlay switch, original
conditional jumps, refresh interval, drawing functions and model builder stay
in place. The existing eligibility globals (`0x1a63bd` and `0x17e95d`) are read
only. No global `Ingame` string or mode flag is forced. Consequently the patch
does not create a native match-start transition for Urchin or rewrite the
conditions of AutoGL, ordinary chat handling or the party Output queue.

`native_replay.py` verifies the exact predicate bytes, nearby caller context and
continuation before replacing each CMP with a rel32 CALL and padding. Original
text lineage is still checked first. The three shared gate implementations save
all volatile integer registers and XMM0–5, preserve nonvolatile registers and
stack position, and use a Windows RBP frame with genuine unwind metadata. A
pending JNI exception is retained; an exception raised by the optional callback
is cleared and fails closed. Missing classes/methods and non-1 callback values
also fail closed. An already-true native predicate avoids Java entirely.

`replay_checks.py` executes only the newly assembled bridge in private mock
memory with fake JNI callbacks. For each runtime profile it exercises all three
entry bridges across 12 combinations of native state, Replay state, missing
class/method, invalid return values and pre-existing/new exceptions (36 cases).
It compares all 16 integer registers, the stack and XMM0–5 before and after each
call, checks ZF and unchanged native eligibility, and verifies the correct
JNIEnv source. Windows `RtlVirtualUnwind` checks the complete frame and each
epilogue instruction boundary. The original DLL is never loaded by these tests.

## Row and mode boundaries retained intentionally

The main model builder's current-Tab row path is not inside the secondary
`Ingame`-only cache gate. The outer model refresh (`0x8f4b0` Lunar) checks valid
JNIEnv/client and an initialized overlay context, rebuilds the model through
`0x83fd0`, and marks it ready. The renderer (`0x8be70`) can rebuild an absent
model and still requires a current OpenGL context.

The separate comparisons at Lunar `0x88098` and compatibility `0x8998c` remain
unchanged. Their secondary
row source is **not a current-world Replay actor roster**: `0x910b0` records
names/colors from prequeue chat into a global timestamped cache, and `0x91e90`
returns cached names absent from the current Tab only while their age is at most
30 seconds. Entries aged 30–60 seconds are retained but not emitted, and entries
over 60 seconds are removed. The equivalent compatibility writer/reader are
`0x93230` and `0x94010`. This cache has no world/entity/UUID identity gate;
its existing match/chat resets are not a general world-identity guarantee.
Allowing Replay through that branch could therefore display previous-match
chat identities. It has not been widened.

Replay overlay rows consequently follow the existing current-Tab pipeline. A
Replay environment whose actors are absent from Tab is not established to show
those actors by this patch. Displaying them would require a separate validated
current-world roster adaptation, rather than relabeling the old chat cache.
The existing native game-mode detector is likewise retained. An unrecognized
title returns no new mode, so the active mode may retain the previous value;
Java's existing unknown-mode column normalization falls back to Bedwars.
This work does not infer the real game mode from a generic Replay label.

## Fixed-phase tick installation and unload safety

The separately reviewed `native_tick_hook.py` patch changes only the disabled
Session Stats branch to run the existing HUD installation/maintenance block.
It still skips the native Session Stats update when that setting is disabled.
Both builders report this as `gameTickHook`; its original installer code is
preserved. This does **not** claim that the old native unload restores the HUD.

The Lunar initializer's End-key call at `0x150da` now invokes a wrapper around
the original `GetAsyncKeyState` IAT (`0x12f628`). Only a pressed key triggers
`AdninGui4.nativeStopGameModules()I` using the initializer's attached-thread
JNIEnv at caller RSP+0x30. The wrapper returns the original key value only after
an exact acknowledgement of 1; otherwise it returns zero and keeps the original
polling loop before cleanup. The existing cleanup instructions are unchanged.
The Java acknowledgement must follow completion of the module lifecycle barrier.
Compatibility retains its existing scheduled-pump stop barrier, which also
stops the shared game modules.

The Lunar mock suite covers 13 key/acknowledgement/failure combinations, including
both End-key high-bit states, absent class/env/method, pre-existing exceptions,
new callback failures and invalid acknowledgements. It verifies JNI descriptors,
ordering, exception behavior and exact return values. The wrapper's ordinary
Windows unwind frame is also checked. No real key, game process, injection,
party message or report is exercised by this fixture.

## Automated run results

- `test_bridge.py`: 57 tests passed, including Lunar PE, native bridge, Replay,
  stop-barrier, register-preservation and Windows unwind checks.
- `test_native_compat.py`: 27 tests passed, including equivalent compatibility
  PE/Replay checks and existing compatibility scheduler/unload behavior.
- `AdninReplayTest`: 34 checks passed against the local runtime classpath.

These targeted runs used the hash-pinned v11 native baselines as fixture inputs
and assembled the **current v12 bridge sources**. Final Java class embedding,
combined v12 EXE/DLL construction, the complete test suite, packaging and live
runtime validation are separate verification stages.
