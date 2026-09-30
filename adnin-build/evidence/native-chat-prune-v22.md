# Native chat history polling audit

## Verified opportunity

The ordinary renderer callback can enter the native pump even when the menu and
Tab are closed. Its periodic chat reader formerly converted up to 100 history
rows through JNI/JSON/text on each eligible poll. The original consumer only
uses the first accepted record during initialization, and otherwise stops its
selection at the first accepted row whose signed counter is at or below its
nonnegative saved counter. A negative saved counter retains the full scan.

The existing Lunar scheduler already has a pending gate and a 50 ms minimum
dispatch interval. The recorded heartbeat is GetTickCount64 milliseconds, not a
callback count. This change does not alter either scheduler or pump timing.

## Implemented change

Both hash-pinned native profiles now branch from the collector's loop tail to a
small bridge fragment. Only the reviewed periodic caller is eligible. The
separate one-row reset caller remains unchanged.

The collector fully converts and appends the first old boundary record before
it stops. Its JSON, formatted/plain text and component reference therefore
remain available to the unchanged consumer, including the same-counter but
changed-text special case. Empty or failed conversions still follow the
original loop, and existing collector/consumer cleanup retains ownership.

No component cache, cross-poll identity assumption, new lock, new JNI operation,
allocation, thread-priority change, input change, renderer change, or additional
scheduling is introduced. The fragment modifies only RAX and flags, which are
already scratch values at this tail; it replays the displaced loop increment
when collection continues.

The bridge has a chained Windows unwind record pointing to the original
collector frame. Offline RtlVirtualUnwind testing found that a direct tail JMP
could be misclassified as a leaf epilogue. Both exits use a fixed conditional
transfer instead; all 19 actual instruction boundaries now restore exactly the
same stack and saved registers as the original collector.

## Offline validation

- Lunar native regression: **77/77 passed**.
- Badlion/Vanilla native regression: **44/44 passed**.
- Each profile: **314 consumer-equivalence cases**, including initialization,
  empty/failed records, same-counter text/style changes, metadata changes,
  multiple new records, out-of-order counters, rollback, signed counter wrap,
  reset/resync and 300 deterministic randomized histories.
- Each profile: **1,545 tail ABI comparisons** covering all non-RAX GPRs,
  XMM0–15, stack/index preservation, and the original index increment.
- Each profile: **19 real RtlVirtualUnwind comparisons**, one per actual helper
  instruction boundary, including both conditional exits.
- Every retained mock component reference is released exactly once.
- Static checks preserve every original collector byte except the six-byte
  hook, the entire consumer, reset caller, original unwind data and function
  table entry. The new function table entry and chained record are checked.
- The final isolated execution report uses exact helper bytes from newly
  generated production-layout DLLs and records their SHA-256 hashes.
- A fixed unchanged 100-row history fixture performs **1 conversion instead of
  100**, retaining the complete first record.

Evidence is in `work/v22-input-audit/chat-prune-equivalence.json`,
`work/v22-input-audit/native-lunar-chat-regression.log` and
`work/v22-input-audit/native-compat-chat-regression.log` relative to the workspace.
The temporary native DLLs used for exact-helper execution are under
`work/v22-input-audit/chat-prune-production`; they are not release artifacts.

No supplied DLL was loaded, no live game was accessed, and no network request
was made. The copied original consumer ran only in owned mock memory with all
external calls replaced. These results establish bounded work reduction and
fixture equivalence, not a measured live FPS gain or a proven cause of stutter.

Exact-helper verification also passed against both final `build-v22-input-perf`
DLLs, with the same 314/1,545/19 results per profile. The report at
`work/v22-input-review/final-native-chat.json` binds these checks to the exact
DLL hashes recorded in `验证状态.md`. For another build, rerun:

```text
python tests/chat_poll_prune_checks.py --production --directory <build>/bin --report <report.json>
```

`<build>` must contain the matching `bridge.json` and `vanilla-bridge.json`.
The test requires the NASM distribution's adjacent `ndisasm.exe`; the normal
configured NASM directory already contains it. This requirement affects offline
testing only and adds nothing to the shipped executable.
