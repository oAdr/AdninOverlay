# Native input-chain lifecycle review

This review covers both the Lunar and Badlion/Vanilla native profiles. It does
not constitute live game validation or a measured FPS improvement.

## Confirmed baseline defects

The published baseline maintenance routines (`0xe7d0` Lunar, `0xe570`
compatibility) reacquired the same HWND whenever its current WndProc changed.
If another component installed a normal subclass above Adnin, the maintenance
routine made that component Adnin's predecessor while its predecessor was
already Adnin. This forms a callback cycle. Both profiles also overwrote the
single predecessor when switching to another HWND while the old window remained
live, and restored the predecessor without checking the current owner at unload.

The exact published maintenance bytes reproduced these conditions in private
memory using fake API callbacks. No DLL was loaded, and no live game/window was
accessed. The baseline reproduction and disassembly are retained under
`work/v22-input-review` in the development workspace.

## Authored fix

- Bind only once during a verified HWND lifetime. A third-party outer subclass
  is never reacquired by the maintenance poll.
- Confirm the candidate belongs to this process; track its GUI thread and a
  window property identifying this binding.
- Do not move the shared predecessor to a second live window. Rebinding requires
  completed old-window destruction, zero active callbacks and the same GUI
  thread. The destruction marker plus property prevents HWND reuse from being
  confused with the old binding.
- Install a small wrapper which calls the existing native WndProc unchanged.
  Ordinary messages add an active counter and direct forwarding only. They do
  not query window properties, resolve APIs, wait on a lock, allocate, or send a
  synchronous message. Every argument and the full return value are retained.
- Once Java acknowledges an explicitly accepted End request, detach through a
  private message handled on the window's own GUI thread. Check the property,
  owner, active-callback count and predecessor before restoring it.
- An outer subclass, active callback, missing API or timeout never authorizes
  FreeLibrary. Preserve compatibility JNI references until detach succeeds.
- A successful synchronous return proves the wrapper has returned. After a
  timeout that completed late, a second WM_NULL round trip through the restored
  original chain supplies the completion barrier. This closes the counter-zero
  versus function-epilogue interval.
- The worker configures a 50 ms SendMessageTimeout only during an explicit
  unload request; this is not an unconditional wall-clock guarantee. The existing
  20 ms poll and one-second maintenance
  interval are unchanged.
- Preserve compatibility's original initializer tail at `0x12240`; it performs
  existing renderer/import setup and must not be skipped.

The original native message handlers are byte-for-byte unchanged. Their
intentional handling of Tab, recognized local Enter commands, and configured
left-click features remains intact. No game/render priority, JVM argument,
mouse sensitivity, keyboard binding or input-device policy was changed here.

## Conservative boundaries

If the old window is gone and no same-thread replacement exists, or a different
GUI thread appears without a proven lifetime transition, the module remains
mapped instead of guessing that it can safely free callback code. A foreign
component that violates the normal subclass contract by later invoking a saved
callback after detaching is outside the verified contract. A single concurrent
owner swap during restoration is detected, restored when still recoverable,
and never authorizes unload.

## Verification

- Integrated final Lunar native suite: **77/77 passed**.
- Integrated final compatibility native suite: **44/44 passed**.
- Each profile executes 10,000 ordinary messages in the private fixture, with
  only its original-handler stub called for each message.
- Tests cover key, mouse, character, focus and raw-input parameter/return
  transparency; nested callbacks; external owners; live-window replacement;
  destruction and reused HWNDs; foreign processes/threads; API failures;
  pre- and post-processing timeouts; restoration races; End acknowledgement;
  retained JNI ownership; and Windows RtlVirtualUnwind for every new frame.
- Real Win32 function resolution is dynamically bound once, never per ordinary
  message. Tests substitute those APIs and never operate on real HWNDs.

The coherent parent build reran both full suites after chat-pruning integration.
Exact final artifact hashes are recorded in `验证状态.md`.
