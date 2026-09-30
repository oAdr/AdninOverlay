# v20 Lunar packet observer hang repair

## Captured v19 failure

On September 30, 2026, read-only inspection of the user's still-frozen Lunar
1.8.9 process established a closed wait cycle. The client thread owned the
Minecraft ArrayDeque task-queue monitor and waited on an unfinished Netty
PromiseTask. The task's runnable was DefaultChannelPipeline's removal action;
its context handler was the proxy created by AdninPacketLog. The PromiseTask's
executor thread was simultaneously blocked acquiring the same ArrayDeque
monitor owned by the client thread.

Local Netty 4.0.23 bytecode confirms that removal from a registered channel
outside its EventLoop submits a task and waits for completion. The recovered
AdninPacketLog.install implementation removed and re-added its handler on
every call. This is the operation connected to the captured wait cycle.
Native API workers were idle or waiting for work; the evidence did not implicate
the v19 team/cache monitors or the named vanilla compile dependency.

No target memory was written, no remote code was executed, and the frozen game
was not closed or reinjected during diagnosis. Raw dumps, addresses, process
metadata and monitor/object snapshots stay in the local diagnostic directory
and are excluded from this release. This note contains only sanitized findings.

## Implementation

- All owned pipeline reads, additions and removals run on the channel EventLoop.
  The calling thread queues the action and never awaits an EventLoop Future.
- A same-channel request reuses the current observer. One global pending action
  and the latest requested channel bound queued work during rapid transitions.
- A stale or closed request cannot install a new observer. Only the exact owned
  handler is removed; an unrelated handler with the same name is preserved.
- Shutdown rejects new observations and schedules removal without waiting.
  Disabled proxies still forward inbound packets, errors and lifecycle events.
- Observation admission and the stop flag share a short private lock. The lock
  is released during spawn decoding and the native callback. A finally block
  decrements the active counter, and isQuiescent acknowledges only stopped/zero.
  Native unload is rejected while an admitted observation remains in flight.
- The original install(Object) and nativeOnSpawnPlayerEntity(int) JNI signatures
  are preserved. One InstallRequest helper is added to each embedded profile.

The End-key unload path checks readiness without waiting for Netty. If the key
has been released before a busy observer drains, pressing End again retries
unload. This repair does not claim a universal native callback lifetime audit.

## Deterministic regression

AdninPacketLogConcurrencyTest uses real registered Netty 4.0.23 LocalChannels,
with no sockets, game process, personal settings or API calls. A controller
holds a task-queue monitor while an EventLoop is waiting to enter it; a separate
fixture caller invokes repeated installation, which must return before the
controller releases the monitor. The controller always releases the fixture,
so the old implementation fails without hanging the test JVM. This recreates
the blocking precondition while keeping the regression safely recoverable.

The same test fails on the v19 PacketLog bytecode at the nonblocking-return
assertion. The corrected source passes 438 checks on both Java 8 and Java 17.
Checks also cover 3,000 repeated calls without reinstallation, bounded pending
work, different EventLoops, A/B/A connection changes, closed pending channels,
external-handler ownership, real spawn decoding, packet/event/error forwarding
and shutdown races. A spawn getter holds an admitted observation in progress:
shutdown returns promptly, readiness stays false until its finally completes,
and later queued spawn packets are forwarded without a new observation.

The production game/HUD lifecycle tests pass 29 and 23 checks. The actual Lunar
JNI-facing Java stop method passes a four-check busy/busy/drained contract test
with owned stop collaborators. Complete build and compatibility verification
results are recorded in `验证状态.md` after the final build.

## Verification boundary

After the user restarted Lunar, the packaged v20 EXE loaded its exact matching
payload successfully. The runtime handshake confirmed installed hooks and an
advancing heartbeat. The user confirmed that /config and normal movement work.
Six read-only samples over 50 seconds showed a responsive window and an
advancing heartbeat at each ten-second interval.

The packet observer is installed only when Party Queue Detector is enabled and
the original native game state is Prequeue. The first post-load inspection found
its InstallRequest helper but no PacketLog class. After the user enabled the
feature and entered Prequeue, the native installed flag and Java
`install:ok:packet_handler` state both confirmed installation. Six observer
snapshots over 25 seconds retained the same request, channel and handler, with
no pending lifecycle task, while spawn counts increased from 36 to 43. A further
50-second heartbeat/window sample remained responsive and advanced throughout.

Packet observation alone does not prove party grouping or display. The user
subsequently reported that Party Detector IDs appears ineffective compared
with Badlion; that separate behavior is being investigated and is not claimed
fixed by v20.

Long-running Lunar gameplay and exit/unload remain subject to live verification
of this exact build. Badlion/Vanilla verification in this pass is offline. No
FPS improvement or removal of all possible crashes is claimed. Public service
endpoints remain intentional; personal credential and Bot URL defaults remain
blank, and local saved settings are not release inputs.
