"""Run final Forge HUD bytecode against owned transformed-HUD fixtures."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'scripts'))
from classfile import read_class

FIXTURES = {
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public final class Minecraft {
    public net.minecraft.client.gui.GuiIngame field_71456_v;
}''',
    'net/minecraft/client/gui/GuiIngame.java': '''package net.minecraft.client.gui;
public class GuiIngame {
    public final net.minecraft.client.Minecraft minecraft;
    public int updateCount, renderCount;
    public GuiIngame(net.minecraft.client.Minecraft minecraft) { this.minecraft = minecraft; }
    public void func_73831_a() { updateCount++; }
    public void func_175180_a(float partial) { renderCount++; }
}''',
    'org/polyfrost/chatting/mixin/GuiIngameForgeAccessor.java': '''package org.polyfrost.chatting.mixin;
public interface GuiIngameForgeAccessor { void drawChat(int width, int height); }
''',
    'net/minecraftforge/client/GuiIngameForge.java': '''package net.minecraftforge.client;
public class GuiIngameForge extends net.minecraft.client.gui.GuiIngame
        implements org.polyfrost.chatting.mixin.GuiIngameForgeAccessor {
    public Object eventParent, res;
    public int forgeRenderCount, chatCount;
    public static final Object STATIC_TOKEN = new Object();
    public GuiIngameForge(net.minecraft.client.Minecraft minecraft) { super(minecraft); }
    @Override public void func_175180_a(float partial) {
        super.func_175180_a(partial); forgeRenderCount++;
    }
    public void drawChat(int width, int height) { chatCount++; }
}''',
    'AdninGameModules.java': '''public final class AdninGameModules {
    public static int gameTicks, renderTicks, hudDraws;
    public static void gameTick(net.minecraft.client.Minecraft mc) { gameTicks++; }
    public static void tick(net.minecraft.client.Minecraft mc) { renderTicks++; }
    public static void drawSessionHud(net.minecraft.client.Minecraft mc, Runnable callback) { hudDraws++; }
}''',
    'ForgeHudVerify.java': '''import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraftforge.client.GuiIngameForge;
import org.polyfrost.chatting.mixin.GuiIngameForgeAccessor;
public final class ForgeHudVerify {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static final class CustomHud extends GuiIngameForge {
        public final Object subclassOnly = new Object();
        CustomHud(Minecraft mc) { super(mc); }
    }
    public static void main(String[] args) throws Exception {
        Minecraft mc = new Minecraft();
        CustomHud original = new CustomHud(mc);
        original.updateCount = 7; original.renderCount = 11; original.forgeRenderCount = 13;
        original.chatCount = 17; original.res = new Object(); original.eventParent = new Object();
        mc.field_71456_v = original;
        AdninIngameGui replacement = new AdninIngameGui(mc);
        check(replacement instanceof GuiIngameForge, "Forge HUD subtype lost");
        check(replacement instanceof GuiIngameForgeAccessor, "Transformed mod interface lost");
        AdninSessionHudInstaller.copyAvoFields(original, replacement);
        mc.field_71456_v = replacement;
        check(replacement.eventParent == original.eventParent && replacement.res == original.res,
              "Forge private-state owner not copied");
        check(replacement.minecraft == mc, "Minecraft reference changed");
        replacement.func_73831_a(); replacement.func_175180_a(0.25f);
        ((GuiIngameForgeAccessor) mc.field_71456_v).drawChat(854, 480);
        check(replacement.updateCount == 8 && replacement.renderCount == 12,
              "Original HUD callback state lost or rendered twice");
        check(replacement.forgeRenderCount == 14 && replacement.chatCount == 18,
              "Forge/mod callbacks not preserved");
        check(original.updateCount == 7 && original.renderCount == 11 && original.chatCount == 17,
              "Detached original HUD is still receiving callbacks");
        check(AdninGameModules.gameTicks == 1 && AdninGameModules.renderTicks == 1
              && AdninGameModules.hudDraws == 1, "Adnin callback missing or doubled");
        check(GuiIngameForge.STATIC_TOKEN != null, "Static Forge state changed");
        System.out.println("Final Forge HUD: mod accessor cast, Forge/base state, one render/tick and subclass-field safety passed on "
                           + System.getProperty("java.version"));
    }
}'''
}

def run(command):
    result = subprocess.run([str(item) for item in command], capture_output=True, text=True, timeout=45)
    if result.returncode: raise RuntimeError(result.stdout+result.stderr)
    return result.stdout.strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    args = parser.parse_args()
    hud = read_class((args.classes/'AdninIngameGui.class').read_bytes())
    if hud['parent'] != 'net/minecraftforge/client/GuiIngameForge':
        raise AssertionError('Final Forge HUD has the wrong superclass')
    with tempfile.TemporaryDirectory(prefix='adnin-forge-hud-') as directory:
        work = Path(directory); sources = []
        for name, text in FIXTURES.items():
            path = work/'sources'/name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='ascii'); sources.append(path)
        out = work/'classes'; out.mkdir()
        for name in ('AdninIngameGui', 'AdninIngameGui$1', 'AdninSessionHudInstaller'):
            shutil.copyfile(args.classes/(name+'.class'), out/(name+'.class'))
        run([args.jdk/'bin/javac.exe', '--release', '8', '-cp', out, '-d', out, *sources])
        runtimes = [args.jdk/'bin/java.exe']
        runtimes.extend(sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-8*/bin/java.exe'))[-1:])
        for runtime in runtimes:
            print(run([runtime, '-Xverify:all', '-cp', out, 'ForgeHudVerify']))

if __name__ == '__main__':
    main()
