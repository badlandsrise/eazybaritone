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

import baritone.gui.BaritoneKeybinds;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Registers Baritone's menu keybind so it shows up in Options -> Controls
 * and is rebindable like any vanilla key.
 */
@Mixin(Options.class)
public class MixinOptions {

    @Mutable
    @Shadow
    @Final
    public KeyMapping[] keyMappings;

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void baritone$registerKeybind(CallbackInfo ci) {
        KeyMapping[] extended = Arrays.copyOf(this.keyMappings, this.keyMappings.length + 1);
        extended[this.keyMappings.length] = BaritoneKeybinds.OPEN_MENU;
        this.keyMappings = extended;
    }
}
