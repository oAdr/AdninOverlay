# v19 output, cache and team identity evidence

This note describes the v19 implementation and offline verification boundary.
It does not establish live Lunar, Badlion or Vanilla behavior. The latest
completed test counts are recorded in `验证状态.md`; final sizes and hashes are
recorded in the release manifest.

## Cross-match cache behavior

| Result source | Fresh content | Failure cooldown | Transition behavior |
| --- | --- | --- | --- |
| Urchin | Tagged and empty successes: 10 minutes | 45 seconds | Completed content and valid in-flight requests survive worlds/matches; refresh admission remains game-entry-only |
| Bot Denicker | Verified matches and explicit no-result responses: 10 minutes | Transport/account-verification failures: 45 seconds | Match/world changes retain entries; URL or feature-toggle changes invalidate them |
| Replay profile lookup | Verified accounts and confirmed absent-account `NICK`: 10 minutes | 45 seconds | Session cache survives roster/match transitions; only current eligible identities can use it |
| Native Skin / Number Denicker | Original native cache behavior | Original native behavior | Existing identity caches already span matches; v19 does not assign them the Java TTL policy |

Urchin keeps up to 512 identities and an estimated 4 MiB of cross-match content.
UUID case/hyphen variants share a cache identity, as do case variants of lookup
names. A game-entry snapshot selects one roster batch. Fresh cache hits do not
make requests; expired or missing entries request only if the identity has no
valid in-flight request. A current match can continue displaying its previously
selected successful content while a refresh is pending. Tab rendering, opening
the menu and periodic ticks do not create a fresh request batch.

A world change clears the visible match selection without throwing away valid
in-flight Urchin work. Changing or clearing the Urchin key retires reservations,
queued work and the current selection but retains completed response content.
Obsolete in-flight completions are rejected; new requests use the current key.
The in-memory cache is cleared at explicit shutdown or game-process restart.

Each Bot entry keeps its profile, provider, status and expiry together. The
cache is bounded to 512 entries, and its provider binding prevents reuse after
the user changes the Bot URL. A completed result for a player who left the
current roster can be retained without output. When a verified cached identity
is eligible in a later match, it can announce once for that match without
another HTTP request. Cached failures and no-result responses prevent duplicate
lookup hints during their cooldown. Only verified positive results can enter
party Output; failures and no-result messages stay local.

The native Skin and Number paths continue using their existing identity maps.
This release preserves their cross-match lifetime and routes their successful
messages to Nick / Denick Output. It does not replace those native caches with
the Java cache or claim a common ten-minute TTL. Applying any cached resolution
still requires a current eligible Tab row, including a confirmed Replay Nick
mapping where appropriate. Resolved statistics accounts never replace the
recorded actor's live GameProfile or entity UUID.

## Self Nick and teammate identity

The shared team helper reads the actual local displayed nametag color and the
current Tab/world evidence; it does not rely on the launcher account name.
Local entity UUID, profile/name aliases and the corresponding Tab identity are
recorded as self. If a server Nick has a different Tab UUID, it is linked only
when the visible name has one exact matching Tab profile. Ambiguous matches
are not guessed.

Confirmed numeric profiles are matched before number-suffix filtering. A
numeric self Nick with a different server UUID additionally requires one
unambiguous full Tab-name match in the local display. A trailing health value
cannot replace an earlier player-name token, and displays matching multiple
Tab accounts are rejected instead of guessing.

Players with the same established name color can become teammates. Once
confirmed, their UUID/name aliases remain teammates for the rest of that match;
later display-color changes do not revoke the decision. Previously unknown
players can still be recognized as they appear. Match, world, connection, local
UUID and Replay transitions reset the set. Spectators/viewers are excluded;
missing, explicit-reset, obfuscated or ambiguous color evidence does not create
a team match. Entity display evidence has priority, with a suitable Tab color
fallback when the entity provides no explicit color.

Using a server Nick is therefore supported by the identity path rather than
treated as a reason to disable teammate recognition. Offline fixtures cover
local Nick names and different server UUIDs, alias preservation, transitions,
spectator rejection and color ambiguity. These fixtures do not substitute for
a live server test with the user's current client.

## Output categories and context

Output has four switches: Player Data, Nick / Denick, Seraph / Urchin Tags, and
Anticheat. All four default off. Nick notices and successful Bot, Skin and
Number results belong to Nick / Denick; native API errors remain local-only.
Tags adds Include Self and Include Teammates, initially on, with values retained
while the parent switch is off. The shared match identity helper supplies
self/team classification for those filters and Anticheat.

Both enqueue and delivery require the native `Ingame` state or an active Replay
detector. Leaving that context discards queued party events. Disabling a
category retires its pending events, and obsolete provider/context work cannot
send after a transition. Messages still use `/pc`, preserve the existing
packet-size-aware splitting and deduplication, and omit only a leading local
`[Adnin]` brand. The four switches do not automatically send real chat during
offline tests.

## Performance and verification boundary

The team and roster paths reuse bounded scratch collections and reduce repeated
regex work in hot name/UUID/formatting checks. Team decisions are cached for the
match, and fresh negative Bot results avoid recurring lookup hints. Urchin
in-flight deduplication and cross-match content reduce duplicate HTTP work.
Bounded queues and caches, shutdown guards and resource retirement remain in
place. This is a description of reduced repeated work, not a benchmarked live
FPS, latency or memory improvement.

The build uses synthetic identities and fake HTTP/packet fixtures. Its OpenGL
checks use a hidden test context. This v19 pass performs no live client
injection, API probe, party message or report command. Personal settings, API
keys, private Bot URLs, raw logs and screenshots are excluded from delivery.
The source archive and embedded notices retain the Mellow Scaffold GPLv3
notice. The 2,493 recovered Lunar native functions remain NASM; these feature
changes do not constitute a complete C++ rewrite.

The final compile dependency was regenerated from a locally installed real
vanilla 1.8.9 JAR using the complete named mapping. This avoids mixing an old
transformed Lunar snapshot with newly updated private Lunar interfaces. All
2,507 classes passed structural checks; 28,577 declarations and 57,765 internal
member references matched the inverse mapping, and repeated generation produced
the same SHA-256. No placeholder game interfaces were substituted. Game JARs
remain local build inputs and are not part of the release archives.
