# v22 lobby, Eagle and crash-diagnostic follow-up

Reviewed on September 30, 2026. This follow-up retains the v22 version label.
The original published v22 files were archived before replacement. Earlier
live observations do not establish the behavior of this new executable.

## Lobby queries and Urchin request ownership

The old Lunar query adapter sent `/locraw` in an owned main-lobby fixture.
The new adapter requires a current Bed Wars waiting/countdown sidebar and
server evidence, then rechecks the scene immediately before sending. Official
and relay addresses share that phase requirement. Missing/hidden evidence
declines the automatic request. Existing delay, rate limit, weak references
and shutdown remain; user-issued commands and already-transmitted responses
are not blanket-suppressed. Badlion/Vanilla's existing native mode discovery
and response parser remain intact.

A delayed match-start notification held behind /config previously captured
the lobby roster after leaving a game. Urchin admission now requires the
current active native match. Queue removal and paced-job registration are
atomic under the existing Features monitor. Unstarted jobs are retired on
leave, world/key change and shutdown; only the exact registered owner can
claim an HTTP request. An old worker cannot cancel a new match's reservation.
Already-claimed, same-key results retain cross-match cache eligibility.
No sleep or network operation runs under that monitor.

Targeted Java 8/17 verification passed query policy 185, scoreboard scope 94,
query adapter 32 across 18 scenarios, Urchin scope 40, worker 213, Feature
policy 189, Bot cache 2,379, Output categories 418 and lifecycle 8,039 checks.
The lifecycle suite includes 2,000 match changes. Baseline fixtures reproduce
both original scope defects without a game, real HTTP or chat sends.

## Mellow Eagle under the existing Legit Scaffold switch

The reviewed source is Roxiun/Mellow commit
`17ef9b7466754a33ee8c8ed87fa7ea717573d775`. The public pinned EagleCheck,
AnticheatListener and AnticheatManager files were downloaded again and matched
the local reference after line-ending normalization. EagleCheck SHA-256 is
`209059b99e57095ab832decb000923d6740ddb964771a75b6413cfdca59ffa42`.

The old Legit Scaffold rule is replaced by Eagle's short crouch/release and
swing association, rather than running both algorithms. The port retains
the 1–2 tick duration, release tick or following-tick swing, 15 tick pattern
window, latest-three-duration variance, pitch/movement/timing/consistency
and consecutive weights, and default accumulated VL threshold of 10. Adnin's
existing alert/report interval and Output routing remain. The independent
Scaffold tower/horizontal calculation is unchanged.

Conservative integration changes are explicit: a release contributes once,
only inside its fresh association window; a player first observed mid-action
does not gain an invented edge; stationary movement earns no backwards-angle
bonus. Discontinuous samples, changed actors, Replay seeks and invalid yaw
retire evidence. The upstream instant-sequence branch is redundant under
monotonic ticks and is not turned into a separate new trigger. History is a
fixed three-integer array, allocated only when this check is used. No network,
terrain scan, Forge dependency or additional thread is introduced.

The adapter now supplies real yaw when Legit Scaffold alone is enabled.
Normal Tab/Nick and admitted Replay actor filters, self/teammate exclusions,
inactive-context suppression, report cooldowns and Replay's WDR prohibition
are preserved. Targeted Java 8/17 suites passed Eagle 232, Core 3,464,
unchanged Scaffold 375, adapter 138 and settings 112 checks. Old core and
adapter bytecode fail the appropriate new negative/yaw fixtures. GPL notices
and corresponding source accompany the executable/source release.

These are correctness and workload tests, not measurements of detection
accuracy against real players or proof of improved live frame times.

## Allocation review

The adapter borrows one reusable Snapshot for a client tick, clearing the
cache slot while it is in use. A same-thread reentrant call therefore cannot
overwrite the active caller's buffer. Every actor fills all ordinary fields
and resets conditional/default fields. Finally clears the player-name string;
a generation check prevents shutdown from being undone by a late return.
The core copies values rather than retaining this mutable sample. Sampling
frequency and the complete actor traversal remain unchanged.

The team helper also skips formatting/spectator transformations when their
required marker characters are absent. Against the old helper bytecode,
200,000 randomized formatting/name cases matched on each of Java 8 and 17.
Final targeted suites passed adapter 644, teams 90 and settings 114 checks
on each runtime, including conditional-field poisoning, actor/config changes,
reentrancy, exception cleanup and shutdown during a borrowed sample.

Java 17 ThreadMXBean measurements used 96 owned actors and 3,000 ticks:

| Fixture | Prior allocated bytes | Revised allocated bytes |
| --- | ---: | ---: |
| Team evidence established | 450,960,048 | 278,799,792 |
| Team evidence not established | 73,143,304 | 27,957,096 |
| 200,000 standalone name-parser pairs | 225,200,048 | 174,584,688 |

A separately instrumented temporary core copy counted 288,000 Snapshot
constructions before the change and zero after warm-up with reuse. That
instrumentation is not in the production core. These are allocation-workload
measurements, not a claim that total game memory or frame time drops by the
same percentage. No user JVM options, heap limit or game settings were changed.

## Automatic crash diagnostics

After successful runtime verification, the standalone injector starts a hidden
worker from the same executable. It inherits only a restricted target-process
handle and a read-only context mapping through an explicit handle whitelist.
The context contains validated profile/build identity and selected local log
paths, not the game's command line or account parameters. The worker checks
both PID and process creation time, then waits on the process handle without
polling. Closing the injector window does not end monitoring. No exception
handler, debug attach, memory dump, suspension or game-thread hook is added.

An abnormal exit produces an `Adnin-crash-*.log` file on the system Desktop,
including redirected Desktop locations. A zero exit without recent PID-matched
JVM fatal evidence produces no report. Abnormal exit alone is labelled as a
cause-unconfirmed observation, not proof that Adnin caused it. Stable process
creation/exit identity and create-new output prevent delayed duplicate workers
from producing multiple reports for the same event or overwriting a report.

Fatal-log lookup probes exact PID filenames before bounded template searches.
Only recent, matching fatal evidence contributes JVM excerpts. For nonzero
exits, a recent crash report in the captured game directory may contribute an
allowlisted candidate summary, explicitly without confirmed PID attribution.
It cannot turn an otherwise clean zero exit into a crash. Missing/unreadable
logs still permit a basic exit/build diagnostic report.

Exported content is size-limited and allowlisted: recognized crash conditions,
safe source basenames/stack frames and selected native module offsets. Raw chat,
command lines, account/session data, properties, credentials, personal URLs,
environment dumps and memory contents are not copied. The feature sends no
telemetry and does not upload reports to a service. Report writing follows the
user's configured Desktop location. System-wide shutdown or termination of
the monitor itself can prevent an export; the feature is not an OS crash logger.

The native module passed warning-as-error Release compilation and 256 checks
using owned subprocesses and temporary logs. Coverage includes parent-injector
exit before target exit, normal/abnormal/259 codes, creation-time mismatch,
late duplicate workers, crowded log directories, stale/wrong PID evidence,
source-path redaction, size limits and unconfirmed Java crash-report candidates.
UNC Desktop syntax is verified without contacting a network share. A second
read-only review checked handle ownership, privacy and export boundaries.
No real game process was crashed or terminated for these tests.

The first full integration run exposed a real long-path failure that was not
present in the shorter standalone test directory. A controlled Win32 comparison
reproduced failure for the ordinary 306-character filename and success for the
same validated path using the internal extended form. The production fix
converts only already-authorized paths for native file calls; it does not admit
arbitrary device paths or UNC log inputs. The original deep-build scenario and
new 220-character output/281-character input-directory tests pass without an
increased timeout. The initial failure and subsequent evidence were preserved.

## Final integrated build

`build-v22-lobby-eagle-crash` passed the complete unchanged-source build and
all six CTests, native bridge/reembedding/compatibility checks and Java suites.
Both final bootstrap profiles passed 4,000 calls and 12/12 classloader collection
under a 32 MiB heap on Java 8 and Java 17. The package privacy regression passed
36 tests with one host symlink skip. Refer to `验证状态.md` for exact artifact
hashes. This candidate has no fresh-client live validation yet; the running
older v22 binary must not be confused with this build. No live FPS or long-session
stability guarantee is claimed.
