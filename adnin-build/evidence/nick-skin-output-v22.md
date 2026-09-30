# v22 self-Nick teams, Skin Denicker and Output review

Reviewed on September 30, 2026. This revision follows the verified
`build-v21-ac-scope`; its earlier live observations are not evidence for v22.

## Own Nick and teammate identities

The old shipped helper failed a controlled regression where the local entity
retained its original account name with reset/rank formatting while the UUID-linked
server Tab profile had a different Nick and the correct scoreboard team color.
The entity formatting took precedence, either leaving self color unresolved or
freezing the wrong color for the match. The revised helper gives the linked
current server identity/scoreboard evidence precedence and does not commit a
provisional original-account rank color while that evidence is incomplete.
Positive teammate identities remain match-scoped. They are cleared on a new
match/world/connection, rather than permanently tied to the login account name.

The Anticheat adapter uses an admitted Replay actor's complete resolved roster
name for the shared teammate exemption; ordinary play still uses entity UUID/name.
A cached raw/skin alias cannot override the currently admitted Replay identity.
It does not use an arbitrary skin owner's identity to classify a Replay bot.
Regression fixtures exercise real adapter delivery counters: a teammate cannot
emit an alert, sound, report or party message with Ignore Teammates enabled,
while a differently colored opponent can still pass the unchanged detector.

The pinned Mellow reference is commit
`17ef9b7466754a33ee8c8ed87fa7ea717573d775`. Its scoreboard/current-player team
lookup is a useful source of server evidence, but it does not supply a complete
local-Nick/Replay identity implementation. Adnin retains its explicit UUID,
current visible-name, spectator and ambiguity checks.

## Output correctness and repeated work

The review reproduced two message-loss cases: a full queue reserved its dedup
marker even though it rejected the message; and the next tick after enabling
Output could clear an already admitted first message. Admission now precedes
dedup reservation and the redundant enable clear is removed. Disable, category,
match/Replay and world transitions retain their eligibility checks.

Unconsumed Nick hints and per-context presentation markers cannot leak into the
next roster. Bot no-result and error text remains local only. Native output
parsing exits before translation/JSON work in an inactive context. Tag subjects
and displayed labels reuse parsed/cached values rather than repeat regex and
abbreviation work for every rendered row.

The Anticheat adapter publishes an immutable cached settings snapshot with no
additional monitor. Unchanged tick/save reads reuse normalized ignored names
and the signature. Direct changes to every supported option refresh the snapshot,
and a new snapshot cannot mutate the old view held by another reader. Ten
thousand repeated fixture reads use the same settings object. Shutdown releases it.

## Mellow Skin Denicker replacement

The pinned Mellow SkinUtils source was checked against its official repository.
Adnin reads the current Tab profile's Base64 textures metadata and excludes the
same 230 public Nick-skin hashes. The adaptation also requires a valid account
name, canonical online UUID-v4 profileId and an official texture URL. Parsing is
bounded in size, depth and property count, with conflicting metadata rejected.
The resolver performs no network or filesystem IO and starts no worker thread.

Six byte-pinned legacy Skin call sites are retired in each native profile. The
old code remains dormant in the recovered image. Number results retain priority;
current Skin evidence is considered next, with Bot after the local Skin attempt.
The existing native stat queues and owned result copies are reused. Replay actor
UUIDs remain unchanged, and Skin results are not written into the Number cache.
Only ready native statistics publication acknowledges Skin success; a client
tick then announces it through the existing Nick / Denick Output category.

Cross-match cache entries are keyed by texture payload, bounded to 512 and valid
for ten minutes. At most eight of 128 pending hints are processed in each 250 ms
batch. Current roster UUID and exact texture evidence are rechecked without
JSON decoding; name reuse, texture changes, disappearance, duplicate names and
lost Replay Nick eligibility invalidate the previous identity. Native readers
reject snapshots older than 300 ms, and clock rollback cannot extend validity.
Configuration/context generation guards protect pending notifications. The
original native Skin availability follows Hypixel key presence; no separate
setting or private key is embedded. Clearing the key stops pending Skin output.

This technique identifies the texture owner, not necessarily the person using
a copied custom skin. Default/shared/missing metadata cannot recover an arbitrary
player identity. No claim of certain deanonymization is made. Source/license
notices are retained in the EXE and corresponding source archive.

The build's older `replayDenick` metadata describes the unchanged Replay admission
patch in isolation. The separate `skinDenickerPolicy` and top-level
`legacySkinResolutionRetired` fields describe v22's subsequent Skin replacement.

## Verification status

The coherent `build-v22-nick-skin` completed on September 30, 2026, with source
hashes unchanged throughout the build. Its standalone EXE embeds both reviewed
payloads, the logo and third-party notices; helper classes remain Java 8 and
Forge-free. Final checks include:

| Verification | Result |
| --- | --- |
| Injector/selection/UI/cache CTest | 5/5 passed |
| Lunar / compatibility native bridges | 73 / 41 tests passed |
| Denicker / reembedding / Java compatibility / tick hook | 15 / 33 / 13 / 3 passed |
| Self-Nick team helper, Java 8 and 17 | 85 checks each |
| Anticheat adapter / settings, Java 8 and 17 | 105 / 112 checks each |
| Mellow Skin resolver, Java 8 and 17 | 2,282 checks each |
| Output / categories / formatted presentation | 364 / 418 / 256 checks |
| Bot cache / resource lifecycle | 2,379 / 8,039 checks |
| Real OpenGL UI, three languages and 70–140% scales | 644 checks |
| Bootstrap, both profiles on Java 8 and 17 | 4,000 repeated calls, 32 MiB heap, 12/12 loaders collected per run |
| Package privacy regression | 36 passed, one host symlink test skipped |

The old shipped helper and adapter fail the new own-Nick fixture; corrected
production sources pass. The old Output class also fails the queue-overflow
regression. A 120,000-case formatted-token differential check preserves prior
name-color parsing behavior. Screenshot inspection confirms `v22` beside Adnin.

No v22 live injection, real API request or actual party message has been used
as verification in these checks. Long-session stability and the reported
real-server Nick scenario still need a fresh-client live test. No measured live
FPS or total-process-memory reduction is claimed.

## Lobby-query follow-up, September 30, 2026

The reported main-lobby JSON is compatible with the old Lunar automatic
`/locraw` path: its policy checked the detector switch, world readiness and
Hypixel branding, but not the pre-game phase. An owned client fixture reproduces
the actual production adapter sending in a Bed Wars main lobby, with the old
source failing the new negative regression. Lunar's recovered chat wrapper is
dormant, so that response can remain visible. Broad response suppression would
also risk starving the original mode parser; it is not part of this correction.

The new adapter requires the current rendered Bed Wars sidebar, a bounded
player-count fraction and an explicit waiting/countdown line. Known lobby,
Replay and active-game rows veto mixed transitional evidence. It independently
checks native Ingame/Replay state and revalidates the world, connection, detector
switch and sidebar immediately before sending. Official addresses must pass
the same phase check as relay addresses. Relay footer and zero-width row-key
recognition are preserved. Phase changes do not impersonate an explicit feature
re-enable or reset an already consumed attempt. The existing 500 ms delay,
five-second minimum attempt interval and terminal shutdown remain intact.

This prevents a lobby from originating a query; it does not suppress a manual
`/locraw` or a response already in transit from an earlier valid waiting-room
query. Missing, hidden or nonstandard phase evidence declines automatic queries.
Badlion/Vanilla retains its original native mode-discovery and installed response
wrapper: its existing parser serves more than Party Detector, so changing its
scope to that feature switch would require separate consumer regressions.

The audit also reproduced a delayed Urchin start: keeping /config open across
the native match-start callback and returning to the lobby before closing it
allowed the old Features tick to enqueue a lookup for the lobby roster. New
batch admission requires the active native match. Leaving retires queued work;
the worker rechecks both scope and start serial after request pacing, before
starting HTTP. A response whose HTTP has already started can still complete
the same-key cross-match cache. Bot behavior, completed caches and Replay
Output eligibility are preserved; Replay does not create a live-start batch.

A further regression separates a worker's paced, not-yet-started job from a
request already claimed for HTTP. Nonblocking dequeue and waiting-owner
registration are atomic under the existing Features monitor. Before a new
roster batch is frozen, cancellation releases that waiting reservation. Claiming
requires the exact registered job as well as the current generation and match.
Thus a cancelled job cannot revive while native phase publication lags, and an
old worker cannot remove a new match's reservation. Sleeps and HTTP remain
outside the monitor. Shutdown also releases the one waiting string-array
reference. No new thread or network object is retained.

Targeted tests passed on Java 8 and Java 17: query policy 185 checks, real
scoreboard scope 94, actual query adapter 32 across 18 scenarios, Urchin scope
40, worker 213 and Feature policy 189. Bot cache 2,379, Output categories 418
and resource lifecycle 8,039 checks also passed, including 2,000 match changes.
The Urchin fixture covers deferred /config, same-world re-entry, Replay,
rate-pacing cancellation, old/new reservation ownership and key/shutdown
cleanup. These tests use owned fixtures and no real HTTP, game chat or keys.

The concurrency review found no new lock-order cycle or client-thread wait for
Netty. Query introduces no worker and examines at most 512 scores/15 rendered
rows per 250 ms probe, stopping probes after the cycle's attempt. PacketLog
retains its asynchronous install/remove and active-observation unload barrier.
HTTP remains outside the Features monitor and late publication checks the
generation under that monitor. Output still caps its queue at 32 messages and
deduplication at 256 entries; Urchin retains its 512-entry/4 MiB cache limits.

A historical cost remains: the existing spawn observer can keep decoding on
the same connection after Party Detector becomes inactive. Its Java task state
is bounded, but the native spawn deque uses a six-second rolling time window
without a verified hard item limit. No evidence links that path to this report;
it was not rewritten or presented as fully bounded. This review is not proof
that every possible native crash or long-session problem has been eliminated.
