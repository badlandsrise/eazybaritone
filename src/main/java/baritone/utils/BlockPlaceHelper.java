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

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private int rightClickTimer;
    private BlockHitResult pendingDirectHit; // printer mode: place against this synthesized hit instead of the live camera ray

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    /**
     * Printer-mode override: place against an exact, synthesized {@link BlockHitResult} on the next
     * tick instead of whatever the camera raytrace happens to hit. Consumed after one tick. This is the
     * "Litematica printer" mechanism (BuilderProcess routes a validated hit here when buildPrinterMode
     * is on); it bypasses the line-of-sight requirement of {@link IPlayerContext#objectMouseOver()}.
     */
    public void placeDirect(BlockHitResult hit) {
        this.pendingDirectHit = hit;
    }

    public void tick(boolean rightClickRequested) {
        if (rightClickTimer > 0) {
            rightClickTimer--;
            pendingDirectHit = null;
            return;
        }
        HitResult mouseOver = pendingDirectHit != null ? pendingDirectHit : ctx.objectMouseOver();
        pendingDirectHit = null; // consume the synthesized hit whether or not we place this tick
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        for (InteractionHand hand : InteractionHand.values()) {
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                ctx.player().swing(hand);
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }
}
