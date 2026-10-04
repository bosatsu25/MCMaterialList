package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.render.MessageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = InfoUtils.class, remap = false)
public interface InGameMessagesTestAccess {
    @Accessor("IN_GAME_MESSAGES") static MessageRenderer phase2Messages() { throw new AssertionError("Test accessor was not applied"); }
}
