# Compatibility Java build validation

The compatibility builder uses the shared Adnin feature sources plus one
compatibility-only scheduler. It emits the nine native entry classes used by
the supplied vanilla-obfuscated 1.8.9 DLL, including `AdninGuiNewChat` and
excluding `AdninClientPump`. The Lunar Java source and build pipeline are
unchanged.

## Mapping and ABI

- `resources/java-compat-1.8.9.json` contains the reviewed class/member mapping
  subset, with the source mapping resource SHA-256. It contains no game class
  bytes and needs no mapping JAR at build time.
- Remapping uses owner, member name and descriptor, resolves inherited
  methods, clones shared constant-pool NameAndType entries, and remaps
  descriptors/signatures/inner-class metadata without changing literal strings.
- Both installers retain the compatibility-native names `copyBfrFields` and
  `copyAvoFields`.
- The recovered chat wrapper had delegates to nonexistent vanilla methods
  `i()V`, `j(I)V`, and `k()I`. Correct vanilla overrides `a()V`, `c(I)V`, and
  `i()I` are added. All three old public aliases remain, forwarding to the
  corrected targets; JVM descriptors allow `i()V` and `i()I` to coexist.
- Every original class parent, field and method signature remains present.
  Gui4 retains the common compile-stage widening of two keyboard/mouse methods
  from protected to public. NewChat adds `Runnable` and the explicit registered
  `nativeClientTick()V` heartbeat.

## Client thread and menus

`AdninGuiNewChat` is installed by the compatibility native initialization path
without depending on Session Stats. Its constructor starts `AdninCompatPump`
once, after native registration succeeds and before the constructed wrapper is
installed into the game's chat field.

The scheduler daemon only calls `Minecraft.addScheduledTask`. A shared lifecycle
monitor allows at most one outstanding callback, with a 100 ms retry cadence and no
catch-up burst. The delivered Runnable calls `AdninGuiNewChat.run()` on the
client thread; that invokes `AdninFeatures.tick()` and then `nativeClientTick()`.
No callback is added to the render wrapper, and no world/player presence is
required to schedule readiness updates. The Lunar native ClientPump is not
changed.

The compatibility End-key hook calls the public static
`AdninGuiNewChat.adninStopClientPump()V` before native cleanup/unload. The helper's
stop method acquires the same monitor held throughout callback execution,
including the native heartbeat, and therefore waits for an executing callback.
It permanently marks this class stopped and interrupts the scheduler. Already
queued deliveries check the stopped state and become no-ops; no native callback
can begin after the barrier returns. A reentrant stop also suppresses the final
heartbeat. Stop-before-start and concurrent start/stop are safe and cannot
restart a stopped class. Native JNI failure defers cleanup and unload.

## Signed classloader bootstrap

Helpers are remapped before Base64 embedding in Gui4 and GuiNewChat. The build
checks every embedded helper chunk against the final helper bytes.

The Java 8 legacy helper definition path was tested against the actual signed
vanilla JAR using a small owned JNI DefineClass test shim. It failed package
signer checks. The compatibility bootstrap now uses the Minecraft class's
protection domain: `privateLookupIn` on Java 9+, or five-argument
`ClassLoader.defineClass` with an explicit protection domain on Java 8.

The signed-loader test preserves the actual JAR certificates. It defines a
harmless generated bootstrap owner using the owned JNI shim, defines all seven
helpers, checks their protection-domain certificates and repeated bootstrap
calls, and uses JVMTI to verify that Minecraft, GuiScreen and GuiNewChat stayed
uninitialized. No supplied EXE/DLL is executed by these checks.

## Completed checks (v10 rebuild)

- 12 Python regression tests passed, including owner-sensitive mapping,
  inheritance, ordinary-string preservation, failure on missing mappings,
  wrong-member rejection, JVM execution of pure remapped fixtures and scheduler
  behavior against a queue-only Minecraft fixture. Scheduler scenarios include
  bounded queue/retry behavior, a queued delivery after stop, an executing
  delivery during stop, stop-before-start, a 16-thread start/stop race and
  reentrant stop. Tests run without sending real game chat.
- The minimal packaged mapping alone builds 9 entrypoints and 7 helpers.
- Static validation resolves 23 game classes and 476 member references against
  the installed vanilla 1.8.9 JAR.
- JVM verification without initialization resolves all 16 generated classes,
  283 declared methods and 272 declared fields.
- Embedded helper bootstrap verification passes with inert game fixtures.
- Real signed-loader JNI bootstrap tests pass on Java 17.0.20.1 and Java
  1.8.0_502. The legacy signer failure is reproduced on Java 8, and all seven
  fixed helpers match Minecraft protection-domain certificates on both.

These checks validate build, mapping, ABI, scheduling and helper definition.
They do not establish live Badlion/Vanilla game behavior; that remains a
separate runtime integration check.
