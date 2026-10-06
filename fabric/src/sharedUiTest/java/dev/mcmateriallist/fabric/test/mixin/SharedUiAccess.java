package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.*;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;

/** Native widget inspection only; actions still use actual mouse/key input. */
public final class SharedUiAccess {
    private SharedUiAccess() {}
    @Mixin(value = ButtonBase.class, remap = false)
    public interface Button {
        @Accessor("displayString") String label();
        @Accessor("enabled") boolean enabled();
    }
    @Mixin(value = GuiListBase.class, remap = false)
    public interface ListScreen { @Invoker("getListWidget") WidgetListBase<?, ?> list(); }
    @Mixin(value = WidgetListBase.class, remap = false)
    public interface Rows { @Accessor("listWidgets") List<WidgetListEntryBase<?>> rows(); }
    @Mixin(value = WidgetContainer.class, remap = false)
    public interface Children { @Accessor("subWidgets") List<WidgetBase> children(); }
    @Mixin(value = dev.mcmateriallist.fabric.client.shared.SharedInputScreen.class, remap = false)
    public interface Input { @Accessor("input") GuiTextFieldMultiLine input(); }
    @Mixin(value = dev.mcmateriallist.fabric.client.material.MaterialDetailScreen.class, remap = false)
    public interface MaterialDetail { @Accessor("note") GuiTextFieldMultiLine note(); }
    @Mixin(value = fi.dy.masa.malilib.gui.GuiBase.class, remap = false)
    public interface Screen {
        @Accessor("textFields") List<fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper<? extends fi.dy.masa.malilib.gui.GuiTextFieldGeneric>> fields();
        @Accessor("messageRenderer") fi.dy.masa.malilib.render.MessageRenderer messages();
    }
    @Mixin(value = fi.dy.masa.malilib.render.MessageRenderer.class, remap = false)
    public interface Messages { @Accessor("messages") List<fi.dy.masa.malilib.gui.Message> messages(); }
    @Mixin(value = fi.dy.masa.malilib.util.InfoUtils.class, remap = false)
    public interface InGameMessages {
        @Accessor("IN_GAME_MESSAGES") static fi.dy.masa.malilib.render.MessageRenderer messages() { throw new AssertionError("Test accessor not applied"); }
    }
}
