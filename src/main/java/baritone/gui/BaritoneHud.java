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

package baritone.gui;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One-line overlay while a Baritone job is active, so the user always knows
 * what the bot is doing and how to reach the controls.
 */
public final class BaritoneHud {

    private BaritoneHud() {}

    public static void extract(GuiGraphicsExtractor extractor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!BaritoneAPI.getSettings().guiHud.value) {
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        Goal goal = baritone.getPathingBehavior().getGoal();
        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (goal == null && !pathing) {
            return;
        }
        String process = baritone.getPathingControlManager().mostRecentInControl()
                .map(p -> prettyName(p.displayName())).orElse("Baritone");
        String line = "[Baritone] " + (pathing ? "> " : "|| ") + process + "  ([B] menu)";
        extractor.text(mc.font, line, 4, 4, 0xFFE080FF, true);
    }

    private static final java.util.regex.Pattern RESOURCE_NAME = java.util.regex.Pattern.compile("minecraft:([a-z0-9_]+)");

    /**
     * Baritone process display names can be raw internals like
     * "Mine BlockOptionalMetaLookup([BlockOptionalMeta(block=Block(minecraft:diamond_ore),...)])".
     * Reduce that to "Mine diamond_ore" for humans.
     */
    public static String prettyName(String raw) {
        if (raw.length() <= 40) {
            return raw;
        }
        java.util.regex.Matcher m = RESOURCE_NAME.matcher(raw);
        java.util.List<String> names = new java.util.ArrayList<>();
        while (m.find() && names.size() < 4) {
            if (!names.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        String firstWord = raw.contains(" ") ? raw.substring(0, raw.indexOf(' ')) : raw;
        if (!names.isEmpty()) {
            return firstWord + " " + String.join(", ", names);
        }
        return raw.substring(0, 37) + "...";
    }
}
