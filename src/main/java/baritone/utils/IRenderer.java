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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Baritone's world-space line rendering.
 * <p>
 * Minecraft 26.2 replaced immediate-mode drawing with a deferred submit/gizmo
 * pipeline, so Baritone no longer draws directly - it emits {@link Gizmos}
 * (cuboids and lines, in world coordinates) into the render thread's gizmo
 * collector, which vanilla then renders. The collector is set for us in
 * {@code MixinWorldRenderer} around the render-pass dispatch.
 * <p>
 * The {@code startLines}/{@code emitAABB}/{@code emitLine}/{@code endLines}
 * signatures are preserved so callers (PathRenderer, SelectionRenderer, ...)
 * are unchanged; the {@code VertexConsumer}/{@code PoseStack} arguments are now
 * ignored, and depth is applied at {@code endLines} via {@code setAlwaysOnTop}.
 */
public interface IRenderer {

    Settings settings = BaritoneAPI.getSettings();

    /** current draw colour, 0..1 rgba, matching the old immediate-mode API */
    float[] color = new float[]{1.0F, 1.0F, 1.0F, 1.0F};

    /** gizmos emitted in the current start/endLines batch, so depth can be set at the end */
    List<GizmoProperties> pending = new ArrayList<>();

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    static int argb() {
        int a = Math.min(255, Math.max(0, (int) (color[3] * 255.0F)));
        int r = Math.min(255, Math.max(0, (int) (color[0] * 255.0F)));
        int g = Math.min(255, Math.max(0, (int) (color[1] * 255.0F)));
        int b = Math.min(255, Math.max(0, (int) (color[2] * 255.0F)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static VertexConsumer startLines(Color color, float alpha) {
        glColor(color, alpha);
        pending.clear();
        return null;
    }

    static VertexConsumer startLines(Color color) {
        return startLines(color, .4f);
    }

    static void endLines(VertexConsumer bufferBuilder, boolean ignoredDepth) {
        if (ignoredDepth) {
            for (GizmoProperties p : pending) {
                if (p != null) {
                    p.setAlwaysOnTop();
                }
            }
        }
        pending.clear();
    }

    private static void addBox(AABB aabb, float lineWidth) {
        try {
            pending.add(Gizmos.cuboid(aabb, GizmoStyle.stroke(argb(), lineWidth)));
        } catch (IllegalStateException ignored) {
            // no gizmo collector active (drawing outside the world render pass) - skip
        }
    }

    private static void addLine(double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
        try {
            pending.add(Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), argb(), lineWidth));
        } catch (IllegalStateException ignored) {
            // no gizmo collector active - skip
        }
    }

    static void emitAABB(VertexConsumer bufferBuilder, PoseStack stack, AABB aabb, float lineWidth) {
        addBox(aabb, lineWidth);
    }

    static void emitAABB(VertexConsumer bufferBuilder, PoseStack stack, AABB aabb, double expand, float lineWidth) {
        addBox(aabb.inflate(expand, expand, expand), lineWidth);
    }

    static void emitLine(VertexConsumer bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
        addLine(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitLine(VertexConsumer bufferBuilder, PoseStack stack,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         double nx, double ny, double nz,
                         float lineWidth
    ) {
        addLine(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitLine(VertexConsumer bufferBuilder, PoseStack stack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float nx, float ny, float nz,
                         float lineWidth
    ) {
        addLine(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitLine(VertexConsumer bufferBuilder, PoseStack stack, Vec3 start, Vec3 end, float lineWidth) {
        addLine(start.x, start.y, start.z, end.x, end.y, end.z, lineWidth);
    }

    // ---- goal-beacon textured quads: not ported to the gizmo pipeline (minor) ----

    static VertexConsumer startBlockQuads() {
        return null;
    }

    static void endBuffer(VertexConsumer bufferBuilder, RenderType renderType) {
    }

    static void emitTexturedVertex(VertexConsumer bufferBuilder, PoseStack.Pose pose, float x, float y, float z, int color, float u, float v, float nx, float ny, float nz) {
    }

    static RenderType beaconBeam(Identifier identifier, boolean bl) {
        return null;
    }

    static RenderType beaconBeam(Identifier identifier, boolean bl, boolean ignoreDepth) {
        return null;
    }

    static void endFrame() {
    }
}
