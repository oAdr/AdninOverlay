# v23 shared configuration and Lunar auto-query follow-up

The user requested one configuration for Lunar, Badlion and Vanilla, including
all API keys and the custom Bot Denicker URL. The canonical file is now
`%LOCALAPPDATA%/Adnin/config.properties`. It captures all public persistent Gui4
settings, Anticheat/Output options, language, scale and four column layouts.
Transient world/reset state is excluded. No private files were read to prepare
the migration tests; owned temporary profiles use explicit synthetic values.

The initial client imports the newest valid native toggles.json from the
hash-specific Adnin payload cache and its old feature properties only if no
shared file exists. The one-level migration examines at most 256 app-cache
entries, accepts only expected hash directories and bounded JSON primitives,
and uses Gson. This covers a new DLL hash whose native loader cannot find the
previous hash's saved API keys. Native-loaded values remain the fallback.
Thereafter even a blank, partial,
malformed, oversized or unreadable shared file is authoritative. Defaults are
captured before native legacy settings load. Missing credential fields default
blank, so legacy values cannot silently return. Legacy files remain untouched.
Switching clients uses the shared file at initialization; running clients do
not watch/reload changes from another process. Simultaneous full-profile saves
use atomic replacement; the most recent save wins.

Both sole native save call sites have pinned argument/neighbor bytes and a
reviewed JNI callback. The original synchronous JSON writers are retired.
The existing API daemon saves changed snapshots with a 600 ms debounce and
unique temporary-file atomic replacement. No extra save thread, game input,
render priority, network on the game thread or Anticheat sampling change is
introduced. Config file reads are bounded to 64 KiB. Shutdown releases snapshot
references without waiting for the daemon on the game/unload thread.

Lunar's observed pre-game phase was `Waiting...` followed by a single U+26BD
row key, which its font proves width zero. The prior build removed only
zero-width surrogate-pair keys, so waitingAllowed remained false with zero
send attempts. Existing pipeline installation and heartbeats were healthy.
The new parser also handles one BMP OTHER_SYMBOL under current-font proof,
with strict phase/footer composition and the existing retry/scope policy.

Focused Java 8 and Java 17 results are recorded in 验证状态.md. Party policy
225, sidebar 115, accessor 66, real Netty pipeline 498; production query
adapter 74 checks across 27 scenarios. The prior packaged bytecode fails the
new observed BMP-key fixture; the changed adapter passes waiting/countdown,
relay, visible-symbol and immediate font-change cases.

The coherent build additionally runs the same settings test against the
remapped Badlion/Vanilla runtime, JNI callback/null/exception/unwind checks for
both native payloads, shared-language CRLF/precedence tests, resource shutdown
and packaging privacy tests. Final results are recorded in 验证状态.md.

No new DLL has been loaded into a live game for this follow-up. Offline checks
do not prove real group detection, actual API service success, long-session
stability or absence of stutter. Restart a game with an older payload before
live verification.
