/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.renderer.RenderGlobal
 */
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.renderer.RenderGlobal;

public class AdninHitboxInstaller
implements Runnable {
    @Override
    public void run() {
        AdninHitboxInstaller.nativeInstallRenderGlobal();
    }

    static void copyRenderGlobalFields(RenderGlobal renderGlobal, RenderGlobal renderGlobal2) throws IllegalAccessException {
        for (Class clazz = RenderGlobal.class; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(renderGlobal2, field.get(renderGlobal));
            }
        }
    }

    private static native void nativeInstallRenderGlobal();
}
