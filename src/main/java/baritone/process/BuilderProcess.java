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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.accessor.IBlockItemAccessor;
import baritone.utils.accessor.IStandingAndWallBlockItemAccessor;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.CategorySubstitutions;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import baritone.api.utils.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    // Properties that are derived purely from a block's neighbours and auto-update after
    // placement - stair/rail shape, and fence/pane/wall/bar/redstone connection sides. While
    // pasting we ignore these when checking "is this block correct", otherwise the builder
    // places a stair, a neighbour flips its shape, and it rebreaks/replaces it forever. They
    // resolve themselves to the right value once the neighbours exist.
    private static final Set<String> NEIGHBOR_DERIVED_PROP_NAMES =
            ImmutableSet.of("shape", "north", "east", "south", "west", "up");

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    private Boolean forceBuildInLayers; // null = use Baritone.settings().buildInLayers.value
    private Boolean forceLayerOrder;    // null = use Baritone.settings().layerOrder.value
    private Boolean forceSkipUnplaceable; // null = use Baritone.settings().buildSkipUnplaceable.value
    private Boolean forcePlaceByItem;     // null = use Baritone.settings().buildPlaceByItem.value
    private LongOpenHashSet skippedPositions; // positions we gave up on this build (never re-added to incorrectPositions)
    private final List<BetterBlockPos> unactionable = new ArrayList<>(); // positions the last assemble() could not place/break

    // "detail sweep" (place-by-item only): oriented / item!=block positions (wall torches,
    // arbitrary-facing stairs) that the coarse check can't confirm up front. They are held
    // out of the main build, then attempted ONE AT A TIME after the shell is finished, so
    // calcFailed / idle become per-candidate signals and the sweep provably terminates.
    private final Set<BetterBlockPos> detailHeld = new HashSet<>();      // deferred, guarded out of incorrectPositions
    private final java.util.ArrayDeque<BetterBlockPos> detailQueue = new java.util.ArrayDeque<>(); // order to attempt them in
    private boolean loggedFamily = false;        // TEMP diagnostic: log the placeable family once per build
    private boolean detailSweep = false;         // true while the sweep is running
    private BetterBlockPos detailCurrent = null; // the single detail block currently being attempted
    private int detailIdleTicks = 0;             // ticks stuck on detailCurrent without pathing or placing
    private int detailCurrentTicks = 0;          // total ticks detailCurrent has been active (hard cap, independent of isPathing)
    private Set<Block> placeableFamily = java.util.Collections.emptySet(); // per-tick cache of block ids the hotbar can place in any orientation
    private static final int DETAIL_IDLE_LIMIT = 120;  // ~6s not-pathing-and-not-placing on one detail block -> retire it
    private static final int DETAIL_HARD_LIMIT = 600;  // ~30s total on one detail block -> retire it no matter what (guarantees the sweep ends)

    // Anti-stuck re-approach: when the bot parks next to a block it wants to place but can't place
    // from that exact spot (a bad aim/position deadlock the user otherwise fixes by walking away a
    // few blocks or spinning the camera), automatically step a few blocks away - which also turns
    // the camera en route - then let normal logic re-approach from a fresh position and heading.
    private BetterBlockPos placeStuckFeet = null; // feet pos we've been parked at, unable to place
    private int placeStuckTicks = 0;              // consecutive parked-and-unable ticks at placeStuckFeet
    private Goal placeNudgeGoal = null;           // transient step-away goal while nudging
    private BetterBlockPos placeNudgeTarget = null; // the cell the step-away goal walks to
    private int placeNudgeTicks = 0;              // remaining ticks to hold the step-away goal
    private int placeNudgeAttempts = 0;           // step-aways tried for the current parked spot (bounded)
    private static final int PLACE_STUCK_LIMIT = 30; // ~1.5s parked-and-unable -> step away and re-approach
    private static final int PLACE_NUDGE_TICKS = 24; // ~1.2s cap to reach the step-away cell before re-approaching
    private static final int PLACE_MAX_NUDGES = 3;   // give up nudging after this many; fall back to existing behavior

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    private boolean useBuildInLayers() {
        return forceBuildInLayers != null ? forceBuildInLayers : Baritone.settings().buildInLayers.value;
    }

    private boolean useLayerOrder() {
        return forceLayerOrder != null ? forceLayerOrder : Baritone.settings().layerOrder.value;
    }

    private boolean useSkipUnplaceable() {
        return forceSkipUnplaceable != null ? forceSkipUnplaceable : Baritone.settings().buildSkipUnplaceable.value;
    }

    private boolean usePlaceByItem() {
        return forcePlaceByItem != null ? forcePlaceByItem : Baritone.settings().buildPlaceByItem.value;
    }

    @Override
    public void setLayerOverride(Boolean inLayers, Boolean topDown) {
        this.forceBuildInLayers = inLayers;
        this.forceLayerOrder = topDown;
    }

    @Override
    public void setSkipUnplaceableOverride(Boolean skip) {
        this.forceSkipUnplaceable = skip;
    }

    @Override
    public void setPlaceByItemOverride(Boolean placeByItem) {
        this.forcePlaceByItem = placeByItem;
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        // A fresh build starts from the global layer settings; callers that want a
        // per-build override (e.g. SelCommand) apply it right AFTER this call so an
        // interrupted sel build can never leak its override onto a later job.
        this.forceBuildInLayers = null;
        this.forceLayerOrder = null;
        this.forceSkipUnplaceable = null;
        this.forcePlaceByItem = null;
        this.skippedPositions = new LongOpenHashSet();
        this.detailHeld.clear();
        this.detailQueue.clear();
        this.detailSweep = false;
        this.detailCurrent = null;
        this.detailIdleTicks = 0;
        this.detailCurrentTicks = 0;
        this.placeStuckFeet = null;
        this.placeStuckTicks = 0;
        this.placeNudgeGoal = null;
        this.placeNudgeTarget = null;
        this.placeNudgeTicks = 0;
        this.placeNudgeAttempts = 0;
        this.loggedFamily = false;
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        Map<Block, List<Block>> substitutes = new HashMap<>();
        Baritone.settings().buildSubstitutes.value.forEach((block, targets) -> substitutes.put(block, new ArrayList<>(targets)));
        CategorySubstitutions.expandInto(substitutes, Baritone.settings().guiSubstitutionRules.value);
        if (!substitutes.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, substitutes);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (useBuildInLayers()) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = useLayerOrder() ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (useLayerOrder() ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;

        // TEMP diagnostic: exactly which block types does this build want, and what gets dropped?
        try {
            Set<String> wants = new HashSet<>();
            Set<String> excludedByMask = new HashSet<>();
            Set<String> threw = new HashSet<>();
            ISchematic sc = this.schematic;
            for (int yy = 0; yy < sc.heightY(); yy++) {
                for (int zz = 0; zz < sc.lengthZ(); zz++) {
                    for (int xx = 0; xx < sc.widthX(); xx++) {
                        BlockState s;
                        try {
                            s = sc.desiredState(xx, yy, zz, null, Collections.emptyList());
                        } catch (RuntimeException e) {
                            threw.add(String.valueOf(e.getClass().getSimpleName()));
                            continue;
                        }
                        if (s == null || s.getBlock() instanceof AirBlock) {
                            continue;
                        }
                        boolean in;
                        try {
                            in = sc.inSchematic(xx, yy, zz, null);
                        } catch (RuntimeException e) {
                            in = false;
                        }
                        if (in) {
                            wants.add(s.getBlock().toString());
                        } else {
                            excludedByMask.add(s.getBlock().toString());
                        }
                    }
                }
            }
            logDirect("Building wants: " + wants);
            logDirect("  excluded-by-inSchematic: " + excludedByMask + " | desiredState-threw: " + threw
                    + " | schematic=" + sc.getClass().getSimpleName()
                    + " | buildSkipBlocks=" + Baritone.settings().buildSkipBlocks.value.size());
        } catch (RuntimeException ignored) {
        }
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (skippedPositions != null && skippedPositions.contains(BetterBlockPos.longHash(pos))) {
                            continue; // we chose to leave this block as-is; don't rebreak it
                        }
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;
        private final Direction orientedFacing; // desired cardinal for yaw-sensitive blocks (stairs); null otherwise
        private final BlockHitResult directHit;  // printer mode: place against this exact synthesized hit; null for the legit path

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot, Direction orientedFacing, BlockHitResult directHit) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
            this.orientedFacing = orientedFacing;
            this.directHit = directHit;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (skippedPositions != null && skippedPositions.contains(BetterBlockPos.longHash(x, y, z))) {
                        continue; // we gave up on / chose to leave this block; don't (re)place it
                    }
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A walkable cell a few blocks away to step to so we re-approach a stuck placement from a fresh
     * position and heading (pathing there also turns the camera). Prefers the farthest reachable-looking
     * cell (up to 3 away) and rotates the starting side per attempt so successive nudges try different
     * approaches. Returns null if we're boxed in with nowhere to step.
     */
    private BetterBlockPos pickNudgeCell(BetterBlockPos feet) {
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < dirs.length; i++) {
            Direction d = dirs[(i + placeNudgeAttempts) % dirs.length];
            // Walk outward requiring a CONTINUOUS same-Y floor: every cell (feet+1 .. feet+N) must be
            // standable - passable at feet and head, with solid ground directly below - so there's an
            // unbroken walkway the whole way and the bot can't be routed across a gap or off a ledge of
            // a tall build. Stop at the first break and take the farthest still-safe cell.
            BetterBlockPos best = null;
            for (int dist = 1; dist <= 3; dist++) {
                BetterBlockPos cell = new BetterBlockPos(feet.relative(d, dist));
                boolean standable = MovementHelper.canWalkThrough(ctx, cell)
                        && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(cell.above()))
                        && MovementHelper.canWalkOn(ctx, new BetterBlockPos(cell.below()));
                if (!standable) {
                    break; // gap/edge/drop in the run; anything past here isn't safely reachable
                }
                best = cell;
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                // Stairs take their FACING from the player's look-yaw (quantized to the nearest cardinal),
                // so aiming purely by click geometry places them facing an arbitrary direction. Lock the
                // yaw to the desired cardinal while keeping the geometry pitch, so the ray still hits the
                // support face (and its height still selects top/bottom HALF). The raytrace + valid() gate
                // below then confirm the resulting state matches the schematic. Only when placing by item.
                Direction orientedFacing = null;
                if (usePlaceByItem() && !Baritone.settings().buildIgnoreDirection.value && toPlace.getBlock() instanceof StairBlock) {
                    java.util.Optional<Direction> stairFacing = toPlace.getOptionalValue(StairBlock.FACING);
                    if (stairFacing.isPresent()) {
                        orientedFacing = stairFacing.get();
                        rot = new Rotation(orientedFacing.toYRot(), rot.getPitch());
                    }
                }
                if (usePlaceByItem() && Baritone.settings().buildPrinterMode.value) {
                    // PRINTER MODE (dangerous, opt-in): place against a synthesized exact hit within reach,
                    // with no camera line-of-sight requirement. Reach is still clamped to vanilla (the
                    // raytrace we skip used to enforce that implicitly). We try the geometry rotation plus,
                    // for oriented blocks, rotations that look along the desired facing (and its opposite,
                    // since some blocks invert); hasAnyItemThatWouldPlace validates the simulated blockstate
                    // against the schematic, so whichever candidate matches is provably the correct one.
                    Vec3 eye = RayTraceUtils.inferSneakingEyePosition(ctx.player());
                    Vec3 hitVec = new Vec3(placeX, placeY, placeZ);
                    double reach = ctx.playerController().getBlockReachDistance();
                    if (eye.distanceToSqr(hitVec) <= reach * reach) {
                        BlockHitResult synth = new BlockHitResult(hitVec, against.getOpposite(), placeAgainstPos, false);
                        for (Rotation cand : placementRotationCandidates(toPlace, rot)) {
                            OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, synth, cand);
                            if (hotbar.isPresent()) {
                                return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), cand, orientedFacing, synth));
                            }
                        }
                    }
                    continue; // printer handled this candidate; never fall through to the line-of-sight gate
                }
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot, orientedFacing, null));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            // Use the item's own placement logic (e.g. a torch item choosing a wall torch
            // on a side face) rather than only the block's, when place-by-item is enabled.
            BlockState wouldBePlaced = usePlaceByItem()
                    ? ((IBlockItemAccessor) (Object) stack.getItem()).callGetPlacementState(meme)
                    : ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Printer mode: the rotations to try (validated by {@link #hasAnyItemThatWouldPlace}) so an oriented
     * block places facing the right way. For a directional block we look straight along the desired
     * facing and along its opposite (blocks differ on which the player looks toward), plus the geometry
     * rotation. Non-oriented blocks (slabs, logs whose axis comes from the clicked face, etc.) just use
     * the geometry rotation, since their state comes from the synthesized hit vector, not the yaw.
     */
    private List<Rotation> placementRotationCandidates(BlockState desired, Rotation geomRot) {
        Direction facing = orientationOf(desired);
        if (facing == null) {
            return java.util.Collections.singletonList(geomRot);
        }
        return java.util.Arrays.asList(lookRotationToward(facing), lookRotationToward(facing.getOpposite()), geomRot);
    }

    /** The desired block's primary directional facing (6-way FACING, else HORIZONTAL_FACING), or null. */
    private static Direction orientationOf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    /** A player rotation that looks straight along {@code d} (pitch +/-90 for up/down). */
    private static Rotation lookRotationToward(Direction d) {
        if (d == Direction.UP) {
            return new Rotation(0, -90);
        }
        if (d == Direction.DOWN) {
            return new Rotation(0, 90);
        }
        return new Rotation(d.toYRot(), 0);
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        placeableFamily = usePlaceByItem() ? placeableFamilies() : java.util.Collections.emptySet();
        if (usePlaceByItem() && !loggedFamily && !placeableFamily.isEmpty()) {
            loggedFamily = true;
            Set<String> f = new HashSet<>();
            for (Block b : placeableFamily) {
                f.add(b.toString());
            }
            logDirect("Placeable family (hotbar can make): " + f);
        }
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (useBuildInLayers()) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (useLayerOrder()) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (useBuildInLayers() && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            // The shell is complete. If a detail sweep is running, the current candidate just
            // finished (placed) - advance to the next one. Otherwise, if we deferred any detail
            // blocks, begin the sweep now (supports all exist, so wall torches/stairs can go up).
            if (detailSweep) {
                detailAdvance(bcc);
                if (detailSweep) {
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
            } else if (usePlaceByItem() && !detailHeld.isEmpty()) {
                detailBeginSweep(bcc);
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        // Detail sweep: if we've been stuck on the current candidate (not pathing toward it and
        // not placing it) for too long - it can't be reached or its exact orientation can't be
        // achieved - retire it and move on. This is the per-candidate termination guarantee.
        if (detailSweep && detailCurrent != null && incorrectPositions.contains(detailCurrent)) {
            // If a block is already sitting here but isn't exactly right, we placed it and can't
            // improve it from where we can stand (e.g. a stair we can't orient from above). Leave
            // it as-is and move on, rather than rebreak/replace it forever.
            if (!(bcc.bsi.get0(detailCurrent).getBlock() instanceof AirBlock)) {
                logDirect("Detail: placed wrong at " + detailCurrent + " (wanted " + bcc.getSchematic(detailCurrent.x, detailCurrent.y, detailCurrent.z, bcc.bsi.get0(detailCurrent)) + "), leaving it");
                detailRetireCurrent();
                detailAdvance(bcc);
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            detailCurrentTicks++;
            if (baritone.getPathingBehavior().isPathing()) {
                detailIdleTicks = 0;
            } else {
                detailIdleTicks++;
            }
            // Retire on either signal: stuck (not pathing/placing) for a while, OR a hard total
            // cap regardless of pathing state - so the sweep terminates even if isPathing() never
            // goes false for an unreachable target.
            if (detailIdleTicks > DETAIL_IDLE_LIMIT || detailCurrentTicks > DETAIL_HARD_LIMIT) {
                logDirect("Detail: couldn't reach/place " + detailCurrent + " (wanted " + bcc.getSchematic(detailCurrent.x, detailCurrent.y, detailCurrent.z, bcc.bsi.get0(detailCurrent)) + "), skipping");
                detailRetireCurrent();
                detailAdvance(bcc);
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
        }

        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            // making real placement progress: clear any anti-stuck bookkeeping
            placeStuckFeet = null;
            placeStuckTicks = 0;
            placeNudgeAttempts = 0;
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (toPlace.get().directHit != null) {
                // PRINTER MODE: route the exact synthesized hit through BlockPlaceHelper. The catch: within
                // one tick the USE packet is sent BEFORE the movement/rotation packet, so if we clicked the
                // same tick we retarget, the server would compute the blockstate from our PREVIOUS rotation
                // (wrong facing for stairs/furnaces/pistons on the first block of a run). updateTarget(rot)
                // above turns us this tick; only click once our rotation has actually reached rot - meaning
                // last tick's movement packet already delivered it to the server. Coarse tolerance (not
                // isReallyCloseTo, whose 0.01 deg never matches the quantized aim) and includes pitch, which
                // vertical 6-way blocks (observers/pistons facing up/down) depend on.
                Rotation cur = ctx.playerRotations();
                boolean converged = Math.abs(net.minecraft.util.Mth.wrapDegrees(cur.getYaw() - rot.getYaw())) < 2.0f
                        && Math.abs(cur.getPitch() - rot.getPitch()) < 2.0f;
                if (converged) {
                    baritone.getInputOverrideHandler().getBlockPlaceHelper().placeDirect(toPlace.get().directHit);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            // For a stair, MC stamps FACING = Direction.fromYRot(playerYaw) at click time. The
            // isLookingAt shortcut below is satisfied across a ~90 degree arc onto the support's cube
            // face, so it can fire mid-turn - before the yaw reaches the locked cardinal - placing the
            // stair facing the wrong way. Only click once our yaw is inside the desired cardinal's
            // window (Direction.fromYRot matches). This just delays the click a few ticks to finish the
            // turn; it can't deadlock (a 90 degree window, not exact-yaw), and it's a no-op for
            // non-oriented blocks (orientedFacing == null).
            boolean facingReady = toPlace.get().orientedFacing == null
                    || Direction.fromYRot(ctx.playerRotations().getYaw()) == toPlace.get().orientedFacing;
            if (facingReady && ((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot))) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Anti-stuck re-approach (place-by-item only): we reach here when there's a block nearby we
        // want to place (desirableOnHotbar) but couldn't place one this tick (toPlace empty). If we've
        // been parked at the same spot unable to place for a while, step a few blocks away and back so
        // we re-approach from a fresh position/heading - the automated version of the user walking off
        // or spinning the camera to break a stuck placement.
        if (usePlaceByItem() && isSafeToCancel && ctx.player().onGround()) {
            BetterBlockPos feet = ctx.playerFeet();
            if (placeNudgeTicks > 0) {
                placeNudgeTicks--;
                boolean arrived = placeNudgeTarget != null && feet.equals(placeNudgeTarget);
                if (placeNudgeTicks > 0 && !arrived && placeNudgeGoal != null) {
                    // Movement won't turn the camera on its own (baritone decouples look from walking),
                    // so spin it explicitly while stepping away. This is safe - no placement is being
                    // attempted mid-nudge - and it reproduces the manual "spin the camera" unstick, so we
                    // re-approach from a fresh position AND heading.
                    baritone.getLookBehavior().updateTarget(new Rotation((placeNudgeTicks * 46f) % 360f - 180f, 0f), false);
                    return new PathingCommandContext(placeNudgeGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
                }
                // arrived or timed out: drop the step-away goal so normal logic re-approaches fresh
                placeNudgeGoal = null;
                placeNudgeTarget = null;
                placeNudgeTicks = 0;
                placeStuckTicks = 0;
            } else {
                boolean wantButCant = !desirableOnHotbar.isEmpty() && !toPlace.isPresent();
                if (wantButCant && feet.equals(placeStuckFeet)) {
                    if (++placeStuckTicks > PLACE_STUCK_LIMIT && placeNudgeAttempts < PLACE_MAX_NUDGES) {
                        BetterBlockPos step = pickNudgeCell(feet);
                        if (step != null) {
                            placeNudgeGoal = new GoalBlock(step);
                            placeNudgeTarget = step;
                            placeNudgeTicks = PLACE_NUDGE_TICKS;
                            placeNudgeAttempts++;
                            logDirect("Nudge: re-approaching a block I couldn't place from here (try " + placeNudgeAttempts + "/" + PLACE_MAX_NUDGES + ")");
                            return new PathingCommandContext(placeNudgeGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
                        }
                        placeNudgeAttempts = PLACE_MAX_NUDGES; // nowhere to step; stop trying at this spot
                    }
                } else {
                    // parked somewhere new (or nothing to place): (re)start tracking here. Treat a spot
                    // more than 3 blocks from the old one as a genuine relocation and reset the attempts.
                    if (placeStuckFeet == null || placeStuckFeet.distSqr(feet) > 9) {
                        placeNudgeAttempts = 0;
                    }
                    placeStuckFeet = wantButCant ? feet : null;
                    placeStuckTicks = 0;
                }
            }
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                // Rather than give up on the whole build, retire the positions we can't
                // currently place or break (unobtainable blocks like potted plants, wall
                // torches, or unreplaceable flowing liquids) and carry on with the rest.
                // Skipped positions are guarded out of future recalcs so the build can
                // actually reach "Done" instead of re-discovering them forever.
                if (useSkipUnplaceable() && !unactionable.isEmpty() && skippedPositions != null) {
                    int newlySkipped = 0;
                    for (BetterBlockPos p : unactionable) {
                        if (skippedPositions.add(BetterBlockPos.longHash(p))) {
                            newlySkipped++;
                        }
                    }
                    incorrectPositions.removeAll(unactionable);
                    if (newlySkipped > 0) {
                        logDirect("Skipping " + newlySkipped + " block(s) I can't place here; continuing.");
                        return onTick(calcFailed, isSafeToCancel, recursions + 1);
                    }
                }
                // If we just deferred detail blocks and have nothing else left, kick off the
                // sweep instead of pausing (covers a detail block whose item only reached the
                // hotbar exactly as the shell finished).
                if (usePlaceByItem() && !detailSweep && !detailHeld.isEmpty()) {
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                if (Baritone.settings().skipFailedLayers.value && useBuildInLayers() && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (skippedPositions != null && skippedPositions.contains(BetterBlockPos.longHash(pos))) {
                            continue; // we gave up on this position earlier; leave it out
                        }
                        if (!detailHeld.isEmpty() && detailHeld.contains(pos)) {
                            continue; // deferred to the detail sweep; not in play until it's the current candidate
                        }
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (skippedPositions != null && skippedPositions.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        continue; // we gave up on this position earlier; never re-add it
                    }
                    if (!detailHeld.isEmpty() && detailHeld.contains(new BetterBlockPos(blockX, blockY, blockZ))) {
                        continue; // deferred to the detail sweep; not in play until it's the current candidate
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    // ---- detail sweep: build oriented / item!=block blocks one at a time after the shell ----

    private void detailBeginSweep(BuilderCalculationContext bcc) {
        detailSweep = true;
        detailQueue.clear();
        // Do the reliably-placeable attached blocks (wall torches etc.) first and the finicky stairs
        // last, so a stair we can't orient never blocks the easy stuff behind it. detailHeld is a HashSet
        // (scrambled iteration order), so within the stairs sort BOTTOM-TO-TOP: a stair is placed by
        // standing on the step below it, so the lower step must exist first - and building the run in
        // order keeps it a continuous, climbable surface so upper steps never become unreachable. Without
        // this, stairs are attempted in arbitrary order and most fail (wrong stance / gaps).
        List<BetterBlockPos> ordered = new ArrayList<>(detailHeld);
        ordered.sort(Comparator.comparingInt((BetterBlockPos p) -> {
                    BlockState d = bcc.getSchematic(p.x, p.y, p.z, bcc.bsi.get0(p));
                    return d != null && d.getBlock() instanceof StairBlock ? 1 : 0;
                })
                .thenComparingInt(p -> p.y)
                .thenComparingInt(p -> p.x)
                .thenComparingInt(p -> p.z));
        detailQueue.addAll(ordered); // detailHeld stays populated (the guard); advance removes each as it becomes current
        detailCurrent = null;
        detailIdleTicks = 0;
        Set<String> types = new HashSet<>();
        for (BetterBlockPos p : ordered) {
            BlockState d = bcc.getSchematic(p.x, p.y, p.z, bcc.bsi.get0(p));
            if (d != null) {
                types.add(d.getBlock().toString());
            }
        }
        logDirect("Detail pass: " + detailQueue.size() + " block(s): " + types);
        detailAdvance(bcc);
    }

    /** Make the next queued detail block the current candidate, or end the sweep if none remain. */
    private void detailAdvance(BuilderCalculationContext bcc) {
        detailCurrent = null;
        detailIdleTicks = 0;
        detailCurrentTicks = 0;
        while (!detailQueue.isEmpty()) {
            BetterBlockPos next = detailQueue.poll();
            detailHeld.remove(next); // no longer held: it becomes active (or is discarded below)
            BlockState desired = bcc.getSchematic(next.x, next.y, next.z, bcc.bsi.get0(next));
            if (desired == null || valid(bcc.bsi.get0(next), desired, false)) {
                continue; // already correct (e.g. placed opportunistically while building a neighbour)
            }
            detailCurrent = next;
            incorrectPositions.add(next); // now in play so assemble routes the bot to it
            return;
        }
        detailSweep = false; // queue exhausted -> sweep done, fall back to normal completion
    }

    /** Give up on the current detail block for the rest of this build. */
    private void detailRetireCurrent() {
        if (detailCurrent != null && skippedPositions != null) {
            skippedPositions.add(BetterBlockPos.longHash(detailCurrent));
            incorrectPositions.remove(detailCurrent);
            detailCurrent = null;
        }
    }

    /**
     * The set of block ids the hotbar can place in SOME orientation. For each hotbar
     * {@link BlockItem} we take its block, plus - for a {@link StandingAndWallBlockItem} such as
     * a torch - its wall variant, so a torch item contributes both {@code torch} and
     * {@code wall_torch}. This is only a routing heuristic; exactness is enforced later by
     * {@link #possibleToPlace}, so an over-inclusive entry just wastes a sweep attempt.
     */
    private Set<Block> placeableFamilies() {
        Set<Block> fam = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            fam.add(((BlockItem) stack.getItem()).getBlock());
            if (stack.getItem() instanceof StandingAndWallBlockItem) {
                fam.add(((IStandingAndWallBlockItemAccessor) (Object) stack.getItem()).getWallBlock());
            }
        }
        return fam;
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        List<BetterBlockPos> toDefer = new ArrayList<>();
        this.unactionable.clear();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                } else if (usePlaceByItem() && placeableFamily.contains(desired.getBlock())) {
                    // We hold an item that produces this block in SOME orientation (e.g. a torch
                    // for a wall torch). The coarse check can't confirm the exact oriented state
                    // up front, so during the main build we defer it; during the sweep we route
                    // to it and let the real placement path (possibleToPlace) solve the orientation.
                    if (detailSweep) {
                        placeable.add(pos);
                    } else {
                        toDefer.add(pos);
                    }
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                    unactionable.add(pos);
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                        unactionable.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        if (!toDefer.isEmpty()) {
            // pull deferred detail blocks out of the main build; the guards keep them out
            // until the sweep makes each one the current candidate.
            incorrectPositions.removeAll(toDefer);
            detailHeld.addAll(toDefer);
        }
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);

        // Wall-attached blocks (wall torch, wall sign, ladder, wall button/lever, ...) can only be
        // placed with a clear line-of-sight to the support block's front face. Standing above
        // (GoalPlace) or beside on the wrong side (GoalAdjacent) gives a bad raytrace angle so
        // possibleToPlace never fires. Route the bot to stand IN FRONT (the FACING side) instead,
        // keeping the old goal as a fallback so we're never worse than before.
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        if (desired != null && isWallAttached(desired)) {
            java.util.Optional<Direction> facingOpt = desired.getOptionalValue(HorizontalDirectionalBlock.FACING);
            if (facingOpt.isPresent()) {
                Direction facing = facingOpt.get(); // points away from the support
                BetterBlockPos front = new BetterBlockPos(pos.relative(facing));
                BlockPos support = pos.relative(facing.getOpposite());
                if (MovementHelper.canPlaceAgainst(ctx, support)
                        && MovementHelper.canWalkThrough(ctx, front)
                        && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(front.above()))
                        && MovementHelper.canWalkOn(ctx, new BetterBlockPos(front.below()))) {
                    // The front cell is the only stance that actually places a wall-attached block, and
                    // we just proved it's standable. Return it as an EXACT goal (no loose GoalAdjacent
                    // fallback): a permissive fallback is satisfied at adjacent dead-stance cells where
                    // the placement raytrace fails, and pathing then refuses to move (isInGoal(feet) is
                    // already true) - the block freezes until the sweep retires it. Exact-cell forces the
                    // bot to the real stance and places from there.
                    return new GoalBlock(front);
                }
            }
        }

        // StairBlock.FACING points UP-SLOPE (the tall riser / ascending side): a stair placed while
        // looking north gets FACING=north and you climb it northward. To reproduce that FACING the bot
        // must LOOK toward +FACING, i.e. stand on the DOWN-SLOPE side (pos.relative(facing.opposite),
        // which for a staircase means standing on the step below) and look back across pos. Standing
        // UP-SLOPE forces a look down-slope and places the MIRROR facing, so we must NEVER accept that
        // stance: return the down-slope cell as an EXACT goal and do NOT fall through to
        // placementGoalDefault (GoalAdjacent would happily stand the bot up-slope). If the down-slope
        // stance isn't reachable yet, the sweep's idle/hard cap retires the stair (left unplaced, not
        // mirrored). Ordering the sweep bottom-to-top (see detailBeginSweep) makes it reachable in time.
        if (desired != null && usePlaceByItem() && !Baritone.settings().buildIgnoreDirection.value && desired.getBlock() instanceof StairBlock) {
            java.util.Optional<Direction> facingOpt = desired.getOptionalValue(StairBlock.FACING);
            java.util.Optional<Half> halfOpt = desired.getOptionalValue(StairBlock.HALF);
            if (facingOpt.isPresent() && halfOpt.isPresent()) {
                Direction facing = facingOpt.get(); // up-slope (ascending) direction
                BlockPos support = halfOpt.get() == Half.TOP ? pos.above() : pos.below();
                BetterBlockPos back = new BetterBlockPos(pos.relative(facing.getOpposite())); // down-slope stance
                if (MovementHelper.canPlaceAgainst(ctx, support)) {
                    return new GoalBlock(back);
                }
            }
        }
        return placementGoalDefault(pos, bcc, current, allowSameLevel);
    }

    /** The original placement goal: stand adjacent to a face we can place against, else on top. */
    private Goal placementGoalDefault(BlockPos pos, BuilderCalculationContext bcc, BlockState current, boolean allowSameLevel) {
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    /**
     * True for blocks that mount on the side of a support with FACING pointing away from it, so
     * the bot must stand in front (pos.relative(FACING)) to place them. A narrow allowlist on
     * purpose: NOT stairs/furnaces/chests (whose FACING is not attachment), and NOT 6-direction
     * blocks like observers/dispensers (whose FACING can be up/down).
     */
    private static boolean isWallAttached(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof WallTorchBlock          // wall_torch, soul_wall_torch
                || b instanceof RedstoneWallTorchBlock  // does NOT extend WallTorchBlock
                || b instanceof WallSignBlock
                || b instanceof WallHangingSignBlock
                || b instanceof WallBannerBlock
                || b instanceof WallSkullBlock
                || b instanceof LadderBlock
                || b instanceof TripWireHookBlock) {
            return state.hasProperty(HorizontalDirectionalBlock.FACING);
        }
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) { // buttons, levers, grindstone
            return state.hasProperty(HorizontalDirectionalBlock.FACING)
                    && state.getOptionalValue(FaceAttachedHorizontalDirectionalBlock.FACE).orElse(null) == AttachFace.WALL;
        }
        return false;
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        forceBuildInLayers = null;
        forceLayerOrder = null;
        forceSkipUnplaceable = null;
        forcePlaceByItem = null;
        skippedPositions = null;
        unactionable.clear();
        detailHeld.clear();
        detailQueue.clear();
        detailSweep = false;
        detailCurrent = null;
        detailIdleTicks = 0;
        detailCurrentTicks = 0;
        placeStuckFeet = null;
        placeStuckTicks = 0;
        placeNudgeGoal = null;
        placeNudgeTarget = null;
        placeNudgeTicks = 0;
        placeNudgeAttempts = 0;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (useBuildInLayers()) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (useBuildInLayers()) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        boolean ignoreNeighborDerived = usePlaceByItem(); // paste: don't oscillate on neighbour-derived props
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && !ignoreNeighborDerived && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop) != second.getOptionalValue(prop).orElse(null)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !(ignoreNeighborDerived && NEIGHBOR_DERIVED_PROP_NAMES.contains(prop.getName()))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
