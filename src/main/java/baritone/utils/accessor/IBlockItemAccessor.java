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

package baritone.utils.accessor;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exposes {@code BlockItem.getPlacementState}, which is protected. Unlike
 * {@code block.getStateForPlacement}, this honors the item's own placement logic
 * (e.g. {@code StandingAndWallBlockItem} choosing a wall torch on a side face), so
 * the builder can figure out that a torch item produces a wall torch.
 */
public interface IBlockItemAccessor {

    BlockState callGetPlacementState(BlockPlaceContext ctx);
}
