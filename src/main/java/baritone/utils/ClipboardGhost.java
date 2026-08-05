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
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.utils.BetterBlockPos;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The copy/paste "placement mode": after copying, the clipboard is shown as a
 * live ghost outline that sits at a fixed spot in the world (not following you),
 * which you can nudge, rotate and mirror from the menu's Clip tab, then confirm
 * to build. The ghost is drawn wherever the paste will actually land, so there's
 * no guessing.
 * <p>
 * The clipboard schematic + copy offset are captured by {@code sel copy}
 * (see {@link baritone.command.defaults.SelCommand}).
 */
public final class ClipboardGhost implements IRenderer {

    /** Above this block volume we only draw the footprint box, to stay cheap. */
    private static final int CONTENT_VOLUME_CAP = 20000;
    /** Hard limit on outlined blocks, to protect the renderer. */
    private static final int CONTENT_BOX_CAP = 1024;
    /** Hard limit on translucent ghost block models submitted per frame. */
    private static final int GHOST_MODEL_CAP = 4096;

    private static final Color FOOTPRINT_COLOR = new Color(90, 220, 255);
    private static final Color CONTENT_COLOR = new Color(160, 235, 255);

    private static ISchematic clipboard;   // raw copied schematic
    private static Vec3i copyOffset;        // origin - copyPos, for the default placement
    private static boolean placing;
    private static BlockPos placePos;       // world min-corner where the paste will build
    private static Rotation rotation = Rotation.NONE;
    private static Mirror mirror = Mirror.NONE;

    private ClipboardGhost() {}

    /** Called by {@code sel copy}. */
    public static void set(ISchematic schematic, Vec3i clipboardOffset) {
        clipboard = schematic;
        copyOffset = clipboardOffset;
    }

    public static boolean hasContent() {
        return clipboard != null && copyOffset != null;
    }

    /** The raw copied schematic (no rotation/mirror), for saving. */
    public static ISchematic currentClipboard() {
        return clipboard;
    }

    /** The copy offset paired with {@link #currentClipboard()}. */
    public static Vec3i currentOffset() {
        return copyOffset;
    }

    public static boolean isPlacing() {
        return placing;
    }

    public static BlockPos placePos() {
        return placePos;
    }

    public static Rotation rotation() {
        return rotation;
    }

    public static Mirror mirror() {
        return mirror;
    }

    /** Drop the ghost with the clipboard's min-corner at the player's feet (not the copy-time offset). */
    public static void startPlacing(BetterBlockPos playerFeet) {
        if (!hasContent()) {
            return;
        }
        placePos = new BlockPos(playerFeet.x, playerFeet.y, playerFeet.z);
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
        placing = true;
        ghostModelCache.clear(); // re-resolve models each placement session (survives resource reloads)
    }

    public static void cancel() {
        placing = false;
    }

    /** Forget the clipboard entirely. */
    public static void clearClipboard() {
        clipboard = null;
        copyOffset = null;
        placing = false;
        placePos = null;
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
    }

    public static void nudge(int dx, int dy, int dz) {
        if (placing && placePos != null) {
            placePos = placePos.offset(dx, dy, dz);
        }
    }

    public static void rotateCW() {
        if (placing) {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        }
    }

    public static void cycleMirror() {
        if (!placing) {
            return;
        }
        mirror = mirror == Mirror.NONE ? Mirror.FRONT_BACK
                : mirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.NONE;
    }

    /** The clipboard with the current mirror/rotation applied - what both the ghost and the build use. */
    private static ISchematic display() {
        ISchematic d = clipboard;
        if (mirror != Mirror.NONE) {
            d = new MirroredSchematic(d, mirror);
        }
        if (rotation != Rotation.NONE) {
            d = new RotatedSchematic(d, rotation);
        }
        return d;
    }

    /** Confirm: build the (rotated/mirrored) clipboard at the ghost's position. */
    public static void build() {
        if (!placing || !hasContent() || placePos == null) {
            return;
        }
        IBuilderProcess bp = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();
        bp.build("Paste", display(), placePos);
        // Apply overrides AFTER build() (build() clears any previous ones): paste builds
        // layered bottom-up, skips anything it can't place rather than getting stuck, and
        // places by item logic so torches etc. build as their wall variant.
        bp.setLayerOverride(true, false);
        bp.setSkipUnplaceableOverride(true);
        bp.setPlaceByItemOverride(true);
        placing = false;
    }

    public static void render(PoseStack stack) {
        if (!placing || !hasContent() || placePos == null) {
            return;
        }
        ISchematic d = display();
        int baseX = placePos.getX(), baseY = placePos.getY(), baseZ = placePos.getZ();
        int w = d.widthX(), h = d.heightY(), l = d.lengthZ();

        float opacity = settings.selectionOpacity.value;
        boolean ignoreDepth = settings.renderSelectionIgnoreDepth.value;
        float lineWidth = settings.selectionLineWidth.value;

        // Footprint: the full bounding box where the paste lands.
        VertexConsumer footprint = IRenderer.startLines(FOOTPRINT_COLOR, opacity);
        IRenderer.emitAABB(footprint, stack,
                new AABB(baseX, baseY, baseZ, baseX + w, baseY + h, baseZ + l), lineWidth);
        IRenderer.endLines(footprint, ignoreDepth);

        // Contents: outline each non-air block, for reasonably sized clipboards.
        if ((long) w * h * l > CONTENT_VOLUME_CAP) {
            return;
        }
        // When ghost block models are drawn (submitGhostBlocks), per-block wireframes are
        // redundant - except for blocks whose models render specially (chests etc.), which
        // the ghost pass can't draw, so those keep their outline box. Wireframes only yield
        // once the ghost pass has actually run - if its hook never fires, they stay.
        boolean ghosts = settings.ghostBlocks.value && ghostPassAlive;
        List<BlockState> none = Collections.emptyList();
        VertexConsumer content = IRenderer.startLines(CONTENT_COLOR, opacity * 0.55f);
        int drawn = 0;
        outer:
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (!d.inSchematic(x, y, z, null)) {
                        continue;
                    }
                    BlockState state;
                    try {
                        state = d.desiredState(x, y, z, null, none);
                    } catch (Exception e) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    if (ghosts) {
                        GhostModel model = ghostModelCache.get(state);
                        if (model == null || !model.parts.isEmpty()) {
                            continue; // ghost model drawn (or will be next frame) - no wireframe
                        }
                    }
                    IRenderer.emitAABB(content, stack,
                            new AABB(baseX + x, baseY + y, baseZ + z,
                                    baseX + x + 1, baseY + y + 1, baseZ + z + 1),
                            -0.05D, lineWidth);
                    if (++drawn >= CONTENT_BOX_CAP) {
                        break outer;
                    }
                }
            }
        }
        IRenderer.endLines(content, ignoreDepth);
    }

    // ------------------------------------------------------- ghost block models

    /** A block state's resolved model, ready to submit each frame. */
    private record GhostModel(List<BlockStateModelPart> parts, int[] tints) {}

    private static final Map<BlockState, GhostModel> ghostModelCache = new HashMap<>();
    /** Set the first time the submit hook runs; until then the wireframes don't yield. */
    private static boolean ghostPassAlive;

    /**
     * Litematica-style paste preview: submits each missing/mismatched block of the
     * ghost as a translucent block model through the vanilla submit pipeline (26.2),
     * so you see exactly what will be built. Called from {@code MixinWorldRenderer}
     * during {@code LevelRenderer#submitFeatures}.
     */
    public static void submitGhostBlocks(LevelRenderState frame, SubmitNodeCollector collector) {
        if (!placing || !hasContent() || placePos == null || !settings.ghostBlocks.value) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || frame.cameraRenderState == null || !frame.cameraRenderState.initialized) {
            return;
        }
        ISchematic d = display();
        int baseX = placePos.getX(), baseY = placePos.getY(), baseZ = placePos.getZ();
        int w = d.widthX(), h = d.heightY(), l = d.lengthZ();
        if ((long) w * h * l > CONTENT_VOLUME_CAP) {
            return; // footprint box only, same as the wireframe path
        }

        ghostPassAlive = true;

        float alpha = Math.max(0f, Math.min(1f, settings.ghostBlockOpacity.value));
        int tintColor = ARGB.color((int) (alpha * 255), 255, 255, 255);
        Vec3 cam = frame.cameraRenderState.pos;
        List<BlockState> none = Collections.emptyList();
        BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
        PoseStack pose = new PoseStack();
        int submitted = 0;

        outer:
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (!d.inSchematic(x, y, z, null)) {
                        continue;
                    }
                    BlockState state;
                    try {
                        state = d.desiredState(x, y, z, null, none);
                    } catch (Exception e) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    worldPos.set(baseX + x, baseY + y, baseZ + z);
                    if (level.getBlockState(worldPos) == state) {
                        continue; // world already matches - the real block speaks for itself
                    }
                    GhostModel model = ghostModelCache.get(state);
                    if (model == null) {
                        model = resolveGhostModel(state);
                        ghostModelCache.put(state, model);
                    }
                    if (model.parts.isEmpty()) {
                        continue; // specially-rendered block (chest etc.) - wireframe covers it
                    }
                    pose.pushPose();
                    pose.translate(worldPos.getX() - cam.x, worldPos.getY() - cam.y, worldPos.getZ() - cam.z);
                    collector.submitBlockModel(pose, Sheets.translucentBlockItemSheet(), model.parts,
                            model.tints, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, tintColor);
                    pose.popPose();
                    if (++submitted >= GHOST_MODEL_CAP) {
                        break outer;
                    }
                }
            }
        }
    }

    /**
     * Resolves a block state's baked model parts once; reused every frame.
     * <p>
     * Goes straight to the public model dispatch ({@code ModelManager ->
     * BlockStateModelSet -> collectParts} into our own list), which sidesteps the
     * {@code BlockModelRenderState} scratch-object dance entirely. Tints resolve
     * through {@code BlockColors} so grass/leaves ghost in the right color.
     */
    private static GhostModel resolveGhostModel(BlockState state) {
        try {
            Minecraft mc = Minecraft.getInstance();
            List<BlockStateModelPart> parts = new java.util.ArrayList<>();
            mc.getModelManager().getBlockStateModelSet().get(state)
                    .collectParts(net.minecraft.util.RandomSource.create(42L), parts);
            List<net.minecraft.client.color.block.BlockTintSource> sources = mc.getBlockColors().getTintSources(state);
            int[] tints = new int[sources.size()];
            for (int i = 0; i < tints.length; i++) {
                tints[i] = sources.get(i).color(state);
            }
            return new GhostModel(List.copyOf(parts), tints);
        } catch (Throwable e) {
            // resolved once per state, so this can't spam; empty model = wireframe fallback
            System.out.println("[EazyBaritone] ghost model resolve failed for " + state + ": " + e);
            return new GhostModel(List.of(), new int[0]);
        }
    }
}
