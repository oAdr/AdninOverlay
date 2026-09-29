# Session HUD lifecycle barrier: v12 offline validation

Always maintaining the fixed-phase IngameGui hook makes it necessary to tolerate
the Java HUD remaining installed after native unloading. Native restoration of
that HUD was not established by the earlier cleanup review. This change guards
the callback rather than assuming restoration occurred.

## Production change

`AdninGameModules.drawSessionHud(Minecraft, Runnable)` acquires the same private
`LIFECYCLE` monitor as `stop()`. While holding it, it checks stopped state and
the Session Stats display setting. Either exclusion clears
`AdninSessionHud.enabled` and returns. Missing clients and non-client threads
also cannot proceed into drawing.

For an active, enabled callback, the monitor remains held across both the
native session-stat update and the entire `AdninSessionHud.draw` call. The
latter includes its `nativePrepareDraw` and `nativeDrawText` calls. A concurrent
`stop()` therefore cannot return midway through that sequence. `stop()` marks
the module stopped and clears `AdninSessionHud.enabled` under the same monitor.
Subsequent callbacks clear stale enabled state and return without invoking
either callback, even if the installed Java HUD remains reachable.

`AdninIngameGui` retains the exact `private static native void
nativeSessionStatsTick()` JNI method. Its existing render method still calls
the original superclass and ordinary maintenance tick, then uses the guarded
entry with a cached private static Runnable. The compiler generates
`AdninIngameGui$1`; the normal helper collector must include it. No callback
object is allocated per rendered frame.

Native unload code must call the existing Java stop barrier before unloading
the module. That native-side integration is owned and verified separately;
this Java test does not claim that the OS unload order or original HUD
restoration has been exercised.

## Lock ordering review

The established order remains `LIFECYCLE -> ClientSounds.class` or
`LIFECYCLE -> AdninAnticheat.class`. Session HUD drawing adds no Java monitor
below `LIFECYCLE`. ClientSounds shutdown clears local queues, cancels its timer
without waiting, and schedules handler removal on Netty without joining that
thread. Packet callbacks do not acquire `LIFECYCLE`; the Anticheat packet
notification only writes a volatile timestamp. Anticheat settings/reset do not
call back into `LIFECYCLE`. The GUI setting path does not add another monitor.
An independent static review found no reverse Java lock path in these sources.

## Offline checks executed

Compiled the actual new `AdninGameModules`, `AdninIngameGui`, and generated
`AdninIngameGui$1` with Microsoft OpenJDK 17.0.20.101 targeting Java 8, against
the normalized local Lunar runtime. `tests/test_game_tick.py` executes those
production class files with owned game/observer fixtures under `-Xverify:all`.

```text
AdninGameTickTest: 21 checks passed; production HUD and module bytecode, offline fixtures
AdninHudLifecycleTest: 19 checks passed; production HUD and lifecycle lock, offline callbacks
```

The lifecycle checks cover:

- The private/static/native method modifiers remain unchanged.
- Calling the actual production `renderGameOverlay` with Session Stats disabled
  succeeds with no native method registered, clears stale HUD enabled state,
  and still reaches the original superclass renderer.
- Enabled drawing invokes preparation then drawing; disabling again invokes
  neither. Missing-client and wrong-thread calls invoke neither.
- A renderer pauses inside the preparation callback, then inside the draw
  callback. A concurrent unload thread is observed blocked on the monitor in
  both phases and can finish only after both callbacks are released.
- After stop returns, repeated direct drawing entries and actual production
  HUD render calls do not enter native preparation or drawing; stale enabled
  flags are cleared each time and the original superclass still renders.
- Repeated stop remains idempotent, with observers stopped once.

The fixture replaces native behavior with local counters/latches. It does not
register or execute a JNI payload, start Minecraft, create OpenGL, play audio,
read personal configuration, send chat/reports, or access a network.
