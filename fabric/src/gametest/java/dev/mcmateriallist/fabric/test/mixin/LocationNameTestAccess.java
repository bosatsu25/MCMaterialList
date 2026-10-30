package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.location.LocationNameScreen;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only: locates the real name field so the test types into it with actual key events. */
@Mixin(value = LocationNameScreen.class, remap = false)
public interface LocationNameTestAccess {
    @Accessor("input") GuiTextFieldMultiLine phase4Input();
}
