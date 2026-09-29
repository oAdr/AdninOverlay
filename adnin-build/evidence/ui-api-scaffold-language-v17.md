# v17 interface, Hypixel, Scaffold and localization

Prepared September 29, 2026. Final build and verification totals are recorded in
`../验证状态.md`. This release retains the original recovered native assembly;
the 2,493 Lunar native functions are not a complete C++ rewrite.

## Interface

The reported comb-like rounded edges were reproduced with the v16 renderer by
inheriting GL_FLAT from the surrounding renderer: 44,092 pixels differed from
the clean-state image. AdninUi now owns and restores the relevant shading,
polygon, blend, texture, shader and clipping state. Seven contaminated-state
scenarios render identically to the clean reference and preserve prior state.

Settings / Interface provides a 70–140% scale slider, default 100%, with viewport
fitting. Drawing, pointer transforms, dragging, scrolling and clipping share
the same animated coordinate system. Chinese uses local CJK system fonts.

English, Simplified Chinese and Traditional Chinese are selectable and saved.
The language applies to menu labels, owned chat/error templates, Anticheat
alerts, Session HUD and injector result controls. Player names, API-provided
tag/reason content, URLs, keys, click commands and established metric
abbreviations remain intact. A bounded language-only preference is shared with
the injector; no credential is stored there.

Translated native chat uses a strict recognized-template bridge. A return of 1
suppresses the original local renderer only after successful delivery; all
unsupported text and errors fall back. Native API-key errors have a local-only
category which cannot enter party Output.

Actual Tab/Overlay headers also translate eleven known titles: Name, Stars,
Wins, Finals, Beds, Requeue%, Kills, Seen, Session, Ping and PingVar. Two guarded
header-only JNI string-construction sites per profile use the same translated
text for measurement and drawing. Short Chinese labels are measured using the
actual game FontRenderer and fit within existing column widths. Players, values,
column order and column geometry are unchanged. Missing callbacks, unsupported
titles and optional Java failures retain the original string and native local
reference ownership. Original pending JNI/NewStringUTF exceptions are preserved.

## Hypixel

Three reviewed native request sites in each profile now use the official
`/v2/player?uuid=...` request and `API-Key` header. Name-based legacy callers
resolve through the existing Mojang resolver first. Empty keys remain rejected;
Vega requires its explicit switch. Existing response parsing and ownership are
preserved. Configuration changes expire unsuccessful stats entries under the
existing stats mutex, without discarding successful cached data.

The adapter validates bounded components, strips credentials from the outgoing
URL and clears its private header buffer on normal return. This is not complete
credential erasure: legacy caller snapshots and HTTP-library buffers can still
contain the configured credential. Previously admitted requests are not
atomically cancelled by a setting change. The native resolver's no-player flag
also covers some malformed successful responses; no new identity inference is
introduced.

An authenticated read-only check using an existing local user configuration
resolved the reported complete player name successfully through Mojang (HTTP
200). The Hypixel service returned HTTP 429. One later retry also returned 429,
RateLimit-Remaining 0 and Retry-After 44556 seconds. No response body, key or
personal configuration was copied into this project. This is evidence of
provider rate limiting, not successful live statistics retrieval. Further
requests were stopped. Offline request and cache tests use synthetic values.

## Scaffold

Only Scaffold is replaced with Mellow's five-position implementation at commit
`17ef9b7466754a33ee8c8ed87fa7ea717573d775`. Other detectors remain in place.
The common gate checks downward pitch, near-straight backward movement,
horizontal speed and nonzero vertical acceleration; Mellow's tower/horizontal
branches contribute weighted violations. The Scaffold threshold is 10 and
Adnin's existing interval, output and report policies still apply.

Replay/Nick admission, lifecycle resets and discontinuity guards are retained.
Replay playback speed is not normalized; validate at 1x. As in upstream, pure
stationary tower or motion outside those predicates can be missed. Offline
fixtures establish implementation behavior, not real-world detection accuracy.

Mellow is GPLv3. The exact upstream license and attribution are embedded in the
EXE, included in resources/THIRD_PARTY_NOTICES.txt and provided with the source
archive. Raven's separate attribution remains.

## Privacy and live-test boundary

Fresh defaults have empty provider keys and custom Bot URL. Personal saved
settings are separate from the EXE and are excluded from both archives. Source,
binary and decoded embedded helper scans run before packaging; public provider
endpoints remain part of the software.

No v17 DLL was injected into a game during this attempt. Desktop validation was
stopped by the user's physical Escape key before injection. Badlion and Lunar
gameplay, live statistics, Replay detection accuracy, sounds and real party
delivery therefore remain unverified for this exact build. No party message or
report was sent. The current launcher also prevents starting Lunar while the
Badlion instance is active; the user agreed to switch clients after Badlion
verification, which has not yet occurred.
