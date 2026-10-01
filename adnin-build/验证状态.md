# Adnin v23 verification

## Current shared-profile and Lunar auto-start follow-up

The new candidate is `build-v23-party-shared-config`. All clients share
`%LOCALAPPDATA%/Adnin/config.properties`; the newest valid native settings from
Adnin's hash-specific payload cache and the current client's old feature file
migrate only before that file exists. Empty/invalid/missing shared credential fields
cannot restore old values. The injector reads the same file's language.
The recurring native per-client writers are retired through guarded JNI
callbacks; changed snapshots save on the existing daemon with atomic replace.

Read-only inspection of the old running Lunar build found the visible phase
stored as `Waiting...` plus a zero-width U+26BD row key. The previous parser
handled only supplementary symbols. BMP symbol handling now requires current
font proof and preserves all phase/footer and retry safeguards.

The coherent build completed October 2, 2026 with stable production source
fingerprints. All 6 CTests passed, including shared-language CRLF and precedence.
Native Lunar 80 and compatibility 46 tests passed, including the new config
callback's byte guards, JNI exceptions, registers and unwind. The full Java and
production-adapter suite passed: shared settings 147 checks for each compiled
profile, lifecycle 8,051, Output 508, Bot cache 2,379, input lifecycle 88,
UI lifetime 4,798 and UI equivalence 146,868. Party sidebar checks passed 115,
and the actual adapter passed 74 checks across 27 scenarios. Packaging privacy
passed 36 tests with one host symlink case skipped. The focused settings and
Party tests also passed on Java 8 and Java 17. Both DLLs are embedded byte-exactly
in the single EXE; settings, private keys and custom Bot URLs are excluded.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.dll | 2,758,656 | `912e7478b045db0794c65586300c10afcad8a570afb85b634f4d7e0585db9f64` |
| AdninVanilla.dll | 2,643,456 | `0b715da6e57b5deac7d5dba835bc0f461b6659c2d628bd9055282f318b6da7f3` |
| Adnin.exe | 7,638,528 | `fe94b9a554aedb2470d99d03029bed2f1c9232ff5b567b96d466b89737e4d2e2` |

The first direct compatibility fixture attempt encountered the signed JAR's
default-package signer restriction. Its final configuration fixture uses an
owned temporary signature-free copy; the separate real signed-loader tests
still use the original JAR and passed. A missing expected unwind entry in the
new hook's test was added; ABI/byte/pixel assertions were not weakened.

See `evidence/shared-config-party-v23.md`. No live follow-up injection yet;
existing live processes retain the previously published payload until restart.

## Previous v23 respawn, decoded Party mode and independent Aurora Ping

The coherent `build-v23-followup` completed on October 1, 2026 with unchanged
production source fingerprints throughout the successful build. It supersedes
the earlier v23 packages below. Both embedded payloads are verified byte-for-byte
against their built DLLs; compiled helpers, defaults and source fingerprints
are checked again by packaging.

Changes:

- Guard cached-stat/tag producers and recheck the current subject before local
  chat or party delivery, closing the respawn-to-next-snapshot interval.
- Treat reliable actor, Tab or scoreboard light-gray name evidence as a pause,
  including conflicting old team colors and Replay actors. Preserve prior
  same-player teammate, identity, tag and statistics state.
- Observe packets after decoding and accept only bounded current-query server
  mode responses. Lunar hands the verified mode to the original native parser
  and Prequeue consumer; known modes skip JNI. Same-world lobby transitions,
  expired windows and stale observer tokens retire the response.
- Query public Aurora Ping independently of Hypixel's API Proxy switch/key.
  Preserve the native background worker, parser, completed-response ten-minute
  cache and request-failure 45-second cache.
- Keep 250 ms bulk snapshots, lock-free worker/Netty snapshot reads, existing
  input behavior, game/render priorities and Anticheat sampling frequency.

Successful final-build verification:

- 6/6 CTests; injector UI/cache 18 cases; automatic crash diagnostics 256 checks.
- Native Lunar 79 and compatibility 45 tests; reembedding 33; Denicker 18;
  Java compatibility 13; native tick 3.
- Full Java suite, including Output categories 508, Bot cache 2,379, resource
  lifecycle 8,050 and owner-loader bootstrap.
- Actual production adapters: gray output 48; MatchTeams 138; Anticheat 653;
  all-five-check gray admission 486; Replay roster 156 and Replay/Anticheat 30;
  Urchin scope 64; Skin Denicker 2,290.
- Party policy 225, sidebar scope 94, accessors 66, real registered Netty
  pipeline 498, and production query adapter 58 across 22 scenarios.
- UI clipping/geometry equivalence 146,868; menu lifetime 4,798; input lifecycle
  and game-tick checks passed. These are fixture results, not live FPS claims.
- Both profiles verify all seven cached notification call sites and five query
  gates. Each executes seven leaf functions across 168 color/result cases,
  21 color recoveries and 14 Windows unwind positions with unchanged row data.
- Package privacy regression: 36 passed, one host symlink test skipped.
- Focused changed Java paths also passed on Java 8 and Java 17.

An earlier run had an intermittent private-desktop PrintWindow red-dot capture
failure. The identical artifact passed the focused recheck and the complete final
suite without changing UI code or weakening the pixel assertions. A compatibility
hook-count assertion was updated from unique-function count to actual call-site
count; all call-site/ABI/byte-preservation assertions remain.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.dll | 2,723,328 | `55b2a797c7fe69128b2bc78b5a4d35c9e972e777f646acbf9a48bfa7dcb19b87` |
| AdninVanilla.dll | 2,606,080 | `8d9725f20f6f375a5f4d67d0efe3af828bd94e3c8b1c4a2391a59fa37e0fa39e` |
| Adnin.exe | 7,563,776 | `4ce03043226255d7d852ca3a73dfb5a792b8652ccef16ec1a8cb58a77d597fde` |

No running Lunar/Badlion game was injected or operated during this repair.
Offline builds and fixtures do not establish live respawn behavior, real party
membership, long-session stutter/crash absence or successful Aurora measurements.
Public Aurora checks returned structured no-data responses; the column may be
blank when that provider has no measurement. See `evidence/respawn-party-ping-v23.md`.

## Earlier v23 gray-policy stage (superseded)

The coherent `build-v23-gray-policy` completed successfully on October 1, 2026,
with unchanged production source fingerprints throughout the build. It
supersedes the earlier team-only v23 candidate. Game/render priorities, input
behavior, native mode parsing, entity-ID grouping and Anticheat sampling remain
unchanged.

Light-gray name tokens pause new player detection, queries and generated
announcements across Anticheat, tags, Nick/Denick, statistics, Ping and Party
observations. Existing same-player identity, teammate, cached data and tag
presentation survive a temporary gray respawn. Already accepted Party history
is retained. White and dark gray remain eligible. Snapshot readers are
lock-free; refresh is bounded to 250 ms and synchronized with shutdown.

Positive teammate names/UUIDs survive same-match respawn and entity changes.
The Lunar Party query can recover from missing players, sidebar rejection or
sender failure, with at most three attempts and five seconds between attempts.

Ping now uses the Aurora v2 public route with UUID and no Ping API key. The
existing API Proxy opt-in and native worker/cache remain: completed responses
are eligible for on-demand refresh after ten minutes; transport failures after
45 seconds. Ping uses the first parsed `avg`; PingVar is the range of averages.

Full coherent-build verification passed:

- 6/6 CTests, including injector UI/cache and crash-diagnostic checks.
- Native Lunar/compatibility suites: 78/45; reembedding 33; Denicker 18;
  Java compatibility 13; native tick 3.
- Complete Java suite, including Output categories 508, Bot cache 2,379,
  resource lifecycle 8,048, Replay profiles 203 and owner-loader bootstrap.
- Focused production adapters: teammate helper 130; Anticheat adapter 650;
  all-five-check gray policy 166; Replay roster 154 and Replay/Anticheat 30;
  Urchin scope 64; Skin Denicker 2,290.
- Party-query policy 197; scoreboard scope 94; production query adapter
  41 checks across 20 scenarios.
- Input lifecycle, game tick, menu lifecycle and clipping/geometry equivalence
  checks passed. Focused changed Java paths also passed on Java 8 and Java 17.
- Native gray guards: five per profile, 120 execution cases, 15 color restores
  and 10 unwind checks each. Cached values and one-shot results are preserved.
- Aurora tests verify seven API policy patches, endpoint relocation, unchanged
  native fetch/parser instructions outside the URL displacement, Proxy gating
  and offline response-contract fixtures.
- Package privacy regression: 36 passed; one host symlink test skipped.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.exe | 7,508,992 | `fd9115ab75d66518b0a63e5ca64a39fab096e0c9e2fba3b13b6054cc81c4de7b` |
| Adnin.dll | 2,696,192 | `40135b2c700e5ab53e51747927bca55c594e67c3b47f854378aff71a6344e261` |
| AdninVanilla.dll | 2,578,432 | `4452a2fd2c27ad0158d3e9a30053da0279d5d58425cf1f6cf741692fdf2ff5d7` |

An independent read-only check confirms EXE resources 101 and 102 match the
two DLLs byte-for-byte. Direct decoding of the embedded DLLs resolves Lunar
Ping LEA `0x97ba8` to `0x28dfa6` and compatibility LEA `0x99d18` to `0x270fc6`;
both contain the Aurora Ping URL in `.adncode`.

No running game was injected or operated for this repair. Fresh Lunar/Badlion
multiplayer, long-session performance and successful live Aurora Ping display
remain unverified. Public route checks returned structured no-data responses;
they do not prove a successful Ping measurement. Current build reports and
the v23 evidence notes are authoritative; generic historical test text files
retained in the source archive are not this build's test transcript.

## Current v22 input safety and equivalent-work reduction

The follow-up targets input lifetime defects and repeated frame-path work.
The coherent `build-v22-input-perf` completed successfully with unchanged
production source hashes and byte-exact payload/resource verification. It keeps
game input state, renderer order, priorities, frame limits and detection sampling
frequency unchanged. This follow-up supersedes the preceding v22 package below.

Final verification passed:

- 6/6 CTests, including 18 injector UI/cache cases and 256 crash-diagnostic checks.
  Success-window nonactivation is checked during its hold/fade as well as its
  style. UI fixtures ran on an unswitched private desktop. Its two system input
  indicators are excluded only after exact class, blank title, nonactivation and
  System32 InputSwitch.dll registration checks; other windows remain counted.
- Native Lunar/compatibility suites: 77/44; reembedding 33; Denicker 15; Java
  compatibility 13; native tick 3. The existing complete Java suite passed.
- Exact final DLL chat fragments, per profile: 314 original-consumer equivalence
  cases, 1,545 ABI comparisons and 19 actual Windows unwind instruction boundaries.
  A stable 100-line history needs one full conversion instead of 100.
- Java 8 and 17: input admission 38, concurrent stop publication 103, game tick
  48, HUD lifecycle 25 and JNI-facing stop acknowledgment 6 on each runtime.
- Java 8 and 17: UI input lifecycle 88, clipping/config/geometry equivalence
  146,868 and UI resource lifecycle 4,798 on each runtime.
- Real isolated OpenGL: 644 checks across seven panels, three languages and
  70/140 percent scales. Rendered panels and the result window were inspected.
- Both bootstrap profiles on Java 8/17: 4,000 repeated calls, 32 MiB heap and
  12/12 classloaders collected in each run.
- Package privacy regression: 36 passed; one host symlink test skipped.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.exe | 7,440,384 | `23d9556f4103ce374d2b6d02df419df58251c3ce44cfb3f6ca1866d87b0c7221` |
| Adnin.dll | 2,662,912 | `a23328c8111fe916f1f8b3d8549dcb82a63a65821fc27178b3b45afb83555ea2` |
| AdninVanilla.dll | 2,543,104 | `cdf6a0bc76d0f94199586a3f8145ff3697e8620ca9b9d78b9382cd5a8d834448` |

No new payload has been loaded into the running game. The measured reductions
are controlled offline workload/allocation results, not live FPS or total-game
memory improvements. Normal movement and turning still need fresh-client
comparison before the reported intermittent stutter can be considered resolved.
Read-only observations of the preceding package are not live evidence for this
candidate. See `evidence/input-performance-v22.md`,
`evidence/input-hook-review-v22.md` and `evidence/native-chat-prune-v22.md`.

## Previous v22 lobby, Eagle, diagnostics and allocation follow-up

The coherent `build-v22-lobby-eagle-crash` completed successfully. Source hashes
remained unchanged during the full build; both runtime payloads and third-party
notices were verified byte-for-byte inside the single EXE. These preceding
v22 results are preserved as historical evidence.

Changes include mandatory pre-game scope for Lunar automatic mode queries,
Urchin queued/paced request retirement and cross-match ownership, Mellow Eagle
under the existing Legit Scaffold switch, reusable Anticheat samples and
team-string fast paths, and hidden automatic Desktop crash diagnostics.
The crash monitor exports bounded sanitized diagnostic logs, not memory dumps,
and does not upload to a service. Normal exits without matching fatal evidence
remain silent. The deep-path integration defect was reproduced and repaired.

Final verification passed:

- 6/6 CTests, including the 256-check automatic crash-diagnostics suite.
- Lunar/compatibility bridge 73/41; Denicker 15; reembedding 33; Java compatibility
  13; native tick hook 3. The full existing Java suite passed.
- Eagle 232; Core 3,464; unchanged Scaffold 375; adapter 644; teams 90; settings
  114. Targeted behavior was also checked on Java 8 and Java 17.
- Query policy 185; scoreboard scope 94; actual query adapter 32; Urchin scope
  40 and worker 213. Output/categories 364/418; Bot cache 2,379; lifecycle 8,039.
- Both bootstrap profiles on Java 8/17: 4,000 repeated calls, 32 MiB heap and
  12/12 classloaders collected in each run.
- Package privacy regression: 36 passed and one host symlink test skipped.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.dll | 2,650,112 | `63e44039cc6f63e0d3bcfe6df6ad54878cd7a943ac2e9c18e015d3f60b0c4ff2` |
| Adnin.exe | 7,414,272 | `6af21de43eec9d0cf4ef8c6966d12c2590379fab1d8c5fc6ff19279f333a2800` |
| AdninVanilla.dll | 2,529,792 | `09d43b4b01c6e5cc453ab6c058d49ffbdea62db97a32640fc69ee7aeb5cff920` |

The 96-actor allocation fixture measured about 38% less allocation with team
evidence established, while preserving sampling frequency. This does not mean
38% lower total process memory or a measured FPS gain. No game/JVM settings were
changed. No real game was terminated to test automatic crash export.

This exact candidate has not yet been loaded into a fresh Lunar or Badlion for
live validation. The still-running older v22 process is not evidence for this
build. Restart before injecting the new executable; long-session behavior and
actual detection accuracy remain unverified. See
`evidence/lobby-eagle-crash-v22.md` for methods, privacy and adaptation limits.

## Historical initial v22 build and offline verification

The coherent `build-v22-nick-skin` completed on September 30, 2026. Lunar and
Badlion/Vanilla payloads were compiled, remapped and embedded into one EXE.
The source input hashes remained unchanged through the complete build. The
menu displays `v22`; the real OpenGL fixtures were inspected visually.

The self-Nick regression fails against the shipped v21 helper and adapter and
passes on the correction. The final team helper has 85 checks; the adapter has
105 and settings 112, each verified on Java 8/17. Mellow Skin has 2,282 checks
on each runtime, covering current evidence, default skins, malformed metadata,
Replay identity, ready-stat acknowledgment, stale snapshots and lifecycle bounds.
Output and categories pass 364/418 checks, with 256 presentation checks and
2,379 Bot-cache checks. The earlier v21 Output class fails the new overflow test.

Integrated verification passed: 5 CTests, Lunar/compatibility bridge 73/41,
Denicker 15, reembedding 33, Java compatibility 13, native tick 3, core Anticheat
3,664 and resource lifecycle 8,039. The complete existing Java suite also passed.
Real OpenGL rendering passed 644 checks across seven panels, three languages
and 70/140 percent scale. Both runtime profiles passed bootstrap stress on
Java 8/17: 4,000 calls, a 32 MiB heap and 12/12 classloaders collected per run.
Packaging privacy regressions passed 36 checks with one host symlink skip.

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Adnin.exe | 7,319,040 | `909d7e761fd75305a9b2fdb346cba5a06699786627aca861a9073c89af163251` |
| Adnin.dll | 2,633,728 | `3b16cf75c8d0b4a5e6e0914aca6a7f3bb17c14a93944de34a05c1aba09cb2154` |
| AdninVanilla.dll | 2,512,384 | `099d90e9f8410574bcddd1f22c2ddae1f7701e7e1563d7414b466b7e3cd80f7a` |

This is offline evidence. v22 has not yet been injected into a fresh game for
the reported real-server own-Nick scenario. Earlier v21 live observations below
belong to their stated binaries and must not be attributed to v22. No live FPS
or total-process-memory reduction is claimed. Mellow Skin resolves texture-owner
metadata; copied custom skins do not prove the current player's identity.
See `evidence/nick-skin-output-v22.md` for scope and detailed limitations.

## Historical v21 completed build and offline verification

The coherent `build-v21-ac-scope` completed on September 30, 2026. Both payloads were
assembled, compiled, remapped and embedded into the standalone EXE; the final
build checked unchanged source inputs and byte-exact embedded resources. It
contains 64 Lunar and 65 Badlion/Vanilla helper classes, targeting Java 8 without
a Forge dependency. The menu displays `v21` beside the red Adnin title.

| Verification | Result |
| --- | --- |
| Injector, runtime selection and UI/cache CTest | 5/5 passed |
| Native Lunar / Badlion-Vanilla bridge | 72 / 40 tests passed |
| Reembedding ABI / Denicker / Java compatibility / native tick | 33 / 15 / 13 / 3 tests passed |
| Party query state machine, Java 8 and 17 | 167 checks on each runtime |
| Official/proxy visible scoreboard scope, Java 8 and 17 | 48 checks on each runtime |
| ClientSounds lifecycle and channel reuse, Java 8 and 17 | 1,529 checks on each runtime |
| Packet observer concurrency / accessor cache, Java 8 and 17 | 439 / 63 checks on each runtime |
| Replay detector and timing, Java 8 and 17 | 2,100 checks on each runtime |
| Replay roster / Replay-Anticheat integration / adapter | 98 / 25 / 90 checks passed |
| Game tick / concurrent HUD stop / Lunar stop acknowledgement | 48 / 25 / 4 checks passed |
| Bot cache / Output categories / self-Nick teams | 2,379 / 411 / 64 checks passed |
| Resource lifecycle | 8,038 checks, including 2,000 match transitions |
| UI interaction / resource lifecycle | 960 / 4,798 checks passed |
| Actual hidden OpenGL render | 644 checks; all seven panels, three languages, 70% and 140% scales |
| Bootstrap stress, both profiles on Java 8 and 17 | 4,000 repeated calls per run; 32 MiB heap; 12/12 loaders collected in each run |
| Packaging privacy regressions | 36 passed; one host symlink-creation test skipped |

The integrated build also passed the existing API, language, message rendering,
Urchin cache/worker, column order, scaffold and Anticheat suites. Targeted
controlled baselines reproduce the old PacketLog deadlock and ClientSounds
queue accumulation, then pass on the corrected source. The new ClientSounds
test bounds 3,000 pending-install attempts to one task; the packet accessor
test resolves once for 10,000 same-class reads; the Replay fixture scans once
for 2,000 callbacks within its observation window. These are workload-count
results, not live FPS or total-process-memory measurements.

Version-label rendering was visually inspected in the real OpenGL fixture.
Default personal key and Bot URL values remain empty; runtime settings and
raw diagnostics are excluded from package inputs. Public service endpoints
remain intentional. Earlier versioned v19/v20 release artifacts were preserved
and checked against their historical manifests.

The latest scope restriction also passed the adapter, core, settings and actual
Replay fixtures on Java 8 and Java 17. The old adapter failed the new inactive
scope fixture by generating a lobby alert/report/sound/output event. The old
GameModules bytecode failed the packet-interest scope fixture. Both corrected
production classes pass, including same-world re-entry, cooldown preservation,
inactive world/connection cleanup and independent ClientSideSounds behavior.
Native state publication retains its original 500 ms polling period; this is
not a claim of zero-latency phase-change recognition.

The latest scope-gated EXE was loaded into a fresh Badlion 1.8.9 process. Its
compatibility payload hash, initialization markers, class references, hooks and
advancing heartbeat matched this exact build. The user confirmed normal menu
operation and movement. With Anticheat enabled, four lobby samples showed no
sampling/alert/report growth, zero actor/evidence state and no Anticheat packet
interest, while independent placement sounds remained enabled.

After the user entered Replay, native Ingame remained false but Replay was true:
sampled ticks rose from 590 to 710 over six seconds, with 13–15 admitted actors
and active packet observation. After returning to the lobby, four more samples
showed continuing game ticks but sampled ticks fixed at 1,342, cumulative flag
decisions fixed at three and report decisions fixed at zero. Current actor and
evidence state were empty, and Anticheat packet interest was off. Cumulative
counters deliberately remain cumulative; the three Replay decisions were not
new lobby alerts. This verifies the scope transition, not detection accuracy.

The latest Lunar payload passed compilation and shared-source/Java 8/17
regressions but was not reinjected after this final scope-only change. A live
ordinary-match sample of this latest build and standalone Vanilla are not
claimed. The following broader live results belong specifically to the
immediately preceding `build-v21-proxy2` candidate.

## v21 proxy2 live baseline before the Anticheat scope restriction

The preceding packaged EXE was loaded into separate fresh Lunar and Badlion 1.8.9
processes on September 30, 2026. Each loaded DLL matched its final payload hash;
independent read-only checks confirmed initialization markers, required class
references, hooks and advancing heartbeat. The user confirmed both configuration
menus work and confirmed normal movement in Lunar.

In Lunar's relay-connected pre-game room, the official-hostname check was false,
but visible-sidebar scope, the one-shot query attempt and native mode discovery
were all true. The installed active detector reported a maximum party size of
four. No manual `/locraw` was used in this final test. Six samples over 50 seconds
showed a responsive window and advancing heartbeat. The user then exited Lunar
normally to switch clients; its observed process exit code was zero.

In Badlion's pre-game room, three stable read-only samples confirmed Prequeue,
installed/active detector, known mode and maximum party size four. The observer
was attached to the packet handler and processed spawn packets, with no pending
installation. Six samples over 50 seconds also showed a responsive window and
advancing heartbeat. Badlion retains its original native mode-query path; it
does not run Lunar's client-pump query helper.

The user had not observed a party-group chat notification in that Lunar
test. These checks establish automatic mode discovery and listener readiness,
not correct classification of every actual party. Standalone Vanilla was not
live-tested in this pass, and short sampling does not prove long-session FPS,
total-memory savings or the absence of every crash. Badlion exit was not tested.

Earlier candidate observations are separate. The direct-domain-only candidate
rejected the relay hostname. The first sidebar-compatible candidate included an
invisible U+1F382 score-row identifier in its strict footer check. Read-only
inspection found both corresponding font widths were zero. The final correction
verifies each width before omitting only that separate row key, retaining the
complete-domain boundary. Its regression reproduces the old failure and then
passes, including visible custom-glyph and missing-font counterexamples.
See `evidence/stability-performance-v21.md` for the review and implementation.

## v20 captured hang and repair

The frozen v19 Lunar process was examined read-only on September 30, 2026.
The client thread held Minecraft's task-queue monitor while awaiting the Netty
task that removed AdninPacketLog's handler. That exact task's EventLoop was
blocked acquiring the same monitor. Object ownership and the PromiseTask's
handler/executor chain establish the closed wait cycle; this is live v19
diagnosis, not a live v20 validation result.

The observer now queues every pipeline operation onto its channel EventLoop,
reuses a same-channel installation, bounds pending lifecycle work and rejects
stale installation tasks. Shutdown immediately forbids new observations and
asynchronously removes only owned handlers. Disabled handlers still forward
packets and events. An admission counter covers spawn parsing and the native
callback; neither payload permits native unload until those observations drain.

The Lunar stop method returns zero while busy. Badlion/Vanilla now uses a new
integer acknowledgement, retaining the old void Java method for ABI compatibility.
Busy results preserve native ownership for a later retry and introduce no
EventLoop wait or exception-based control flow. If End has been released by
the time the observation finishes, another End press retries the unload.

Original fields, native methods and declared method ABIs remain protected.
Reembedding permits only the precise private synthetic lambda relocation caused
by moving the handler into its EventLoop factory; all unrelated ABI checks stay
strict. Both runtime profiles include the additional InstallRequest helper.

## v20 recorded verification

| Verification | Result |
| --- | --- |
| Same controlled Netty regression against old v19 | Fails at the expected EventLoop-wait assertion; fixture releases safely |
| Registered Netty packet observer regression, Java 8 / 17 | 438 / 438 checks passed |
| Game tick / concurrent HUD unload / Lunar stop acknowledgement | 29 / 23 / 4 checks passed |
| Native Lunar / Badlion-Vanilla bridge | 72 / 40 tests passed |
| Java compatibility, including busy-to-ready acknowledgement | 13 tests passed |
| Reembedding ABI / Denicker suites | 33 / 15 tests passed |
| CTest injector, runtime selection and UI/cache | 5/5 passed |
| UI interaction / UI resource lifecycle fixtures | 960 / 4,798 checks passed |
| Bot cache / Output categories / self-Nick team fixtures | 2,379 / 411 / 64 checks passed |
| Resource lifecycle | 8,038 checks, including 2,000 match transitions |
| Java 8 / 17 bootstrap stress, both profiles | 4,000 repeated calls per run under a 32 MiB heap; 12/12 isolated loaders collected in every run |
| Packaging privacy regressions | 36 passed; one host symlink-creation test skipped |

The final profiles contain 53 Lunar and 54 compatibility helpers. The new
Netty suite covers duplicate installs, bounded pending tasks, different
EventLoops, A/B/A transitions, external-handler ownership, spawn decoding,
forwarding and an observation held in progress during shutdown. It does not
start a game or connect to a server. Complete v20 results belong to the final
build and release manifests; source and embedded payload hashes are checked
during packaging.

The final standalone EXE was loaded into a fresh Lunar 1.8.9 process after the
user restarted the frozen game. The injector verified installed hooks and an
advancing heartbeat. The user confirmed that /config and normal movement work;
six read-only samples over 50 seconds showed a responsive window and advancing
heartbeat. The user then enabled Party Queue Detector and entered Prequeue.
The native installed flag was set and the Java status was
`install:ok:packet_handler`. Six further observer snapshots across 25 seconds
retained the same request/channel/handler, with no pending lifecycle task; the
spawn count advanced from 36 to 43. Another six heartbeat samples over 50
seconds remained responsive and advanced at every interval.

This verifies observer installation and packet observation, not the accuracy
of party grouping/display. The user subsequently reported that Party Detector
IDs does not appear to produce the same result as Badlion; that separate
functionality issue is under investigation and is not marked resolved by v20.

Offline tests use synthetic/loopback data. The live test driver performs no
manual API probes or party/report sends. Raw process diagnostics and user-local
settings are excluded from the release. No UI rendering code changed in v20,
so the real hidden OpenGL
result below remains explicitly historical v19 evidence rather than a new run.
v20 long-running gameplay and exit still require testing. Badlion and Vanilla
have offline verification only in this pass. The captured deadlock is addressed;
this is not proof that every possible client crash has been removed.

See `evidence/lunar-hang-v20.md` for the sanitized failure analysis. The original
2,493 Lunar native functions remain recovered NASM, not a complete C++ rewrite.

## Historical v19 cache, team and performance changes

- Urchin now uses the Seraph-style ten-minute success/empty cache and
  45-second failure cooldown. Expired content refreshes only at the next
  detected game entry. Selected old successful content remains readable during
  refresh. Completed content and valid in-flight requests survive world changes;
  a key change retires pending work while preserving completed content.
- Bot cache records keep value, provider, status and expiry together. Verified
  and explicit no-result responses use ten minutes; transport or account-
  verification failures use 45 seconds. Match/world transitions preserve them;
  a provider URL or feature-toggle change invalidates them. A late result can be
  cached after roster departure, and verified cache hits can announce once per
  eligible match without another request.
- Replay profile lookups cache verified identities and confirmed `[NICK]`
  results for ten minutes; failures use 45 seconds. Native Skin/Number caches
  already survive match transitions. Their original cache lifetimes remain;
  this release does not unify their TTL with the Java caches.
- A self Nick with a server UUID different from the local entity is associated
  only after a unique visible-name match. Team color decisions are positive and
  fixed for one match; unknown players can be recognized later. The local
  displayed nametag color, rather than launcher username, supports self Nick.
  Match/world/connection/local UUID/Replay transitions clear membership.
  Missing or ambiguous identity/color evidence is not guessed.
- Output delivery is gated by Ingame or Replay state and split into Player
  Data, Nick / Denick, Seraph / Urchin Tags and Anticheat categories. Tag
  output has independent self/team switches.
- Replay roster scans reuse bounded collections and reduce repeated regex work
  in name, UUID and formatting paths. Fresh negative Bot cache hits suppress
  duplicate lookup hints. These changes reduce repeated work, but no live FPS,
  latency or game-memory improvement is claimed from offline fixtures.

## v19 recorded offline checks

| Verification | Result |
| --- | --- |
| CTest injector, UI/cache, runtime selection and rejection | 5/5 passed |
| Native Lunar / compatibility bridge | 72 / 38 passed |
| Urchin cache / worker | 65 / 213 checks |
| Bot cache / Replay profiles | 2,379 / 138 checks |
| Output categories | 411 checks |
| Resource lifecycle | 8,038 checks, including 2,000 match transitions |
| UI interactions / module integration | 960 / 209 checks |
| Replay roster/Anticheat and self-Nick team fixtures | 81 / 25 / 64 checks |
| Real hidden OpenGL rendering | 644 checks, 7 panels, 3 languages, 70–140% |
| Java 8 / 17 bootstrap stress, both runtime profiles | 4,000 repeated calls per run under a 32 MiB heap; 12/12 isolated loaders collected in every run |
| Packaging privacy regressions | 36 passed, 1 host symlink-creation test skipped |

The final compiled payloads contain 52 Lunar and 53 compatibility helpers.
Self-Nick cases include confirmed numeric names and a server UUID different from
the local entity UUID; ambiguous numeric/health suffixes remain rejected. The
final build, hidden GL render and both bootstrap stress profiles completed on
September 30, 2026. The named compile dependency was restored from real vanilla
bytecode after a local Lunar update invalidated the old transformed snapshot's
private interface dependencies. No placeholder interfaces were used.

These results describe offline checks, including a hidden OpenGL test context;
they are not screenshots or timings from a running Lunar/Badlion client. This
pass did not perform live injection, API requests, party messages or report
commands. Final artifact sizes and hashes belong to the v19 release manifest.

The release targets Lunar, Badlion and Vanilla Minecraft 1.8.9 x64 without
Forge. The injector is C++20, helpers use Java 8 bytecode, and the original
2,493 Lunar native functions remain recovered NASM, not fully rewritten C++.

## Historical v18 repairs retained by v19

- Delete the ignored JNI Future local reference in the long-lived Lunar
  scheduler. Its Java task ownership and exception handling are preserved.
- During whole-process termination only, bypass recovered CRT thread
  destructors that can terminate on still-joinable worker objects. Explicit
  unload and all other DLL notifications retain the original entry behavior.
- Reuse bounded direct buffers for texture upload and GL state queries; release
  menu textures on close, world changes and explicit unload. GL cleanup stays
  on the client thread. Matrix cleanup is exception-safe.
- Stop optional API/Replay daemons, discard stale queued work, and serialize
  result publication with world/key transitions and unload. An in-flight HTTP
  call is bounded by existing deadlines, not guaranteed to cancel immediately.
- Bound estimated cross-match Urchin cache content to 4 MiB as well as 512
  entries. Complete current-match reasons remain separate.
- Prepare embedded helpers only when missing from the loader, reducing
  temporary bootstrap allocations without changing helper ownership.

The matching Windows failure occurs at UCRT abort's FAST_FAIL_FATAL_APP_EXIT
instruction. The supplied log shows shutdown and no OutOfMemoryError. The
thread-destructor chain is a concrete candidate for this abort; without a dump
its actual caller is unproven. The memory screenshot shows ample physical RAM,
not the game's individual memory allocations.

## Historical v18 final build checks

| Verification | Result |
| --- | --- |
| CTest injector, UI/cache, runtime selection and rejection | 5/5 passed |
| Native Lunar / compatibility bridge | 71 / 37 passed |
| Reembedding / Denicker suites | 29 / 15 passed |
| Ignored JNI reference stress | 100,000 calls; peak 1, final 0 handles |
| Process-exit entry guard | 18 cases per profile, ABI and real unwind passed |
| Resource lifetime, 48 MiB Java heap | 8,035 checks; 2,000 simulated match transitions |
| UI direct-memory lifecycle | 4,798 checks on Java 8 and 17; 12,000 frames |
| UI close/reopen | 20 cycles; 4,230 generated texture names deleted |
| Direct buffer pool during UI stress | 365,948 bytes; zero measured growth |
| Real hidden OpenGL rendering | 641 checks, 7 panels, 3 languages and scales |
| Production game tick / concurrent HUD unload | 26 / 21 checks |
| Final helper bootstrap, 32 MiB heap | Both profiles on Java 8/17; 12/12 loaders collected each |
| UI interactions / module integration | 707 / 209 checks |
| Language catalog / owned messages / header fixture | 1,327 / 146 / 340 checks |
| Mellow Scaffold / Anticheat core | 375 / 3,664 checks |
| Actual Anticheat adapter / Replay admission | 45 / 25 checks |
| Replay roster / profiles / API | 81 / 132 / 759 checks |
| API / party Output / category policy | 409 / 363 / 231 checks |
| Actual packet fixtures, Lunar / Vanilla | 110 / 112 checks |
| Privacy package suite | 37 run: 36 passed, 1 host symlink skip |

The v18 full build also checked native byte ledgers, checksums, PE exception tables,
Java mappings against installed jars, signed helper loading on Java 8 and 17,
Forge-free references, embedded DLL/icon/license resources and unchanged
production source hashes. Fixtures do not send game messages or reports.
OpenGL screenshots are generated by a hidden test context, not a live client.

## Live boundary and remaining scope

No v19 payload was injected into a game process during this pass. The v18 crash
investigation was also offline. Live Lunar/Badlion long sessions, match switching,
Replay and game exit have not been retested with v19. Offline stress and ABI checks cannot
prove the user's crash is resolved or establish real detection accuracy.

No live API probes were added. Earlier Hypixel checks returned HTTP 429 with a
long Retry-After; successful authenticated statistics retrieval remains
unverified. Mellow Scaffold Replay timing is intended to be checked at 1x.

Original native class-cache JNI globals and an explicit-unload world-global
lifetime gap remain audit boundaries. Ordinary world replacement releases
that world global. Java classes survive with their loader. Restart the game
before loading a new version; same-process replacement is not supported.

## Privacy and delivery

The usage archive contains exactly Adnin.exe, embedding both DLLs. The
corresponding source ZIP includes the full Mellow GPLv3 notice; the notice is
also embedded in the EXE. All provider keys and the personal Bot URL default
to empty. Source, raw binaries, helper classes and decoded embedded payloads
are scanned; local private comparison values pass only through memory stdin.
Personal settings and raw crash logs are excluded.

Current settings are read from the shared config.properties described above.
Legacy adnin-features.properties, toggles.json and language.txt are migration
inputs only. Opening a new EXE on the same PC does not erase the saved profile.

Current cache, Output and team details: evidence/output-cache-team-v19.md.
The v19 build and offline test artifacts are kept under work/replay-v19 in the
parent workspace. Historical v18 crash/resource details are in
evidence/stability-v18.md, with its test artifacts under work/replay-v18.
Earlier reports remain historical evidence and are not new live verification.
