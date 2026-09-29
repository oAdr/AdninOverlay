# v14: normalized Replay profile names

## Fresh-process v13 observations

The versioned v13 EXE selected a newly restarted Badlion 1.8.9 process with no
previous Adnin module. Injection returned runtime-ready: Minecraft/GUI resolved,
hooks installed and heartbeat advancing. Its compatibility payload hash matched
the final v13 release. No additional DLL was loaded into that process afterward.

The sanitized runtime diagnostics showed Replay active, no observer failures,
17 world players, one accepted Replay actor, one successful account-profile
lookup, ongoing fixed-phase Anticheat sampling and zero report decisions.
These results demonstrated a remaining eligibility problem, not a complete fix.

A bounded read of the known native row identity fields emitted only character
shapes and UUID-version counts, never names or UUID values. Sixteen rows used
v2 identities; one used v4. Stored names were ASCII. No settings, credentials,
API response bodies or unrelated memory were accessed.

## Proven native/Java normalization mismatch

The native model reads the actual NetHandler player-info collection. It does
not switch to a historical name cache or world-only roster in Replay. Before
storing row+0x40, it removes section-sign formatting from GameProfile.getName:

| Operation | Lunar RVA | Compatibility RVA |
|---|---:|---:|
| GameProfile.getName call | 0x85532 | 0x86D71 |
| Formatting wrapper call | 0x8559B -> 0x77020 | 0x86DDA -> 0x78140 |
| Formatting implementation | 0x32C00 | 0x34720 |
| Store normalized row name | 0x855AA -> 0x7220 | 0x86DE9 -> 0x7020 |

Thus an ASCII native row does not prove its original Java GameProfile name is
ASCII. v13 rejected formatted raw names before publishing its current-Tab keys.
The callback also receives the normalized native key, not the original formatted
name. v14 applies a bounded valid-Minecraft-format normalization before Java
name validation and candidate matching. Invalid formatting remains rejected;
normalization conflicts remain ambiguous. Native actor UUIDs and profiles are
unchanged, and its existing strict ASCII callback guard requires no modification.

New fixed diagnostic counts are replayTabProfiles, replayFormattedTabProfiles
and replayValidTabProfiles. They contain no individual identity data.

## Regression coverage and scope

The production-bytecode roster fixtures include raw profile names with a reset
suffix, team-colored display names, normalized native callback lookup, conflicts
created by different formatting sequences, current Tab departure and world loss.
Normal-game AdninFeatures eligibility was not changed. Replay AC still requires
a current-world entity correlated with the current Tab; no world-only fallback
or arbitrary bot admission was added. Statistics remain asynchronous and use
verified account UUIDs through the v13 native bridge.

The v14 full build and any fresh-process live results are recorded separately
in `验证状态.md`. Offline fixtures do not establish real-client detection accuracy.
