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
import baritone.api.Settings;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWaypointCollection;
import baritone.api.cache.Waypoint;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A beginner-friendly menu for starting Baritone jobs without memorizing chat
 * commands. Opened with the "Open Baritone Menu" keybind (default: B).
 * <p>
 * Every action funnels into {@code CommandManager} or the public process API,
 * so behavior is identical to typing the equivalent #command in chat.
 */
public class BaritoneMenuScreen extends Screen {

    private enum Tab {
        MINE("Mine"),
        GOTO("Go to"),
        FOLLOW("Follow"),
        FARM("Farm"),
        BUILD("Build"),
        AREA("Area"),
        SETTINGS("Settings");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final String[] PINNED_BLOCKS = {
            "coal_ore", "iron_ore", "copper_ore", "gold_ore",
            "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore",
            "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore", "deepslate_gold_ore",
            "deepslate_redstone_ore", "deepslate_lapis_ore", "deepslate_diamond_ore", "deepslate_emerald_ore",
            "nether_quartz_ore", "nether_gold_ore", "ancient_debris", "obsidian",
            "oak_log", "spruce_log", "birch_log", "sand"
    };

    /**
     * Human-friendly labels for the curated settings tab. Order matters.
     */
    private static final String[][] TOGGLE_SETTINGS = {
            {"allowBreak", "Break blocks"},
            {"allowPlace", "Place blocks"},
            {"allowSprint", "Sprint"},
            {"allowParkour", "Parkour jumps"},
            {"allowInventory", "Use inventory"},
            {"freeLook", "Free look (camera stays yours)"},
            {"legitMine", "Legit mine (no xray-like mining)"},
            {"allowDownward", "Dig straight down"},
            {"mineScanDroppedItems", "Grab dropped items while mining"},
            {"notificationOnPathComplete", "Notify when path completes"},
            {"guiHud", "Show job HUD overlay"},
    };

    private Tab tab = Tab.MINE;

    // per-tab state that must survive widget rebuilds
    private String mineSearch = "";
    private String mineQuantity = "";
    private String gotoX = "", gotoY = "", gotoZ = "";
    private String waypointName = "";
    private String farmRadius = "32";
    private String subFrom = "", subTo = "";
    private String statusMessage = "";
    private int buildSubsLabelY = -1;

    public BaritoneMenuScreen() {
        super(Component.literal("Baritone"));
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void runCommand(String command) {
        baritone().getCommandManager().execute(command);
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int contentLeft() {
        return this.width / 2 - 150;
    }

    private int contentTop() {
        return 58;
    }

    @Override
    protected void init() {
        // tab row
        int tabCount = Tab.values().length;
        int tabWidth = 44;
        int tabsLeft = this.width / 2 - (tabCount * (tabWidth + 2)) / 2;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.literal(t.label), btn -> {
                this.tab = t;
                this.statusMessage = "";
                this.rebuildWidgets();
            }).bounds(tabsLeft + t.ordinal() * (tabWidth + 2), 32, tabWidth, 20).build();
            b.active = t != this.tab;
            this.addRenderableWidget(b);
        }

        switch (this.tab) {
            case MINE -> initMine();
            case GOTO -> initGoto();
            case FOLLOW -> initFollow();
            case FARM -> initFarm();
            case BUILD -> initBuild();
            case AREA -> initArea();
            case SETTINGS -> initSettings();
        }

        // status bar controls (all tabs)
        int barY = this.height - 26;
        this.addRenderableWidget(Button.builder(Component.literal("Pause"), b -> baritone().getCommandManager().execute("pause"))
                .bounds(this.width - 190, barY, 56, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Resume"), b -> baritone().getCommandManager().execute("resume"))
                .bounds(this.width - 130, barY, 56, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Stop").copy().withStyle(ChatFormatting.RED), b -> baritone().getCommandManager().execute("cancel"))
                .bounds(this.width - 70, barY, 56, 20).build());
    }

    // ------------------------------------------------------------------ MINE

    private void initMine() {
        int left = contentLeft();
        int top = contentTop();

        EditBox search = new EditBox(this.font, left, top, 190, 18, Component.literal("search"));
        search.setHint(Component.literal("Search blocks..."));
        search.setValue(mineSearch);
        search.setResponder(s -> {
            mineSearch = s;
            this.rebuildWidgets();
        });
        this.addRenderableWidget(search);

        EditBox qty = new EditBox(this.font, left + 210, top, 60, 18, Component.literal("amount"));
        qty.setHint(Component.literal("amount"));
        qty.setValue(mineQuantity);
        qty.setResponder(s -> mineQuantity = s.replaceAll("[^0-9]", ""));
        this.addRenderableWidget(qty);

        List<Block> shown = new ArrayList<>();
        String q = mineSearch.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            for (String id : PINNED_BLOCKS) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).ifPresent(shown::add);
            }
        } else {
            for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                if (id.getPath().contains(q) && shown.size() < 32) {
                    shown.add(BuiltInRegistries.BLOCK.getValue(id));
                }
            }
            shown.sort(Comparator.comparingInt(b -> BuiltInRegistries.BLOCK.getKey(b).getPath().length()));
        }

        int cols = 12, cell = 24;
        int gridLeft = this.width / 2 - (cols * cell) / 2;
        int gridTop = top + 28;
        for (int i = 0; i < Math.min(shown.size(), 36); i++) {
            Block block = shown.get(i);
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            ItemStack icon = new ItemStack(block.asItem());
            if (icon.isEmpty()) {
                continue;
            }
            ItemButton btn = new ItemButton(
                    gridLeft + (i % cols) * cell, gridTop + (i / cols) * cell, cell - 2, cell - 2,
                    Component.literal(id.getPath()), icon,
                    () -> {
                        String amount = mineQuantity.trim();
                        runCommand(amount.isEmpty() ? "mine " + id.getPath() : "mine " + amount + " " + id.getPath());
                    });
            btn.setTooltip(Tooltip.create(Component.literal(id.getPath().replace('_', ' '))));
            this.addRenderableWidget(btn);
        }
    }

    // ------------------------------------------------------------------ GOTO

    private void initGoto() {
        int left = contentLeft();
        int top = contentTop();

        EditBox x = coordBox(left, top, "X", gotoX, s -> gotoX = s);
        EditBox y = coordBox(left + 65, top, "Y (optional)", gotoY, s -> gotoY = s);
        EditBox z = coordBox(left + 130, top, "Z", gotoZ, s -> gotoZ = s);
        this.addRenderableWidget(x);
        this.addRenderableWidget(y);
        this.addRenderableWidget(z);

        this.addRenderableWidget(Button.builder(Component.literal("Go!"), b -> {
            String xs = gotoX.trim(), ys = gotoY.trim(), zs = gotoZ.trim();
            if (xs.isEmpty() || zs.isEmpty()) {
                statusMessage = "Enter at least X and Z";
                return;
            }
            if (!xs.matches("-?\\d+") || !zs.matches("-?\\d+") || (!ys.isEmpty() && !ys.matches("-?\\d+"))) {
                statusMessage = "Coordinates must be whole numbers";
                return;
            }
            runCommand(ys.isEmpty() ? "goto " + xs + " " + zs : "goto " + xs + " " + ys + " " + zs);
        }).bounds(left + 200, top - 1, 50, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Go to surface"), b -> runCommand("surface"))
                .bounds(left + 255, top - 1, 90, 20).build());

        // waypoints
        EditBox name = new EditBox(this.font, left, top + 34, 140, 18, Component.literal("waypoint name"));
        name.setHint(Component.literal("New waypoint name..."));
        name.setValue(waypointName);
        name.setResponder(s -> waypointName = s);
        this.addRenderableWidget(name);

        this.addRenderableWidget(Button.builder(Component.literal("Save here"), b -> {
            String n = waypointName.trim().replace(' ', '_');
            if (n.isEmpty()) {
                statusMessage = "Type a name for the waypoint first";
                return;
            }
            IWaypointCollection waypoints = waypoints();
            if (waypoints == null) {
                statusMessage = "No world loaded";
                return;
            }
            waypoints.addWaypoint(new Waypoint(n, IWaypoint.Tag.USER, baritone().getPlayerContext().playerFeet()));
            waypointName = "";
            this.rebuildWidgets();
        }).bounds(left + 145, top + 33, 70, 20).build());

        IWaypointCollection waypoints = waypoints();
        if (waypoints != null) {
            List<IWaypoint> all = new ArrayList<>(waypoints.getAllWaypoints());
            all.sort(Comparator.comparing(IWaypoint::getName));
            int rowY = top + 60;
            for (IWaypoint wp : all) {
                if (rowY > this.height - 56) {
                    break;
                }
                BetterBlockPos pos = wp.getLocation();
                final int fy = rowY;
                this.addRenderableWidget(Button.builder(
                        Component.literal(wp.getName() + "  (" + pos.x + ", " + pos.y + ", " + pos.z + ")"),
                        b -> {
                            baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
                            this.onClose();
                        }).bounds(left, fy, 220, 18).build());
                this.addRenderableWidget(Button.builder(Component.literal("x").copy().withStyle(ChatFormatting.RED), b -> {
                    waypoints.removeWaypoint(wp);
                    this.rebuildWidgets();
                }).bounds(left + 224, fy, 18, 18).build());
                rowY += 21;
            }
        }
    }

    private EditBox coordBox(int x, int y, String hint, String value, java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, 60, 18, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value);
        box.setResponder(responder);
        return box;
    }

    private IWaypointCollection waypoints() {
        if (baritone().getWorldProvider().getCurrentWorld() == null) {
            return null;
        }
        return baritone().getWorldProvider().getCurrentWorld().getWaypoints();
    }

    // ---------------------------------------------------------------- FOLLOW

    private void initFollow() {
        int left = contentLeft();
        int top = contentTop();
        List<AbstractClientPlayer> players = new ArrayList<>();
        if (this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null) {
            for (AbstractClientPlayer p : this.minecraft.level.players()) {
                if (!p.getUUID().equals(this.minecraft.player.getUUID())) {
                    players.add(p);
                }
            }
        }
        if (players.isEmpty()) {
            statusMessage = "No other players nearby to follow";
            return;
        }
        int rowY = top;
        for (AbstractClientPlayer p : players) {
            if (rowY > this.height - 56) {
                break;
            }
            String name = p.getGameProfile().name();
            this.addRenderableWidget(Button.builder(Component.literal("Follow " + name), b -> runCommand("follow player " + name))
                    .bounds(left, rowY, 200, 20).build());
            rowY += 24;
        }
    }

    // ------------------------------------------------------------------ FARM

    private void initFarm() {
        int left = contentLeft();
        int top = contentTop();

        EditBox radius = new EditBox(this.font, left, top, 60, 18, Component.literal("radius"));
        radius.setHint(Component.literal("radius"));
        radius.setValue(farmRadius);
        radius.setResponder(s -> farmRadius = s.replaceAll("[^0-9]", ""));
        this.addRenderableWidget(radius);

        this.addRenderableWidget(Button.builder(Component.literal("Start farming"), b -> {
            String r = farmRadius.trim();
            runCommand(r.isEmpty() ? "farm" : "farm " + r);
        }).bounds(left + 70, top - 1, 110, 20).build());
    }

    // ----------------------------------------------------------------- BUILD

    private void initBuild() {
        int left = contentLeft();
        int top = contentTop();
        File dir = new File(this.minecraft.gameDirectory, "schematics");
        File[] files = dir.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.ROOT);
            return l.endsWith(".schem") || l.endsWith(".schematic") || l.endsWith(".litematic") || l.endsWith(".nbt");
        });
        int rowY = top;
        if (files == null || files.length == 0) {
            statusMessage = "No schematics found in " + dir.getPath();
        } else {
            int maxFileRows = 3;
            for (int i = 0; i < Math.min(files.length, maxFileRows); i++) {
                File f = files[i];
                this.addRenderableWidget(Button.builder(Component.literal("Build " + f.getName()), b -> {
                    BetterBlockPos feet = baritone().getPlayerContext().playerFeet();
                    baritone().getBuilderProcess().build(f.getName(), f, feet);
                    this.onClose();
                }).bounds(left, rowY, 240, 20).build());
                rowY += 22;
            }
        }

        // block substitutions ("schematic wants X, place Y instead")
        int subTop = rowY + 16;
        buildSubsLabelY = subTop - 11;

        EditBox from = new EditBox(this.font, left, subTop, 105, 18, Component.literal("block in schematic"));
        from.setHint(Component.literal("acacia_planks"));
        from.setValue(subFrom);
        from.setResponder(s -> subFrom = s);
        this.addRenderableWidget(from);

        EditBox to = new EditBox(this.font, left + 110, subTop, 105, 18, Component.literal("block to place"));
        to.setHint(Component.literal("oak_planks"));
        to.setValue(subTo);
        to.setResponder(s -> subTo = s);
        this.addRenderableWidget(to);

        this.addRenderableWidget(Button.builder(Component.literal("Add swap"), b -> addSubstitution())
                .bounds(left + 220, subTop - 1, 70, 20).build());

        Settings settings = BaritoneAPI.getSettings();
        int swapY = subTop + 24;
        for (Map.Entry<Block, List<Block>> entry : settings.buildSubstitutes.value.entrySet()) {
            for (Block target : entry.getValue()) {
                if (swapY > this.height - 52) {
                    break;
                }
                String fromName = BuiltInRegistries.BLOCK.getKey(entry.getKey()).getPath();
                String toName = BuiltInRegistries.BLOCK.getKey(target).getPath();
                final Block fromBlock = entry.getKey(), toBlock = target;
                this.addRenderableWidget(Button.builder(
                        Component.literal(fromName + " -> " + toName + "   [click to remove]"),
                        b -> removeSubstitution(fromBlock, toBlock)).bounds(left, swapY, 290, 18).build());
                swapY += 21;
            }
        }
    }

    private Block findBlock(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(null);
    }

    private void addSubstitution() {
        Block fromBlock = findBlock(subFrom);
        Block toBlock = findBlock(subTo);
        if (fromBlock == null || toBlock == null) {
            statusMessage = "Unknown block name: " + (fromBlock == null ? subFrom : subTo);
            return;
        }
        Settings settings = BaritoneAPI.getSettings();
        Map<Block, List<Block>> copy = deepCopySubstitutes(settings.buildSubstitutes.value);
        List<Block> targets = copy.computeIfAbsent(fromBlock, k -> new ArrayList<>());
        if (!targets.contains(toBlock)) {
            targets.add(toBlock);
        }
        settings.buildSubstitutes.value = copy;
        SettingsUtil.save(settings);
        subFrom = "";
        subTo = "";
        statusMessage = "";
        this.rebuildWidgets();
    }

    private void removeSubstitution(Block fromBlock, Block toBlock) {
        Settings settings = BaritoneAPI.getSettings();
        Map<Block, List<Block>> copy = deepCopySubstitutes(settings.buildSubstitutes.value);
        List<Block> targets = copy.get(fromBlock);
        if (targets != null) {
            targets.remove(toBlock);
            if (targets.isEmpty()) {
                copy.remove(fromBlock);
            }
        }
        settings.buildSubstitutes.value = copy;
        SettingsUtil.save(settings);
        this.rebuildWidgets();
    }

    private static Map<Block, List<Block>> deepCopySubstitutes(Map<Block, List<Block>> map) {
        Map<Block, List<Block>> copy = new java.util.HashMap<>();
        for (Map.Entry<Block, List<Block>> entry : map.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    // ------------------------------------------------------------------ AREA

    private void initArea() {
        int left = contentLeft();
        int top = contentTop();

        this.addRenderableWidget(Button.builder(Component.literal("Corner 1 = here"), b -> runCommandKeepOpen("sel pos1"))
                .bounds(left, top, 95, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Corner 2 = here"), b -> runCommandKeepOpen("sel pos2"))
                .bounds(left + 100, top, 95, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Pick by clicking"), b -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new baritone.utils.GuiClick());
            }
        }).bounds(left + 200, top, 100, 20).build());

        boolean hasSelection = baritone().getSelectionManager().getSelections().length > 0;

        Button clear = Button.builder(Component.literal("Clear area (dig it out)").copy().withStyle(ChatFormatting.RED),
                b -> runCommand("sel cleararea")).bounds(left, top + 24, 145, 20).build();
        clear.active = hasSelection;
        this.addRenderableWidget(clear);

        Button deselect = Button.builder(Component.literal("Deselect"), b -> runCommandKeepOpen("sel clear"))
                .bounds(left + 150, top + 24, 70, 20).build();
        deselect.active = hasSelection;
        this.addRenderableWidget(deselect);
    }

    private void runCommandKeepOpen(String command) {
        baritone().getCommandManager().execute(command);
        this.rebuildWidgets();
    }

    private List<String> areaInfoLines() {
        List<String> lines = new ArrayList<>();
        baritone.api.selection.ISelection[] selections = baritone().getSelectionManager().getSelections();
        if (selections.length == 0) {
            lines.add("No area selected yet.");
            lines.add("Stand on a corner and press \"Corner 1 = here\", walk to the opposite");
            lines.add("corner and press \"Corner 2 = here\" - or use \"Pick by clicking\" and");
            lines.add("drag from one block to another, then press B to come back here.");
        } else {
            for (baritone.api.selection.ISelection sel : selections) {
                BetterBlockPos min = sel.min(), max = sel.max();
                int dx = max.x - min.x + 1, dy = max.y - min.y + 1, dz = max.z - min.z + 1;
                lines.add("Selected: (" + min.x + ", " + min.y + ", " + min.z + ") to (" + max.x + ", " + max.y + ", " + max.z + ")"
                        + "  -  " + dx + " x " + dy + " x " + dz + " = " + (dx * (long) dy * dz) + " blocks");
            }
            if (!BaritoneAPI.getSettings().allowBreak.value) {
                lines.add("Warning: \"Break blocks\" is OFF in Settings - clearing needs it.");
            }
        }
        return lines;
    }

    // -------------------------------------------------------------- SETTINGS

    private void initSettings() {
        Settings settings = BaritoneAPI.getSettings();
        int left = contentLeft();
        int top = contentTop();
        int i = 0;
        for (String[] entry : TOGGLE_SETTINGS) {
            Settings.Setting<?> raw = settings.byLowerName.get(entry[0].toLowerCase(Locale.ROOT));
            if (raw == null || !(raw.value instanceof Boolean)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Settings.Setting<Boolean> setting = (Settings.Setting<Boolean>) raw;
            int col = i % 2, row = i / 2;
            this.addRenderableWidget(CycleButton.onOffBuilder(setting.value)
                    .create(left + col * 155, top + row * 24, 150, 20, Component.literal(entry[1]), (btn, val) -> {
                        setting.value = val;
                        SettingsUtil.save(settings);
                    }));
            i++;
        }
    }

    // ------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTicks) {
        extractor.fill(0, 24, this.width, this.height, 0x90000000);
        extractor.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        super.extractRenderState(extractor, mouseX, mouseY, partialTicks);

        if (!statusMessage.isEmpty()) {
            extractor.centeredText(this.font, Component.literal(statusMessage), this.width / 2, this.height - 44, 0xFFFFAA55);
        }

        if (this.tab == Tab.AREA) {
            int y = contentTop() + 54;
            for (String line : areaInfoLines()) {
                extractor.text(this.font, line, contentLeft(), y, 0xFFDDDDDD, true);
                y += 11;
            }
        }

        if (this.tab == Tab.BUILD && buildSubsLabelY > 0) {
            extractor.text(this.font, "Substitutions - if the schematic wants the left block, place the right one:",
                    contentLeft(), buildSubsLabelY, 0xFFDDDDDD, true);
        }

        // status bar: what's baritone doing right now
        String status = currentStatus();
        int maxWidth = this.width - 200;
        while (this.font.width(status) > maxWidth && status.length() > 4) {
            status = status.substring(0, status.length() - 4) + "...";
        }
        extractor.text(this.font, status, 8, this.height - 20, 0xFFFFFFFF, true);
    }

    private String currentStatus() {
        IBaritone baritone = baritone();
        Goal goal = baritone.getPathingBehavior().getGoal();
        String process = baritone.getPathingControlManager().mostRecentInControl()
                .map(p -> BaritoneHud.prettyName(p.displayName())).orElse("");
        if (goal == null && process.isEmpty()) {
            return "Idle";
        }
        String pathing = baritone.getPathingBehavior().isPathing() ? "Pathing" : "Ready";
        return pathing + (process.isEmpty() ? "" : " - " + process);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTicks) {
        // keep the world visible behind the menu
    }
}
