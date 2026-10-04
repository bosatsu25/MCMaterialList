package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ButtonGeneric.class, remap = false)
public interface NativeNoticeTestAccess {
    @Accessor("icon") IGuiIcon phase2Icon();
}
