# Replay identities and observed v12 failure

The existing Badlion 1.8.9 process was already running the released v12 compatibility
payload, verified by its DLL hash. No second DLL was injected. A read-only scalar
snapshot found master overlay, native eligibility, native Replay/Atlas recognition,
model readiness, context initialization and both historical installation flags all
equal to 1. The stable current-Tab vector contained 17 rows and game mode was Bedwars.
No settings, strings, heap payloads, API keys or URLs were read for that snapshot.

The user's current screenshot independently showed the actual Adnin custom table,
with configured headers and 17 name/HP rows, but blank statistics. This refuted an
overlay ownership/visibility explanation for that session. The dedicated native
Replay placeholder branch is documented in `replay-native-v13-audit.md`.

## Java identity separation

- `AdninReplay` observes the visible scoreboard on the client thread. Status now
  distinguishes no-world, no-sidebar, ordinary, active and fixed error categories.
- Every 250 ms during Replay it publishes immutable current-world actor identities
  and a bounded current-Tab statistics roster. Entity and GameProfile/Tab UUIDs need
  not agree. Ordinary gameplay retains the existing v1/v4 profile filter.
- Account names use the raw profile name or explicitly displayed account text;
  color/rank/team decorations are excluded. Viewer, spectator, anonymous Suspect
  roles and ambiguous mappings are excluded. Nearby loaded actors are required for
  detection, not for statistics on a valid current Tab entry.
- `nativeReplayProfile` only reads the roster/cache and queues bounded work. The
  background worker uses the existing exact-name/online-v4 Mojang verification;
  it has no API key and does not access a custom user URL. A nickname with no
  matching account cannot produce reliable historical statistics and remains blank.
- Native stats are contemporary account statistics returned by the configured
  service, not a reconstruction of historical values at the replay's recording time.
- No game profile is mutated. Departed aliases cannot access cached identities;
  old chat/prequeue names are not admitted. Cache success TTL is ten minutes,
  failed-lookup retry is 45 seconds, roster/pending limit is 256, cache limit is 512.
- Replay entry does not trigger an Urchin match batch, Denicker message or party
  Output event. Existing normal-match behavior is retained.

## Verification boundary

Production Replay bytecode is exercised with owned offline Minecraft/Tab/entity
fixtures, including three different UUIDs for one recorded actor, different raw
bot names linked through the displayed account, viewer exclusion, ambiguous names,
far-away Tab entries, Tab/world departure and return to ordinary gameplay. Separate
fixtures cover asynchronous-only requests, deduplication, TTLs, invalid UUIDs and
bounded queues. The tests never start a game, send chat or contact a real API.

Native bridges have independent JNI/register/unwind/cleanup tests. These checks
do not prove actual v13 in-game statistics or live Replay cheat alerts. A fresh
game process is required for live validation because v12 helpers remain loaded.
The original native slot-1 Replay marker and generic-mode limitations remain as
documented in the native audit. Five-block Replay jumps conservatively reset
detector evidence; see `anticheat-audit-v13.md` for the NoFall/seek ambiguity.
