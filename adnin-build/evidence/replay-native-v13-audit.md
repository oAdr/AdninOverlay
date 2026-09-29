# Replay v13: native stats root cause, narrow fix, and validation

This records static analysis, isolated execution of new bridge code, and the
parent agent's bounded live observations of v12. It does not claim that the
v13 fix has already passed a real-client test. This native subtask never
loaded a game DLL, read live process memory, accessed API keys, or sent chat.
The four retained v12 predicate bridges preserve the original eligibility byte and
consult the cached Java `AdninGui4.nativeReplayMode()I` result only when native
eligibility is false. Existing mock-CPU tests establish ABI preservation and
branch results for supplied inputs, not whether the running Java helper sees
the real sidebar or whether the roster contains Replay actors.

## Decisive current-session evidence

The parent reported these bounded scalar observations from the verified v12
compatibility payload in the open Badlion process during the failing Replay session:

```text
contextInitialized=1; gameplayMode=1; hudInstalled=1; masterTabOverlay=1
modelReady=1; nativeEligible=1; nativeReplayAtlas=1; rowCount=17
rowVectorStable=true; tabInstalled=1
```

The user's screenshot shows the custom Tab headers, 17 rows, names and HP;
the missing information is account statistics. This rules out missing Tab
ownership, absent rows, and a false native eligibility/Replay flag as the
cause of this particular failure. The screenshot was supplied by the user in
this session; its personal temporary-file path is excluded from release evidence.
The user confirmed that Replay actors use bot UUIDs which differ from the
displayed account names.

## Confirmed stats skip and v13 correction

The original model builder explicitly formats placeholders when its cached
Replay flag is true. It bypasses the normal stats queue/cache block. In
Lunar, the check is at `0x87406`, placeholder call at `0x87433 -> 0x76340`,
and the branch jumps to `0x8799F`. The corresponding compatibility placeholder
call is `0x88D08 -> 0x77040`.

`scripts/native_replay_stats.py` pins each complete dedicated branch by
SHA-256, in addition to the builder's existing normalized native text hash.
Only the 5-byte placeholder CALL is redirected. The bridge calls that same
placeholder formatter first and preserves its return value, then:

1. Reads the raw GameProfile name at row `+0x40`, preserving the actor UUID at
   `+0x148` and all entity/team/name attribution.
2. Calls `AdninGui4.nativeReplayProfile(String)` for the cached asynchronous
   Java result. Only `accountName|dashedUUIDv4` with an ASCII account name and
   valid UUID-v4/variant is accepted. Missing, pending, stale (Java rejects),
   or malformed results retain placeholders.
3. Queues only the verified UUID through the existing internal UUID queue
   under its original mutex. It never invokes historical name registration
   or the public name+UUID wrapper. Original API configuration, worker
   throttling, cache TTL and inflight de-duplication stay in that native queue.
4. Constructs a native Stats scratch object, calls the original cache getter,
   and formats the row only if the selected mode's result is ready. Every
   embedded string starts as valid empty SSO (capacity 15), and the matching
   native destructor frees the scratch object.

| Dependency | Lunar RVA | Compatibility RVA |
|---|---:|---:|
| UUID-only internal queue | `0x9D570` | `0x9FA40` |
| Stats cache getter | `0x98E50` | `0x9AFC0` |
| Stats row formatter | `0x5BF40` | `0x5C900` |
| Stats destructor | `0x6D40` | `0x6AF0` |
| Existing stats mutex | `0x1A4B70` | `0x17CB50` |
| Bedwars/Skywars/Duels readiness offsets | `8 / 0x60 / 0xA0` | `8 / 0xC0 / 0x100` |

No Replay-specific chat, Denicker or Output events are produced. No global
game-state or native Replay flag is changed. The prior potential slot-1 versus
team-color-sidebar gap remains explicitly outside this fix: this hook runs in
the original native Replay branch and does not attempt a global mode bypass.

The appended `ADNRST02` metadata describes four functions: main bridge,
locked queue, mutex unwind cleanup, and main-frame unwind cleanup. Windows
UHANDLER records release the lock and owned Stats/JNI temporary resources
during native exception unwind without swallowing the exception.

## Verification performed before native source freeze

Both profiles compile with NASM. `tests/test_bridge.py` passes **58/58**;
`tests/test_native_compat.py` passes **28/28**. The new shared
`tests/replay_stats_checks.py` executes only the new bridge in private memory
with fake JNI, lock, queue, getter, formatter and destructor callbacks. It
checks original placeholder arguments/return, nonvolatile registers/stack,
row identity preservation, SSO construction, verified UUID routing under the
mutex, all four modes/readiness and cache misses, malformed results, 16-char
heap-backed names, JNI failure/ref cleanup, existing exception preservation,
lock failure, once-only cleanup, and Windows `RtlVirtualUnwind` handler lookup.
Static PE checks reject drift in the original placeholder branch and cleanup
handler RVA, retain the history-cache gate, and ledger all original-byte edits.
An independent read-only assembly review confirmed the final ABI and cleanup.

The full regression suites also cover existing branding, Output, Denicker,
column, tick, lifecycle and Replay predicate behavior. Final Java integration,
actual network stats availability and live-client validation remain the parent
agent's responsibility. Published v12 artifacts are unchanged.

## Confirmed recognition mismatch and remaining actor-row gate

The original native detector reads objective display slot **1**, then examines
only that objective's display name for `atlas` or `replay`. It does not select
the team-color sidebar objective used by the Java v12 observer.

| Native operation | Lunar RVA | Compatibility RVA |
|---|---:|---:|
| Scoreboard detector | `0x12A20` | `0x12B90` |
| `mov r9d, 1` (`41b901000000`) | `0x12C1A` | `0x12D8A` |
| Call obtaining the selected objective | `0x12C29 -> 0xC270` | `0x12D99 -> 0xC010` |
| Original Replay/Atlas recognition byte | `0x1A63B8` | `0x17E438` |
| Current-Tab model builder | `0x83FD0` | `0x85830` |
| UUID-version parser | `0x7BF80` | `0x7D540` |
| UUID-v2 row-recognition predicate | `0x856ED` | `0x86F2C` |

The builder allows ordinary UUID versions directly, but for UUID version 2 it
requires the original Replay/Atlas byte to be nonzero. Consequently, a visible
Replay sidebar recognized only by Java can pass the four v12 overlay gates
while the builder still discards UUID-v2 actors from the current Tab roster.
This is a retained general limitation, not the cause of the observed 17-row
session (its native Replay flag is true). Actors absent from the current Tab
cannot be supplied by changing this predicate.

Exact bounded original-byte evidence for a potential **local** row predicate:

| Item | Lunar | Compatibility |
|---|---|---|
| Predicate RVA | `0x856ED` | `0x86F2C` |
| Predicate bytes | `803dc40c120000` | `803d05750f0000` |
| Preceding context RVA | `0x856D8` | `0x86F17` |
| Preceding bytes | `488d8dd8040000e89c68ffff8944246083f8027538` | `488d8df8040000e81d66ffff8944246083f8027538` |
| Following bytes | `752f498b4500498bd7498bcdff90b800000090` | Same |
| Keep-row target | `0x85725` | `0x86F64` |
| Skip-row target after cleanup | `0x87FCC` | `0x898C8` |

Both sites have `JNIEnv*` in R13 and the model context in R14. UUID version is
already stored at `[rsp+0x60]`. This evidence does not authorize changing the
global Replay/Atlas byte or the actual game-state value.

## Compatibility scalar diagnostic whitelist

Each address below is an RVA relative to the verified compatibility DLL base.
Only these individual scalar locations are needed. Do not read adjacent
strings, API configuration, chat caches, or memory addressed by the row-vector
pointers.

| Meaning | RVA | Representation | Evidence |
|---|---:|---|---|
| Master `tabOverlay` toggle | `0x17C5D8` | uint8 | Compare at `0x205A6` / `0x1A109` |
| Context initialized | `0x17E4C0` | uint8 | Model check at `0x91587` |
| Model ready | `0x17E840` | uint8, context `+0x380` | Checks at `0x91590` / `0x8D6DD` |
| Model row-vector begin | `0x17E748` | uint64 pointer value only | Context `+0x288` |
| Model row-vector end | `0x17E750` | uint64 pointer value only | Context `+0x290` |
| One-shot Tab installer flag | `0x17E945` | uint8 | Early-return / success flag in `0x11B00` |
| Original overlay eligibility | `0x17E95D` | uint8 | Original compare at `0x1A111` |
| Original Replay/Atlas marker | `0x17E438` | uint8 | Current-Tab predicate at `0x86F2C` |
| Native game-mode enum | `0x17EF48` | int32 | Builder reads through `0x8D250` |
| HUD installer flag | `0x17FC49` | uint8 | Getter `0xC6CC0`, bytes `0fb605828f0b00c3` |

For the row count, validate `end >= begin`, `(end-begin) % 0x178 == 0`, and a
reasonable count limit, then compute `(end-begin)/0x178`. Read twice and compare
because the client thread may update the vector concurrently. Do not follow
the pointers. Game-mode enum mapping is Bedwars=1, Skywars=2, Duel=3,
BedwarsDuels=4, unknown=0. The enum differs from the Replay/Atlas byte.

## Other dependencies and failure modes

- The native JniCache initialization is attempted once during startup. If it
  fails, later model refresh returns immediately. A normally working Bedwars
  baseline makes this less likely than a Replay-specific recognition problem.
- Tab installation uses a one-shot flag (Lunar `0x1A63BC`, compatibility
  `0x17E945`). Once set, the native installer does not verify that the current
  HUD still owns the custom Tab overlay. HUD replacement is a possible stale
  installation mechanism, not an observed event in this audit.
- The renderer requires an OpenGL context. Its fallback path requires no open
  current screen. The `Ingame + Bedwars` resource-header gate does not prevent
  the entire player table from rendering.
- The initial Lunar `Gui4` definition receives the Minecraft class loader in
  RSI (`0x14E5F`), later stored as global `0x1A63B0`. `ClientPump` uses that same
  saved loader. Ordinary initial loading therefore does not show a separate
  helper-loader path. Failed `DefineClass` operations may reuse existing named
  classes, so a matching native DLL hash does not prove Java helper freshness
  after reinjection into the same JVM.
- Optional JNI lookup or callback exceptions are cleared and return zero.
  Missing `nativeReplayMode` in an old helper therefore resembles a negative
  Replay observation. Java v12 sidebar observation likewise silently catches
  `Throwable`, making linkage failure indistinguishable from no Replay marker.

## Deliberately retained data boundary

Do not widen the secondary historical-chat cache's Ingame condition. Its
entries contain prequeue names/colors/timestamps without world/entity/UUID
identity. Allowing that cache during Replay can show previous-match players.
The current-Tab builder and its row predicate are separate from this cache.

| Historical cache site | Lunar | Compatibility |
|---|---:|---:|
| Ingame comparison call | `0x88098 -> 0x3260` | `0x8998C -> 0x3140` |
| False branch | `0x8809F -> 0x89E0F` | `0x89993 -> 0x8B526` |
| Cache reader call | `0x880C0 -> 0x91E90` | `0x899B4 -> 0x94010` |
| Cache writer | `0x910B0` | `0x93230` |

The decisive current-session evidence above supersedes the earlier overlay
ownership/eligibility hypotheses. The v13 change still requires a fresh-JVM
live Replay check before it can be described as confirmed in-game behavior.
