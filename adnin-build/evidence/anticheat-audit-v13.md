# Anticheat v13 audit, Replay actors, and offline regression evidence

## Scope and source inventory

The audit used the supplied RavenBS-Plus-Plus source at commit
`14b0a03e8b3af4f109d7c05bc5d0b98d42470179`. The integrating task independently
checked the repository's official GitHub API HEAD and confirmed that it is the
same commit. This subtask inspected the pinned local source; it did not make
an additional network request.

There is exactly one Anticheat module under
`src/main/java/keystrokesmod/module/impl/other/Anticheat.java`. Its detection
registrations at lines 57-61 and five `performCheck` alert branches at lines
154, 158, 162, 176, and 189 cover the following complete inventory:

| Check | Preserved detection conditions |
| --- | --- |
| Autoblock | At least 10 distinct swing/block observations |
| NoFall | 5-40 block downward raw-server-position change; at most 10 blocks on either horizontal axis; recent packets and original terrain/flight/fluid/ladder exclusions |
| NoSlow | Exactly 11 sprint/use observations and horizontal speed at least 0.08 |
| Scaffold | Horizontal speed at least 0.07 for 20 observations, pitch at least 70, holding a block, swinging, 30 ticks since sneak, 20 ticks since vertical movement of magnitude at least 0.1, and four air blocks starting two blocks below |
| Legit scaffold | Three correlated swing/sneak cycles, pitch at least 70, and holding a block; retains the phase-independent v12 correction |

Whole-source searches found no additional detector class or extra Anticheat
registration. `AntiCheatFlagEvent` and its ScriptEvents consumer publish
notifications; they are not detectors. The separate movement/player modules
named NoSlow, NoFall, and Scaffold implement client features, not additional
Anticheat checks. No sixth check was available to port from this source/HEAD.

Owned production changes are limited to `AdninAnticheat.java` and
`AdninAnticheatCore.java`. Owned tests are the two existing Java tests and the
new `tests/test_anticheat_adapter.py`. Replay detection/roster construction,
diagnostic file writing, mapping, native changes, and the final build belong
to the integrating task. The v12 release artifacts and historical v12
evidence were not overwritten.

## Findings reproduced before changing the v12 core

`work/raven-v13/AnticheatV12AuditBaseline.java` was compiled against a saved copy
of the unmodified v12 core. Each report below is only a returned decision; the
fixture never sends one. With a 20-second interval, it demonstrated:

| Fixture | v12 | v13 |
| --- | --- | --- |
| Change only flag-sound setting, rebuild 10-tick evidence, report again after about 0.5 seconds | Repeated report | Suppressed |
| Replace entity object while retaining UUID | Repeated report | Suppressed |
| Remove then restore UUID in the current roster | Repeated report | Suppressed |
| Insert one NaN movement sample then rebuild evidence | Repeated report | Suppressed |
| Another check flags the same player 50 ms after a report | Duplicate automatic command | Local alert only |
| Future packet timestamp requests a NoFall scan | Rejected | Still rejected |
| Infinite terrain distance qualifies as NoFall | Accepted by the pure core | Rejected |

The invalid-terrain case was a core input-validation gap: the normal game
adapter already returns a finite bounded distance or its negative sentinel.
It is not represented here as a captured gameplay failure. Before-source
hashes and the before/after decision values are in the accompanying JSON files.

## Cooldown and time corrections

Short-lived player evidence is separate from UUID-keyed alert/report history.
Changing settings, the main-switch off/on cycle, entity replacement, temporary
roster absence, invalid samples, missed ticks, seeks, and error recovery retire
the evidence without erasing cooldowns. A genuine world replacement or
disconnect/shutdown clears the old world's history. Replacing the local player
or connection in the same world retires evidence and packet freshness while
preserving those histories.

Local alerts retain per-player/per-check cooldowns. Automatic WDR additionally
uses one per-UUID cooldown shared by all checks, using the same configured
interval. Interval zero remains allowed across successive game ticks, but
even then the adapter's local game-tick key allows at most one automatic report
per UUID in a sampled game tick. A suppressed automatic report does not remove
the manual WDR action from an otherwise eligible live alert.

History is bounded to 1,024 UUIDs with a 60-second TTL, matching the maximum
supported interval. Expiry is swept at most once per second. At capacity the
engine skips a new decision rather than evicting an unexpired history and
accidentally permitting another report. Entity evidence itself still follows
the bounded current-world scan and is removed with the roster.

The adapter uses positive elapsed milliseconds derived from a static
`System.nanoTime()` origin for both sampling and the Netty notification. It
does not mix wall time with elapsed time or assume raw nanoTime is positive.
Tests cover a negative raw origin and signed wrap in the subtraction. Packet
timestamps are cleared on world/connection/local-player/Replay context changes,
settings/evidence reset, disable, and disconnect. Missing, future, or older-than-
150-ms packet timestamps cannot authorize NoFall terrain scanning. Both the
current and preceding snapshots still need fresh inbound-packet evidence.

Non-positive sample time, non-finite movement coordinates, and non-finite pitch
retire evidence. Non-finite terrain distance cannot become a NoFall flag.
`resetEvidence()` provides an error-recovery entry that preserves histories;
the integrating GameModules error path uses it instead of full shutdown.

## Replay bot actors and identity policy

Replay actors are admitted by `AdninReplay.actorName(EntityPlayer)`, supplied by
the integrating Replay module. Its immutable identity-keyed snapshot matches
current, alive, non-self `EntityOtherPlayerMP` world actors to the current Replay
Tab roster using validated account-name information. It excludes viewer or
spectator rows and ambiguous duplicate actors. Entity UUID equality with the
skin GameProfile or the Tab UUID is not required. The Anticheat adapter only
uses a nonempty validated recorded name returned for that exact entity object.

The actual adapter regression includes an actor whose entity UUID, skin-profile
UUID/name, visible entity name, and Tab UUID all differ. The verified Replay
roster supplies its recorded name, and the production adapter flags the tenth
Autoblock observation. The same actor is rejected in ordinary multiplayer.
A normal v1/v4 Tab profile absent from the Replay identity snapshot is also
rejected while viewing Replay. Normal multiplayer's matching-Tab and normal-
profile gate is unchanged.

Name matching does not authenticate ownership of a historical account. Every
Replay alert has an empty report command, so neither a clickable WDR control
nor an automatic report is emitted. Atlas-only keeps its separate exact
`Suspect\u00a7r` and matching-current-Tab policy; generic Replay actor admission
does not silently expand that special mode.

## Replay seeks, pauses, and playback limits

In Replay only, an instantaneous change of at least five blocks on any client
movement axis or any raw server-position axis is treated as a discontinuity.
The core establishes a new actor baseline and retains alert/report cooldowns.
It also rejects a NoFall terrain-scan request for that discontinuity. Five
blocks is the existing minimum NoFall server-drop boundary, not a newly lowered
cheat threshold. Live detection does not use this additional Replay guard.

This has an explicit tradeoff: a Replay seek and a 5-40 block Replay NoFall
transition cannot be distinguished using the available snapshots. Those
Replay NoFall candidates are conservatively suppressed. Live NoFall remains
unchanged. Tests cover each client/server axis, large drops, post-seek
Autoblock recovery, complete Scaffold rebuilding, and three new Legit scaffold
cycles after a seek, with no reporting of historical players.

Repeated entity/local ticks do not create new evidence. Real missing ticks,
backward ticks, or sample gaps above 250 ms establish a new baseline. There is
no fabricated interpolation of missing recorded ticks. No reliable recorded
timeline, pause-state, or playback-rate API is available to this adapter.
Consequently, accelerated or slowed playback is not normalized to recording
time; raw observed ticks and movement remain the detection inputs. A server-
side pause that continues local ticks may not be distinguishable from ordinary
stationary actor behavior. These limits are not described as full playback-
rate support.

## Diagnostics and remaining heuristic boundaries

`diagnostics(Properties)` publishes English status and anonymous counts for
calls, sampled ticks, world/accepted actors, local-flag decisions, automatic-
report decisions, bounded-history size, and last sample age. It reads no game
objects and exposes no names, UUIDs, commands, API keys, or response data. The
integrating Features worker writes those fields to the existing local status
file. Decisions are counted as decisions; this is not a claim of server receipt.

The five detectors remain client-side heuristics. In particular, live NoFall's
packet timestamp records recent inbound activity, not per-entity proof that a
particular position was legitimate or malicious. An ordinary live teleport
that satisfies its original positional/environment conditions is still an
inherent ambiguity of that upstream check. Scaffold intentionally excludes
sneaking, jumping, slow, ground-supported, or differently pitched bridges.
No identity relaxation or lower numerical threshold was used to manufacture
coverage. Automatic reporting remains explicitly opt-in and defaults off.

## Executed offline validation

Compiled with Microsoft OpenJDK 17.0.20.101 targeting Java 8; all Java runs used
`-Xverify:all`.

```text
AdninAnticheatCoreTest: 3840 checks passed
AdninAnticheatSettingsTest: 83 checks passed
AdninAnticheatAdapterTest: 25 checks passed; production bytecode, offline game/roster/chat fixtures
```

The core runs without a Minecraft classpath. Settings tests use actual current
adapter/core classes with the normalized local runtime, but do not initialize
Minecraft. The adapter test runs the actual production-compiled adapter/core
bytecode with owned Minecraft, Replay-roster, and chat/audio-counter fixtures.
It verifies admission, the unchanged threshold, Replay report prohibition,
settings/main-switch cooldown retention, roster and entity replacement, error
recovery, real world replacement, same-world connection replacement,
disconnect, wrong-thread rejection, seek recovery, and duplicate ticks.

Run the additional production-adapter test with:

```text
python tests/test_anticheat_adapter.py --jdk <jdk-directory> --classes <compiled-java-runtime>
```

No game process, JNI payload, OpenGL context, audio device, network connection,
personal configuration, real chat output, or real report sender was used by
these checks. The integrating full build and any later live verification must
be reported separately.
