# v23 teammate cache and light-gray filter

This change keeps positive teammate identity decisions for the current match.
Names and UUIDs remain associated when a respawn temporarily changes the entity,
the player disappears from Tab, the entity tick counter restarts, or the entity
UUID is replaced by a replay/respawn wrapper. Match, world, connection, local
identity and Replay transitions still clear the cache.

Legacy color `§7` is treated as light gray and is never accepted as the local
team color or a teammate color. It cannot freeze a false team decision and it
cannot trigger a fallback to an older entity color when current server color is
light gray. A player already positively recognized as a teammate remains in the
match cache while briefly changing to light gray. White `§f` and dark gray `§8`
remain valid colors.

The Anticheat adapter continues to ignore cached teammates across alert, report,
sound and party-output paths. Sampling frequency and detector algorithms are
unchanged.

Offline verification passed on Java 8 and Java 17:

- Teammate helper: 130 checks per runtime, including shared nametag policy.
- Anticheat adapter with cached-team filtering: 650 checks per runtime.
- All-five-check gray admission, recovery and cooldowns: 166 checks per runtime.

Fixtures covered same-match cache retention, temporary spectator/respawn state,
Tab disappearance, entity UUID replacement, light-gray transitions, new-match
clearing and Replay boundaries. No game process or live input was accessed.
