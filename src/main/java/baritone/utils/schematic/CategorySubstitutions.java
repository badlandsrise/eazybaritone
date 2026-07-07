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

package baritone.utils.schematic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Category-based fallback substitutions for builds: rules like
 * {@code stairs->cobblestone_stairs} expand to a substitution for every
 * stairs block in the registry, with the original block listed first so the
 * real material is always preferred when it is in the inventory
 * ({@link baritone.api.schematic.SubstituteSchematic} picks the first
 * placeable entry).
 * <p>
 * Rule strings are stored in the {@code guiSubstitutionRules} setting as
 * {@code category->block}.
 */
public final class CategorySubstitutions {

    /**
     * Categories offered by the GUI, in cycle order.
     */
    public static final String[] CATEGORIES = {
            "solid", "stairs", "slab", "wall", "fence", "fence_gate",
            "door", "trapdoor", "glass", "glass_pane", "planks", "log"
    };

    private CategorySubstitutions() {}

    public static boolean matches(String category, Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        switch (category) {
            case "solid":
                return block.defaultBlockState().isSolidRender()
                        && block.defaultDestroyTime() >= 0 // no bedrock etc.
                        && !(block instanceof AirBlock);
            case "stairs":
                return block instanceof StairBlock;
            case "slab":
                return block instanceof SlabBlock;
            case "wall":
                return block instanceof WallBlock;
            case "fence":
                return block instanceof FenceBlock;
            case "fence_gate":
                return block instanceof FenceGateBlock;
            case "door":
                return block instanceof DoorBlock;
            case "trapdoor":
                return block instanceof TrapDoorBlock;
            case "glass":
                return path.endsWith("glass");
            case "glass_pane":
                return path.endsWith("glass_pane");
            case "planks":
                return path.endsWith("_planks");
            case "log":
                return path.endsWith("_log") || path.endsWith("_wood")
                        || path.endsWith("_stem") || path.endsWith("_hyphae");
            default:
                return false;
        }
    }

    /**
     * Expands every rule into {@code subs}. Blocks that already have an
     * explicit substitution keep it (specific swaps beat category rules).
     */
    public static void expandInto(Map<Block, List<Block>> subs, List<String> rules) {
        for (String rule : rules) {
            String[] parts = rule.split("->", 2);
            if (parts.length != 2) {
                continue;
            }
            String category = parts[0].trim().toLowerCase(Locale.ROOT);
            Block target = BuiltInRegistries.BLOCK.getOptional(
                    Identifier.withDefaultNamespace(parts[1].trim().toLowerCase(Locale.ROOT))).orElse(null);
            if (target == null) {
                continue;
            }
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == target || subs.containsKey(block) || !matches(category, block)) {
                    continue;
                }
                List<Block> targets = new ArrayList<>(2);
                targets.add(block); // prefer the real block when available
                targets.add(target);
                subs.put(block, targets);
            }
        }
    }

    /**
     * @return how many registry blocks a category matches, for GUI preview
     */
    public static int count(String category) {
        int n = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (matches(category, block)) {
                n++;
            }
        }
        return n;
    }
}
