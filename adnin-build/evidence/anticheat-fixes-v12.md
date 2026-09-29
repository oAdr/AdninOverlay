# Anticheat v12: phase, sampling, Replay policy, and enemy-option removal

## Scope and evidence limits

This change owns `src/java/AdninAnticheat.java`,
`src/java/AdninAnticheatCore.java`, and their two existing tests. It does not
start or inject a game, read personal settings, send chat or report commands,
play audio, or call a network API. All results below are offline fixtures and
source inspection. They do not establish that a particular real player is
cheating or that every Scaffold variant can be detected.

The pinned comparison source is RavenBS-Plus-Plus commit
`14b0a03e8b3af4f109d7c05bc5d0b98d42470179`, already supplied locally under
`work/raven-port/source/`. The prior port's provenance/license evidence is in
`evidence/raven-anticheat-v11.md`; that document describes the historical v11
behavior, including the now-removed enemy feature.

## What caused the reported gaps

### LegitScaffold depended on a single animation integer

Both pinned upstream `utility/PlayerData.java:54-65` and the v11 core counted
a new sneak transition only when `swingProgressInt == 1`. `updateSneak` records
the current state every update. If the transition arrives at phase 0, the
player is already recorded as sneaking by phase 1, so the counter resets.
This is an inherited phase limitation, not a newly introduced numeric threshold.

Before editing the core, `work/raven-v12/AnticheatV11Baseline.java` was compiled
against v11 and run with 40 synthetic snapshots. The original source hash and
results were preserved in `evidence/anticheat-phase-baseline-v12.json`.
The exact same fixture source was then compiled against the v12 core:

| Fixture | v11 alerts | v12 alerts |
| --- | ---: | ---: |
| Three or more repeated cycles, sneak begins at phase 1 | 1 | 1 |
| Same cycle pattern, sneak begins at phase 0 | 0 | 1 |
| Scaffold conditions, one observation per entity tick | 1 | 1 |
| Scaffold conditions, only every other entity tick observed | 0 | 0 |

The fixture uses a 60-second alert interval, a valid live-profile snapshot,
50-ms entity ticks, and only the tested check enabled. Legit cycles have six
swinging ticks and two idle ticks; the sneak edge starts at phase 0 or 1 and
is held through phase 3. Scaffold speed is exactly 0.07 with pitch 70, a held
block, and the supplied four-block-air predicate true. These are snapshots,
not a recording of the user's session.

### Scaffold was vulnerable to the integration's sampling schedule

Pinned `Raven.java:90-113` dispatches updates at Forge ClientTickEvent END.
The v11 port could instead be entered from frame/native/scheduler paths, and
its true in-game GUI tick hook was only installed when Session Stats was on.
A 50-ms scheduler is not phase-locked to every entity game tick. If it repeatedly
misses an entity tick, the core correctly restarts its evidence; the every-other
tick fixture demonstrates why such a stream cannot satisfy Scaffold.

The v12 integrating change is owned separately: Anticheat is called only from
`AdninIngameGui.updateTick -> AdninGameModules.gameTick`; ordinary render and
pump ticks maintain other features without competing to consume detector ticks.
The core still rejects duplicate ticks and still resets on missing/backward
ticks or sample gaps above 250 ms. It does not fabricate observations to make
a detector fire. Same-entity cooldowns still survive such evidence resets.

## Implemented detector changes

LegitScaffold now recognizes a fresh swing start or observed progress wrap.
It consumes exactly one fresh sneak rising edge within one entity tick before,
at, or after that start. The edge cannot be reused for another cycle, held
sneak is not a new edge, and a constant animation integer cannot invent cycles.
The player must still be looking down at least 70 degrees and holding a block.
The threshold remains three consecutive correlated cycles. An unmatched swing,
loss of the required pose/item, a fresh entity baseline, or missing/stalled
samples clears that evidence. A completed third cycle may alert once; later
animation phases do not repeatedly alert using the old count.

Scaffold's upstream conditions are unchanged: horizontal speed measured as
`max(abs(deltaX), abs(deltaZ)) >= 0.07` for at least 20 observed ticks; at least
30 ticks since sneaking and 20 ticks since vertical movement of magnitude 0.1
or more; currently swinging; pitch at least 70; held ItemBlock; and all four
blocks starting two blocks below the player must be air. Sneaking bridges,
jumping bridges, low/ground-supported bridges, slower movement, or other pitch
can legitimately remain outside this specific check. Thresholds were not
lowered to manufacture coverage.

## Replay admission and report policy

Live multiplayer keeps its matching current-Tab UUID and real-profile gate.
Only when `AdninReplay.isReplay()` is true may an additional actor be admitted:
it must already be in `world.playerEntities`, be alive and not the local player,
and have its own GameProfile UUID/name matching the entity. The name must be
an ASCII account-style name of 1-16 characters. Its UUID must have RFC variant 2
and version 1-5; this includes the version-3 offline UUIDs that the live v1/v4
gate excludes. Arbitrary world entities are never scanned as fallback actors.

The adapter clears detector state on a Replay/live transition. Replay snapshots
never generate a WDR command, including regular Tab profiles in the Replay
world, so neither clickable WDR nor automatic WDR is emitted there. Atlas-only
still requires its exact `Suspect\u00a7r` identity in current Tab; the Replay
fallback does not weaken that policy. Replay recognition itself is separately
implemented and tested by the integrating Replay module.

## Complete enemy-option removal

The public adapter/core `addEnemies` fields, settings signature bit, loader,
runtime set, query APIs, alert result field, and `[Enemy]` suffix were removed.
There is no dormant runtime branch waiting for an old property to re-enable it.
`saveSettings` removes `anticheat.addEnemies` from a supplied Properties object
and writes only the 12 remaining settings. Loading an old saved true value has
no effect. Tests cover both a fresh saved object and reusing the old object,
and verify unrelated properties survive. Reflection checks assert the removed
fields/APIs are absent. The GUI row and integration-test expectation are owned
by the concurrent GUI/integration work.

The production Java source search after the changes found only one occurrence
of `addEnemies`: the explicit `Properties.remove` migration. Other occurrences
are negative regression assertions. The upstream source snapshot and historical
evidence are intentionally not rewritten.

## Offline validation performed

Compiler: local Microsoft OpenJDK 17.0.20.101, with `--release 8 -encoding UTF-8
-g:none -proc:none -implicit:none`. Tests ran with `java -Xverify:all`.

```text
AdninAnticheatCoreTest: 2419 checks passed
AdninAnticheatSettingsTest: 56 checks passed
```

The core and the unchanged before/after fixture run with only their scratch
class directory on the classpath. No Minecraft class is required by them.
The adapter/settings test compiled the actual current Anticheat and Replay
sources against the existing normalized local Lunar runtime and parent helper
classes, without stubs. It loads chat-component value types for name-color
fixtures but does not initialize Minecraft or create a world.

Coverage includes initial phases -1/0/1/2, sneak timing -1/0/+1 tick, early and
late edges, constant phases, held sneak, continuous swing progress wrap, unmatched
cycles, posture/item loss, missing/stalled samples, duplicate callbacks, all
existing detector thresholds and cooldown boundaries, strict live/Replay/Atlas
admission, Replay WDR suppression, and old-property retirement. No new test class
is needed by the builder; it should run the same two existing test entrypoints.

Machine-readable hashes and result counts are in
`evidence/anticheat-tests-v12.json`. Full native packaging and gameplay behavior
remain the integrating build's responsibility; the results here are not a claim
of live injection or gameplay validation.
