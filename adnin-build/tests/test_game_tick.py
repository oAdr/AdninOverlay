"""Execute the production HUD/module bytecode with owned, offline game fixtures.

The test never starts Minecraft, loads an injection payload, reads settings or
sends a packet. Fixtures make callback phase/order and skipped ticks observable.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('tick_build_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public final class Minecraft {
    private static Minecraft current;
    public Minecraft() { current = this; }
    public static Minecraft getMinecraft() { return current; }
    public boolean clientThread = true;
    public net.minecraft.client.multiplayer.WorldClient theWorld;
    public final java.util.List<Runnable> scheduled = new java.util.ArrayList<Runnable>();
    public com.google.common.util.concurrent.ListenableFuture<Object> addScheduledTask(Runnable task) {
        scheduled.add(task); return null;
    }
    public int phase;
    public net.minecraft.client.entity.EntityPlayerSP thePlayer = new net.minecraft.client.entity.EntityPlayerSP();
    public boolean isCallingFromMinecraftThread() {
        return clientThread && !Thread.currentThread().getName().equals("owned-unload-fixture");
    }
}''',
    'net/minecraft/client/multiplayer/WorldClient.java': 'package net.minecraft.client.multiplayer; public final class WorldClient { }',
    'com/google/common/util/concurrent/ListenableFuture.java': 'package com.google.common.util.concurrent; public interface ListenableFuture<T> { }',
    'AdninUi.java': '''public final class AdninUi {
        public static int releases, disposals;
        public static boolean failRelease;
        public static void releaseTextures() {
            releases++; if (failRelease) throw new IllegalStateException("owned unavailable context");
        }
        public static void dispose() { disposals++; }
    }''',
    'AdninFeatures.java': '''public final class AdninFeatures {
        public static int stops;
        public static void shutdown() { stops++; }
    }''',
    'net/minecraft/client/entity/EntityPlayerSP.java': '''package net.minecraft.client.entity;
public final class EntityPlayerSP {
    public int messages;
    public String lastMessage;
    public void addChatMessage(net.minecraft.util.IChatComponent message) {
        messages++; lastMessage = ((net.minecraft.util.ChatComponentText) message).text;
    }
}''',
    'net/minecraft/util/IChatComponent.java': 'package net.minecraft.util; public interface IChatComponent { }',
    'net/minecraft/util/ChatComponentText.java': '''package net.minecraft.util;
public final class ChatComponentText implements IChatComponent {
    public final String text;
    public ChatComponentText(String value) { text = value; }
}''',
    'net/minecraft/client/gui/GuiIngame.java': '''package net.minecraft.client.gui;
public class GuiIngame {
    private final net.minecraft.client.Minecraft mc;
    public int updates, renders;
    public GuiIngame(net.minecraft.client.Minecraft value) { mc = value; }
    public void updateTick() { updates++; mc.phase++; }
    public void renderGameOverlay(float partial) { renders++; }
}''',
    'org/lwjgl/input/Keyboard.java': '''package org.lwjgl.input;
public final class Keyboard {
    public static final int KEY_END = 207;
    public static boolean end;
    public static boolean isCreated() { return true; }
    public static boolean isKeyDown(int key) { return key == KEY_END && end; }
}''',
    'AdninGui4.java': 'public final class AdninGui4 { public static boolean clientSideSounds, sessionStats; }',
    'AdninReplay.java': '''public final class AdninReplay {
    public static int ticks, clears;
    public static void tick(net.minecraft.client.Minecraft mc) { ticks++; }
    public static void clear() { clears++; }
    public static void shutdown() { clears++; }
}''',
    'AdninClientSounds.java': '''public final class AdninClientSounds {
    public static int ticks, stops;
    public static void tick(net.minecraft.client.Minecraft mc, boolean sounds, boolean packets) { ticks++; }
    public static void shutdown() { stops++; }
}''',
    'AdninSessionHud.java': '''public final class AdninSessionHud {
    public static boolean enabled;
    public static int draws;
    public static Runnable whileDrawing;
    public static void draw(net.minecraft.client.Minecraft mc) {
        draws++;
        if (whileDrawing != null) whileDrawing.run();
    }
}''',
    'AdninAnticheat.java': '''public final class AdninAnticheat {
    public static boolean enabled = true, fail;
    public static int samples, stops, lastPhase;
    public static void tick(net.minecraft.client.Minecraft mc) {
        if (fail) throw new IllegalStateException("owned fixture");
        if (mc.phase <= lastPhase) throw new AssertionError("Sampled before the completed HUD tick");
        samples++; lastPhase = mc.phase;
    }
    public static void resetEvidence() { stops++; }
    public static void shutdown() { stops++; }
}''',
    'AdninGameTickTest.java': '''import net.minecraft.client.Minecraft;
public final class AdninGameTickTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Minecraft mc = new Minecraft();
        AdninIngameGui hud = new AdninIngameGui(mc);
        check(!AdninGui4.sessionStats, "Session Stats is off in the fixture");
        for (int i = 0; i < 20; i++) AdninGameModules.tick(mc);
        check(AdninAnticheat.samples == 0, "Frame/scheduled callbacks cannot consume a detector tick");
        check(AdninReplay.ticks == 20 && AdninClientSounds.ticks > 0, "Maintenance remains active");
        mc.theWorld = new net.minecraft.client.multiplayer.WorldClient();
        AdninGameModules.tick(mc); AdninGameModules.tick(mc);
        check(AdninUi.releases == 1, "World change releases textures once, not every frame");
        mc.theWorld = null; AdninGameModules.tick(mc);
        check(AdninUi.releases == 2, "Leaving a world releases its menu textures");
        mc.theWorld = new net.minecraft.client.multiplayer.WorldClient();
        AdninUi.failRelease = true; AdninGameModules.tick(mc);
        check(AdninUi.releases == 3, "Unavailable optional GL cleanup does not abort maintenance");
        AdninUi.failRelease = false; AdninGameModules.tick(mc); AdninGameModules.tick(mc);
        check(AdninUi.releases == 4, "Failed transition cleanup retries once context is available");
        for (int i = 1; i <= 5; i++) {
            AdninGameModules.tick(mc);
            hud.updateTick();
            AdninGameModules.tick(mc);
            check(AdninAnticheat.samples == i, "Every catch-up update contributes exactly one sample");
            check(hud.updates == i && AdninAnticheat.lastPhase == i, "Superclass updates before sampling");
        }
        mc.clientThread = false;
        int replayBefore = AdninReplay.ticks;
        AdninGameModules.gameTick(mc);
        check(AdninAnticheat.samples == 5 && AdninReplay.ticks == replayBefore,
            "Worker threads cannot read game state or sample");
        mc.clientThread = true;
        AdninAnticheat.fail = true;
        hud.updateTick(); hud.updateTick();
        check(AdninAnticheat.stops == 2 && mc.thePlayer.messages == 1,
            "Detector failure resets state and reports once");
        check(mc.thePlayer.lastMessage.startsWith("\\u00a7c[Anticheat]")
            && mc.thePlayer.lastMessage.endsWith("\\u00a7r"), "Failure text is red with a reset");
        AdninAnticheat.fail = false;
        hud.updateTick();
        check(AdninAnticheat.samples == 6, "A later healthy tick recovers");
        AdninAnticheat.fail = true; hud.updateTick();
        check(mc.thePlayer.messages == 2, "A new failure after recovery can report once");
        AdninAnticheat.fail = false;
        org.lwjgl.input.Keyboard.end = true;
        hud.updateTick();
        check(AdninAnticheat.samples == 6 && AdninClientSounds.stops == 1,
            "End stops observers before another detector sample");
        check(AdninReplay.clears == 1, "Unload clears replay eligibility");
        check(AdninFeatures.stops == 1 && AdninUi.disposals == 1,
            "Client-thread unload stops optional work and disposes GL resources");
        org.lwjgl.input.Keyboard.end = false;
        hud.updateTick(); AdninGameModules.tick(mc); AdninGameModules.stop();
        check(AdninAnticheat.samples == 6 && AdninClientSounds.stops == 1,
            "Queued callbacks after stop are inert; stop is idempotent");
        System.out.println("AdninGameTickTest: " + checks + " checks passed; production HUD and module bytecode, offline fixtures");
    }
}''',
    'AdninHudLifecycleTest.java': '''import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
public final class AdninHudLifecycleTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch, String message) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError(message); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static boolean blocked(Thread thread) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < until)
            Thread.sleep(1);
        return thread.getState() == Thread.State.BLOCKED;
    }
    public static void main(String[] args) throws Exception {
        Minecraft mc = new Minecraft();
        AdninIngameGui hud = new AdninIngameGui(mc);
        AtomicInteger nativeTicks = new AtomicInteger();
        Runnable nativeTick = new Runnable() {
            public void run() { nativeTicks.incrementAndGet(); AdninSessionHud.enabled = true; }
        };
        int abi = AdninIngameGui.class.getDeclaredMethod("nativeSessionStatsTick").getModifiers();
        check(Modifier.isPrivate(abi) && Modifier.isStatic(abi) && Modifier.isNative(abi),
            "Original private static native session tick ABI remains intact");
        AdninSessionHud.enabled = true;
        hud.renderGameOverlay(0);
        check(hud.renders == 1, "Disabled Session Stats still executes the original HUD render");
        check(!AdninSessionHud.enabled && AdninSessionHud.draws == 0,
            "Actual disabled production HUD render clears stale state without native binding or drawing");
        AdninGameModules.drawSessionHud(mc, nativeTick);
        check(nativeTicks.get() == 0 && AdninSessionHud.draws == 0,
            "Disabled Session Stats bypasses both native preparation and drawing");
        AdninGui4.sessionStats = true;
        AdninGameModules.drawSessionHud(mc, nativeTick);
        check(nativeTicks.get() == 1 && AdninSessionHud.draws == 1 && AdninSessionHud.enabled,
            "Enabled Session Stats performs preparation followed by drawing");
        AdninGui4.sessionStats = false;
        AdninGameModules.drawSessionHud(mc, nativeTick);
        check(nativeTicks.get() == 1 && AdninSessionHud.draws == 1 && !AdninSessionHud.enabled,
            "Disabling immediately clears stale enabled state and blocks both callbacks");
        AdninGui4.sessionStats = true;
        mc.clientThread = false;
        AdninGameModules.drawSessionHud(mc, nativeTick);
        AdninGameModules.drawSessionHud(null, nativeTick);
        check(nativeTicks.get() == 1 && AdninSessionHud.draws == 1,
            "Missing client or wrong thread cannot enter native preparation or drawing");
        mc.clientThread = true;

        CountDownLatch enteredNative = new CountDownLatch(1), releaseNative = new CountDownLatch(1);
        CountDownLatch enteredDraw = new CountDownLatch(1), releaseDraw = new CountDownLatch(1);
        CountDownLatch stopAttempted = new CountDownLatch(1), stopFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AdninSessionHud.whileDrawing = new Runnable() {
            public void run() { enteredDraw.countDown(); await(releaseDraw, "Draw was not released"); }
        };
        Thread renderer = new Thread(new Runnable() {
            public void run() {
                try {
                    AdninGameModules.drawSessionHud(mc, new Runnable() {
                        public void run() {
                            nativeTicks.incrementAndGet(); AdninSessionHud.enabled = true;
                            enteredNative.countDown(); await(releaseNative, "Native preparation was not released");
                        }
                    });
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }
        }, "owned-hud-render-fixture");
        Thread stopper = new Thread(new Runnable() {
            public void run() {
                try { stopAttempted.countDown(); AdninGameModules.stop(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { stopFinished.countDown(); }
            }
        }, "owned-unload-fixture");
        try {
            renderer.start(); await(enteredNative, "Renderer did not enter native preparation");
            stopper.start(); await(stopAttempted, "Unload thread did not start");
            check(blocked(stopper) && stopFinished.getCount() == 1,
                "Unload waits while native session preparation owns the lifecycle lock");
            releaseNative.countDown(); await(enteredDraw, "Renderer did not advance to drawing");
            check(blocked(stopper) && stopFinished.getCount() == 1,
                "The same lock remains held through drawing and its native prepare/draw calls");
            releaseDraw.countDown();
            renderer.join(2000); stopper.join(2000);
            check(!renderer.isAlive() && !stopper.isAlive() && failure.get() == null,
                "Rendering and concurrent unload both complete without failure or deadlock");
        } finally {
            releaseNative.countDown(); releaseDraw.countDown();
            renderer.join(2000); if (stopper.getState() != Thread.State.NEW) stopper.join(2000);
            AdninSessionHud.whileDrawing = null;
        }
        check(nativeTicks.get() == 2 && AdninSessionHud.draws == 2 && !AdninSessionHud.enabled,
            "Unload clears enabled only after both in-flight HUD callbacks finish");
        check(AdninClientSounds.stops == 1 && AdninAnticheat.stops == 1 && AdninReplay.clears == 1,
            "The serialized unload stops all observers once");
        for (int i = 0; i < 5; i++) {
            AdninSessionHud.enabled = true;
            AdninGameModules.drawSessionHud(mc, nativeTick);
            hud.renderGameOverlay(0);
            check(!AdninSessionHud.enabled, "Each callback after stop clears stale HUD enabled state");
        }
        check(nativeTicks.get() == 2 && AdninSessionHud.draws == 2 && hud.renders == 6,
            "Actual queued HUD renders after stop preserve the original HUD and skip every native callback");
        AdninGameModules.stop();
        check(AdninClientSounds.stops == 1 && AdninAnticheat.stops == 1,
            "Repeated stop remains harmless after queued renders");
        check(AdninFeatures.stops == 1 && AdninUi.disposals == 0 && mc.scheduled.size() == 1,
            "Native-thread unload schedules Java-only GL cleanup instead of using GL on worker");
        mc.scheduled.remove(0).run();
        check(AdninUi.disposals == 1, "Client task disposes GL resources after native barrier exits");
        System.out.println("AdninHudLifecycleTest: " + checks + " checks passed; production HUD and lifecycle lock, offline callbacks");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    args = parser.parse_args()
    classes = args.classes.resolve()
    for name in ('AdninIngameGui', 'AdninIngameGui$1', 'AdninGameModules'):
        if not (classes / (name + '.class')).is_file():
            raise ValueError('Missing production class: ' + name)
    with tempfile.TemporaryDirectory(prefix='adnin-tick-') as directory:
        work = Path(directory)
        sources = []
        for name, content in FIXTURES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, str(classes), output, work / 'args.txt')
        for test in ('AdninGameTickTest', 'AdninHudLifecycleTest'):
            result = subprocess.run([str(common.find_java(args.jdk, 'java')), '-Xverify:all', '-Dfile.encoding=UTF-8',
                                     '-cp', str(output) + os.pathsep + str(classes), test],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
            print((result.stdout + result.stderr).strip())
            if result.returncode:
                raise RuntimeError('Offline production tick/lifecycle regression failed: ' + test)


if __name__ == '__main__':
    main()
