# ClientSideSounds 1.1 static source audit and independent v11 implementation

## Supplied artifact and method

The user-supplied artifact was `ClientSideSounds-1.1.jar`, 14,464 bytes, SHA-256
`87ce5320f36b4852c6884d4d6aa6ffdd30c3e1791b062c5c6927ec98bce88629`.
It was read as a ZIP and Java class files. CFR 0.152 and JDK 17 `javap` were used
as static readers. No class from the supplied mod was executed. No game was
started, settings read, connection made, audio played, or chat sent for this audit.

The archive contains six Java 8 class files, `mcmod.info`, and a minimal manifest;
there are no sound assets, sources, README, license, or copyright-notice files.
`mcmod.info` states:

- Name/mod ID: `ClientSideSounds` / `clientsidesounds`.
- Version: `1.1`; its Minecraft-version field remains the literal `${mcversion}`.
- Author: `LetsGoAway`; credit: `ASDFCube`.
- Project URL: `https://modrinth.com/mod/client-side-sounds`.
- Description: removes the delay in the sound of placing a block.

The main class's Forge annotation identifies a client-only mod accepting
Minecraft `[1.8.9]`, with annotation version `1`. The metadata URL is provenance
inside the supplied binary, not independently verified online source or license
evidence. No license grant can be established from this archive alone.

The active entrypoint constructs `BPSCommand` and `PacketHandler`. The archive
also contains `ClientSideSounds$1`, `$2`, and `$CMD`; the active entrypoint does
not construct them. Their constant pools refer to old synthetic accessors absent
from the active main class, so they were treated as stale archive contents, not
additional active features.

## Observed behavior

The mod has one user option: enabled/disabled, initially enabled. `/bps` reports
status and `/bps toggle` changes the flag, with reconnection required by its
hook-installation lifecycle. It disables the sound feature for a local/integrated
server and shows local status messages. It does not define an audio pack or
separate volume, pitch, or sound-category settings.

On a remote connection it intercepts outgoing `C08PacketPlayerBlockPlacement`:

1. Skip creative mode, use-in-air (`face == 255`), absent stacks, and non-`ItemBlock`
   stacks.
2. Read the clicked block ID. For a functional right-click block, skip unless
   the player is sneaking.
3. Use the clicked block center for replaceable blocks. Otherwise move that
   center one block along the clicked face, covering all six directions.
4. Play the held block's `stepSound.getPlaceSound()` locally through Minecraft's
   sound handler, with volume `1.0` and pitch `0.7936508`.
5. Keep the clicked and placed centers for `ServerData.pingToServer + 500 ms`.
   Its incoming `S29PacketSoundEffect` handler suppresses sounds at those centers
   during that window to avoid a later server echo.

The functional-ID table is:

```text
23,25,26,36,54,61,62,63,64,68,58,69,71,77,84,85,92,93,94,96,107,
113,116,117,118,122,130,137,138,140,143,145,146,149,150,151,154,
167,178,183,184,185,186,187,188,189,190,191,192,193,194,195,196
```

The replaceable-ID table is `6,8,9,10,11,31,32,78,106`.

## Independent implementation and integration

`src/java/AdninClientSounds.java` is a fresh behavior-based implementation. It
does not incorporate the mod's class files, decompiled source, Forge command or
event classes, or any third-party audio assets. The provided JAR is not a release
dependency. It uses the existing Minecraft 1.8.9 sound resources and Netty runtime.

The integration API is:

```java
AdninClientSounds.tick(Minecraft mc, boolean soundsEnabled, boolean observePackets);
AdninClientSounds.shutdown();
```

Call `tick` from the existing client thread even when a menu is open or the world
has ended. Call `shutdown` before stopping the client pump. The Utils toggle
replaces the Forge command's configuration role and takes effect without requiring
a reconnect; no additional sound options are introduced.

A single duplex handler observes outgoing placement packets and incoming sounds.
It always forwards outgoing packets unchanged. Netty reads packet metadata and
queues bounded work. World, held-item, game-mode, sneaking, and audio access happen
on the client tick. A fast server sound that arrives before the placement decision
can wait briefly; it is suppressed only after a matching local playback, otherwise
it is forwarded. Non-sound packets pass through unchanged.

With `observePackets` enabled, each actual incoming Minecraft `Packet` calls
`AdninAnticheat.packetReceived()` before sound filtering. That callback only updates
the anticheat timestamp. Observation alone does not queue placements or suppress
sounds. No second network handler or packet-logging option is required.

The NetworkManager channel is found by its Java field type, covering Lunar's
public field and vanilla's private/obfuscated field without relying on Forge's
added `channel()` method. The handler is inserted before the actual manager in
the pipeline, with the standard `packet_handler` name as a fallback. It never
replaces an existing handler that happens to use its own handler name.

Intentional implementation improvements over the supplied binary are:

- One duplex insertion instead of adding the same sharable handler on both sides
  of the packet handler. This avoids processing an outgoing placement twice.
- Synchronized bounded metadata replaces a shared unsynchronized list and a
  15-thread pool that sleeps once per placement. The limits are 128 queued
  placements, 256 deferred sounds, and 256 recent suppression keys; each tick
  processes at most 64 placements and 128 deferred sounds.
- Echo suppression requires the matching sound name as well as the clicked or
  placed center. An unrelated sound at the same coordinates remains audible.
- The normal echo window remains ping plus 500 ms. Missing/negative ping falls
  back to 500 ms; extreme values are bounded at 60 seconds without overflow.
- Pending client work expires after two seconds. Queue overflow fails open for
  network traffic rather than dropping packets to enforce a queue limit.
- Disable, disconnect, world change, or shutdown clears sound metadata and
  releases held server sounds. Shutdown makes no Minecraft queries and does not
  wait on a Netty thread.
- Each client tick renews a two-second monotonic lease. Incoming and outgoing
  handlers become transparent immediately after the lease expires. A cancellable
  check every second removes a stale handler and disables packet observation,
  including when a native unload stops the Lunar pump before Java cleanup runs.
  Fresh client ticks can install a new session.

## Validation boundaries

`tests/java/AdninClientSoundsTest.java` runs as `AdninClientSoundsTest` without
arguments. It checks the functional/replaceable block tables, all six placement
faces, creative/item/face guards, fixed sound parameters, echo matching and expiry,
queue bounds, packet forwarding, observation-only behavior, shutdown, and lease
expiry/reinstallation. It uses a real Netty 4.0.23 in-memory pipeline with owned
packet fixtures. Its packet adapter calls the same production handler and queue
logic while avoiding Lunar's game-startup-dependent packet static initializers.
The test's watchdog scheduling adapter is needed because that old Netty version's
`EmbeddedEventLoop` does not implement scheduled tasks; no channel is bound or
connected.

The Java 8-targeted helper/test passed 432 checks under `-Xverify:all` against the
current parent build's Lunar classpath and actual compiled anticheat dependency.
The test does not play audio or start a game. Static Minecraft mapping references
are recorded separately in `client-sounds-mappings-v11.json`; final dual-runtime
ABI, signed-loader, build, and packaging outcomes belong to `验证状态.md`.
No in-game auditory equivalence, multiplayer behavior, or successful native
injection is established by this offline test.
