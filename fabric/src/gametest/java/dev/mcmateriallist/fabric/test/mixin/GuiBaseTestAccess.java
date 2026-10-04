package dev.mcmateriallist.fabric.test.mixin;

import java.util.List;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.MessageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only inspection: these accessors are excluded from the distributed mod. */
@Mixin(value = GuiBase.class, remap = false)
public interface GuiBaseTestAccess {
    @Accessor("buttons") List<ButtonBase> phase0Buttons();
    @Accessor("widgets") List<WidgetBase> phase0Widgets();
    @Accessor("messageRenderer") MessageRenderer phase0MessageRenderer();
}
