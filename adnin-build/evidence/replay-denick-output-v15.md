# Replay Nick, Denicker, detector admission and separate Output categories

## Observed v14 result

Fresh-process v14 injection succeeded in Badlion 1.8.9 on September 29, 2026.
The loader reported no prior Adnin module and confirmed runtime readiness.
Replay recognition was active. The current Tab contained 17 profiles, including
16 formatted names; all 17 passed the normalized name gate. Twelve current-world
actors matched the roster, and detector sampled ticks advanced across fresh
diagnostic writes. The native model contained 17 rows and its Replay, eligibility
and display-installation scalars were set.

The user confirmed that most players displayed account statistics, then showed
nicked players with placeholder cells rather than the normal Nick indication.
Eight account-lookup attempts succeeded. Failed requests retried; cumulative
request failures are not a count of distinct players. No detector alert occurred
in the observed paused recording, so these counters do not verify actual cheat
detection accuracy. No test party message or WDR report was sent.

## v15 implementation contract

- The dedicated official account lookup distinguishes HTTP 404 from pending,
  transport failure, rate limits, server errors and invalid profile data.
  Only the absent-account result becomes the exact cached `NICK` marker.
  The native renderer uses the original red `[NICK]` formatter.
- A missing current account is a Replay nickname heuristic. Historical renames
  and nicknames that coincide with an existing account remain ambiguous without
  original recording identity metadata. Synthetic Replay UUIDv2 alone is never
  treated as proof of a nickname.
- Normalized raw profile aliases, displayed names and entity names are matched
  against the current Tab. Conflicts, duplicates and spectator roles remain
  excluded. AC admission never depends on a successful account API lookup.
- Replay Denick uses the existing native Skin/Number result paths and the
  existing user-configured Bot worker. Actor GameProfiles and UUIDs are preserved;
  only verified resolved account identities may select account statistics.
- Output uses three independently persisted categories: player data and all
  Denicker results; Seraph/Urchin tags; Anticheat alerts. Native producers supply
  their category explicitly, without classifying messages by text prefixes.
  Anticheat Output contains the detection text, without the local WDR control.
- Queue fragments retain their category and are discarded when that category
  is disabled. Existing party command length, formatting cleanup and expiry
  rules remain in force. Migration from the prior single switch enables the two
  previously supported groups; the new Anticheat group defaults to disabled.
- Replay/Atlas WDR commands remain disabled. Urchin still uses the existing live
  match-entry trigger. No personal settings or credentials are release inputs.

## Number Denicker contention repair

The original Number worker holds its cache mutex across a synchronous statistics
HTTP call. The periodic result getter, candidate registration and message-pop
operations wait for that same mutex on the UI path. Both payloads now use
`_Mtx_trylock` at precisely guarded polling sites. Success enters the unchanged
locked path; busy enters the existing no-lock cleanup/defer path. Registration
and queued messages remain available for the next poll. Unexpected CRT errors
retain the original C++ error behavior. Existing Win32 imports resolve the
already-loaded CRT export; no additional library is loaded.

This addresses three high-frequency polling waits, not every possible Number
lock. Once-only incoming-chat parsing and changed settings still use their
original synchronization. Releasing the worker lock around HTTP without also
revalidating its shared entries is unsafe; that broader change is not claimed.
An unusual CRT missing the trylock export falls back to the prior blocking lock.
Actual frame-time improvement has not yet been measured in a fresh v15 game.

## Validation status

Targeted Java API/cache, independent Output/GUI and production Replay-to-Anticheat
fixtures have passed. Native Lunar, compatibility and Denicker suites have passed
60, 30 and 15 tests, including injected lock-contention results and exact cleanup
branches. The full integrated v15 build passed. Fresh-process live validation remains
pending. Final results are recorded in the release verification document.
