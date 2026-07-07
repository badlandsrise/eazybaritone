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

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A button that renders an item icon instead of a text label.
 */
public class ItemButton extends AbstractButton {

    private final ItemStack icon;
    private final Runnable action;

    public ItemButton(int x, int y, int width, int height, Component message, ItemStack icon, Runnable action) {
        super(x, y, width, height, message);
        this.icon = icon;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTicks) {
        extractor.item(this.icon, this.getX() + (this.getWidth() - 16) / 2, this.getY() + (this.getHeight() - 16) / 2);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }
}
