package dev.mcmateriallist.fabric.test.mixin;

import java.util.List;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.render.MessageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MessageRenderer.class, remap = false)
public interface MessageRendererTestAccess {
    @Accessor("messages") List<Message> phase0Messages();
}
