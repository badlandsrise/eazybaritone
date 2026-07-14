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

package baritone.utils.schematic.format.defaults;

import baritone.api.schematic.ISchematic;
import baritone.utils.type.VarInt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes any {@link ISchematic} out as a Sponge v2 {@code .schem} file — the same
 * flat Palette/BlockData format {@link SpongeSchematic} reads back, so a saved file
 * round-trips through this fork's own reader and through {@code #build}.
 * <p>
 * Every property of every block state is written explicitly: the reader starts from
 * a block's default state and only re-applies the properties present in the palette
 * string, so an omitted property would silently revert to its default on load.
 */
public final class SpongeSchematicWriter {

    private SpongeSchematicWriter() {}

    public static void writeToFile(ISchematic schematic, Path file) throws IOException {
        NbtIo.writeCompressed(write(schematic), file);
    }

    /** Build the Sponge v2 CompoundTag for {@code schematic}. */
    public static CompoundTag write(ISchematic schematic) {
        int w = schematic.widthX();
        int h = schematic.heightY();
        int l = schematic.lengthZ();
        List<BlockState> none = Collections.emptyList();

        Map<String, Integer> palette = new LinkedHashMap<>();
        // bounded initial capacity: the stream grows as needed, and (long) avoids an
        // int overflow producing a negative/absurd capacity for a huge selection
        int hint = (int) Math.max(16, Math.min(1 << 22, (long) w * h * l));
        ByteArrayOutputStream blockData = new ByteArrayOutputStream(hint);

        // Sponge cell order: y outer, z middle, x inner (index = (y*Length + z)*Width + x).
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockState state = Blocks.AIR.defaultBlockState();
                    if (schematic.inSchematic(x, y, z, null)) {
                        try {
                            BlockState s = schematic.desiredState(x, y, z, null, none);
                            if (s != null) {
                                state = s;
                            }
                        } catch (RuntimeException ignored) {
                            // e.g. a CompositeSchematic cell outside any sub-region; leave as air
                        }
                    }
                    String key = serialize(state);
                    Integer index = palette.get(key);
                    if (index == null) {
                        index = palette.size();
                        palette.put(key, index);
                    }
                    byte[] encoded = new VarInt(index).serialize();
                    blockData.write(encoded, 0, encoded.length);
                }
            }
        }

        CompoundTag paletteTag = new CompoundTag();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            paletteTag.putInt(e.getKey(), e.getValue());
        }

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("Width", w);
        root.putInt("Height", h);
        root.putInt("Length", l);
        root.putInt("PaletteMax", palette.size());
        root.put("Palette", paletteTag);
        root.putByteArray("BlockData", blockData.toByteArray());
        return root;
    }

    /**
     * Serialize a block state to the reader's palette-key grammar:
     * {@code namespace:path[prop=val,prop=val]}, emitting every property.
     */
    private static String serialize(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder sb = new StringBuilder(id.toString());
        List<Property.Value<?>> values = state.getValues().toList();
        if (!values.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property.Value<?> v : values) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(v.property().getName()).append('=').append(v.valueName());
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
