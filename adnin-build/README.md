# Adnin build v23

## v23 teammate cache and Lunar Party Detector recovery

Teammate identities are now cached for the current match by name and UUID.
Temporary respawn/spectator states, Tab disappearance, entity replacement and
the local entity tick counter restarting no longer discard a positive teammate
decision. Light gray `§7` is never used as a team color for self or teammates;
an already confirmed teammate remains cached while briefly gray. Match, world,
connection, local identity and Replay transitions still clear the cache.

Light-gray player handling also pauses all five Anticheat checks, new player
queries and Nick/Denick work, tag announcements and their queued Output.
Known teammate, identity and tag state is retained while a player temporarily
turns gray on respawn. Native query gates preserve cached statistics and base
Tab rows. Party IDs skips new observations already identified as gray without
retracting an earlier accepted group. Dark gray `§8` and white `§f` are allowed.

Ping now uses Aurora's public v2 Ping endpoint. It sends the player's UUID and
no API key, and retains the existing API Proxy opt-in, native background worker,
10-minute completed-response cache and 45-second request-failure retry cache.
Ping is the first `avg` value; PingVar is `max(avg) - min(avg)`. The Number
Denicker key setting remains independent of this public Ping endpoint.

Lunar Party Detector IDs now completes a cycle only after `/locraw` is actually
sent. A transient missing player, changed pre-game sidebar or sender failure can
recover in the same world, with at most three attempts, five seconds between
actual attempts, and a fresh 500 ms waiting-room check for each retry. Lobby,
active-game and Replay scope restrictions remain. The native mode parser and
entity-ID grouping algorithm is unchanged.

The settings header displays `v23`. Build with
`Build.ps1 -BuildDirectory build-v23-gray-policy`; package with
`python scripts/package.py --build build-v23-gray-policy --version v23`.
See `evidence/team-cache-v23.md`, `evidence/party-query-recovery-v23.md`,
`evidence/gray-player-policy-v23.md`, `evidence/ping-source-v23.md` and `验证状态.md` for the focused
checks and live-validation limits.

## v22 input safety and equivalent-work reduction

This follow-up fixes window subclass ownership and unload lifetime, prevents
background/menu End presses from requesting unload, and cancels stale slider
drags during close/resize. Keyboard repeat is restored when the menu closes.
The successful injection notification is displayed without activating it.

Native chat collection now stops after fully converting the same boundary
record where its unchanged consumer already stops. It retains first-record,
counter-reset and same-counter text/style behavior while avoiding conversion
of discarded history. Unchanged overlay-column configuration uses bounded
snapshots; menu text clipping and rounded geometry avoid equivalent repeated
work. Game input state, render order, priorities, frame limits and Anticheat
sampling frequency remain unchanged. Existing deliberate feature-specific
Tab/local-command handling remains enabled according to its existing options.

Build with `Build.ps1 -BuildDirectory build-v22-input-perf`; package with
`python scripts/package.py --build build-v22-input-perf --version v22`.
See `evidence/input-performance-v22.md`, `evidence/input-hook-review-v22.md`
and `验证状态.md` for verification and the limits of offline performance results.
Restart a game containing an older payload before loading this follow-up.

## Previous v22 lobby, Legit Scaffold and crash-diagnostic follow-up

Lunar automatic mode discovery now requires a visible Bed Wars pre-game
waiting/countdown sidebar as well as the server check. A Hypixel main lobby,
active game or Replay cannot arm the query. The sender rechecks current world,
connection, phase and the detector switch immediately before dispatch. Relay
addresses still qualify through the complete visible Hypixel footer. Missing
or unusual phase evidence declines the automatic query; manual commands remain
available. The 500 ms readiness delay, five-second minimum attempt spacing and
one attempt per world/connection/enable cycle remain in place.

A delayed Urchin match-start notification can no longer create requests for a
lobby roster after closing the configuration screen. Leaving a game retires
unstarted requests; the worker rechecks the admitted match before HTTP. Already
started, same-key results can still populate the bounded cross-match cache.

Legit Scaffold now uses the reviewed Mellow Eagle crouch/swing model. Its
existing switch and alert routing remain; the separate Scaffold calculation
is unchanged. The adaptation consumes each fresh action once and preserves
Adnin's actor, teammate, Replay and cooldown protections.

Successful injection also starts a hidden crash monitor from this same EXE.
An abnormal game exit writes a bounded, sanitized `Adnin-crash-*.log` to the
system Desktop; ordinary exits remain silent. It uses a process-handle wait,
does not add a game exception hook, and does not upload reports. Candidate
game-directory crash excerpts are labelled separately from PID-matched fatal
evidence. Use the standalone EXE to start this monitor.

Anticheat samples reuse a protected scratch buffer and team-name parsing avoids
unnecessary transformations. Detection frequency is unchanged. The source
verification notes report controlled allocation measurements without claiming
an equivalent reduction in total game memory or measured live frame times.

Use `Build.ps1 -BuildDirectory build-v22-lobby-eagle-crash` for this follow-up and
`python scripts/package.py --build build-v22-lobby-eagle-crash --version v22`
to package it. See `evidence/lobby-eagle-crash-v22.md` and `验证状态.md` for the
actual checks and the distinction between offline and fresh-client evidence.

## v22 self-Nick, Skin Denicker and Output

Self-Nick teammate recognition now prefers the linked current server Tab and
scoreboard identity over stale local account/rank formatting. Partial initial
Tab data cannot freeze the original account's rank as the match team color.
Replay teammate checks use the admitted recorded name rather than a bot's raw
or skin alias. Positive teams remain cached separately for each match.

Skin Denicker uses the Mellow texture-owner approach with its default Nick-skin
exclusions. Resolution is local, bounded and cached by actual texture evidence;
it does not require an added API or Forge. A shared/custom skin's owner metadata
is not proof of who is playing; unavailable/default skin data remains unresolved.
Statistics replacement and success Output are checked separately from finding
a candidate in metadata.

Output keeps its four independent switches and game/Replay scope. This revision
repairs full-queue deduplication and first-message loss when enabling Output,
retires stale unconsumed Nick hints, and reduces repeated parsing/render work.
Anticheat reuses immutable normalized settings until an option changes.

Build with `Build.ps1 -BuildDirectory build-v22-nick-skin`; package with
`python scripts/package.py --build build-v22-nick-skin --version v22`.
The usage package contains only `Adnin.exe`. Restart a game that has an older
payload loaded before updating. See `evidence/nick-skin-output-v22.md` and
`验证状态.md` for the candidate's actual test status and live-test boundaries.

## Historical v21 queue discovery and performance review

Lunar's recovered world-change path reset the Party Detector IDs mode but did
not request it. Manual `/locraw` restored grouping in the user's v20 session.
The Lunar client pump now requests the mode after a 500 ms readiness delay on
a new world/connection or an explicit re-enable. Attempts are at least five
seconds apart, restricted to Hypixel multiplayer, and never retried per frame.
Official hostnames are recognized directly. Custom relay addresses are admitted
when the current visible sidebar contains Hypixel's complete official footer;
the user's relay address is never hardcoded into the release.
Invisible Unicode row identifiers are handled only after the current font
confirms that they add no visible text to the otherwise complete footer.
Badlion/Vanilla keeps its existing native query path.

Anticheat samples players only during native `Ingame` or a detected Replay.
Lobbies and pre-game waiting rooms stop sampling and retire transient evidence
before configuration snapshots, team scans or actor scans. Returning to an
eligible context starts fresh evidence; the current world's report cooldowns
remain intact. Its packet-observation interest follows the same scope, while
ClientSideSounds keeps its separate switch. This gate is independent of all
Output category switches and does not change the detection algorithms.

The settings header displays `v21` beside the red Adnin title. The additional
stability and allocation changes, regression results, and remaining live-test
limits are recorded in `evidence/stability-performance-v21.md` and
`验证状态.md`. v20's asynchronous observer and native unload guards remain.

Build with `Build.ps1 -BuildDirectory build-v21`; package with
`python scripts/package.py --build build-v21 --version v21`.
The usage package still contains only `Adnin.exe`. Restart a game that has an
older payload loaded before testing v21. Private settings and diagnostics are
never release inputs.

## Historical v20 packet observer deadlock repair

A live frozen v19 Lunar process showed a complete wait cycle: the Minecraft
client thread held its task-queue monitor while waiting for Netty to remove
the Adnin packet observer; that Netty EventLoop was waiting for the same task
queue. The observer previously removed and reinstalled itself repeatedly.

Packet pipeline changes now run asynchronously on the channel's EventLoop.
Repeated installation on the same channel reuses its observer. At most one
installation/removal task is pending, connection changes retain the latest
request, and stale tasks cannot reinstall an observer after shutdown. Retired
observers continue forwarding packets and events.

Shutdown disables new observations without waiting for Netty. An in-flight
observation counter prevents native unload acknowledgement until all admitted
observations have returned. The original packet JNI entrypoint is preserved.
This addresses the captured deadlock and the related observer/unload race;
it is not a claim that every possible client crash has been resolved.

The new regression uses real registered Netty 4.0.23 channels and reproduces
the task-monitor interleaving. The old v19 bytecode fails the controlled test;
the corrected implementation passes on Java 8 and Java 17. See
`evidence/lunar-hang-v20.md` and `验证状态.md` for exact test boundaries.
The frozen game was inspected read-only. v20 was subsequently loaded into a
fresh Lunar 1.8.9 process: the runtime handshake passed, the user confirmed the
menu and movement work, and six samples over 50 seconds showed an advancing
heartbeat and a responsive window. See the verification notes for the narrower
packet-observer trigger test and remaining long-session validation. Do not
inject an update into a process that already loaded an older version.

Build with `Build.ps1 -BuildDirectory build-v20`; package with
`python scripts/package.py --build build-v20 --version v20`.
The usage package contains only `Adnin.exe`. Personal keys, Bot URLs, saved
settings and raw process diagnostics are excluded.

## Historical v19 cache, team identity and performance

Urchin content now follows the Seraph cache policy: successful and empty
responses stay fresh for ten minutes, failures cool down for 45 seconds, and
expired content is refreshed only at the next game entry. Completed content and
valid in-flight requests survive world changes. Changing the Urchin key retires
pending work but preserves completed content; new requests use the new key.

Bot Denicker caches verified results and explicit no-result responses for ten
minutes, and transport/account-verification failures for 45 seconds. Entries
survive match and world transitions and remain bound to the configured Bot URL.
Replay profile lookup also keeps verified identities and confirmed `[NICK]`
results for ten minutes, with a 45-second cooldown for request failures. Native
Skin and Number identity caches already survive match transitions; v19 preserves
their original cache behavior and does not assign them this new Java TTL policy.
Cached identities are applied only to an eligible row in the current Tab roster.

Team membership is recorded per match from the color of the local player's
displayed nametag, including when that player uses a server Nick. It does not
depend on the launcher account name. A self Nick with a different Tab UUID is
linked only through a unique visible-name match and recorded as self. Confirmed
teammates keep their UUID/name aliases for the rest of the match; unknown players
can be recognized later. Match, world, connection, local UUID and Replay
transitions reset membership. Missing or ambiguous identity/color evidence is
not guessed. Replay and live rosters share this gate.

Player Data, Nick / Denick, Seraph / Urchin Tags and Anticheat have separate
Output switches. Tag output has independent self and teammate switches. Party
delivery is rejected outside Ingame or Replay, and bounded queues are retired
when a category, provider or game context changes. Per-tick roster helpers reuse
bounded scratch collections and avoid repeated regex work in name/UUID checks.
Fresh negative Bot cache entries suppress duplicate lookup hints. A verified
cached Bot result may be announced once in each eligible match, without another
HTTP request. These changes reduce repeated work; they are not a measured live
FPS or latency guarantee.

The usage package contains only `Adnin.exe`; keys, personal Bot URLs, settings,
logs and screenshots are excluded. The v19 build has not been injected into a
live client during this pass; all reported verification is offline.

Build with `Build.ps1 -BuildDirectory build-v19`; package with
`python scripts/package.py --build build-v19 --version v19`.

## Historical v18 resource lifetime and shutdown repair

The Lunar scheduler now deletes its otherwise-discarded JNI Future local
reference. Both payloads bypass the recovered CRT destructors only during
whole-process termination; explicit unloading retains the original cleanup.
This addresses a concrete joinable-thread destructor hazard at game exit.
The supplied Windows event points to UCRT abort/fail-fast, but without a dump
the caller cannot be proven and this exact live crash is not yet retested.

UI uploads and state queries reuse direct buffers. Menu close, world changes
and explicit unload retire textures on the client thread. Optional API/Replay
workers stop on unload, stale work is removed, late results cannot refill a
retired queue, and cross-match Urchin content has a 4 MiB estimated-content
budget in addition to its entry limit. Current-match complete reasons are
separate. Embedded helper decoding is lazy and temporary.

Build with `Build.ps1 -BuildDirectory build-v18`; package with
`python scripts/package.py --build build-v18 --version v18`. The usage ZIP
contains only Adnin.exe. Keep the corresponding source ZIP and license notices
when distributing this build. All personal API keys and Bot URL defaults are
empty; existing user-local settings are not packaged.

See `evidence/stability-v18.md` and `验证状态.md` for tested boundaries and live
limitations. The following sections record prior releases.

## Historical v17 rendering repair, interface scale, languages and Scaffold

The rounded-edge rendering corruption is fixed by isolating and restoring the
OpenGL state inherited from the client. Settings / Interface now provides a
70–140% UI Scale slider and English / 简体中文 / 繁體中文 selection. Language changes
apply to the menu, known Tab/Overlay headers, owned messages/errors, Session HUD
and injector result UI.
API payload text, player names, commands and standard ratio abbreviations remain
unchanged. Settings are saved outside the executable.

Direct Hypixel requests now use `/v2/player` with a UUID and `API-Key` header.
Editing the key expires unsuccessful native statistics cache entries. Explicit
proxy selection and empty credential defaults remain. This cannot override a
service quota: the local online check returned HTTP 429 and a Retry-After value
of 44556 seconds. Successful authenticated statistics retrieval remains
unverified for v17.

Only Scaffold has been replaced with the Mellow implementation at commit
17ef9b7466754a33ee8c8ed87fa7ea717573d775. Existing detectors and Replay/Nick guards
remain. Mellow's GPLv3 license is included in the embedded notices and source
archive; this port is not covered solely by Raven's MIT notice.

Build with `Build.ps1 -BuildDirectory build-v17`; package with
`python scripts/package.py --build build-v17 --version v17`. The usage ZIP
contains only Adnin.exe; the source ZIP is separate. All provider key and
personal Bot URL defaults are blank. Existing local settings are not packaged.

See `evidence/ui-api-scaffold-language-v17.md` and `验证状态.md` for exact checks
and limitations. Desktop validation was interrupted by the physical Escape key
before injection, so this exact release has not been verified in live Lunar or
Badlion gameplay. The following sections are retained release history.

## Historical v16 interface, complete Replay names and explicit API selection

The configuration panel now has antialiased system typography, vector icons,
rounded grouped controls, smooth time-based transitions, viewport scaling and
real nested clipping. Scrolled content and hitboxes share the same coordinates.
The Overlay fixed header no longer also toggles hidden rows beneath it.

Replay identity uses the full current Tab display, including scoreboard-team
suffixes. Internal formatting no longer cuts off characters. The reported
XiaoShu_SKY2026 truncation has dedicated cache, roster and Anticheat regressions.
Conflicting identities remain excluded rather than being guessed.

An empty Hypixel key no longer silently enables proxy requests. Proxy stats and
ping require the visible Vega Proxy switch. Number's independent key copy now
synchronizes on replacement and clear. Settings show the configured provider or
missing key; already loaded statistics can remain cached and previously admitted
requests may finish. All default keys and the personal Bot URL remain blank.

Release privacy checks cover compiled helper classes as well as raw DLL/EXE and
source data. Runtime settings are never included. Build with
`Build.ps1 -BuildDirectory build-v16`; package with
`python scripts/package.py --build build-v16 --version v16`.

See `evidence/ui-replay-api-v16.md` and `验证状态.md` for exact verification and
limitations. Full build and offline render checks passed; v16 live injection has
not been performed. The remaining sections describe retained release history.

## Historical v15 Replay Denicker and independent party Output

The v14 Badlion test loaded successfully and the user confirmed visible Replay
statistics. This update restores the normal red `[NICK]` presentation for an
explicit absent-account result, admits confirmed Replay Nick rows to the existing
Skin/Number/Bot Denicker paths, and keeps recorded actor identities separate from
resolved statistics accounts. Pending and failed network requests are not Nick
evidence. Old recordings with renamed accounts or names matching another account
remain ambiguous without original identity metadata.

Replay Anticheat now normalizes every raw/display fallback and rejects conflicting
aliases. Production fixtures exercise Nick detection without a successful account
API lookup. Anonymous rejection counters distinguish actor eligibility from API
resolution. The five detector thresholds and Replay WDR prohibition remain.

v15 introduced independent party Output categories and checks at both enqueue
and delivery. v19 now exposes four switches: Player Data, Nick / Denick,
Seraph / Urchin Tags, and Anticheat. Nick, Bot, Skin and Number results are
controlled by Nick / Denick. Fresh installations default all four off. Output
uses `/pc` and retains the packet-size splitting rules described below.

See `evidence/replay-denick-output-v15.md` and `验证状态.md` for exact verification
scope, the Number Denicker contention repair and remaining live-test limits.
Build with `Build.ps1 -BuildDirectory build-v15`, then package with
`python scripts/package.py --build build-v15 --version v15`.

## Historical v14 live-derived Replay name correction

Fresh-process v13 loading succeeded in the user's Badlion Replay, and detector
sampling ran, but only one of 17 world players was admitted. Native analysis
confirmed that the native Tab builder strips Minecraft formatting from the
GameProfile name before storing the row key, whereas the Java roster rejected
formatted raw profile names. v14 removes valid Minecraft format codes before
validating names and publishing the native lookup key. Conflicting names after
normalization remain excluded. No ordinary-game profile filter or native ASCII
gate was relaxed. Three anonymous profile counters support live diagnosis.
See `evidence/replay-format-v14.md` and `验证状态.md` for current status. The v13
implementation history below remains applicable with this name-key correction.
The following sections describe the earlier implementation history.

This project builds one distributable `Adnin.exe` targeting Windows x64 Minecraft **1.8.9**, with client selection for Lunar Client, Badlion Client and Vanilla Minecraft. The EXE embeds two runtime DLLs: the Lunar payload and a vanilla-obfuscated compatibility payload shared by Badlion and Vanilla. It verifies the selected payload and extracts it to a local application-data cache identified by its content hash. The injector is C++20; the 2,493 recovered Lunar native functions remain editable NASM, alongside the compatibility native image, added Java feature code and NASM JNI bridges. This is **not a complete recovered C++ rewrite**.

See `验证状态.md` for v19 automated checks and runtime verification limits, and the release manifest for final artifact hashes. Earlier repairs to the native Replay statistics path, recorded account identities and actor eligibility remain included. Static ABI, signed-classloader and offline fixture checks do not establish live game behavior. Prior release game/API/window evidence remains historical.

## Historical changes in v13

The failing v12 Badlion session had Replay recognition enabled and 17 populated rows. The custom headers, names and HP rendered, but statistics were blank. Static inspection confirmed that the native Replay/Atlas branch bypassed its statistics queue and cache. v13 adds a dedicated bridge there: a bounded background worker resolves the current Tab account name through the existing Mojang name-verification API, then the native queue/cache supplies statistics for that verified online UUID. The bot's live GameProfile, actor UUID, displayed raw name and team remain unchanged. Viewer/spectator roles and ambiguous mappings do not supply an identity. Current Tab entries can request statistics outside entity tracking distance; no old chat-name cache is used.

Profile lookups are asynchronous, deduplicated and limited to 256 current candidates. Successful identities are cached for ten minutes, failures for 45 seconds, with at most 512 cache entries and a minimum 750 ms worker delay. Leaving Replay removes eligibility and queued departed identities. No Replay identity result becomes a Denicker message or party Output event. The existing Urchin game-entry-only trigger is unchanged: Replay entry does not manufacture a live-match transition or new Urchin batch.

Anticheat now matches current-world replay entities to current Tab recorded names without requiring their entity UUID, GameProfile UUID and Tab UUID to agree. Ordinary-game bot filtering remains unchanged. Settings changes, brief roster absence, entity replacement, invalid samples and recoverable hook failures clear detection evidence while preserving the current world's alert/report cooldowns. Automatic reports are separately limited per UUID across checks. Actual disconnect/world changes clear history, which is bounded to 1,024 entries and expires after 60 seconds.

Replay jumps of at least five blocks on any world/server axis retire detector evidence while retaining cooldowns. With the available samples, a 5–40-block replay seek cannot be distinguished reliably from the NoFall heuristic; these Replay NoFall candidates are conservatively suppressed. Live-game NoFall is unchanged. The other four checks retain their thresholds; accelerated playback and missing recorded actions can still reduce reliability. Replay/Atlas detections cannot issue WDR commands; optional Anticheat party Output is separate. Use normal playback speed when checking detection behavior.

The official RavenBS-Plus-Plus HEAD was checked against the pinned commit `14b0a03e8b3af4f109d7c05bc5d0b98d42470179`. It still registers exactly five checks: Autoblock, NoFall, NoSlow, Scaffold and Legit Scaffold. Event classes and similarly named gameplay modules are not additional detectors. There is no omitted sixth check in this source version.

Sanitized local diagnostics now include Replay observation status, actor/profile counters and Anticheat sample/decision counters. They contain no account names, UUIDs, keys, URLs, commands or response bodies. Native Replay statistics still require the original native Replay/Atlas marker, which reads the ordinary slot-1 sidebar title; the four Java-assisted visibility gates alone do not resolve every row-only/team-colored-sidebar variation. The reproduced 17-row failure had that marker enabled and is covered by the dedicated repair.

The sections below retain the prior feature history. v13 evidence is in `evidence/replay-native-v13-audit.md`, `evidence/replay-identities-v13.md` and `evidence/anticheat-audit-v13.md`.

v12 retains the v11 Forge-free Anticheat and Client Side Sounds modules, Badlion window-title fix, party Output behavior, dual payload selection, successful Skin Denicker forwarding, Urchin cache/formatting, configured column order, and the existing result window and icon. The usage ZIP still contains exactly `Adnin.exe`; this README and maintenance evidence are in the separate source archive.

## Anticheat and Client Side Sounds

Anticheat exposes five checks: Autoblock, NoFall, NoSlow, Scaffold and Legit Scaffold. The original port used RavenBS-Plus-Plus at commit `14b0a03e8b3af4f109d7c05bc5d0b98d42470179`; since v17, only Scaffold uses the Mellow implementation recorded above. No other Raven gameplay modules are included. The master switch defaults off; the five check switches and Flag Sound default on. The menu also exposes a 0–60 second per-player/per-check flag interval (default 20), Ignore teammates, Only Atlas suspect, Auto report (off by default), and a comma-separated Ignored players list. The interval uses dedicated minus/plus action buttons with disabled bounds. The enemy option and bottom menu watermark have been removed; the MIT and GPLv3 notices remain in source and EXE resource 202.

Flags appear locally with the player's formatted nametag and a clickable WDR action for valid live account names. Optional Auto report sends a WDR command only when the corresponding alert clears its cooldown. It was not exercised against a real server during testing. Atlas identities and Replay actors never produce report commands. Since v15, the independent Anticheat Output option may forward these alerts to party chat. The removed enemy setting has no runtime behavior, even when an old saved property is present.

Both old native detector calls are replaced by guarded five-byte NOP patches. Old JNI/config fields remain solely for compatibility, so historical settings cannot turn the retired detector back on. The old checks and the new checks do not run together.

Utils → Client Side Sounds supplies the block-placement sound behavior of the supplied ClientSideSounds 1.1 JAR. It defaults off in Adnin and takes effect immediately when toggled. In remote non-creative play, placing a held block plays its existing Minecraft sound locally; matching delayed server echoes are suppressed. Functional-block interaction, sneaking, replaceable blocks, six placement faces and the ping-plus-500-ms echo window follow the supplied behavior. Singleplayer, creative mode, non-block items and air-use packets do not add prediction sounds. There is no Forge dependency, new audio pack, or required sidecar JAR. The original `/bps` command is replaced by this Utils control.

The shared integration uses client-thread callbacks and a bounded Netty observer. Game/audio calls stay on the client thread, outgoing packets are forwarded unchanged, and world/connection changes clear pending sound work. Disabling the sound option restores sound forwarding. A two-second heartbeat lease makes the observer stop suppressing sounds and remove itself after callback loss or native unload. The compatibility unload barrier also explicitly shuts down both modules.

`GuiIngame.updateTick` owns detector sampling at a fixed tick phase, including catch-up ticks. Both native profiles maintain that hook independently of the Session Stats display setting. Ordinary frame/scheduled callbacks no longer consume detector ticks. Missing/backward/stalled samples restart evidence while preserving cooldowns. Legit Scaffold associates one fresh sneak edge with a nearby swing start instead of requiring the edge at animation phase 1; its three-cycle threshold remains. `evidence/anticheat-fixes-v12.md` records historical Raven fixes, while `evidence/ui-api-scaffold-language-v17.md` records the later Mellow Scaffold replacement. Detection quality still needs live validation on each client.

## Replay overlay

The actual visible sidebar title and its last 15 visible rows are checked for `replay`, ignoring case and Minecraft formatting. A team-color-specific sidebar takes precedence over the ordinary sidebar, matching the game renderer; hidden rows and the Tab objective do not activate this mode. Replay supplements the native overlay enabled gate, both render paths and model refresh. It does not change the native global game state or manufacture match-start transitions for AutoGL or Urchin. Since v19, the detected Replay state independently permits enabled Output categories. Losing the sidebar/world clears the observed state and retires queued output. The overlay still shows eligible players in the current Tab roster; historical Prequeue chat-cache names are not admitted as Replay actors. A Replay actor omitted from Tab is therefore not automatically added to the stats overlay. Generic Replay titles preserve the existing last-known/default game-mode selection. Anticheat cannot report historical players; optional party Output is separately controlled. See `evidence/replay-overlay-v12.md` for historical overlay gates and `evidence/output-cache-team-v19.md` for the current Output gate.

The always-installed tick HUD skips native Session Stats drawing when disabled or stopped. A shared lifecycle lock spans its native preparation and complete draw, and both native profiles wait for Java stop before End-key unloading. This keeps any remaining Java HUD wrapper inert after native unload; it does not claim that the old GUI object was restored. See `evidence/session-hud-lifecycle-v12.md`.

New settings are saved in the same game-local `adnin-features.properties`. The release does not include that file, API keys, or personal endpoint settings. `resources/THIRD_PARTY_NOTICES.txt` includes the Raven MIT license and is also embedded verbatim in the EXE as resource 202.

## Features

- Adnin branding replaces the old runtime class names, JNI exports, menu text and native colored prefixes.
- The menu's upper-left title shows only red `Adnin`, as introduced in v7. The `/config` command still opens the menu; its text is removed only from the visible title.
- Settings → Urchin API Key: masked editable key, with show and clear controls.
- Configured column order is shared by native columns, FK/LV and Urchin. Moving either new column with the menu arrows moves its header and values together; disabled columns reserve no space. Settings are independent for Bedwars, SkyWars, Duel and BW Duels.
- Overlay → Urchin: adds a column aligned with the existing native Tab rows, containing a local tag icon and individually colored compact labels: `blatant_cheater` → `BC`, `closet_cheater` → `CC`, `sniper` → `S`, `confirmed_cheater` → `Confirmed`, `legit_sniper` → `LS`, and `possible_sniper` → `PS`. The misspelling `comfirmed_cheater` is accepted as an alias; case and space/underscore/hyphen variations are normalized. Unknown tag types keep their readable names in gray. Local chat uses `Confirmed`, `LS`, and `PS` for those three types, retains the full type for other tags, and preserves each complete reason under `[Urchin]`. Seraph tag messages use `[Seraph]`.
- Overlay → Bedwars → FK/LV: final kills divided by the Bedwars Stars value. The parser now accepts native colored prestige cells such as `§f[187✫]`, including formatting between individual digits. It uses the existing native statistics without another API request. Display uses two decimal places; missing/invalid values or zero Stars show `--`. Color selection uses the unrounded ratio.
- Chat Overlay → Output: four switches control Player Data, Nick / Denick, Seraph / Urchin Tags, and Anticheat. Enabled categories forward eligible generated messages through `/pc ` only in a game or Replay. Tag Output also has Include Self and Include Teammates options. Successful Skin/Number/Bot results and Nick notices use Nick / Denick. The original Skin source covers successful resolutions only. The party version omits a leading `[Adnin]`, while `[Seraph]` and `[Urchin]` remain. Messages split only as required by the accepted packet capacity. Bot `No results`, request errors and unavailable-verification notices stay local. Enabling a main Output category also enables In Chat; its statistics filters still apply. Main Output switches start disabled.
- Utils → Bot Denicker: optional nick lookup using a URL supplied by the user. The URL starts empty. Both `?q=<>&page=0` and `?q=&page=0` work. The nickname is URL encoded; all other query parameters are preserved. Exact `nickname`/`aliases` matches supply `username`; missing, invalid or conflicting results display `No results` locally. Candidates come only from the original native Denicker's filtered Tab-player branch. The description under the toggle has been removed; the toggle and URL field remain.

Application-generated menu text, supported local status/error messages and injector dialogs follow Settings → Interface language: English, 简体中文 or 繁體中文. API-returned tag reasons, player names and received game chat retain their original content.

Urchin colors follow the supplied official icon screenshot's color families, mapped to Minecraft 1.8.9's fixed legacy palette: sniper, legit sniper and possible sniper use red (`§c`, `#FF5555`); confirmed cheater uses purple (`§5`, `#AA00AA`); blatant cheater, closet cheater, caution and account use gold (`§6`, `#FFAA00`); info and unknown types use gray (`§7`, `#AAAAAA`). These are palette approximations, not pixel-identical screenshot colors. Each Tab abbreviation owns its color and reset. In local chat, only the tag label has that color; the colon and complete normal reason explicitly use white (`§f`) for every tag type, and each tag ends with a reset. Normal server-provided reason text is preserved without translation or rewriting.

Local Urchin chat keeps the original player's nickname/identity and follows the scoreboard nametag color. If that color is unavailable, it uses a recognized custom Tab color, then white if neither is known. A Bot-resolved account name does not replace the player's live identity or nickname in this display.

Urchin requests use `GET /v3/player/tags?player=...` on the official Urchin HTTPS service and the `X-API-Key` header. That key is never attached to a Bot or Mojang request. HTTP runs on a bounded background worker; drawing an overlay and reading cached tags do not perform HTTP.

Urchin failures retain a fixed category from the HTTP/transport layer through the worker and local chat. A player-not-found response (HTTP 404) stays silent and does not consume or extend the visible-error throttle; its failure category and cache cooldown remain intact. Other authentication, permissions, rate-limit, service, DNS, TLS, timeout, connection and response errors have separate fixed localized messages with red bodies, including retry guidance. Bot failure bodies are also red. Exception details, request URLs, keys and raw response bodies never enter these messages. The original reported failure was not reproduced in the historical authenticated checks for v4. Those standalone probes do not prove that every in-game request will succeed; `evidence/api-verification-v4.json` is explicitly historical evidence.

Native Hypixel/Seraph error tails are changed from gray to red through equal-length native-string edits in `scripts/reembed.py`. The v7 integrated checks verified exact original-string guards and confirmed that these color edits do not change the base assembly or `.text` section; current v12 results are recorded in `验证状态.md`. The injector's `failed` status and log-export error text use red. These application error colors do not recolor ordinary server-provided chat or normal Urchin reason text.

## Urchin requests and content cache

Roster scanning, match-entry queries and tag presentation share the same current-Tab profile gate: the entry must have a valid player name and a UUID of version 1 or 4. This excludes the native UUIDv2 bot category, UUIDv3/offline and other UUID versions, malformed profiles, and native cache/placeholder rows without a corresponding eligible current Tab entry. v6 Urchin queries already checked UUIDv1/v4; v7 shares that existing boundary with roster scanning and presentation rather than claiming a newly discovered query bug. A server-forged NPC using a valid-looking UUIDv1/v4 profile cannot be independently distinguished from a player by this gate; the recovered native filter has the same limitation. `evidence/native-tab-filter-v7.md` records the source/native filter analysis and its limits; it is not live-game evidence.

The sole automatic trigger is the existing native scoreboard detector's transition from a non-`Ingame` state into `Ingame`. On the following eligible client tick, Adnin freezes one roster batch, reuses fresh cached responses and queues only uncached or expired identities. The native callback itself only advances a serial counter. Opening Tab, drawing the overlay, changing columns, later player arrivals and periodic client ticks do not create additional Urchin batches. A Bot identity resolved after that snapshot also does not trigger a mid-match Urchin lookup.

This trigger means **entering a detected ongoing game**, not exclusively the countdown reaching zero. Rejoining an ongoing game, or first attaching while already in a game, can produce the same transition. Such entries use the same cache rules; a fresh cached identity does not cause another HTTP request. Normal queue-to-game transitions create a new roster snapshot. Detection follows the original native mode and scoreboard logic.

The bounded in-memory cache holds up to 512 identities, deduplicates UUID case/hyphen variants and lookup-name case, and shares a result across aliases of the same identity. Successful responses, including an empty tag list, remain fresh for 10 minutes. Failed requests have a 45-second cooldown. A response expires only after its applicable interval has been exceeded; expiry is checked at the next match-entry snapshot. Tags already selected for the current match remain available for display, and expiry or an error does not schedule a retry during that match.

World changes clear the current match view but preserve completed content and valid in-flight identity requests for later matches. A repeated identity can share an existing request rather than enqueue another one. The cache is in memory only and is cleared by game restart or explicit module shutdown. Changing or clearing the Urchin API key retires its pending reservations, queued batch and visible match selection, while preserving completed response content. Obsolete in-flight completions are rejected. Fresh completed cache entries can be reused; any newly admitted request uses the current key at the next detected match entry. Changing Bot settings does not clear Urchin content. The cache is limited to 512 identities and an estimated 4 MiB of stored content.

## Bot identity and statistics

After an exact API nickname match, a background request resolves the returned real name through the official Mojang profile endpoint and validates an exact account-name match and an online UUIDv4. Only a validated result can supply a real UUID to the native statistics-request bridge. This permits the existing statistics pipeline to retrieve the matched account's statistics without modifying the live player `GameProfile`, network identity or Tab roster. A temporarily unavailable account resolution is reported as such and is not treated as a verified UUID.

The Bot result is displayed locally as `Bot Denicker <nick> → <real name>` or `No results`; lookup errors and temporarily unavailable account verification also retain local feedback. The `Bot Denicker` title is gold, the arrow is neutral, and each name uses its own recognized nametag colors. If the resolved real name has no visible nametag of its own, it inherits the first actual nickname color, never a preceding rank color; missing nickname formatting falls back to white. `No results`, request-failure text and the full unavailable-verification note are red. With Output enabled, only a positive result whose account identity has been verified is forwarded to party chat, after local formatting is stripped. A later eligible retry that succeeds can still announce and forward the verified result; an earlier local failure or unavailable-verification notice does not suppress that success. Bot has its own request generation and cooldown; its later completion does not rebuild the frozen Urchin roster.

Bot cache entries hold identity, provider, status and expiry together, with at most 512 entries. Verified matches and explicit no-result responses remain fresh for ten minutes; transport or account-verification failures use 45 seconds. World and match changes preserve them. A completion for a player who left the current roster is cached without output; a verified cached result can be announced once when that identity is eligible in a later match. Changing the Bot URL or its feature toggle clears these entries. Only Nick / Denick Output controls party forwarding, and the game/Replay gate still applies.

Replay profile lookup independently retains verified accounts and confirmed absent-account `[NICK]` results for ten minutes; API failures are not Nick evidence and use a 45-second cooldown. Native Skin and Number Denicker identity caches already span matches. This release preserves those caches without changing their original TTL or claiming they share the Java cache implementation.

## FK/LV colors

| Unrounded FK/LV | Color |
| --- | --- |
| 80 and above | `#AA00AA` |
| 70 to below 80 | `#FF55FF` |
| 60 to below 70 | `#AA0000` |
| 50 to below 60 | `#FF5555` |
| 40 to below 50 | `#FFAA00` |
| 30 to below 40 | `#FFFF55` |
| 25 to below 30 | `#00AA00` |
| 20 to below 25 | `#55FF55` |
| 15 to below 20 | `#FFFFFF` |
| 0 to below 15, or missing | `#AAAAAA` |

Screenshot-derived regression cases include `4517 / 187 = 24.16`, `92 / 17 = 5.41`, `14338 / 205 = 69.94`, and `0 / 1 = 0.00`. A value of `79.999` displays as `80.00` but retains the 70–80 color because coloring precedes display rounding. FK/LV is offered only for Bedwars. Both added columns participate in the original native width and screen-layout handling. The recovered native prestige formatter has a separate four-digit limitation for Stars at or above 10000; the current implementation parses the supplied cell and does not repair that original formatter.

## Party output

Output has four independent switches: Player Data for statistics; Nick / Denick for Nick notices and successful Bot, Skin and Number results; Seraph / Urchin Tags; and Anticheat. All four default off, and enabling a main category also enables In Chat. The original In Chat filters still apply to statistics. Tag Output has separate Include Self and Include Teammates switches, both initially on; they retain their values while the parent category is off.

Both enqueue and delivery require the native `Ingame` state or an active Replay detector. Leaving that context discards pending events, so lobby ticks cannot send old party text. Turning off a category also removes its pending events. Skin forwarding is restricted to its native success branch; received chat and Skin failure/no-result paths are not Output sources. Bot forwarding requires a verified positive result; `No results`, errors and unavailable-verification notices remain local. Native API errors are also local-only.

The v9 party-output behavior is retained: before sending, Adnin removes local color/style codes, normalizes whitespace and strips only a leading `[Adnin]`. It preserves embedded `[Adnin]` text, `[Seraph]`, `[Urchin]`, names and tag reasons within the existing normalization budget. Local messages retain their original branding and presentation. For example, local `[Adnin] Nick -> RealName` becomes `/pc Nick -> RealName`; `[Urchin] Player: reason` becomes `/pc [Urchin] Player: reason`. Deduplication uses this final party body, so colored, branded and unbranded aliases of the same result share one 30-second deduplication entry.

The queue stores up to 32 whole events and delays splitting until each send. On the client tick immediately before sending, Adnin constructs a `C01PacketChatMessage` probe and reads the text accepted by the current runtime, with an upper bound of 256 UTF-16 units for the complete command. The probe itself is never sent. A 256-unit capacity allows 252 units of body after `/pc ` in one command; vanilla's 100-unit capacity allows 96 body units and splits only when necessary. A capacity change also applies to the unsent remainder. Splitting preserves surrogate pairs, so a boundary may carry one fewer unit. Client acceptance does not establish acceptance by every multiplayer server.

The old eight-fragment cutoff has been removed. The existing `cleanText` budget remains 1,500 UTF-16 units before leading-brand removal and final whitespace normalization, including any `[Adnin]` prefix; it is not an unlimited message budget. The 15-second event expiry and minimum 1.5-second send interval also remain. An unsent remainder keeps the event's original timestamp and can expire while waiting, including when a long message needs many 96-unit fragments. Disabling Output or changing worlds clears pending output. Tests verify exact reconstruction of normalized content within these limits while the event remains unexpired; they do not assert that every queued message will reach a live party.

## Use

1. Exit a game instance containing an older copy of the DLL, then start Lunar, Badlion or Vanilla Minecraft **1.8.9 x64**.
2. Double-click `Adnin.exe` with one recognized game window open. A separate DLL is not required. The matching embedded DLL is checked against its expected size and SHA-256 before use.
3. Open `/config` in a world. Fill your own API key and Bot API URL as needed, and select the Overlay columns you want.
4. After entering or changing the Urchin key, close the menu and wait for the next detected match entry. Opening Tab or waiting for a cache interval does not itself request tags.
5. Enable Bot Denicker and/or Output deliberately. Output messages are visible to your party.

The reported Badlion failure stopped at target discovery with exit code `3` and `No recognized visible Lunar, Badlion or Vanilla game window was found`. No DLL had been loaded in that attempt. The visible `LWJGL` title used `Badlion Minecraft Client`, which was absent from the earlier brand rules. v10 accepts that longer brand as well as the existing `Badlion Client` spelling, including the Badlion label after a `Minecraft` title. In the observed title, `v4.4.4` identifies the client build and `(1.8.9)` identifies the supported game version. Explicit `(1.20)` and `(1.8.8)` game versions remain unsupported. The final v10 EXE completed both `--client auto --dry-run` and `--client badlion --pid ... --dry-run` checks against that open process with exit code `0`; neither test loaded a DLL. These checks establish target discovery and preflight only. Game initialization, the `/config` menu and multiplayer features remain unverified. `验证状态.md` and `evidence/badlion-window-v10.json` record the results and their limits.

Client selection is `auto` by default. Explicitly different game versions, unknown windows and ambiguous targets are rejected. If several clients or game instances are open, use `--client auto|lunar|badlion|vanilla` and, when needed, `--pid` to select the intended process. An explicit PID does not bypass client/version checks. These PowerShell examples use `12345` as a placeholder PID to replace with the actual game PID:

```powershell
.\Adnin.exe --client lunar --dry-run
.\Adnin.exe --client badlion --pid 12345 --inject
.\Adnin.exe --client vanilla --inject
```

When selecting a target with command-line arguments, the injector performs a dry run unless `--inject` is present. `--help` lists the remaining options. Double-clicking with no arguments performs injection and shows the result window. The 1.8.9 profiles do not provide compatibility with other Minecraft versions or arbitrary client modifications.

The injector refuses to attach a conflicting old/new DLL copy to the same game. It reports success only after the selected runtime's Minecraft/GUI references are initialized, required hooks are installed and its heartbeat advances. DLL presence alone does not pass these checks. The Badlion/Vanilla compatibility scheduler queues bounded work on Minecraft's client thread without depending on Tab rendering, HUD visibility or Session Stats. Runtime initialization checks are separate from testing every feature in a live game.

Double-click launches without a console and shows one compact result window with `success` or `failed`. The current release retains the earlier typography, visible keyboard-focus cue and success hold/fade timings. The `failed` status and export-error feedback are red.

- The window fades in over approximately 200 ms. On success it stays at full opacity for two seconds, displaying `Closing in 2s` and then `Closing in 1s`, before an approximately 200 ms fade-out.
- A red-dot close control closes either result window through the same fade-out. A failure remains open until closed and offers `Export log`; it does not disappear on the success timer.
- Exported diagnostics contain the current injection attempt's fixed failure category, phases and technical status, without API keys, URLs, personal settings or chat. Export feedback stays in the same window.
- The transparent metallic icon is embedded in the EXE. The v7 build verified its seven ICO frames against the approved `src/injector/assets/adnin.ico`; the companion `adnin.png` is a source-maintenance asset and is not a required sidecar file.

## Empty defaults and existing local settings

All compiled API key fields and the custom Bot URL start empty. **A clean first run means a game-data directory without saved settings; launching a new EXE on the same PC is not a clean settings profile.** Urchin and Bot settings are loaded from `mc.mcDataDir/adnin-features.properties`, not from the EXE folder or the extracted-DLL cache. `urchin.apiKey` and `botDenicker.url` in that file replace the empty in-memory defaults during the first feature initialization. An existing file is therefore reused after changing EXEs or restarting the game with the same game-data directory.

Initialization is latched for the current Java class instance. Reopening the menu does not repeatedly reload the file. Editing or clearing the Urchin key or Bot URL through the menu schedules a save back to the same file, using a short delay and a temporary-file replacement. This is separate from the recovered native configuration, which handles the original Hypixel, Seraph and Aurora key fields; native configuration does not independently persist the two new Urchin/Bot fields.

During the v5 investigation, the existing local settings file was identified using metadata only. Its private contents were not read or changed, and no saved credential value was compared or cleared. The audit confirmed empty source and v4 compiled defaults and traced the settings lifecycle; the displayed old values are consistent with the existing-file reuse described above.

Do not share `adnin-features.properties`. `adnin-runtime-status.properties` contains only initialization/counter diagnostics and a fixed Urchin error category. User settings, personal API keys and custom Bot URLs are excluded from release archives. Public service endpoint constants remain necessary for Urchin and Mojang functionality.

## Build

Run `Build.ps1` in Windows PowerShell. Optional arguments let you supply `-Toolchain`, `-CMake`, `-Ninja`, `-Nasm`, `-Jdk`, `-Python`, `-RuntimeClasspath`, `-VanillaJar`, and `-BuildDirectory`. Install the Python package in `requirements.txt` in the selected Python environment.

For a separate v21 build directory, run `.\Build.ps1 -BuildDirectory build-v21`. If the vanilla 1.8.9 JAR cannot be discovered locally, pass `-VanillaJar` with its full path. After the build and its checks complete, package it with `python scripts/package.py --build build-v21 --version v21` from the project directory. The usage ZIP contains only `Adnin.exe`; the source archive and release manifest are separate maintenance artifacts. Packaging verifies both payloads and their compiled Java source/mapping hashes, and preserves earlier versioned archives. `AdninGuiNewChat` is retained dormant only in the Lunar payload; the compatibility build compiles and hashes it as an active entrypoint. `AdninClientPump` is Lunar-specific and is not compiled into the compatibility payload. Both sources remain privacy-checked and included in the source archive.

The build requires an x64 Windows C/C++ toolchain, CMake, Ninja, NASM, Python with pefile, and a JDK 9+ compiler capable of targeting Java 8. Local Minecraft dependencies and the vanilla 1.8.9 JAR are required for compilation and ABI verification; this project does not redistribute game JARs. The script prefers a locally prepared `work/dependencies/vanilla-1.8.9-named.jar`, falls back to the analyzed local Lunar JAR, or accepts an explicit named compile classpath through `-RuntimeClasspath` and a vanilla runtime JAR through `-VanillaJar`. Keep transformed Lunar classes and their private libraries from the same client revision; launcher updates can invalidate that pair. The v21 final build uses real vanilla bytecode remapped to named APIs, with the original interfaces preserved, so compilation does not depend on Lunar's changing private interface names. The compatibility mapping subset is included as data in `resources/java-compat-1.8.9.json`.

The build assembles the fixed native images from `src/native/ChatReaderLunar.asm` and `src/native/ChatReaderVanilla.asm`. It compiles the shared Java features, remaps the compatibility references, embeds helper bytecode, verifies original field/method ABI and appends the appropriate JNI bridges. Compatibility helpers use the Minecraft class's protection domain; owned JNI fixtures check signed-loader bootstrap behavior without initializing the game. The injector is built against generated runtime descriptors and embeds the Lunar `Adnin.dll` as resource 101, compatibility `AdninVanilla.dll` as resource 102, and the approved icon. Each DLL resource must be byte-for-byte identical to its generated file, and the EXE must use the GUI subsystem. Native initialization and Java/API/output regressions then run. No original DLL is required to assemble either baseline. Both DLLs in `build-v21/bin` are internal build/test artifacts and are not needed beside the delivered EXE.

The selected build directory's `build-report.json` records both runtime payloads and final artifact hashes. Lunar details are in `reembedding.json`, `bridge.json` and `java-runtime/java-build-report.json`; compatibility details are in `vanilla-reembedding.json`, `vanilla-bridge.json` and `java-vanilla/java-build-report.json`. `验证状态.md` records the current and historical test scope and limitations; the release manifest records final EXE/DLL sizes and SHA-256 values. Archive publication uses the separate packaging command above; successful compilation, static verification or offline tests are not a claim of live game validation.
