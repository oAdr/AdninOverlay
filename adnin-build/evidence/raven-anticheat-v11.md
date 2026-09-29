# Raven AntiCheat Java port: implementation and offline evidence

## Scope and provenance

The implementation adapts `Anticheat.java`, `PlayerData.java`, and the required
terrain/team helpers from the MIT-licensed RavenBS-Plus-Plus source snapshot
`14b0a03e8b3af4f109d7c05bc5d0b98d42470179` supplied under
`work/raven-port/source/RavenBS-Plus-Plus-14b0a03e8b3af4f109d7c05bc5d0b98d42470179`.
The integrating build owns inclusion of the upstream MIT license notice.

Owned production files:

- `src/java/AdninAnticheat.java`: Minecraft adapter, settings, local alerts.
- `src/java/AdninAnticheatCore.java`: pure snapshot/state detector and alert policy.

Owned regression files:

- `tests/java/AdninAnticheatCoreTest.java`
- `tests/java/AdninAnticheatSettingsTest.java`

No GUI, feature integration, builder, native binary, personal configuration,
or known-nick database was edited by this implementation task. No game process
was loaded or injected, no HTTP request was made, and no real chat/report command
was sent by these tests.

## Adapter contract

Public static methods are `tick(Minecraft)`, `shutdown()`, `packetReceived()`,
`loadSettings(Properties)`, and `saveSettings(Properties)`. The packet callback
only records `System.currentTimeMillis()` in a volatile timestamp; it accesses
no Minecraft objects and sends nothing. All game operations occur in `tick`,
guarded by Minecraft's client-thread check. Additional `isEnemy(String)` and
`enemyNames()` return the local world-session enemy decisions.

Public settings default to:

| Field | Default |
| --- | --- |
| enabled | false |
| flagSound | true |
| autoBlock, noFall, noSlow, scaffold, legitScaffold | true |
| ignoreTeammates, atlasOnly, addEnemies, autoReport | false |
| intervalSeconds | 20, clamped to 0–60 |
| ignoredPlayers | empty, comma-separated names |

Each property uses `anticheat.` followed by the exact public field name. Save
preserves unrelated properties. Invalid boolean values fall back to the field's
default; invalid interval text falls back to 20. Ignored names are ASCII account
names of 1–16 characters, normalized case-insensitively and deduplicated, with a
4096-character/128-name input bound. Settings signatures and normalization are
cached within the per-tick Settings snapshot rather than reparsed per player.

## Behavior and conservative changes

The five checks retain Raven's priority and thresholds: Autoblock at ten
swing/block samples; Legit scaffold at three matching sneak/place transitions;
NoSlow at exactly eleven sprint/use samples with horizontal speed at least
0.08; Scaffold with speed streak at least twenty ticks, pitch at least 70,
thirty ticks since sneaking, twenty ticks since significant vertical movement,
and air at the four tested blocks; NoFall with a 5–40 block server-position drop,
at most ten blocks of horizontal change, recent packets, and Raven's terrain,
fluid, flight and ladder exclusions.

Intentional corrections/adaptations:

- Raw server coordinates use `/ 32.0`, avoiding integer truncation.
- The first sample can establish evidence but cannot flag NoFall.
- Repeated render/native/HUD callbacks for the same entity `ticksExisted` do not
  advance counters or issue another alert/report.
- Missing, backward, or more than 250-ms-separated samples restart streaks and
  server-position baselines. The same entity retains per-check alert timestamps,
  so interrupted sampling cannot bypass the alert cooldown.
- New entity identity, world/local-player replacement, disconnect, shutdown,
  or changed settings starts fresh state. Removed roster members are evicted.
- Both the current and preceding NoFall samples need inbound-packet freshness
  of at most 150 ms. Packet timestamp zero, future timestamps, and the first fresh
  sample after a stale sample do not authorize a NoFall alert.
- Normal players must have a current Tab UUID/profile and pass
  `AdninFeatures.isRealTabProfile`. Atlas-only mode permits the exact
  `Suspect\u00a7r` replay identity, still requiring current Tab presence. This
  special identity never receives a WDR command or an enemy addition.
- The exact player-name color is used for the teammate fallback; rank colors,
  partial name matches, and uncolored prefixes do not establish a team.
- NoFall terrain scans occur only after the pure engine identifies a possible
  drop. Stationary players do not cause two downward terrain scans per tick.
  Unloaded or out-of-range terrain suppresses detection.
- World-independent Forge events and Raven module dependencies are omitted.

Alerts use English local `addChatMessage` output and preserve the entity's
formatted nametag. A validated ASCII account name enables a clickable WDR
component. Optional automatic reporting defaults off and emits one command
only when an alert clears its per-player/per-check cooldown; there is no queued
or retrying report sender. The exact interval deadline is inclusive. Clock
rollback cannot bypass a recorded alert timestamp. Ping sound is shared across
players and limited to once per 1500 ms. First enemy addition is visible in
the local flag as `[Enemy]`; repeated flags do not repeat the addition marker.

## Offline validation

Compiled the two production files and the two tests against the existing
normalized local Lunar classpath plus the v10 helper classes using JDK
21.0.12.1 with `--release 8 -encoding UTF-8 -g:none -proc:none -implicit:none`.
The only compiler diagnostics were JDK's standard obsolete Java-8 target
warnings. Tests ran under `-Xverify:all`:

```text
AdninAnticheatCoreTest: 1818 checks passed
AdninAnticheatSettingsTest: 21 checks passed
```

The core test runs with only the scratch class directory on its classpath,
so no Minecraft class or dependency is required for the 1818 core assertions.
The settings test loads chat-component value types for five nametag-color
cases, but never initializes Minecraft or opens a world.

Coverage includes all five thresholds, 1000 repeated render frames, streak
resets, entity replacement, roster removal, disable/reenable, check toggles,
NoFall first sample/fractional coordinates/packet loss/time boundaries, terrain
exclusions, current-Tab and Atlas policies, friend/team exemptions, validated
WDR decisions, per-player and per-check cooldown independence, exact cooldown
deadline, cooldown preservation across missed ticks/stalls/clock rollback,
sound limits, enemy feedback, property defaults/round trips/malformed inputs,
and exact name-color matching.

Scratch compilation and logs are in `work/raven-port/ac-tests/`.
Full dual-profile remapping, packaging, runtime linkage and live game testing
belong to the integrating task; this report does not claim those have passed.

## Source hashes at handoff

| File | SHA-256 |
| --- | --- |
| AdninAnticheat.java | `81506dda53ed806b61ff8dfdb6dd232463315290e82048f47be0b0ef764d4418` |
| AdninAnticheatCore.java | `2a10e2ebb7fcb14977094a0a40bf27cca40eeab89283e16c837833e38a0f1f63` |
| AdninAnticheatCoreTest.java | `093199dd202a4ac2cce9f31e1c0c437ba01b613112c635a8c7c514e19cd014be` |
| AdninAnticheatSettingsTest.java | `ac8c9d47501eb3ac05182da6a9bf7f37b384c6b2921d2475634f81b17196a165` |
