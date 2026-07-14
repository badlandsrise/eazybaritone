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

import net.minecraft.world.level.block.Block;

/**
 * Exposes the wall variant of a {@code StandingAndWallBlockItem} (e.g. a torch item's
 * {@code wall_torch}), so the builder knows that item can produce that block.
 */
public interface IStandingAndWallBlockItemAccessor {

    Block getWallBlock();
}
