# Native compatibility and Skin Output evidence

This is development evidence for the source package. It is not a claim of live
Minecraft, Lunar, Vanilla, or Badlion verification. The supplied DLL and injector
were never loaded or executed during this compatibility work.

## Scope and lineage

The original 1.8.9 compatibility input is `ChatReader.dll`, SHA-256
`4b66c2f91c91d7c5798488b487c5b1110d4ae74364a1ad2cd3cbae72d1e49d1d`.
The native build remains a preserved/reassembled native implementation with
reviewed additions. It is not a complete C++ rewrite.

The input has 3,795 x64 runtime-function records, including funclets. This is not
a count of distinct C++ source functions. All original records are retained;
eleven added bridge records produce a merged table of 3,806 entries.
The bridge checks the normalized original `.text` hash
`2be3c91504dff27c652e0ac6cf4d024794ba8fa7522e5faa496a17ad8490ad4b`, validates
the expected instruction bytes, and records every original-byte mutation.

Nine original class-loader sites remain active. Each relocated Java entry class
retains its original class/member ABI after branding. `AdninGuiNewChat` additionally
implements `Runnable`. No absent `ClientPump` loader is assumed.

## Native layout differences

The compatibility Denicker row remains `0x178` bytes, with name at `+0x40` and
UUID at `+0x148`. Its Stats payload is `0x180` bytes. Number-result mode readiness
bytes are at `+0x48`, `+0x100`, and `+0x140`; final Stats readiness is at `+0x1c0`.
The Skin result's last scalar ends at `+0x274`, so the bridge reserves and zeroes
`0x280` bytes rather than the smaller Lunar scratch region. The expanded native
frame is `0x3d0` bytes. The Stats cache getter is `0x9afc0`; a different cache
getter at `0x9b3b0` is not substituted despite similar machine code.

The compatibility native catalog contains 30 original columns, IDs 0 through 29,
with Seraph last. New FK/LV and Urchin IDs are 30 and 31. Lunar retains its
original 29-column catalog and extension IDs 29 and 30.

Default-profile assembly still produces the exact 3,728-byte v7 Lunar bridge
payload, SHA-256
`6bff2eaf49b95eb87169230e9263cb3e4bc1dfe6102b304693b59d310f198398`.
The new Skin Output change adds a reviewed CALL-site redirection; it does not
change that common assembly payload.

## Scheduled callback and unload lifecycle

The original IngameGui installation is conditional on `sessionStats`, so it is
not used as the sole update source. The original NewChat installer is called
unconditionally from initialization at `0x1515e` and installs its class via
`0x11530`. Java's bounded helper queues at most one task on the client thread.
The client-thread task runs `AdninFeatures.tick()` and then `nativeClientTick()`.
This can advance while in menus and does not duplicate a render-path tick.

The wrapper at original RegisterNatives CALL `0x1187c` preserves both original
JNI method records and adds `nativeClientTick()V`. It verifies the original
two-entry table and caller class. Before reporting registration success, it
resolves `AdninGuiNewChat.adninStopClientPump()V` and obtains a global reference
to that class. If either lookup fails, it returns JNI failure before the original
installer can construct and start NewChat's pump. Existing registration errors
and JNI exceptions are preserved.

The original initializer polls End at `0x15205`, then performs cleanup and calls
FreeLibraryAndExitThread at `0x15340`. Original cleanup at `0x1b780` only restores
native vtables; it does not stop the added Java scheduler. The added End wrapper
therefore calls the Java stop/drain barrier before allowing the original cleanup
branch. Its JNIEnv comes from the pinned initializer's current-thread stack slot.
Missing environment/state or a JNI exception returns zero from the key poll,
deferring cleanup and DLL unload. Successful stop releases the cached class
reference and permits the original End path.

The separate nonexecutable `.adnstat` section is 40 bytes:

| Offset | Contents |
|---:|---|
| `+0` | `ADNST001` marker |
| `+8` | JNI registration/lifecycle-ready flag, uint32 |
| `+12` | stop barrier completed flag, uint32 |
| `+16` | advancing client callback heartbeat, uint64 |
| `+24` | cached NewChat global class reference |
| `+32` | cached stop method ID |

The code section is read/execute; state is read/write and nonexecutable.

## Skin Denicker Output repair

Skin success messages bypass the previously hooked shared message queue. Each
profile now redirects the specific success producer through the existing plain
Output bridge while preserving the original local-chat call.

| Profile | Success chat CALL | Original target | Expected CALL bytes |
|---|---:|---:|---|
| Lunar | `0x87f1d` | `0x38260` | `e83e03fbff` |
| Compatibility | `0x89819` | `0x39f40` | `e82207fbff` |

Both call sites use RCX=JNIEnv, RDX=Minecraft, R8=Minecraft class, and R9 pointing
to an MSVC `std::string`. The colored plain message becomes
`[Adnin] Nick -> RealName` after removing formatting. It is not a JSON payload.

The success getters (`0xaa620` Lunar; `0xacc00` compatibility) require cache
status 3, a nonempty real name, and a pending-success flag, then consume the flag.
The existing `test al,al` and conditional jump skip the chat call on failure.
Those checks, argument setup, and flag consumption are byte-pinned and unchanged.
The change therefore does not attach an Output callback to Skin loading,
no-result, or error paths. The existing Java Output setting, deduplication,
bounded queue, and `/pc` delivery policy still apply.

Final hook counts are 14 for Lunar, and 16 for compatibility (14 feature hooks
plus registration and End lifecycle wrappers).

## Validation performed

`test_native_compat.py` passed 22 tests with the actual lifecycle Java classes in
`work/adnin-compat/java/lifecycle-build`. `test_bridge.py` passed 50 tests against
the original Lunar fixture. These runs cover deterministic PE assembly, exact
patch ledgers, all entry-class loaders, section permissions, Skin success-only
guards, the column catalog, expanded Denicker scratch and Stats copies, and
original argument preservation.

The execution tests allocate a fresh private mock image containing only newly
assembled bridge code and Python-backed JNI/native stubs. No original DLL
machine code is copied into or executed from that image. Tests exercise JNI
registration success/failure, missing stop method/global reference, idle/End
keys, callback stop exceptions, and class-reference lifetime. Windows'
RtlVirtualUnwind verifies the registration and End-wrapper unwind records against
synthetic stacks. Existing Lunar tests likewise exercise the new plain callback
and preserve its original local-chat delivery.

Reproduction commands (run from workspace root, with `work/python-tools` on
PYTHONPATH):

```powershell
python outputs/adnin-build/tests/test_native_compat.py `
  --input work/adnin-compat/input/ChatReader.dll `
  --classes work/adnin-compat/java/lifecycle-build `
  --nasm ../https-github-com-freecodexyz-free-code/work/nasm/nasm-3.02/nasm.exe -v

python outputs/adnin-build/tests/test_bridge.py `
  --input outputs/adnin-build/build-v7/native-fixed.dll `
  --nasm ../https-github-com-freecodexyz-free-code/work/nasm/nasm-3.02/nasm.exe -v
```

Read-only static analysis and independent byte-comparison artifacts remain under
`work/adnin-compat/native`, including `audit-lunar-final-check.json`,
`audit-skin-output.json`, and `audit-skin-output-disassembly.txt`.
Game runtime behavior remains unverified by this evidence.
