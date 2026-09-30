# v21 stability and performance review

Date: September 30, 2026.

## Scope and observed defect

The previous v20 repair addresses a captured client-task-queue / Netty
EventLoop deadlock. It remains in v21. Its production history and live-test
limits are documented separately in `lunar-hang-v20.md`.

With v20 loaded, the user enabled Party Detector IDs in Lunar's pre-game
waiting room. Packet observations and entity IDs were present, but grouping
appeared only after the user manually entered `/locraw`. Comparing recovered
native paths showed that Vanilla requests the mode after a world transition,
whereas Lunar clears the learned mode without scheduling that query. This
evidence supports fixing mode discovery; it does not require a second packet
or chat interception path.

The first relay-compatible candidate still rejected the user's visible footer.
A bounded read-only inspection of that exact loaded payload found the selected
sidebar in slot 1, with 11 visible rows and matching world/connection identities.
The public footer was split into team prefix `\u00a7ewww.hypixel.ne`, a score
entry consisting of U+1F382, and suffix `\u00a7et`. The entry is an internal
row identifier that the 1.8.9 font does not draw. Concatenating the raw strings
therefore failed the strict domain check even though the displayed footer was
`www.hypixel.net`. Query, Replay and Minecraft shared a defining classloader;
this was not a cross-loader access failure.

## Changes

- The Lunar-only client pump calls a small mode-query state machine. A ready
  Hypixel multiplayer context waits 500 ms before one `/locraw` attempt.
  A new world/connection or an explicit disabled-to-enabled transition creates
  a new opportunity. Attempts remain at least five seconds apart. Failed or
  busy sends are consumed for that cycle, and state-read failures back off for
  one second without creating a false enable edge. Shutdown is terminal.
  The helper retains only current weak world/connection identities, an address
  cache, and scalar state; it creates no worker or scheduled task. The existing
  Badlion/Vanilla native query is unchanged.
- Official Hypixel domains are accepted directly. A custom relay address needs
  the complete official Hypixel footer in the currently visible scoreboard.
  This uses the selected team sidebar, formatted prefix/suffix text, hidden-row
  filtering and Vanilla's visible-row clipping. Scope probes are limited to once per
  250 ms while enabled, ready and still awaiting a query; stable completed
  cycles do not keep scanning. Scope and context are checked again at send time.
  No user relay address is built in or read from personal API settings.
- Some Hypixel sidebar rows use a supplementary Unicode character as an
  invisible unique score entry between the team prefix and suffix. The relay
  gate can omit only that separate entry when it is exactly one valid UTF-16
  surrogate pair, the untouched prefix/suffix form the complete official
  domain, and the current font reports zero width for each surrogate unit.
  A missing font, visible custom glyph, ordinary/compound/malformed key or
  extra prefix/suffix text still fails the gate. Font checks occur only for
  such a complete-domain candidate; there is no global Unicode stripping.
- ClientSounds admits only one pending installation per session and clears
  its admission slot on completion, early return, submission rejection, and
  failure. Stop is idempotent and does not queue repeated cleanup. Pipeline
  mutations remain on their EventLoop, without a client-thread wait.
- ClientSounds reuses the active channel for the same manager and world while
  that channel remains open. Manager/world replacement, retirement, and closed
  channels still trigger discovery. Short-interval sound draining is retained.
- Replay observes the scoreboard at most once per 50 ms in a stable context.
  World, local-player, or connection replacement and disconnect invalidate the
  old state immediately. The monotonic clock keeps paused Replay responsive;
  actor roster refresh and the existing long-name/Nick admission rules remain.
  Ordinary/empty states share a read-only empty counter snapshot.
- Spawn packet decoding retains one immutable accessor table for its current
  packet class. Available and missing aliases are resolved once, preserving
  method/field ordering and invocation-failure fallbacks. Additional verified
  named/SRG aliases cover UUID, pitch, and held-item diagnostic fields. Native
  party grouping still receives the original entity ID. The cache does not
  grow with packets or retain a map of old classes.
- The panel header displays a muted `v21` label beside the red Adnin title and
  uses the existing scale, font, and animation transforms.
- A later requested Anticheat scope restriction permits sampling only when the
  shared native-Ingame/Replay predicate is true. The adapter checks this before
  configuration snapshots and team/actor traversal. Outside that scope it
  clears transient evidence, retained actor identities, sample tick, last-packet
  timing and current-sample diagnostics, without erasing same-world report
  cooldowns or cumulative diagnostic counters. World/disconnect cleanup still
  precedes the scope return. Anticheat packet interest follows the same lock-free
  predicate; the separate placement-sound switch and Replay maintenance continue.
  No detector algorithm, ignored-player policy, report option or Output switch
  is changed by this restriction.
  Native phase publication retains its existing 500 ms polling period, so this
  is not a zero-latency phase-change guarantee. World/connection changes still
  clear the prior sampling evidence before any newly permitted sampling.

## Review boundaries

The review also checked bounded feature/API request and result queues, Output
queues, Urchin and denick cache policy, Replay resolver queues, per-match team
identities, worker stop paths, UI resource release, and native unload guards.
No second independently proven permanent deadlock was found. The concrete
repeated-work and pending-task issues above were selected for this release.

ClientSounds pending-install limits are per session, not a promise that every
old stalled EventLoop across many connections shares one global slot. Urchin's
entry-weight budget does not include all current-match visible references;
those remain separately bounded by roster, tag-count and response limits.
The Anticheat settings snapshot still has small per-tick allocation overhead;
it was not rewritten as part of this change. No measured FPS or total-process
RAM reduction is claimed.

## Verification

All regression fixtures are offline or loopback, use synthetic identities,
and do not read user settings, send game messages, or probe APIs.

| Targeted regression | Result |
| --- | --- |
| Party query policy, Java 8 / 17 | 167 / 167 checks |
| Official/proxy visible sidebar scope, Java 8 / 17 | 48 / 48 checks |
| ClientSounds rules, lifecycle and channel reuse, Java 8 / 17 | 1,529 / 1,529 checks |
| Packet observer concurrency, Java 8 / 17 | 439 / 439 checks |
| Packet accessor cache and fallbacks, Java 8 / 17 | 63 / 63 checks |
| Replay detection and observation window, Java 8 / 17 | 2,100 / 2,100 checks |
| Production Replay roster and lifecycle | 98 checks |
| Replay-to-Anticheat integration / Anticheat adapter | 25 / 90 checks |
| Scope-gated game tick / HUD lifecycle / stop acknowledgement, Java 8 and 17 | 48 / 25 / 4 checks on each runtime |

The actual registered Netty blocked-EventLoop regression demonstrates that
3,000 ClientSounds installation attempts queue one task, while the pre-change
source queues 3,000 and fails the same controlled assertion. Repeated stop
queues one cleanup. A stable packet class resolves once for 10,000 reads;
the eight-thread 8,000-read fixture also resolves once. Missing aliases are
cached, dynamic invocation failures still fall through, and shutdown cannot
publish a late retained accessor table. The older v19 observer still fails the
controlled client-monitor deadlock regression and the test process exits.

Two thousand repeated Replay frame callbacks inside the observation window
scan the scoreboard once. Clock advance, paused observation, context changes,
disconnect, actor refresh after seeking and observation failures are covered.
The native declared ABI check remains strict, including the single approved
private synthetic lambda relocation; no compatibility rule was weakened.

The observed supplementary-row-key footer fails against the previous source
and passes against the correction. Negative fixtures cover missing font
evidence, either nonzero surrogate width, negative widths, visible and malformed
keys, altered prefix/suffix content and hidden/unselected objectives. The two
widths must each be zero; adding them cannot hide positive/negative cancellation.
Ordinary footers and unrelated domains do not call the font probe.

The additional Anticheat scope regression first fails against the previously
published adapter: a synthetic Lobby/Prequeue actor still generated an alert,
sound, report and party-output event. The correction passes on Java 8 and 17,
with 90 adapter checks, 84 settings checks and 3,664 core checks. Fixtures cover
zero inactive sampling, no retained actors or stale packet timing, fresh evidence
after same-world re-entry, preserved report cooldowns, Replay without native
Ingame, world/connection changes and disconnect/unload cleanup. A separate
packet-interest regression fails against the old GameModules bytecode, then
passes with independent placement sounds preserved in the lobby.

Final integrated build, bootstrap and packaging results are recorded in
`验证状态.md` before publication. Live testing of the final v21 binary is separate
from the v20 user observations above; no mixed-version injection is performed.

The `build-v21-proxy2` standalone EXE was verified in fresh Lunar and Badlion
1.8.9 processes before the later Anticheat scope request. Both loaded the
hash-matched payload, initialized their hooks,
opened the configuration menu and passed six responsive heartbeat samples over
50 seconds. Lunar's relay hostname failed the direct-domain check as expected;
the corrected visible-sidebar gate passed, the query was attempted and native
mode discovery reported party size four. Badlion's original path also reported
Prequeue, an installed active detector and party size four. Neither test used
manual `/locraw`. The user's normal Lunar exit returned process exit code zero.
Actual party-group chat output was not confirmed in the final Lunar test, and
Standalone Vanilla and Badlion exit remain outside this live verification.
Those observations belong to proxy2; the new scope-gated build's live status is
recorded separately in `验证状态.md` and must not inherit the older binary's result.
The scope-gated build subsequently passed a fresh Badlion lobby → Replay → lobby
test with Anticheat enabled throughout. Lobby samples did not advance detection
or delivery decisions, Replay sampling resumed with admitted actors, and the
return to the lobby stopped sampling and cleared current evidence/actors while
preserving cumulative counters. Anticheat packet observation followed scope;
independent placement sounds stayed enabled. The user confirmed the new menu
and movement worked. This last scope-only revision was not live-loaded in Lunar.

The distribution must still contain only the standalone EXE. Personal keys,
Bot URLs, local settings, raw logs and process-memory diagnostics are excluded.
Original native functions remain recovered NASM with targeted bridges, not a
complete C++ rewrite.
