package dev.mcmateriallist.fabric.test.mixin;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import net.minecraft.server.MinecraftServer;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SharedServerNetworking.class, remap = false)
public interface SharedServerAccess {
    @Accessor("RUNTIMES") static Map<MinecraftServer, ?> runtimes() { throw new AssertionError("Test accessor missing"); }
}
