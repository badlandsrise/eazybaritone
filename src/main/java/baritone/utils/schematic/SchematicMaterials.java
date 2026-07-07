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

import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Counts the blocks a schematic file needs, for the Build tab materials list
 * and for chest restocking.
 */
public final class SchematicMaterials {

    private SchematicMaterials() {}

    /**
     * @return blocks needed by the schematic, most numerous first, or null if
     * the file could not be parsed
     */
    public static Map<Block, Integer> count(File file) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(file);
        if (!format.isPresent()) {
            return null;
        }
        IStaticSchematic schematic;
        try (FileInputStream in = new FileInputStream(file)) {
            schematic = format.get().parse(in);
        } catch (Exception e) {
            return null;
        }
        Map<Block, Integer> counts = new HashMap<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    BlockState state = schematic.getDirect(x, y, z);
                    if (state == null || state.getBlock() instanceof AirBlock) {
                        continue;
                    }
                    counts.merge(state.getBlock(), 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }
}
