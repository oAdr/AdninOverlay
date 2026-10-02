/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiIngame
 */
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;

public class AdninIngameGui
extends GuiIngame {
    private final Minecraft mcInstance;
    /*
     * Forge replaces GuiIngame with GuiIngameForge. Keep that original
     * instance as a delegate so Forge's RenderGameOverlayEvent and HUD
     * extensions still run when the compatibility wrapper is installed.
     * Reflection keeps this source independent of the Forge API and also
     * tolerates the obfuscated field name after compatibility remapping.
     */
    private final GuiIngame originalGui;
    private final boolean forgeGui;
    private static final Runnable SESSION_STATS_TICK = new Runnable() {
        @Override public void run() { AdninIngameGui.nativeSessionStatsTick(); }
    };

    public AdninIngameGui(Minecraft minecraft) {
        super(minecraft);
        this.mcInstance = minecraft;
        this.originalGui = findOriginalGui(minecraft);
        this.forgeGui = originalGui != null
                && "net.minecraftforge.client.GuiIngameForge".equals(originalGui.getClass().getName());
    }

    private static GuiIngame findOriginalGui(Minecraft minecraft) {
        if (minecraft == null) return null;
        try {
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (!GuiIngame.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                final Object value = field.get(minecraft);
                if (value instanceof GuiIngame && value != minecraft) return (GuiIngame)value;
            }
        } catch (IllegalAccessException | RuntimeException ignored) { }
        return null;
    }

    @Override public void updateTick() {
        if (forgeGui) originalGui.updateTick();
        else super.updateTick();
        AdninGameModules.gameTick(this.mcInstance);
    }

    public void renderGameOverlay(float f) {
        if (forgeGui) originalGui.renderGameOverlay(f);
        else super.renderGameOverlay(f);
        AdninGameModules.tick(this.mcInstance);
        AdninGameModules.drawSessionHud(this.mcInstance, SESSION_STATS_TICK);
    }

    private static native void nativeSessionStatsTick();
}
