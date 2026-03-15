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
import baritone.utils.accessor.IEntityRenderManager;
import baritone.utils.accessor.IRenderPipelines;
import baritone.utils.accessor.IRenderType;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.function.BiFunction;

public interface IRenderer {

    Tesselator tessellator = Tesselator.getInstance();
    IEntityRenderManager renderManager = (IEntityRenderManager) Minecraft.getInstance().getEntityRenderDispatcher();
    Settings settings = BaritoneAPI.getSettings();
    RenderPipeline.Snippet BARITONE_LINES_SNIPPET = RenderPipeline.builder(((IRenderPipelines) new RenderPipelines()).getLinesSnippet())
        .withBlend(new BlendFunction(
            SourceFactor.SRC_ALPHA,
            DestFactor.ONE_MINUS_SRC_ALPHA,
            SourceFactor.ONE,
            DestFactor.ZERO
        ))
        .withDepthWrite(false)
        .withCull(false)
        .buildSnippet();

    RenderPipeline.Snippet BARITONE_BEACON_BEAM_SNIPPET = RenderPipeline.builder(((IRenderPipelines) new RenderPipelines()).getMatricesFogSnippet())
            .withVertexShader("core/rendertype_beacon_beam")
            .withFragmentShader("core/rendertype_beacon_beam")
            .withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .withDepthWrite(false)
            .withCull(false)
            .buildSnippet();

    RenderPipeline BEACON_BEAM_OPAQUE = ((IRenderPipelines) new RenderPipelines()).baritone$registerPipeline(RenderPipeline.builder(BARITONE_BEACON_BEAM_SNIPPET)
            .withLocation("pipeline/beacon_beam_opaque")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build());

    RenderPipeline BEACON_BEAM_TRANSLUCENT = ((IRenderPipelines) new RenderPipelines()).baritone$registerPipeline(RenderPipeline.builder(BARITONE_BEACON_BEAM_SNIPPET)
            .withLocation("pipeline/beacon_beam_translucent")
            .withDepthWrite(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .build());

    RenderType linesWithDepthRenderType = ((IRenderType) RenderTypes.lines()).createRenderType(
        "renderType/baritone_lines_with_depth",
        RenderSetup.builder(RenderPipeline.builder(BARITONE_LINES_SNIPPET)
            .withLocation("pipelines/baritone_lines_with_depth")
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .build())
            .bufferSize(256)
            .createRenderSetup()
    );
    RenderType linesNoDepthRenderType = ((IRenderType) RenderTypes.lines()).createRenderType(
        "renderType/baritone_lines_no_depth",
        RenderSetup.builder(RenderPipeline.builder(BARITONE_LINES_SNIPPET)
                .withLocation("pipelines/baritone_lines_no_depth")
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .build())
            .bufferSize(256)
            .createRenderSetup()
    );


    BiFunction<Identifier, Boolean, RenderType> BEACON_BEAM = Util.memoize(
            (identifier, boolean_) -> ((IRenderType) RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, boolean_))
                    .createRenderType("renderType/beacon_beam",
            RenderSetup.builder(boolean_ ? BEACON_BEAM_TRANSLUCENT : BEACON_BEAM_OPAQUE)
                    .withTexture("Sampler0", identifier)
                    .sortOnUpload()
                    .createRenderSetup())
    );

    float[] color = new float[]{1.0F, 1.0F, 1.0F, 255.0F};

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    static BufferBuilder startLines(Color color, float alpha) {
        glColor(color, alpha);
        return tessellator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
    }

    static BufferBuilder startLines(Color color) {
        return startLines(color, .4f);
    }

    static void endLines(BufferBuilder bufferBuilder, boolean ignoredDepth) {
        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            if (ignoredDepth) {
                linesNoDepthRenderType.draw(meshData);
            } else {
                linesWithDepthRenderType.draw(meshData);
            }
        }
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double dz = z2 - z1;

        final double invMag = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float nx = (float) (dx * invMag);
        final float ny = (float) (dy * invMag);
        final float nz = (float) (dz * invMag);

        emitLine(bufferBuilder, stack, x1, y1, z1, x2, y2, z2, nx, ny, nz, lineWidth);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         double nx, double ny, double nz,
                         float lineWidth
    ) {
        emitLine(bufferBuilder, stack,
                (float) x1, (float) y1, (float) z1,
                (float) x2, (float) y2, (float) z2,
                (float) nx, (float) ny, (float) nz,
                lineWidth
        );
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float nx, float ny, float nz,
                         float lineWidth
    ) {
        PoseStack.Pose pose = stack.last();

        bufferBuilder.addVertex(pose, x1, y1, z1).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        bufferBuilder.addVertex(pose, x2, y2, z2).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, float lineWidth) {
        AABB toDraw = aabb.move(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());

        // bottom
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // top
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // corners
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double expand, float lineWidth) {
        emitAABB(bufferBuilder, stack, aabb.inflate(expand, expand, expand), lineWidth);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, Vec3 start, Vec3 end, float lineWidth) {
        double vpX = renderManager.renderPosX();
        double vpY = renderManager.renderPosY();
        double vpZ = renderManager.renderPosZ();
        emitLine(bufferBuilder, stack, start.x - vpX, start.y - vpY, start.z - vpZ, end.x - vpX, end.y - vpY, end.z - vpZ, lineWidth);
    }

    static RenderType beaconBeam(Identifier identifier, boolean bl) {
        return BEACON_BEAM.apply(identifier, bl);
    }

    static void submitBeaconBeam(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, Identifier identifier, float f, float g, int i, int j, int k, float h, float l) {
        int m = i + j;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, (double)0.5F);
        float n = j < 0 ? g : -g;
        float o = Mth.frac(n * 0.2F - (float)Mth.floor(n * 0.1F));
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(g * 2.25F - 45.0F));
        float p;
        float s;
        float t = -h;
        float w = -h;
        float z = -1.0F + o;
        float aa = (float)j * f * (0.5F / h) + z;
        float finalT = t;
        float finalAa = aa;
        float finalZ = z;
        submitNodeCollector.submitCustomGeometry(poseStack, beaconBeam(identifier, false), (pose, vertexConsumer) -> renderPart(pose, vertexConsumer, k, i, m, 0.0F, h, h, 0.0F, finalT, 0.0F, 0.0F, w, 0.0F, 1.0F, finalAa, finalZ));
        poseStack.popPose();
        p = -l;
        float q = -l;
        s = -l;
        t = -l;
        z = -1.0F + o;
        aa = (float)j * f + z;
        float finalP = p;
        float finalS = s;
        float finalT1 = t;
        float finalAa1 = aa;
        float finalZ1 = z;
        submitNodeCollector.submitCustomGeometry(poseStack, beaconBeam(identifier, true), (pose, vertexConsumer) -> renderPart(pose, vertexConsumer, ARGB.color(32, k), i, m, finalP, q, l, finalS, finalT1, l, l, l, 0.0F, 1.0F, finalAa1, finalZ1));
        poseStack.popPose();
    }
    static void renderPart(PoseStack.Pose pose, VertexConsumer vertexConsumer, int color, int yOffset, int height, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float u1, float u2, float v1, float v2) {
        renderQuad(pose, vertexConsumer, color, yOffset, height, x1, z1, x2, z2, u1, u2, v1, v2);
        renderQuad(pose, vertexConsumer, color, yOffset, height, x4, z4, x3, z3, u1, u2, v1, v2);
        renderQuad(pose, vertexConsumer, color, yOffset, height, x2, z2, x4, z4, u1, u2, v1, v2);
        renderQuad(pose, vertexConsumer, color, yOffset, height, x3, z3, x1, z1, u1, u2, v1, v2);
    }

    static void renderQuad(PoseStack.Pose pose, VertexConsumer vertexConsumer, int color, int yOffset, int height, float x1, float z1, float x2, float z2, float u1, float u2, float v1, float v2) {
        addVertex(pose, vertexConsumer, color, height, x1, z1, u2, v1);
        addVertex(pose, vertexConsumer, color, yOffset, x1, z1, u2, v2);
        addVertex(pose, vertexConsumer, color, yOffset, x2, z2, u1, v2);
        addVertex(pose, vertexConsumer, color, height, x2, z2, u1, v1);
    }

    static void addVertex(PoseStack.Pose pose, VertexConsumer vertexConsumer, int color, int y, float x, float z, float u, float v) {
        vertexConsumer.addVertex(pose, x, (float)y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
