# Native Tab eligibility audit — v7 source evidence

This is development evidence for the source package, not usage-package material.
The investigation is read-only: no new native roster hook was added, and no game
injection, API request, or private configuration access was used for this audit.

## Finding and implementation consequence

The shared Java `AdninFeatures.isRealTabProfile(name, uuid)` predicate is more
restrictive than the verified original native primary Tab-row predicate. Both
Java consumers enumerate `getPlayerInfoMap()`, skip null entries/profiles, require
an ASCII Minecraft-style name of 1–16 letters/digits/underscores, and accept only
nonnull UUID v1/v4 profiles. `scanPlayers` applies this before updating `present`;
`beginUrchinMatch` applies it before freezing the Urchin roster. Cached chat
announcements additionally require current membership in that filtered `present`.
Periodic scans do not create new Urchin match batches.

The original primary native loop skips UUID v2 only when its mode byte is zero.
No additional name, spectator, texture, entity, or independent human-player
predicate was recovered before appending a primary row. JNI failures can abort
collection; they are not evidence of a stronger bot discriminator. Therefore an
extra native callback would add coupling without a demonstrated filtering gain.

## Verified original addresses

All addresses below are RVAs relative to original image base `0x180000000`.
The corresponding absolute VAs appear in `src/native/ChatReaderLunar.asm` comments.

| RVA | Verified operation |
| --- | --- |
| `0x83fd0` | Primary Tab/model-building function (`FUN_180083fd0`). |
| `0x85428` → `0xc210` | Initial iterator `hasNext`. |
| `0x8546e` → `0xc270` | Iterator `next`. |
| `0x8548a` → `0x87fe8` | JNI-exception branch aborts primary iteration. |
| `0x85493` → `0x87fcc` | Null-entry branch skips to next row. |
| `0x856df` → `0x7bf80` | Classify UUID version. |
| `0x856e8`, `0x856ed` | Compare version with 2, then test mode byte at `0x1a63b8`. |
| `0x85720` → `0x87fcc` | Version 2 plus mode zero skips to next row. |
| `0x87f72` → `0x8bdc0` | Append accepted primary row. |
| `0x87fd9` → `0xc210`, `0x87fe0` → `0x85461` | Continue primary iterator. |
| `0x87ff5` | Secondary cached/synthetic-row processing begins after primary loop. |

The null-entry and v2/mode-zero branches are the only jumps to the primary loop's
next-entry block at `0x87fcc`. Classifier `0x7bf80` reads hexadecimal character 14
when the UUID string is long enough; it does not validate a complete UUID.
Appender `0x8bdc0` copies a `0x178`-byte row through `0x58130` or grows the vector
through `0x4fa20`; it adds no eligibility checks. The final rendered native model
also contains secondary rows, so it is not interchangeable with live Tab membership.

The existing Bot Denicker interception at `0x86cff` → `0xba530` is narrower:
mode zero and UUID v1, preserving already-known Number and enabled Skin identities.
It cannot serve as an all-player Urchin roster because ordinary v4 profiles and
already-resolved identities need not call its Java candidate path.

The model is built by `0x8f525` → `0x83fd0`, stored at context `+0x288`, and refreshed
by the pump at `0x1a617` → `0x8f4b0` when enabled by byte `0x1a63bd` and the 300 ms
time gate. This model work is separate from overlay rendering gates; neither Java
roster consumer requires the Tab overlay to be visible.

## Limits and regression coverage

A server-created NPC with a valid-looking name and UUID v1/v4 can pass both
filters. Neither the recovered native checks nor this Java helper independently
authenticates that a row represents a human. The helper name does not imply such
a guarantee. UUID v3/offline identities and other versions are deliberately
excluded by the Java policy, even where the original native mode could include them.

`tests/java/AdninTabEligibilityTest.java` executes the production helper against
name boundaries, null UUIDs, nil UUID and versions 0–15. Source guards verify
both real Tab consumers use the helper before roster insertion, null-profile
guards, the 256-entry bound, filtered cached announcements, and match-entry scan
ordering. These source guards are explicitly not a simulated game integration
test. Existing cache tests separately cover frozen batches and cache lifetimes.

Evidence was checked against the generated assembly and original decompilation
(`all_functions.c`: `FUN_180083fd0`, `FUN_18007bf80`, `FUN_18008bdc0`, `FUN_18008f4b0`).
