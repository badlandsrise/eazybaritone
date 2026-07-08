/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RenderEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires Baritone's render pass just before the level renderer finalizes its
 * per-frame gizmo collection, with the render thread's gizmo collector
 * installed so {@link baritone.utils.IRenderer} can emit cuboids/lines through
 * the 26.2 gizmo pipeline.
 * <p>
 * {@code finalizeGizmoCollection} (called each frame from {@code submitFeatures}
 * during {@code render}) drains {@code renderThreadGizmos}, so injecting at its
 * HEAD lets us add our gizmos to that same collector right before they render.
 * {@code require = 0} keeps a target miss from ever taking the rest of Baritone
 * down with it - worst case, world lines just don't draw.
 *
 * @author Brady
 * @since 2/13/2020
 */
@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {

    @Shadow
    @Final
    private SimpleGizmoCollector renderThreadGizmos;

    @Inject(
            method = "finalizeGizmoCollection",
            at = @At("HEAD"),
            require = 0
    )
    private void baritoneRenderPass(final CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = new PoseStack();
        Matrix4f projection = new Matrix4f();
        try (Gizmos.TemporaryCollection collection = Gizmos.withCollector(this.renderThreadGizmos)) {
            for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
                try {
                    ibaritone.getGameEventHandler().onRenderPass(new RenderEvent(partialTicks, poseStack, projection));
                } catch (Throwable t) {
                    // never let a render listener crash the frame
                    baritone.utils.IRenderer.pending.clear();
                    t.printStackTrace();
                }
            }
        }
    }
}
