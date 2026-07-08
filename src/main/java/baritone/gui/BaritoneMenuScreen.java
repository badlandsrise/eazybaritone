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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        AREA("Area"),
        CLIPBOARD("Clip"),
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
     * The most-used settings, in the order they should appear at the top of the
     * Settings tab, each paired with a short plain-English description. Any
     * setting not listed here still appears below (alphabetically) with an
     * auto-generated label. Names that don't exist are skipped harmlessly.
     */
    private static final String[][] COMMON_SETTINGS = {
            // permissions / movement
            {"allowBreak", "Break blocks"},
            {"allowPlace", "Place blocks"},
            {"allowSprint", "Sprint"},
            {"allowParkour", "Parkour jumps"},
            {"allowParkourPlace", "Place blocks mid-parkour"},
            {"allowInventory", "Use blocks from your inventory"},
            {"allowDownward", "Dig straight down"},
            {"allowDiagonalDescend", "Descend diagonally"},
            {"allowDiagonalAscend", "Ascend diagonally"},
            {"allowWaterBucketFall", "Water-bucket to break long falls"},
            {"assumeWalkOnWater", "Assume you can walk on water"},
            {"step", "Step up full blocks without jumping"},
            // mining
            {"legitMine", "Legit mine (no x-ray behaviour)"},
            {"mineScanDroppedItems", "Pick up dropped items while mining"},
            {"mineGoalUpdateInterval", "Mining rescan interval (ticks)"},
            {"blockReachDistance", "How far you can reach to mine (blocks)"},
            // building
            {"buildInLayers", "Build layer by layer"},
            {"layerOrder", "Build top layer first"},
            {"layerHeight", "Layer thickness (blocks)"},
            {"startAtLayer", "Start building at layer #"},
            {"buildIgnoreExisting", "Re-check blocks already placed"},
            {"buildRepeatCount", "Times to repeat the build"},
            {"schematicOrientationX", "Mirror schematic on X"},
            {"schematicOrientationZ", "Mirror schematic on Z"},
            // following / avoidance
            {"followRadius", "Follow distance (blocks)"},
            {"avoidance", "Steer around mobs"},
            {"mobAvoidanceRadius", "Mob avoidance radius (blocks)"},
            // pathing behaviour
            {"allowSprintToKeepDirection", "Keep sprinting through turns"},
            {"sprintAscends", "Sprint up slopes"},
            {"primaryTimeoutMS", "Pathfinding time budget (ms)"},
            {"failureTimeoutMS", "Give-up time when stuck (ms)"},
            // rendering / performance
            {"renderPath", "Show the path line"},
            {"renderGoal", "Show goal markers"},
            {"renderGoalXZBeacon", "Show goal beacon beam"},
            {"renderSelection", "Show area selections"},
            {"renderCachedChunks", "Draw Baritone's cached chunks"},
            {"pathRenderLineWidthPixels", "Path line thickness (px)"},
            {"freeLook", "Free look (camera stays yours)"},
            // interface / notifications
            {"chatControl", "Accept chat commands"},
            {"desktopNotifications", "Desktop notifications"},
            {"notificationOnPathComplete", "Notify when a path finishes"},
            {"notificationOnBuildFinished", "Notify when a build finishes"},
            // this fork's GUI extras
            {"selectionWand", "Selection wand enabled"},
            {"selectionWandItem", "Selection wand item (id)"},
            {"guiHud", "Show job HUD overlay"},
    };

    /** Lower-cased setting name -> short description, built from COMMON_SETTINGS. */
    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static {
        for (String[] entry : COMMON_SETTINGS) {
            DESCRIPTIONS.put(entry[0].toLowerCase(Locale.ROOT), entry[1]);
        }
    }

    /** camelCase setting name -> "Camel case" fallback label for the long tail. */
    private static String prettifyName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                sb.append(' ');
                sb.append(Character.toLowerCase(c));
            } else if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String labelFor(Settings.Setting<?> setting) {
        return DESCRIPTIONS.getOrDefault(setting.getName().toLowerCase(Locale.ROOT), prettifyName(setting.getName()));
    }

    private Tab tab = Tab.MINE;

    // per-tab state that must survive widget rebuilds
    private String mineSearch = "";
    private String mineQuantity = "";
    private String gotoX = "", gotoY = "", gotoZ = "";
    private String waypointName = "";
    private String farmRadius = "32";
    private String areaFillBlock = ""; // fill/placement block chosen in the Area tab
    private String areaReplaceFrom = ""; // block that Replace looks for and swaps out
    private int blockPickTarget = 0; // 0 = none, 4 = area fill, 5 = area replace-from, 6 = wand item
    private String pickerSearch = "";
    private boolean restoreSearchFocus = false;
    private String statusMessage = "";


    // Settings tab: search + paging over the full ~200-setting list
    private String settingsSearch = "";
    private int settingsPage = 0;
    private String settingsPageInfo = "";
    private int settingsLeftX = 0; // left edge of the settings list (set in init, used in render)
    private final List<SettingRow> settingRows = new ArrayList<>();

    /** A setting name label drawn next to its value control in the full list. */
    private record SettingRow(String label, int y) {}

    private static final int SETTINGS_PAGE_SIZE = 11;

    // sensible items to offer as the selection wand (no-search default set)
    private static final String[] WAND_PICKS = {
            "blaze_rod", "stick", "bone", "feather", "breeze_rod",
            "wooden_axe", "golden_hoe", "wooden_shovel", "brush", "clock",
            "compass", "name_tag"
    };

    private static final String[] COMMON_PICKS = {
            "cobblestone", "cobbled_deepslate", "stone", "stone_bricks", "bricks", "sandstone",
            "dirt", "oak_planks", "spruce_planks", "birch_planks", "glass", "glass_pane",
            "cobblestone_stairs", "oak_stairs", "stone_brick_stairs", "cobblestone_slab", "oak_slab", "stone_brick_slab",
            "cobblestone_wall", "oak_fence", "oak_fence_gate", "oak_door", "oak_trapdoor", "oak_log"
    };

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
            case AREA -> initArea();
            case CLIPBOARD -> initClipboard();
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
            restoreSearchFocus = true;
            this.rebuildWidgets();
        });
        this.addRenderableWidget(search);
        if (restoreSearchFocus) {
            this.setInitialFocus(search);
            restoreSearchFocus = false;
        }

        EditBox qty = new EditBox(this.font, left + 210, top, 60, 18, Component.literal("amount"));
        qty.setHint(Component.literal("amount"));
        qty.setValue(mineQuantity);
        qty.setResponder(s -> mineQuantity = s.replaceAll("[^0-9]", ""));
        this.addRenderableWidget(qty);

        addBlockGrid(mineSearch, PINNED_BLOCKS, top + 28, block -> {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            String amount = mineQuantity.trim();
            runCommand(amount.isEmpty() ? "mine " + id : "mine " + amount + " " + id);
        });
    }

    /**
     * A searchable grid of clickable block icons; the shared "block picker".
     */
    private void addBlockGrid(String query, String[] pinned, int gridTop, java.util.function.Consumer<Block> onPick) {
        List<Block> shown = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            for (String id : pinned) {
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
                    () -> onPick.accept(block));
            btn.setTooltip(Tooltip.create(Component.literal(id.getPath().replace('_', ' '))));
            this.addRenderableWidget(btn);
        }
    }

    /** Picker sub-view for choosing the selection wand item. */
    private void initItemPicker() {
        int left = contentLeft();
        int top = contentTop();

        EditBox search = new EditBox(this.font, left, top, 190, 18, Component.literal("search"));
        search.setHint(Component.literal("Search items for the wand..."));
        search.setValue(pickerSearch);
        search.setResponder(s -> {
            pickerSearch = s;
            restoreSearchFocus = true;
            this.rebuildWidgets();
        });
        this.addRenderableWidget(search);
        if (restoreSearchFocus) {
            this.setInitialFocus(search);
            restoreSearchFocus = false;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            blockPickTarget = 0;
            this.rebuildWidgets();
        }).bounds(left + 210, top - 1, 60, 20).build());

        addItemGrid(pickerSearch, WAND_PICKS, top + 28, item -> {
            String id = BuiltInRegistries.ITEM.getKey(item).getPath();
            Settings settings = BaritoneAPI.getSettings();
            settings.selectionWandItem.value = id;
            SettingsUtil.save(settings);
            blockPickTarget = 0;
            pickerSearch = "";
            statusMessage = "Wand item set to " + id;
            this.rebuildWidgets();
        });
    }

    /**
     * A searchable grid of clickable item icons; the item counterpart of
     * {@link #addBlockGrid}.
     */
    private void addItemGrid(String query, String[] pinned, int gridTop, java.util.function.Consumer<Item> onPick) {
        List<Item> shown = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            for (String id : pinned) {
                BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id)).ifPresent(shown::add);
            }
        } else {
            for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                if (id.getPath().contains(q) && shown.size() < 32) {
                    shown.add(BuiltInRegistries.ITEM.getValue(id));
                }
            }
            shown.sort(Comparator.comparingInt(it -> BuiltInRegistries.ITEM.getKey(it).getPath().length()));
        }

        int cols = 12, cell = 24;
        int gridLeft = this.width / 2 - (cols * cell) / 2;
        for (int i = 0; i < Math.min(shown.size(), 36); i++) {
            Item item = shown.get(i);
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack icon = new ItemStack(item);
            if (icon.isEmpty()) {
                continue;
            }
            ItemButton btn = new ItemButton(
                    gridLeft + (i % cols) * cell, gridTop + (i / cols) * cell, cell - 2, cell - 2,
                    Component.literal(id.getPath()), icon,
                    () -> onPick.accept(item));
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

    private void openPicker(int target) {
        blockPickTarget = target;
        pickerSearch = "";
        statusMessage = "";
        this.rebuildWidgets();
    }

    private void initBlockPicker() {
        int left = contentLeft();
        int top = contentTop();

        EditBox search = new EditBox(this.font, left, top, 190, 18, Component.literal("search"));
        search.setHint(Component.literal("Search blocks..."));
        search.setValue(pickerSearch);
        search.setResponder(s -> {
            pickerSearch = s;
            restoreSearchFocus = true;
            this.rebuildWidgets();
        });
        this.addRenderableWidget(search);
        if (restoreSearchFocus) {
            this.setInitialFocus(search);
            restoreSearchFocus = false;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            blockPickTarget = 0;
            this.rebuildWidgets();
        }).bounds(left + 210, top - 1, 60, 20).build());

        addBlockGrid(pickerSearch, COMMON_PICKS, top + 28, block -> {
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            switch (blockPickTarget) {
                case 4 -> areaFillBlock = path;
                case 5 -> areaReplaceFrom = path;
            }
            blockPickTarget = 0;
            pickerSearch = "";
            this.rebuildWidgets();
        });
    }

    // ------------------------------------------------------------------ AREA

    // fill shapes offered as buttons: {sel action, button label}
    private static final String[][] AREA_SHAPES = {
            {"set", "Fill solid"},
            {"walls", "Walls"},
            {"shell", "Shell"},
            {"sphere", "Sphere"},
            {"hsphere", "Hollow sphere"},
            {"cylinder", "Cylinder"},
            {"hcylinder", "Hollow cyl."},
    };

    private void initArea() {
        if (blockPickTarget == 6) {
            initItemPicker();
            return;
        }
        if (blockPickTarget != 0) {
            initBlockPicker();
            return;
        }
        int left = contentLeft();
        int top = contentTop();

        this.addRenderableWidget(Button.builder(Component.literal("Corner 1 = here"), b -> runCommandKeepOpen("sel pos1"))
                .bounds(left, top, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Corner 2 = here"), b -> runCommandKeepOpen("sel pos2"))
                .bounds(left + 150, top, 145, 20).build());

        boolean hasSelection = baritone().getSelectionManager().getSelections().length > 0;

        Button clear = Button.builder(Component.literal("Clear area (dig it out)").copy().withStyle(ChatFormatting.RED),
                b -> runCommand("sel cleararea")).bounds(left, top + 24, 145, 20).build();
        clear.active = hasSelection;
        this.addRenderableWidget(clear);

        Button deselect = Button.builder(Component.literal("Deselect"), b -> runCommandKeepOpen("sel clear"))
                .bounds(left + 150, top + 24, 70, 20).build();
        deselect.active = hasSelection;
        this.addRenderableWidget(deselect);

        // wand item picker (icon of the current wand item; click to change)
        Item wand = baritone.utils.SelectionWand.wandItem();
        ItemStack wandIcon = new ItemStack(wand);
        if (!wandIcon.isEmpty()) {
            ItemButton wandBtn = new ItemButton(left + 278, top + 24, 20, 20,
                    Component.literal("wand item"), wandIcon, () -> openPicker(6));
            wandBtn.setTooltip(Tooltip.create(Component.literal(
                    "Wand item: " + BuiltInRegistries.ITEM.getKey(wand).getPath() + "  (click to change)")));
            this.addRenderableWidget(wandBtn);
        }

        // fill block picker + shape buttons
        this.addRenderableWidget(Button.builder(
                Component.literal(areaFillBlock.isEmpty() ? "Choose fill block..." : "Fill block: " + areaFillBlock),
                b -> openPicker(4)).bounds(left, top + 52, 300, 20).build());

        boolean canFill = hasSelection && !areaFillBlock.isEmpty();
        int gridTop = top + 76;
        for (int i = 0; i < AREA_SHAPES.length; i++) {
            final String action = AREA_SHAPES[i][0];
            int col = i % 3, row = i / 3;
            Button shape = Button.builder(Component.literal(AREA_SHAPES[i][1]),
                    b -> runCommand("sel " + action + " " + areaFillBlock))
                    .bounds(left + col * 102, gridTop + row * 22, 97, 20).build();
            shape.active = canFill;
            this.addRenderableWidget(shape);
        }

        // Replace: swap one existing block for the fill block, everything else untouched
        int replaceTop = gridTop + ((AREA_SHAPES.length + 2) / 3) * 22 + 4;
        this.addRenderableWidget(Button.builder(
                Component.literal(areaReplaceFrom.isEmpty() ? "Replace which block..." : "Replace: " + areaReplaceFrom),
                b -> openPicker(5)).bounds(left, replaceTop, 195, 20).build());
        Button replace = Button.builder(Component.literal("Replace → fill"),
                b -> runCommand("sel replace " + areaReplaceFrom + " " + areaFillBlock))
                .bounds(left + 200, replaceTop, 100, 20).build();
        replace.active = canFill && !areaReplaceFrom.isEmpty();
        this.addRenderableWidget(replace);
    }

    private void runCommandKeepOpen(String command) {
        baritone().getCommandManager().execute(command);
        this.rebuildWidgets();
    }

    // --------------------------------------------------------------- CLIPBOARD

    private void initClipboard() {
        int left = contentLeft();
        int top = contentTop();

        if (baritone.utils.ClipboardGhost.isPlacing()) {
            // placement mode: nudge / rotate / mirror the ghost, then build
            addNudgeButton("Move West (X-)", -1, 0, 0, left, top);
            addNudgeButton("Move East (X+)", 1, 0, 0, left + 150, top);
            addNudgeButton("Move North (Z-)", 0, 0, -1, left, top + 24);
            addNudgeButton("Move South (Z+)", 0, 0, 1, left + 150, top + 24);
            addNudgeButton("Move Down", 0, -1, 0, left, top + 48);
            addNudgeButton("Move Up", 0, 1, 0, left + 150, top + 48);

            this.addRenderableWidget(Button.builder(Component.literal("Rotate 90°"), b -> {
                baritone.utils.ClipboardGhost.rotateCW();
                this.rebuildWidgets();
            }).bounds(left, top + 72, 145, 20).build());
            this.addRenderableWidget(Button.builder(
                    Component.literal("Mirror: " + mirrorName(baritone.utils.ClipboardGhost.mirror())), b -> {
                        baritone.utils.ClipboardGhost.cycleMirror();
                        this.rebuildWidgets();
                    }).bounds(left + 150, top + 72, 145, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("Build here").copy().withStyle(ChatFormatting.GREEN), b -> {
                        baritone.utils.ClipboardGhost.build();
                        this.onClose();
                    }).bounds(left, top + 100, 145, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                baritone.utils.ClipboardGhost.cancel();
                this.rebuildWidgets();
            }).bounds(left + 150, top + 100, 145, 20).build());
            return;
        }

        boolean hasSelection = baritone().getSelectionManager().getSelections().length > 0;
        boolean hasContent = baritone.utils.ClipboardGhost.hasContent();

        Button copy = Button.builder(Component.literal("Copy selection"), b -> {
            baritone().getCommandManager().execute("sel copy");
            this.rebuildWidgets();
        }).bounds(left, top, 145, 20).build();
        copy.active = hasSelection;
        this.addRenderableWidget(copy);

        Button cut = Button.builder(Component.literal("Cut selection").copy().withStyle(ChatFormatting.RED), b -> {
            baritone().getCommandManager().execute("sel copy");
            baritone().getCommandManager().execute("sel cleararea");
            this.rebuildWidgets();
        }).bounds(left + 150, top, 145, 20).build();
        cut.active = hasSelection;
        this.addRenderableWidget(cut);

        Button place = Button.builder(Component.literal("Place paste (ghost)"), b -> {
            baritone.utils.ClipboardGhost.startPlacing(baritone().getPlayerContext().playerFeet());
            this.rebuildWidgets();
        }).bounds(left, top + 24, 145, 20).build();
        place.active = hasContent;
        this.addRenderableWidget(place);

        Button clear = Button.builder(Component.literal("Clear clipboard"), b -> {
            baritone.utils.ClipboardGhost.clearClipboard();
            this.rebuildWidgets();
        }).bounds(left + 150, top + 24, 145, 20).build();
        clear.active = hasContent;
        this.addRenderableWidget(clear);
    }

    private void addNudgeButton(String label, int dx, int dy, int dz, int x, int y) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            baritone.utils.ClipboardGhost.nudge(dx, dy, dz);
            this.rebuildWidgets();
        }).bounds(x, y, 145, 20).build());
    }

    private static String mirrorName(net.minecraft.world.level.block.Mirror m) {
        return m == net.minecraft.world.level.block.Mirror.NONE ? "off"
                : m == net.minecraft.world.level.block.Mirror.FRONT_BACK ? "front-back" : "left-right";
    }

    private static String rotationName(net.minecraft.world.level.block.Rotation r) {
        return r == net.minecraft.world.level.block.Rotation.NONE ? "0°"
                : r == net.minecraft.world.level.block.Rotation.CLOCKWISE_90 ? "90°"
                : r == net.minecraft.world.level.block.Rotation.CLOCKWISE_180 ? "180°" : "270°";
    }

    private List<String> clipboardInfoLines() {
        List<String> lines = new ArrayList<>();
        boolean hasSelection = baritone().getSelectionManager().getSelections().length > 0;
        if (baritone.utils.ClipboardGhost.isPlacing()) {
            net.minecraft.core.BlockPos p = baritone.utils.ClipboardGhost.placePos();
            lines.add("Placing paste - the blue ghost shows exactly where it will build.");
            lines.add("Close this menu (B) to see it clearly; reopen to keep adjusting.");
            if (p != null) {
                lines.add("Corner: (" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")    "
                        + "Rotation: " + rotationName(baritone.utils.ClipboardGhost.rotation())
                        + "    Mirror: " + mirrorName(baritone.utils.ClipboardGhost.mirror()));
            }
            lines.add("Nudge / Rotate / Mirror above, then \"Build here\" to start building.");
            if (!BaritoneAPI.getSettings().allowPlace.value) {
                lines.add("Warning: \"Place blocks\" is OFF in Settings - building needs it.");
            }
        } else if (!baritone.utils.ClipboardGhost.hasContent()) {
            if (!hasSelection) {
                lines.add("Nothing copied yet. Make a selection in the Area tab, then Copy or Cut here.");
            } else {
                lines.add("Copy or Cut your selection to put it on the clipboard.");
            }
            lines.add("Cut = Copy then dig the area out (needs \"Break blocks\" on).");
        } else {
            lines.add("Clipboard ready. Hit \"Place paste (ghost)\" to drop a preview you can");
            lines.add("nudge, rotate and mirror before building, or \"Clear clipboard\" to discard it.");
        }
        return lines;
    }

    private List<String> areaInfoLines() {
        List<String> lines = new ArrayList<>();
        baritone.api.selection.ISelection[] selections = baritone().getSelectionManager().getSelections();
        if (selections.length == 0) {
            lines.add("No area selected yet.");
            lines.add("Stand on a corner and press \"Corner 1 = here\", walk to the opposite");
            lines.add("corner and press \"Corner 2 = here\" - or hold the wand and left/right-click.");
        } else {
            for (baritone.api.selection.ISelection sel : selections) {
                BetterBlockPos min = sel.min(), max = sel.max();
                int dx = max.x - min.x + 1, dy = max.y - min.y + 1, dz = max.z - min.z + 1;
                lines.add("Selected: (" + min.x + ", " + min.y + ", " + min.z + ") to (" + max.x + ", " + max.y + ", " + max.z + ")"
                        + "  -  " + dx + " x " + dy + " x " + dz + " = " + (dx * (long) dy * dz) + " blocks");
            }
            if (areaFillBlock.isEmpty()) {
                lines.add("Pick a fill block to enable Fill / Walls / Shell / Sphere / Cylinder.");
            } else {
                lines.add("Fill block: " + areaFillBlock + " - shapes build inside the selection.");
                lines.add("Replace swaps only \"" + (areaReplaceFrom.isEmpty() ? "(pick a block)" : areaReplaceFrom)
                        + "\" for " + areaFillBlock + ", leaving everything else.");
            }
            if (!BaritoneAPI.getSettings().allowBreak.value) {
                lines.add("Warning: \"Break blocks\" is OFF in Settings - clearing needs it.");
            }
            if (!areaFillBlock.isEmpty() && !BaritoneAPI.getSettings().allowPlace.value) {
                lines.add("Warning: \"Place blocks\" is OFF in Settings - filling needs it.");
            }
        }
        return lines;
    }

    // -------------------------------------------------------------- SETTINGS

    // Settings tab layout: a wide single-column list (description on the left,
    // value control on the right). Width adapts to the (scaled) screen so it
    // never runs off the edge.
    private static final int SETTINGS_CTRL_W = 100;

    private void initSettings() {
        Settings settings = BaritoneAPI.getSettings();
        int top = contentTop();
        int listWidth = Math.min(480, this.width - 40);
        int left = (this.width - listWidth) / 2;
        int ctrlX = left + listWidth - SETTINGS_CTRL_W;
        settingsLeftX = left;
        settingRows.clear();
        settingsPageInfo = "";

        EditBox search = new EditBox(this.font, left, top, listWidth, 18, Component.literal("search"));
        search.setHint(Component.literal("Search settings (e.g. \"break\", \"layer\", \"render\")..."));
        search.setValue(settingsSearch);
        search.setResponder(s -> {
            settingsSearch = s;
            settingsPage = 0;
            restoreSearchFocus = true;
            this.rebuildWidgets();
        });
        this.addRenderableWidget(search);
        if (restoreSearchFocus) {
            this.setInitialFocus(search);
            restoreSearchFocus = false;
        }

        // Build the display order: curated common settings first, then the rest
        // alphabetically. Then filter by the search box (matching name OR label).
        List<Settings.Setting<?>> ordered = orderedSettings(settings);
        String query = settingsSearch.toLowerCase(Locale.ROOT);
        List<Settings.Setting<?>> matches = new ArrayList<>();
        for (Settings.Setting<?> s : ordered) {
            if (query.isEmpty()
                    || s.getName().toLowerCase(Locale.ROOT).contains(query)
                    || labelFor(s).toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(s);
            }
        }

        int totalPages = Math.max(1, (matches.size() + SETTINGS_PAGE_SIZE - 1) / SETTINGS_PAGE_SIZE);
        settingsPage = Math.max(0, Math.min(settingsPage, totalPages - 1));
        int startIdx = settingsPage * SETTINGS_PAGE_SIZE;
        int endIdx = Math.min(matches.size(), startIdx + SETTINGS_PAGE_SIZE);

        int rowY = top + 26;
        for (int idx = startIdx; idx < endIdx; idx++) {
            Settings.Setting<?> s = matches.get(idx);
            settingRows.add(new SettingRow(labelFor(s), rowY + 6));
            if (s.value instanceof Boolean) {
                @SuppressWarnings("unchecked")
                Settings.Setting<Boolean> bs = (Settings.Setting<Boolean>) s;
                this.addRenderableWidget(CycleButton.onOffBuilder(bs.value).displayOnlyValue()
                        .create(ctrlX, rowY, SETTINGS_CTRL_W, 20, Component.literal(labelFor(s)), (btn, val) -> {
                            bs.value = val;
                            SettingsUtil.save(settings);
                        }));
            } else {
                String current;
                try {
                    current = SettingsUtil.settingValueToString(s);
                } catch (Exception e) {
                    current = "";
                }
                EditBox box = new EditBox(this.font, ctrlX, rowY + 1, SETTINGS_CTRL_W, 18, Component.literal(labelFor(s)));
                box.setMaxLength(512);
                box.setValue(current);
                final String name = s.getName();
                // set the responder AFTER setValue so populating the field
                // doesn't re-apply/save during rebuild
                box.setResponder(v -> {
                    try {
                        SettingsUtil.parseAndApply(settings, name, v.trim());
                        SettingsUtil.save(settings);
                    } catch (Exception ignored) {
                        // partial or invalid input; keep the old value until it parses
                    }
                });
                this.addRenderableWidget(box);
            }
            rowY += 22;
        }

        int barY = top + 26 + SETTINGS_PAGE_SIZE * 22 + 4;
        Button prev = Button.builder(Component.literal("< Prev"), b -> {
            settingsPage--;
            this.rebuildWidgets();
        }).bounds(left, barY, 60, 20).build();
        prev.active = settingsPage > 0;
        this.addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("Next >"), b -> {
            settingsPage++;
            this.rebuildWidgets();
        }).bounds(left + listWidth - 60, barY, 60, 20).build();
        next.active = settingsPage < totalPages - 1;
        this.addRenderableWidget(next);

        settingsPageInfo = "Page " + (settingsPage + 1) + " / " + totalPages + "   (" + matches.size() + " shown)";
    }

    /** All editable settings, common ones first (in curated order) then A-Z. */
    private static List<Settings.Setting<?>> orderedSettings(Settings settings) {
        List<Settings.Setting<?>> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] entry : COMMON_SETTINGS) {
            Settings.Setting<?> s = settings.byLowerName.get(entry[0].toLowerCase(Locale.ROOT));
            if (s != null && !SettingsUtil.javaOnlySetting(s) && seen.add(s.getName())) {
                ordered.add(s);
            }
        }
        List<Settings.Setting<?>> rest = new ArrayList<>();
        for (Settings.Setting<?> s : settings.allSettings) {
            if (!SettingsUtil.javaOnlySetting(s) && !seen.contains(s.getName())) {
                rest.add(s);
            }
        }
        rest.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        ordered.addAll(rest);
        return ordered;
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

        if (this.tab == Tab.AREA && blockPickTarget == 0) {
            int y = contentTop() + 172;
            for (String line : areaInfoLines()) {
                extractor.text(this.font, line, contentLeft(), y, 0xFFDDDDDD, true);
                y += 11;
            }
        }

        if (this.tab == Tab.CLIPBOARD) {
            int y = contentTop() + (baritone.utils.ClipboardGhost.isPlacing() ? 128 : 56);
            for (String line : clipboardInfoLines()) {
                extractor.text(this.font, line, contentLeft(), y, 0xFFDDDDDD, true);
                y += 11;
            }
        }


        if (this.tab == Tab.SETTINGS) {
            for (SettingRow r : settingRows) {
                extractor.text(this.font, r.label(), settingsLeftX, r.y(), 0xFFDDDDDD, true);
            }
            if (!settingsPageInfo.isEmpty()) {
                extractor.text(this.font, settingsPageInfo,
                        settingsLeftX + 70, contentTop() + 26 + SETTINGS_PAGE_SIZE * 22 + 10, 0xFFBBBBBB, true);
            }
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
