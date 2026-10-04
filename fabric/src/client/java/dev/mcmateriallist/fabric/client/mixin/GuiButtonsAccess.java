package dev.mcmateriallist.fabric.client.mixin;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GuiBase.class, remap = false)
public interface GuiButtonsAccess {
    @Accessor("buttons") List<ButtonBase> mcmateriallist$buttons();
}
