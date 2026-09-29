/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.gui.GuiIngame
 */
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.gui.GuiIngame;

public class AdninSessionHudInstaller
implements Runnable {
    @Override
    public void run() {
        AdninSessionHudInstaller.nativeInstallIngameGui();
    }

    static void copyGuiIngameFields(GuiIngame guiIngame, GuiIngame guiIngame2) throws IllegalAccessException {
        for (Class clazz = GuiIngame.class; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(guiIngame2, field.get(guiIngame));
            }
        }
    }

    private static native void nativeInstallIngameGui();
}
