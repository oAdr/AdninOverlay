# Ping / PingVar source review and Aurora migration

Reviewed on 2026-10-01 by static inspection of `build-v22-input-perf/native-fixed.dll`, its reconstructed assembly, and the API policy wrapper. The Aurora contract was checked against the current Mellow source and public requests without any API key. No game injection was used. This note contains no user credentials or private service settings.

## v23 migration contract

The current `build-v23-followup` additionally removes the unintended Hypixel
API Proxy gate from Aurora Ping. Proxy OFF previously returned a false fetch
result without making a request, then retained it for the normal failure TTL.
The Ping tailcall now always reaches its public provider; Hypixel statistics
and Number Denicker retain their own credential/provider rules. Native mock
tests exercise Proxy values 0/1/2 with missing, blank and populated dummy keys.
The background worker, completed/failed cache intervals and no-data behavior
remain unchanged. See `respawn-party-ping-v23.md` for current build results.

The requested change replaces the native Bordic v3 Ping endpoint with the Aurora v2 Ping endpoint used by Mellow:

- Previous endpoint: `https://api.bordic.xyz/v3/player/ping?uuid=`.
- Aurora endpoint: `https://bordic.xyz/api/v2/resources/ping?uuid=`.
- Request: HTTPS GET with the player's UUID; no `key` query parameter or authentication header is required by the reviewed Mellow Ping implementation.
- Successful response contract: `success: true`, with `data` containing objects with integer `avg` values. Mellow displays the first usable `avg` value.
- `AuroraApi`'s Number Denicker lookup is a separate service that requires a key. The Ping route must not be coupled to that key setting.
- Integration should keep the existing native background worker, parser and bounded request/cache policy, and relocate only the Ping endpoint reference. There is no need for a second Java worker, JNI networking callback, or duplicate cache.

GitHub's main branch was checked on 2026-10-01 and still resolved to commit `17ef9b7466754a33ee8c8ed87fa7ea717573d775` (commit date 2026-09-26). Raw upstream copies of these files matched the local audited checkout after line-ending normalization:

- `src/main/java/com/roxiun/mellow/api/aurora/AuroraPingService.java`.
- `src/test/java/com/roxiun/mellow/api/aurora/AuroraPingServiceTest.java`, including its `fetchPingDoesNotSendAnApiKey` test.

Public route checks used the public Notch UUID in both compact and hyphenated form, without a key. Both returned HTTP 404 with `success: false` and `cause: "No data found [uuid]"`. This confirms the response was a structured no-data response, not proof that this player has a successful Ping measurement. No successful live data response was obtained in this review.

## Previous native source and request

- Native provider: Bordic Ping API.
- Public endpoint constant: `https://api.bordic.xyz/v3/player/ping?uuid=`.
- Endpoint string RVA in the Lunar image: `0x170188`; image base: `0x180000000`.
- Lunar fetch function: RVA `0x97b00`. The compatibility profile maps the equivalent function to `0x99c70` in `scripts/native_api_policy.py`.
- The fetch function normalizes the UUID, escapes it, appends it to the endpoint, and calls the existing HTTP helper. It checks HTTP status `200` and the response's `success` field before reading the available averages.
- The reviewed pre-migration API policy wrapper in `src/native/adnin-api-policy.asm` allows this provider only when proxy mode is enabled. Otherwise it returns an unavailable result. The wrapper delegates the calculation to the native fetch function.

## Displayed values

The native parser extracts integer values matching `"avg"\s*:\s*(-?[0-9]+)` into a vector.

| Column | Native calculation |
| --- | --- |
| Ping | The first parsed `avg` value. |
| PingVar | The largest parsed `avg` minus the smallest parsed `avg`. |

`PingVar` is therefore an average-value range, not statistical variance. Neither column is calculated from a local ICMP probe. The binary alone does not establish the upstream provider's measurement interval or collection method.

The result structure stores a timestamp at `+0x00`, a completion/cache marker at `+0x08`, Ping at `+0x0c`, and PingVar at `+0x10`. Missing data retains negative unavailable values. The result is copied into a cache node starting at node `+0x30`.

Relevant Lunar instructions:

- `0x9882e` reads the first vector element; `0x98831` stores Ping.
- `0x98890` through `0x9892c` calculate the minimum and maximum.
- `0x9892c` subtracts minimum from maximum; `0x9892f` stores PingVar.

## Cache and refresh eligibility

Lunar stale-check helper `0x99f10` reads the native cache map at `0x1a74f0`. It uses the node timestamp at `+0x30` and completion marker at `+0x38`.

| Existing entry | Eligible for another fetch when its age exceeds |
| --- | --- |
| Completed response | `0x927c0` ms = 600,000 ms = 10 minutes |
| Transport/request failure | `0xafc8` ms = 45,000 ms = 45 seconds |
| No cache entry | Eligible immediately |

A completed response without usable successful data can still retain the completion marker and unavailable Ping/PingVar values. It must not be described as necessarily retrying after 45 seconds.

The worker beginning at Lunar RVA `0xa0260` checks refresh eligibility at `0xa03d3`, calls the fetch function at `0xa03fc`, and copies the result into the cache at `0xa0446`. These durations govern on-demand refresh eligibility. They are not an automatic periodic network timer.

## Verification limits

- The earlier `build-v23-gray-policy` passed both native suites (Lunar 78, compatibility 45) but retained the now-removed Proxy gate. Current-build results and final artifact hashes are recorded in `验证状态.md`. Endpoint tests still pin all seven API patches and the original fetch/parser body outside the URL displacement.
- The public Aurora route returned a structured no-data response. Successful upstream data parsing, real-time latency and client rendering behavior were not validated live in this review.
- Compatibility function addresses were checked against the build profile mapping; the detailed calculation and cache branches cited here were inspected in the Lunar binary.
