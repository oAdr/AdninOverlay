/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.FontRenderer
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.renderer.GlStateManager
 */
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

public final class AdninSessionHud {
    public static boolean enabled;
    public static int bgOpacity;
    public static boolean textShadow;
    public static float scale;
    public static float startX;
    public static float startY;
    public static float endX;
    public static float endY;
    public static float baseWidth;
    public static float baseHeight;
    public static String[] rows;

    private AdninSessionHud() {
    }

    public static void draw(Minecraft minecraft) {
        int n;
        if (!enabled || minecraft == null) {
            return;
        }
        FontRenderer fontRenderer = minecraft.fontRendererObj;
        if (fontRenderer == null || rows == null || rows.length == 0) {
            return;
        }
        if (baseWidth <= 0.0f || baseHeight <= 0.0f) {
            return;
        }
        // Native values stay in their original language and dimensions. Translate
        // a render snapshot and grow its local width so Chinese labels fit.
        String[] displayRows = new String[rows.length];
        float displayWidth = baseWidth;
        for (int i = 0; i < rows.length; i++) {
            displayRows[i] = AdninMessages.sessionRow(rows[i]);
            if (displayRows[i] != null)
                displayWidth = Math.max(displayWidth, fontRenderer.getStringWidth(displayRows[i]) + 4.0f);
        }
        AdninSessionHud.nativePrepareDraw();
        float f = scale;
        if (f < 0.5f) {
            f = 0.5f;
        }
        if (f > 1.5f) {
            f = 1.5f;
        }
        if ((n = bgOpacity) < 0) {
            n = 0;
        }
        if (n > 255) {
            n = 255;
        }
        GlStateManager.pushMatrix();
        try {
        GlStateManager.translate((float)endX, (float)endY, (float)0.0f);
        GlStateManager.scale((float)f, (float)f, (float)1.0f);
        GlStateManager.translate((float)(-displayWidth), (float)(-baseHeight), (float)0.0f);
        Gui.drawRect((int)0, (int)0, (int)((int)Math.ceil(displayWidth)), (int)((int)baseHeight), (int)(n << 24));
        float f2 = 2.0f;
        float f3 = 11.0f;
        float f4 = f2;
        for (int i = 0; i < displayRows.length; ++i) {
            String string = displayRows[i];
            if (string == null || string.isEmpty()) {
                f4 += f3;
                continue;
            }
            int n2 = fontRenderer.getStringWidth(string);
            float f5 = displayWidth - (float)n2 - f2;
            AdninSessionHud.nativeDrawText(string, f5, f4, textShadow);
            f4 += f3;
        }
        } finally { GlStateManager.popMatrix(); }
    }

    private static native void nativePrepareDraw();

    private static native void nativeDrawText(String var0, float var1, float var2, boolean var3);

    static {
        bgOpacity = 100;
        scale = 1.0f;
        rows = new String[0];
    }
}
