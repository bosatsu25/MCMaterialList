package dev.mcmateriallist.fabric.test.mixin;

import java.util.List;
import fi.dy.masa.malilib.gui.Message;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Message.class, remap = false)
public interface MessageTestAccess {
    @Accessor("messageLines") List<String> phase0Lines();
}
