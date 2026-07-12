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

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiFunction;

/**
 * @author Brady
 * @since 7/31/2018
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Shadow
    public LocalPlayer player;
    @Shadow
    public ClientLevel level;

    @Unique
    private BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void postInit(CallbackInfo ci) {
        BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            opcode = Opcodes.PUTFIELD,
                            target = "net/minecraft/client/Minecraft.missTime:I"
                    )
            )
    )
    private void runTick(CallbackInfo ci) {
        this.tickProvider = TickEvent.createNextProvider();

        for (IBaritone baritone : BaritoneAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN
                    : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onTick(this.tickProvider.apply(EventState.PRE, type));
        }
    }

    @Inject(
            method = "tick",
            at = @At("RETURN")
    )
    private void postRunTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        while (baritone.gui.BaritoneKeybinds.OPEN_MENU.consumeClick()) {
            if (mc.level != null && mc.gui.screen() == null) {
                mc.gui.setScreen(new baritone.gui.BaritoneMenuScreen());
            }
        }

        // Drive the GUI macros tab (auto/hold click). Runs at tick RETURN so
        // vanilla's next handleKeybinds() consumes whatever key state we set.
        baritone.utils.MacroManager.onClientTick(mc);

        if (this.tickProvider == null) {
            return;
        }

        for (IBaritone baritone : BaritoneAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN
                    : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onPostTick(this.tickProvider.apply(EventState.POST, type));
        }

        this.tickProvider = null;
    }

    @Inject(
            method = "startAttack",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        // Blaze-rod selection wand: left-click sets corner 1. Consume the click
        // so the rod doesn't break the block.
        if (baritone.utils.SelectionWand.onClick((Minecraft) (Object) this, false)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "startUseItem",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onStartUseItem(CallbackInfo ci) {
        // Blaze-rod selection wand: right-click sets corner 2. Consume the click
        // so the rod doesn't place/use anything.
        if (baritone.utils.SelectionWand.onClick((Minecraft) (Object) this, true)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "continueAttack",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onContinueAttack(boolean leftClickHeld, CallbackInfo ci) {
        // Don't let a held left-click chip the block while the blaze rod is
        // acting as a selection wand.
        if (leftClickHeld && baritone.utils.SelectionWand.isActive((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/multiplayer/ClientLevel.tickEntities()V",
                    shift = At.Shift.AFTER
            )
    )
    private void postUpdateEntities(CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(this.player);
        if (baritone != null) {
            // Intentionally call this after all entities have been updated. That way, any modification to rotations
            // can be recognized by other entity code. (Fireworks and Pigs, for example)
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        }
    }

    @Inject(
            method = "setLevel",
            at = @At("HEAD")
    )
    private void preLoadWorld(final ClientLevel world, final CallbackInfo ci) {
        // If we're unloading the world but one doesn't exist, ignore it
        if (this.level == null && world == null) {
            return;
        }

        // mc.world changing is only the primary baritone

        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().onWorldEvent(
                new WorldEvent(
                        world,
                        EventState.PRE
                )
        );
    }

    @Inject(
            method = "setLevel",
            at = @At("RETURN")
    )
    private void postLoadWorld(final ClientLevel world, final CallbackInfo ci) {
        // still fire event for both null, as that means we've just finished exiting a world

        // mc.world changing is only the primary baritone
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().onWorldEvent(
                new WorldEvent(
                        world,
                        EventState.POST
                )
        );
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;",
                    ordinal = 0
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;"
                    ),
                    to = @At(
                            value = "CONSTANT",
                            args = "stringValue=Keybindings"
                    )
            )
    )
    private Screen passEvents(net.minecraft.client.gui.Gui instance) {
        // allow user input is only the primary baritone
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing() && player != null) {
            return null;
        }
        return instance.screen();
    }

    // TODO
    // FIXME
    // bradyfix
    // i cant mixin
    // lol
    // https://discordapp.com/channels/208753003996512258/503692253881958400/674760939681349652
    // https://discordapp.com/channels/208753003996512258/503692253881958400/674756457966862376
    /*@Inject(
            method = "rightClickMouse",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/entity/player/ClientPlayerEntity.swingArm(Lnet/minecraft/util/Hand;)V",
                    ordinal = 1
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onBlockUse(CallbackInfo ci, Hand var1[], int var2, int var3, Hand enumhand, ItemStack itemstack, EntityRayTraceResult rt, Entity ent, ActionResultType art, BlockRayTraceResult raytrace, int i, ActionResultType enumactionresult) {
        // rightClickMouse is only for the main player
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().onBlockInteract(new BlockInteractEvent(raytrace.getPos(), BlockInteractEvent.Type.USE));
    }*/
}
