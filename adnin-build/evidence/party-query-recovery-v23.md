# v23 Lunar Party Detector IDs query recovery

Reviewed on October 1, 2026. This follow-up is included in the v23 package.

## Reproduced defect

The Lunar query policy consumed its one opportunity before invoking the live
sender. The live sender independently checks the current player, world,
connection and visible waiting-room sidebar. If those changed between the
cached 250 ms scope probe and the immediate send, it correctly declined the
command, but the policy never recovered in that same world. A player missing
at the deadline and a transient state-read or sender exception caused the
same permanent loss until a world change or manual disable/enable.

The new adapter scenarios reproduce the missing-player, changed-sidebar and
sender-exception defects against the previous `build-v22-input-perf` production
bytecode. Each fails on the expected subsequent recovery assertion. The first
attempt is still correctly rejected in those fixtures.

## Repair and limits

- A successful send completes the current world/connection/enable cycle.
- A declined or failed send may retry, with at most three attempts in that
  cycle and at least five seconds between actual attempts. The rate limit
  remains global across world changes and explicit enables.
- Every retry discards stale scope authorization, requires current Hypixel
  domain or visible official-footer evidence plus a Bed Wars waiting room,
  and starts a fresh 500 ms readiness delay. The live sender still rechecks
  the current scene immediately before sending.
- Missing-player/read failures have transmitted nothing: they cancel the
  old deadline and allow fresh evidence after recovery. State-read failures
  retain the existing one-second backoff. They cannot reopen a cycle that
  has already sent successfully or exhausted its retry budget.
- Shutdown is terminal. The helper adds no threads, futures, blocking waits,
  game-input changes or render-priority changes.

The existing native mode parser, Prequeue gate, spawn observer and consecutive
entity-ID grouping algorithm are unchanged. Prior source/normalized-instruction
analysis already established that the grouping and mode parser are equivalent
between Lunar and the compatibility profile; this patch repairs the Java
query opportunity lost before that response path. It does not infer a party
size from player count or replace the real server-mode response. The helper
remains called only by Lunar's client pump; Badlion/Vanilla keeps its native
mode-query path.

These failures were proven in owned offline fixtures. A fresh live Lunar
session has not been injected or exercised for this repair, so it is not
claimed that this is the only cause of every missing party notification.
Consecutive entity IDs remain a heuristic and do not guarantee that every
inferred group is a real party.

## Targeted verification

Java 8 and Java 17 each passed:

| Suite | Checks |
| --- | ---: |
| Query policy, including delay, rate limit, retry budget and weak lifecycle | 197 |
| Real scoreboard sidebar selection, visibility and official-footer scope | 94 |
| Production adapter with owned game fixtures | 41 across 20 scenarios |

The adapter covers due-time missing/restored player, removed/restored sidebar,
live revalidation decline, sender exception recovery, official/relay lobbies,
active games, Replay, disconnects, explicit disable, foreign servers and
terminal shutdown. The policy additionally proves exact five-second retry
boundaries, exhaustion after three failures, no repeated send after success,
and recovery from read failures without inventing an enable transition.

The standalone helper was compiled for Java 8 using the real local runtime
classpath. These focused results precede the parent task's coherent release
build and packaging checks; the release manifest and main verification record
are authoritative for the final published artifact.
