# v22 input safety and frame-work review

This follow-up keeps game/JVM/thread priorities, frame limits, detector sampling,
game input state and world-render order unchanged. Its changes address concrete
input-lifecycle bugs and repeated work. It does not promise zero overhead or
establish that every reported frame hitch has the same cause.

## Confirmed input defects

- Both native unload polls read system-wide End state, including its historical
  press bit, while the Java client path also accepted End without screen/focus
  checks. The new single client-thread admission requires foreground Display,
  captured game focus, a local player/world, no GUI, and a release followed by
  a new press. Ineligible contexts disarm the gesture. Native workers read the
  accepted request only, after client cleanup completes; key release does not
  cancel a pending safe unload. The original 20 ms worker polling interval stays.
- Closing Gui4 while holding a slider continued editing during its fade. Resize
  or F11 reset the closing timestamp and retained stale hitbox drag coordinates.
  Close/release/resize now cancel pointer editing; resize preserves an ongoing
  fade. Normal focus recovery remains Minecraft's displayGuiScreen(null) path.
- Gui4 now scopes keyboard repeat to its own lifetime, restores the previous
  value on close, and accepts keypad Enter like main Enter. Repeated resize does
  not replace the captured previous repeat state.
- The timed success window now uses nonactivating display/window styles. Failure
  retains its interactive diagnostic-export controls. No game focus API is called.

## Window subclass ownership

The original native maintainers could install themselves again above a later
third-party subclass, then record that subclass as their predecessor. If it
already forwarded to Adnin, this formed a cycle. Original teardown also restored
a predecessor without checking whether a later subclass owned the window.

The replacement binding preserves the original native message handler and its
return values. A stable live binding is not repeatedly reinstalled. Installation,
window destruction and explicit unload validate ownership and window identity.
Ordinary messages use only active-call accounting and original-handler forwarding.
An outer subclass which still references Adnin prevents native image release;
unload must wait rather than leave it pointing at freed memory.

The existing deliberate Tab/local-command/optional-click feature handling is
preserved. This review must not be described as making every message transparent
when an explicitly enabled existing feature intentionally handles that message.

## Equivalent repeated-work reductions

- The native chat collector previously converted up to 100 historical components
  to JSON/formatted text before its unchanged consumer discarded old entries.
  The collector now preserves and fully converts the first boundary record, then
  stops at the same counter condition where the consumer already stops. No
  identity-only cache of mutable components is introduced. The first record is
  still converted so same-counter text/style changes retain their old behavior.
  Owned equivalence fixtures run the original consumer for both native profiles;
  a stable 100-record history performs one complete conversion instead of 100.
- Overlay configuration uses bounded immutable snapshots. Direct JNI/public-array
  edits, mode switches and clearing still invalidate the result immediately;
  unchanged calls avoid sorting, CSV rebuilding and normalization allocations.
- Ordinary atlas text uses monotonic width searches; shaped Unicode/formatting
  retains the reference scan. Input clips use a maximum ten-entry per-screen
  cache, bounded to 2,048 source characters per entry and cleared on close.
  Personal input is never written into the distribution or a static clip cache.
- Rounded-shape angles are precomputed. Geometry regression compares the emitted
  float vertices bit for bit and in the original order. GL calls, drawing order,
  depth/blend restoration and the surrounding game renderer remain unchanged.

The owned Java 8 fixture reduced unchanged column getter allocation from
6,528 / 8,480 / 10,088 bytes to zero for its respective getter workloads. A
2,048-character URL's direct clipping fell from about 43.9 ms to 0.10 ms in the
same fixture. These are offline CPU/allocation measurements with owned GL
boundaries, not measured live FPS, GPU time or total-process memory changes.

## Read-only live observations of the preceding package

The currently running Lunar payload was hash-matched to
`63e44039cc6f63e0d3bcfe6df6ad54878cd7a943ac2e9c18e015d3f60b0c4ff2`.
Read-only initialization and advancing heartbeat checks passed. The process was
responsive and its priority class was Normal. No process/thread priority was
changed, and no target code was invoked, suspended or reinjected.

A nine-second jstat observation showed four additional young collections,
approximately 23 ms added young-GC time, and no additional Full GC. This short
sample cannot assign a particular frame hitch to Adnin or rule out later GC.
Whole-client memory/CPU includes Lunar, the game and other loaded components.

Lunar's native heartbeat is a GetTickCount64 timestamp, not a callback counter.
A roughly 1,000-unit delta per second must not be interpreted as 1,000 callbacks.
Static inspection confirms its scheduled pump has a 50 ms/pending-task gate;
the render entry can additionally reach the shared pump's existing 20 ms gate.

Frame-scalar inspection was left unclaimed because multiple Minecraft class
definitions remained ambiguous even after classloader/mirror checks. No guessed
object or class was used to report FPS.

## Additional normal-movement path review

The Java renderer/HUD/client-pump review found no recurring file or network wait,
join, Future wait, forced GC, priority change, cursor capture or focus mutation
in its steady-state path. Initial configuration loading remains a bounded,
one-time synchronous read. Worker HTTP, configuration saves and Replay identity
resolution occur outside the client integration monitor. Reviewed lifecycle lock
orders did not reveal another concrete inverse-lock cycle.

Remaining profiling candidates include per-frame Session HUD translations and
width calculations when enabled; full-scoreboard sorting before selecting the
last 15 visible rows; per-tick bounded roster-name parsing; a coincident periodic
and match-start roster scan; and rebuilding visible tag rows for completed
requests. These have not been attributed to the user's frame hitches. Their
behavior is unchanged in this follow-up. NoFall terrain checks remain guarded
by a narrow drop condition, a height bound and an already-loaded-chunk check.

## Verification status

The coherent `build-v22-input-perf` completed with unchanged production source
hashes and exact embedded DLL/resources. All six CTests passed, including the
18-case injector UI/cache suite. Native Lunar/compatibility suites passed 77/44;
the complete existing Java, compatibility, reembedding and privacy suites passed.

Both Java 8 and 17 passed input admission 38, concurrent unload publication 103,
game tick 48, HUD lifecycle 25, JNI stop acknowledgment 6, UI input lifecycle 88,
exact clipping/config/geometry 146,868 and UI resource lifecycle 4,798 checks.
Real isolated OpenGL passed 644 checks across seven panels, all three languages
and both scale extremes; representative images were inspected. Both profiles'
bootstrap stress passed 4,000 calls and collected 12/12 classloaders on both JVMs.

The final exact DLL chat-helper test passed 314 equivalence cases, 1,545 ABI
comparisons and 19 real unwind instruction boundaries per profile, with hashes
matching `验证状态.md`. Per-profile normal-message fixtures forward 10,000
messages without an added ordinary-path WinAPI call or wait.

Build/UI tests used an unswitched private desktop and reduced priority only for
owned test/build processes. Initial UI window-count failures were traced to two
Windows InputSwitch.dll widgets created on that desktop, not new application
dialogs. The test now requires exact widget classes, empty titles, nonactivation
and verified System32 module registration before excluding them. No product
change was made for that fixture issue.

Detailed logs and exact-artifact checks are under `work/v22-input-review`.
The running preceding payload is not live evidence for this candidate. This
follow-up has not been reinjected, FPS-profiled or measured over a live session.
