# v23 light-gray player pause

The current `build-v23-followup` extends the original stage recorded below:
cached-stat/tag producer guards, immediate delivery checks and conflict-aware
actor/Tab/scoreboard color admission close the respawn gaps. Replay also uses
the admitted actor's current name color. The bulk snapshot interval remains
250 ms. Current counts and build results are in `respawn-party-ping-v23.md`
and `../验证状态.md`; the smaller counts below are historical stage results.

The requested scope is all player detection and queries, not only team
recognition. An explicit light-gray name token (`§7`, RGB `0xAAAAAA`) pauses
new observations and work. A gray rank prefix alone is not a gray name. White
and dark gray remain allowed. This is a transient pause, not removal of the
player's earlier same-match identity or classification.

## Paths

- All five Anticheat checks share the admission gate, regardless of the
  Ignore Teammates option. Motion evidence is retired so a respawn gap does
  not become a detection. Alert/report cooldowns and teammate identities remain.
- Replay retains complete name aliases and current Tab mappings. Gray entries
  keep their last UUID/NICK/unknown presentation and cancel unstarted lookups.
  In-flight replies may populate cache; normal presentation resumes on recovery.
- Bot, Urchin and Skin preserve known identities/content while pausing new
  queries, classification and announcements for gray entries. Output checks
  the subject both when queued and immediately before a fragment is sent.
- A bounded immutable name/UUID/entity-ID snapshot is refreshed on the client
  thread at most four times a second. Native/Netty consumers read it without
  taking the Features monitor or traversing Minecraft objects.
- Party IDs omits newly observed identities already known to be gray, while
  forwarding the game's packets normally. Already accepted group history is
  not retracted when someone later respawns gray. Unknown spawn identities
  cannot be classified before the server supplies their current nametag.
- Native Tab-model query guards read the already computed name RGB scalar.
  Gray rows pause Number registration, new stats/tag/ping jobs and consumption
  of one-shot identity notifications. Cached statistics/identity reads, base
  rows and draw order remain. The guards allocate nothing and perform no JNI.

No process/thread priorities, keyboard/mouse polling, frame limit, game packet
delivery, game objects or renderer order are changed by this policy.

The external client-pump preflight shares the Features lifecycle monitor before
reading mutable provider state. This serializes snapshot publication with
shutdown; the three native/Netty snapshot guards remain lock-free. Shutdown
clears scratch containers. Skin delivers callbacks only after leaving its own
monitor, preserving the established Features-to-Skin lock order.

## Verification

Focused Java 8 and 17 fixtures pass: teammate helper 130, existing Anticheat
adapter 650, gray five-check policy 166, Replay roster 154, Replay-to-Anticheat
30 and paused Replay profile cache 203 checks per runtime. These are offline
fixtures. Final combined-build, native execution and packaging results are
recorded in `验证状态.md`; no fresh live Lunar/Badlion validation is claimed.

Additional Java 8/17 fixtures passed for Urchin scope (64), Skin Denicker
(2,290), Bot identity cache (2,379), packet accessors (66), registered Netty
pass-through/concurrency (443), Output categories (508) and resource lifecycle
(8,048). Output covers plain/JSON messages, three languages, subject retention
across fragments and color changes between admission and delivery. The lifecycle
fixture holds the Features monitor and proves that preflight waits safely while
the three snapshot readers can complete, including a pending shutdown.

Native fixtures cover five leaf guards per profile with 120 execution cases,
15 color-restoration cases and 10 unwind checks. Gray rows preserve existing
Nick/Denick and cached statistics, do not queue new work, and do not consume
one-shot notifications. Previously accepted Party ID history is not retracted.
