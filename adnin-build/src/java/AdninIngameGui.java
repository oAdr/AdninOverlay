/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiIngame
 */
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;

public class AdninIngameGui
extends GuiIngame {
    private final Minecraft mcInstance;
    private static final Runnable SESSION_STATS_TICK = new Runnable() {
        @Override public void run() { AdninIngameGui.nativeSessionStatsTick(); }
    };

    public AdninIngameGui(Minecraft minecraft) {
        super(minecraft);
        this.mcInstance = minecraft;
    }

    @Override public void updateTick() {
        super.updateTick();
        AdninGameModules.gameTick(this.mcInstance);
    }

    public void renderGameOverlay(float f) {
        super.renderGameOverlay(f);
        AdninGameModules.tick(this.mcInstance);
        AdninGameModules.drawSessionHud(this.mcInstance, SESSION_STATS_TICK);
    }

    private static native void nativeSessionStatsTick();
}
