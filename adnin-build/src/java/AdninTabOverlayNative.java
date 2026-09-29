/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiIngame
 *  net.minecraft.client.gui.GuiPlayerTabOverlay
 *  net.minecraft.scoreboard.ScoreObjective
 *  net.minecraft.scoreboard.Scoreboard
 */
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

public class AdninTabOverlayNative
extends GuiPlayerTabOverlay {
    public static boolean enabled = true;

    public AdninTabOverlayNative(Minecraft minecraft, GuiIngame guiIngame) {
        super(minecraft, guiIngame);
    }

    public void renderPlayerlist(int n, Scoreboard scoreboard, ScoreObjective scoreObjective) {
        if (!enabled) {
            super.renderPlayerlist(n, scoreboard, scoreObjective);
            return;
        }
        AdninTabOverlayNative.nativeRenderCustomTab();
    }

    private static native void nativeRenderCustomTab();
}
