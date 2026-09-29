# v18 stability investigation

## Reported shutdown and memory evidence

The supplied Lunar 1.8.9 log ends during ordinary game/audio/socket shutdown,
without an OutOfMemoryError or native stack trace. The matching Windows event
records Java 17.0.18, ucrtbase.dll 10.0.26100.9444, exception 0xC0000409 and
offset 0xA527E. Static inspection of that exact system DLL places this offset at
abort's int 0x29 immediately after selecting FAST_FAIL_FATAL_APP_EXIT (7).
This identifies an explicit CRT abort, but does not identify its caller.

The user's Task Manager screenshot shows whole-system memory use of 21.2 GiB
out of 93.5 GiB usable (23%), with 72.4 GiB available. It does not show a game's
individual heap, process commit, direct buffers or GPU allocations. Neither the
screenshot nor the log supports diagnosing physical RAM exhaustion.

Only these sanitized facts are included. The original log, screenshot, chat,
player identity, process identifiers and local configuration are excluded.

## Native repairs

The long-lived Lunar initializer's scheduler calls addScheduledTask and ignores
its returned Future local JNI reference at RVA 0xD5D3A. Other temporaries are
deleted, but the Future remains until thread detach. A pinned one-call-site
wrapper now deletes that non-null return. It preserves argument forwarding and
pending exceptions, and does not cancel the Java task. This is a concrete
continuous-reference accumulation bug, not proof of the reported crash stack.

Recovered CRT teardown invokes registered joinable std::thread destructors.
The original process-detach handler does not first stop those workers. Ordinary
End-key unloading joins workers, but directly closing the game can enter
terminate during CRT destruction. Both payloads now have a leaf PE entry guard
which returns TRUE only for process detach with a non-null reserved pointer
(whole-process termination). Explicit FreeLibrary and all other notifications
tail-call the original CRT entry. No JNI, allocation, wait or lock is attempted
under loader lock. The process-exit case leaves reclamation to the OS.

Both original entry bodies are pinned, and existing import/export/TLS/IAT/reloc
directories remain intact. The new guard is ASLR-safe and has a correct runtime
function/unwind record. The reviewed original TLS callback tables are empty.

Private execution fixtures run the authored wrappers against fake callbacks,
not the original recovered DLL initialization. The JNI fixture performs 100,000
calls with peak one and final zero local references; the baseline retains 1,000
of 1,000. Each entry guard passes 18 reason/reserved combinations, nonvolatile
register checks and actual RtlVirtualUnwind. Independent review found no blocker.
These establish the patch behavior and ABI, not live shutdown reproduction.

## Java and rendering lifetime

- UI uploads reuse one 256 KiB direct staging buffer and tile larger images;
  state queries reuse 64 bytes. Failed uploads delete generated texture names.
  Menu close and world transitions release textures on the client thread.
  Explicit unload also drops scratch references. Optional cleanup failure
  cannot abort the HUD tick, and world cleanup retries when available.
- The existing Unicode GPU budget is 32 MiB and three atlases use 12 MiB.
  Closing releases those textures; the budget is not total process memory.
  Session HUD now always balances its matrix push/pop on exceptions.
- API and Replay daemons stop on explicit unload. World/key/match changes
  remove stale queued work; publication rechecks its epoch under the same lock
  as cleanup. Bot hint insertion is also serialized. Late HTTP completions
  cannot refill retired queues. Existing HTTP deadlines bound in-flight work;
  interrupt is not claimed to instantly cancel every connection. Pending
  user-requested settings saves receive a best-effort final flush.
- Urchin cross-match cache content has a conservative 4 MiB estimated-byte
  budget in addition to the 512-entry limit. Complete current-match reasons
  remain separate and are not truncated by this budget. It is not a 4 MiB cap
  on all Urchin or JVM memory.
- Embedded Base64 helpers were already defined once per loader. Preparation is
  now lazy per missing helper, eliminating unnecessary chunk-array preparation
  by the second bootstrap owner. This reduces bounded startup allocations;
  it is not claimed as a per-frame leak fix. Java helpers remain associated
  with the game's loader even if a native DLL is unloaded.

## Verification scope

The full build covers both payloads, Java 8 bytecode, mappings, Forge-free
dependencies, signed helper loading, PE/unwind integrity, output routing and
privacy. Additional tests use small JVM heaps, small direct-memory budgets,
fake game state and real hidden OpenGL contexts. Exact release results are in
the root verification document. No real API request, player message or report
is sent by the stability fixtures.

This exact release has not been injected into live Lunar or Badlion during this
investigation. Live long sessions, match switching and closing the game remain
to be retested. The new process guard fixes a demonstrated hazardous path, but
the original crash caller cannot be confirmed without a dump. The original
2,493 native functions remain recovered NASM, not a complete C++ rewrite.

Remaining legacy audit boundaries include JNI globals retained by original
class caches and a world global not found in explicit End cleanup. Ordinary
world replacement releases that world global, so it is not established as a
match-switch leak. Reinjecting into the same JVM also reuses existing Java
classes. Restart the game before loading a new release; same-process version
replacement is not supported. Native C++ allocation-failure cleanup was not
exhaustively proven for all recovered functions.
