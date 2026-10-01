# v23 follow-up: respawn output, Lunar Party IDs and Aurora Ping

Reviewed offline on October 1, 2026. This supersedes the earlier
`build-v23-gray-policy` candidate. No running game was injected or operated,
and no personal configuration or API credential was used in these checks.

## Reproduced defects and repairs

1. A native cached-stat notification could bypass query admission. Its player
   could also turn gray before the next 250 ms Java snapshot. The reported
   message shape was reproduced against the previously packaged Features
   bytecode: it returned zero and fell back to the native chat renderer.
   Both profiles now guard seven cached-stat/tag producer calls using the actual
   row's RGB, and Java rechecks the subject at delivery. Plain and JSON subject
   colors provide an additional fallback. Gray ranks, reasons and uniformly gray
   progress templates are not interpreted as the player's nametag color.
2. Current actor/Tab/scoreboard player-name tokens now treat explicit light gray
   as a pause even when a second source retains the previous red/team color.
   Default/unformatted text is not positive gray evidence. Replay reuses its
   existing actor loop to pause identity queries without deleting admitted
   identities. Same-player teammate, Nick/Denick, tag and statistics state is
   retained; completed requests can populate reusable caches without announcing
   or changing the frozen identity while gray.
3. The Lunar packet observer's decoder fallback was installed before decoding.
   It now installs after the decoder, while still preferring before the normal
   packet handler. All packets continue unchanged to the original consumer.
   A sent mode query now waits for a plain server response belonging to its
   installed observer and current world/connection. The window is 4.5 seconds;
   parsing is bounded to 1,024 characters, five unique string keys and recognized
   Bed Wars modes. Player chat, styled/sibling components, actionbar packets,
   unknown modes and stale tokens cannot complete it.
4. The Lunar Prequeue bridge obtains the verified mode on the client thread,
   then uses the original native parser, mutex and consumer. Known modes skip
   JNI entirely. Missing callbacks, exceptions and invalid values fall through
   to the original consumer with its arguments and return value preserved.
   Queries remain limited to three attempts with a five-second global interval
   and current waiting-room/domain-or-footer evidence. Leaving the scope retires
   the response even if the WorldClient is reused. Grouping remains an entity-ID
   heuristic, not authoritative party membership.
5. Aurora Ping incorrectly required the Hypixel API Proxy switch. With that
   switch off, it returned failure without attempting a fetch. Both profiles
   now call the public Aurora provider independently of Proxy and the Hypixel
   key. The provider URL, UUID handling, existing background worker and parser
   are preserved. Completed responses retain a ten-minute cache, failures a
   45-second retry eligibility interval. A no-data response can remain blank;
   it is not replaced with a fabricated ping.

## Performance and lifecycle boundaries

- Keep bulk player snapshots at 250 ms. Actual output/query boundaries resolve
  one subject using the bounded name/UUID/actor index; only a missing new entry
  uses a bounded Tab fallback. Workers and Netty readers use immutable snapshots.
- No new worker, game-thread networking, blocking wait, input hook, render-order
  or thread-priority change. Native gray guards are allocation-free leaf checks.
- Non-chat packets skip mode parsing; expired/consumed response windows stop it.
  The response is one weakly bound slot, not a growing queue. Sidebar probes
  remain at most four per second while enabled and eligible for waiting scope.
- Clear new indices with the existing lifecycle barrier. Preserve cross-match
  caches and already accepted Party observations through temporary gray states.

## Focused verification before the coherent build

Java 8 and Java 17 each passed: MatchTeams 138; Anticheat adapter 653;
all-five-check gray policy 486; Replay roster 156; Replay/Anticheat 30;
Features/Output/Skin/Urchin/resource regressions 14,518. The latter includes
48 direct respawn/output checks. Party policy 225, sidebar scope 94,
accessor/cache 66, actual registered Netty pipeline 498, and production query
adapter 58 checks across 22 scenarios passed on both runtimes.

Native tests execute only the new bridges in owned memory with mock callbacks;
they do not load the game DLL. They verify call-site lineage, stack/register
arguments, SSO mode strings, Windows unwind, null/exception paths and 1,000
known-mode iterations without additional JNI or parsing. Cached producer guards
cover both model-row locations and all original stack arguments.

The full build's authoritative outcomes and final artifact hashes are recorded
in `../验证状态.md` and the release manifest. Public Aurora checks produced
structured no-data responses, not a successful player measurement. Live Lunar
and Badlion display, respawn, party grouping and long-session performance still
require verification in a newly started game process.
