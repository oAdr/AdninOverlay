/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.RenderGlobal
 *  net.minecraft.client.renderer.culling.ICamera
 *  net.minecraft.entity.Entity
 */
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

public class AdninRenderGlobal
extends RenderGlobal {
    public AdninRenderGlobal(Minecraft minecraft) {
        super(minecraft);
    }

    public void renderEntities(Entity entity, ICamera iCamera, float f) {
        super.renderEntities(entity, iCamera, f);
        AdninRenderGlobal.nativeAfterRenderEntities();
    }

    private static native void nativeAfterRenderEntities();
}
