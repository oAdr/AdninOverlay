# Legacy native AntiCheat retirement

This change retires the old client-side detector so the replacement Java
AntiCheat can own its settings and checks. It does not modify or bypass an
external server's anti-cheat. No supplied DLL was loaded, no game was injected,
no chat was sent, and no personal configuration was read during this work.

## Exact patch

| Fixed profile | Periodic CALL RVA | Original target | Before | After |
|---|---:|---:|---|---|
| Lunar | `0x1a3de` | `0x44100` | `e81d9d0200` | `9090909090` |
| Vanilla / compatibility | `0x1a5a6` | `0x44760` | `e8b5a10200` | `9090909090` |

Each original periodic pump reaches its detector at approximately 50 ms
intervals while the native game-mode condition permits it. The detector reads
`anticheat`, `ac_flagsound`, `ac_autoblock`, `ac_legitscaff`, `ac_scaffold`,
`ac_scaffoldb`, and `ac_noslow`, then runs the five old checks. Config loading and
GUI-to-config synchronization can restore those fields from historical values,
so changing Java defaults alone is insufficient.

The five-byte CALL replacement prevents entry independently of every old field
value. The call has four register arguments and no used return value. Its
argument preparation, existing timestamp update, stack frame, subsequent pump
operations, and all original detector code remain intact. There is no new native
function, callback, or unwind record for this change.

## Static reachability and ABI evidence

The original detector runtime-function ranges are:

- Lunar: `[0x44100, 0x483e8)`.
- Compatibility: `[0x44760, 0x48a27)`.

Disassembly of the original runtime-function records finds only the specified
external control-flow entry into each entire detector range. Neither entry is
exported; no absolute image-pointer or RIP-relative memory reference to either
entry was found. Both entry points and their original unwind records are kept.
The normalized original `.text` hashes already checked by the bridge builders
pin this reviewed call graph.

Immediately before each patched CALL, the same twelve bytes prepare the ABI:

```text
4c8bce          mov r9, rsi
4d8bc4          mov r8, r12
498bd5          mov rdx, r13
488bcf          mov rcx, rdi
```

Immediately after the existing following NOP, both pumps reload their saved
objects and continue to unrelated work. The patch does not remove the detector
entry prologue or alter its unwind description. It also leaves all Java fields,
JNI lookups, native configuration readers, and configuration writers available
for compatibility. Replacement features must use their own Java state.

Compatibility's config reader is `0x91f0`, called at initialization `0x15110`;
its GUI synchronization/save function is `0x3b680`, called at `0x1a1c5`. Their
code is unchanged by this retirement.

## Build guards and validation

`scripts/native_anticheat.py` checks the original five-byte CALL, its twelve-byte
argument setup, the eighteen-byte following continuation, the first sixteen
detector bytes, and the exact runtime-function boundary. Unknown profiles or
changed bytes fail closed. Both bridge builders apply the replacement only
after their full native-lineage check, add it to the existing exact patch ledger,
and report `legacyAnticheat.disabled = true`.

The expanded regression suites passed:

- Lunar `test_bridge.py`: **53 tests**.
- Compatibility `test_native_compat.py`: **25 tests**, with actual lifecycle
  Java classes supplied to the static class-reembedding fixture.

Added tests verify the exact replacement, unchanged detector body and unwind
record, unchanged argument/continuation bytes, and refusal after each guarded
region is altered. Existing tests verify every original byte change belongs to
the patch ledger. Isolated execution uses only the new five NOPs within an owned
miniature pump and an owned detector stub; the continuation runs and the stub is
not called regardless of the supplied fake GUI reference. No original DLL
function body is copied into or executed from those fixtures.

Source analysis artifacts remain under `work/adnin-compat/native`, including:

- `anticheat-v11/disable-call-audit.json`.
- `anticheat-v11/external-body-edges.json`.
- `audit-disable-ac-compat.json` and `ac-decompiled/`.

This evidence covers retirement of the old native detector. The new Java
AntiCheat's detection quality, timing, and live game behavior require their own
validation; no live-game compatibility claim is made here.
