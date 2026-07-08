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
import baritone.api.selection.ISelection;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * A WorldEdit/Litematica-style selection wand: hold a blaze rod, left-click a
 * block to set corner 1, right-click a block to set corner 2. The two corners
 * define one live selection that updates as you re-click either corner, so the
 * whole {@code #sel} toolset (cleararea, fill, walls, copy, build, ...) and the
 * menu's Area tab work on it immediately.
 * <p>
 * Gated by the {@link baritone.api.Settings#selectionWand} setting.
 */
public final class SelectionWand implements Helper {

    private static final SelectionWand INSTANCE = new SelectionWand();

    /** The two corners we are tracking, either may be null until first click. */
    private BetterBlockPos pos1;
    private BetterBlockPos pos2;
    /** The selection this wand owns, so re-clicking replaces it instead of stacking. */
    private ISelection owned;

    private SelectionWand() {}

    /**
     * @return the configured wand item, falling back to a blaze rod if the id
     * in the setting is empty or unknown
     */
    public static Item wandItem() {
        String id = BaritoneAPI.getSettings().selectionWandItem.value;
        if (id != null && !id.isBlank()) {
            try {
                Item item = BuiltInRegistries.ITEM.getOptional(
                        Identifier.parse(id.trim().toLowerCase())).orElse(null);
                if (item != null) {
                    return item;
                }
            } catch (Exception ignored) {
                // malformed id -> fall back below
            }
        }
        return Items.BLAZE_ROD;
    }

    /**
     * @return true if the wand is enabled and the player is holding the wand
     * item, meaning left/right clicks should be treated as selection actions
     * rather than block break/use
     */
    public static boolean isActive(Minecraft mc) {
        return BaritoneAPI.getSettings().selectionWand.value
                && mc.player != null
                && mc.player.getMainHandItem().is(wandItem());
    }

    /**
     * @return true if the click was consumed by the wand (caller should cancel
     * the vanilla break/use action)
     */
    public static boolean onClick(Minecraft mc, boolean rightClick) {
        if (!BaritoneAPI.getSettings().selectionWand.value) {
            return false;
        }
        if (mc.player == null || mc.level == null) {
            return false;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!held.is(wandItem())) {
            return false;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return INSTANCE.handle(new BetterBlockPos(((BlockHitResult) hit).getBlockPos()), rightClick);
    }

    private synchronized boolean handle(BetterBlockPos pos, boolean rightClick) {
        ISelectionManager manager = BaritoneAPI.getProvider().getPrimaryBaritone().getSelectionManager();

        // If our owned selection was cleared/undone elsewhere, forget it so we
        // don't try to remove a stale one.
        if (owned != null) {
            boolean stillThere = false;
            for (ISelection sel : manager.getSelections()) {
                if (sel == owned) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                owned = null;
                pos1 = null;
                pos2 = null;
            }
        }

        if (rightClick) {
            pos2 = pos;
        } else {
            pos1 = pos;
        }

        if (owned != null) {
            manager.removeSelection(owned);
            owned = null;
        }

        if (pos1 != null && pos2 != null) {
            owned = manager.addSelection(pos1, pos2);
            int size = owned.size().getX() * owned.size().getY() * owned.size().getZ();
            logDirect(String.format("%s set (%d,%d,%d) — region is %dx%dx%d = %d blocks",
                    rightClick ? "Corner 2" : "Corner 1",
                    pos.x, pos.y, pos.z,
                    owned.size().getX(), owned.size().getY(), owned.size().getZ(), size),
                    ChatFormatting.GRAY);
        } else {
            logDirect(String.format("%s set (%d,%d,%d) — now %s-click the other corner",
                    rightClick ? "Corner 2" : "Corner 1",
                    pos.x, pos.y, pos.z,
                    rightClick ? "left" : "right"),
                    ChatFormatting.GRAY);
        }
        return true;
    }

    /**
     * Draws a live outline while the blaze rod is held: a box around the block
     * you are aiming at (what the next click will grab) and, if only one corner
     * has been placed so far, a marker on that corner. The completed two-corner
     * region is already drawn by {@link baritone.selection.SelectionRenderer},
     * so we don't redraw it here.
     */
    public static void render(PoseStack stack) {
        INSTANCE.renderPreview(stack);
    }

    private synchronized void renderPreview(PoseStack stack) {
        Settings s = BaritoneAPI.getSettings();
        if (!s.selectionWand.value || !s.renderSelection.value) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getMainHandItem().is(wandItem())) {
            return;
        }

        float opacity = s.selectionOpacity.value;
        boolean ignoreDepth = s.renderSelectionIgnoreDepth.value;
        float lineWidth = s.selectionLineWidth.value;

        // A lone corner that hasn't yet formed a box (only pos1, or only pos2).
        if (pos1 != null && pos2 == null) {
            emitBox(stack, new AABB(pos1), s.colorSelectionPos1.value, opacity, lineWidth, ignoreDepth);
        } else if (pos2 != null && pos1 == null) {
            emitBox(stack, new AABB(pos2), s.colorSelectionPos2.value, opacity, lineWidth, ignoreDepth);
        }

        // Highlight the block currently under the crosshair.
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos aim = ((BlockHitResult) hit).getBlockPos();
            emitBox(stack, new AABB(aim).inflate(0.01D), s.colorSelection.value, opacity, lineWidth, ignoreDepth);
        }
    }

    private static void emitBox(PoseStack stack, AABB box, java.awt.Color color, float opacity, float lineWidth, boolean ignoreDepth) {
        VertexConsumer buffer = baritone.utils.IRenderer.startLines(color, opacity);
        baritone.utils.IRenderer.emitAABB(buffer, stack, box, lineWidth);
        baritone.utils.IRenderer.endLines(buffer, ignoreDepth);
    }
}
