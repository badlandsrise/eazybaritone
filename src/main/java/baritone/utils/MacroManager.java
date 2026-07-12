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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.utils.accessor.IMinecraftAccessor;
import net.minecraft.client.Minecraft;

/**
 * Backs the GUI "Macros" tab: light client-side input macros (auto/hold left- and
 * right-click). Pure static state, mirroring {@link SelectionWand} / ClipboardGhost,
 * and ticked once per client tick from {@code MixinMinecraft.postRunTick}.
 * <p>
 * All state is read and written on the client thread (GUI callbacks and the tick
 * hook run there), so no synchronization is needed. Nothing here persists across
 * restarts — an auto-clicker should never come back on by itself.
 */
public final class MacroManager {

    private MacroManager() {}

    // toggles
    private static boolean autoAttack;
    private static boolean autoUse;
    private static boolean holdAttack;
    private static boolean holdUse;

    // interval between discrete auto-clicks, in ticks (20/sec); never below 1
    private static int autoAttackTicks = 10; // 0.5s default
    private static int autoUseTicks = 10;

    // per-behaviour tick counters
    private static int attackTimer;
    private static int useTimer;

    // whether we were driving each held key last tick, so we can release it exactly
    // once (never fighting the player's real mouse when the macro is off)
    private static boolean drivingAttack;
    private static boolean drivingUse;

    // -------------------------------------------------------------- GUI state

    public static boolean isAutoAttack() { return autoAttack; }

    public static void setAutoAttack(boolean v) { autoAttack = v; attackTimer = 0; }

    public static boolean isAutoUse() { return autoUse; }

    public static void setAutoUse(boolean v) { autoUse = v; useTimer = 0; }

    public static boolean isHoldAttack() { return holdAttack; }

    public static void setHoldAttack(boolean v) { holdAttack = v; }

    public static boolean isHoldUse() { return holdUse; }

    public static void setHoldUse(boolean v) { holdUse = v; }

    /** Interval between auto left-clicks, in seconds (min one tick). */
    public static double getAutoAttackSeconds() { return autoAttackTicks / 20.0; }

    public static void setAutoAttackSeconds(double seconds) { autoAttackTicks = toTicks(seconds); }

    /** Interval between auto right-clicks, in seconds (min one tick). */
    public static double getAutoUseSeconds() { return autoUseTicks / 20.0; }

    public static void setAutoUseSeconds(double seconds) { autoUseTicks = toTicks(seconds); }

    private static int toTicks(double seconds) {
        int ticks = (int) Math.round(seconds * 20.0);
        return Math.max(1, ticks);
    }

    // ------------------------------------------------------------------ tick

    public static void onClientTick(Minecraft mc) {
        boolean gate = mc.options != null
                && mc.player != null
                && mc.level != null
                && mc.gui.screen() == null
                && !baritoneBusy();

        // Held clicks: press while active, and release exactly once when we stop.
        // We only ever write the key state on ticks we're the one driving it, so a
        // player mining/using normally (macro off) is never overridden.
        boolean wantAttack = holdAttack && gate;
        if (wantAttack) {
            mc.options.keyAttack.setDown(true);
            drivingAttack = true;
        } else if (drivingAttack) {
            mc.options.keyAttack.setDown(false);
            drivingAttack = false;
        }

        boolean wantUse = holdUse && gate;
        if (wantUse) {
            mc.options.keyUse.setDown(true);
            drivingUse = true;
        } else if (drivingUse) {
            mc.options.keyUse.setDown(false);
            drivingUse = false;
        }

        // Auto (interval) clicks: fire the same private methods vanilla fires.
        if (gate && autoAttack) {
            if (++attackTimer >= autoAttackTicks) {
                attackTimer = 0;
                ((IMinecraftAccessor) mc).callStartAttack();
            }
        } else {
            attackTimer = 0;
        }

        if (gate && autoUse) {
            if (++useTimer >= autoUseTicks) {
                useTimer = 0;
                ((IMinecraftAccessor) mc).callStartUseItem();
            }
        } else {
            useTimer = 0;
        }
    }

    /** True while Baritone is actively pathing or a process is in control, so macros stand down. */
    private static boolean baritoneBusy() {
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            return b.getPathingBehavior().isPathing()
                    || b.getPathingControlManager().mostRecentInControl().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
