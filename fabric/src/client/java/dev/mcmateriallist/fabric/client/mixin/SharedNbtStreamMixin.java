package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.shared.SharedSourceLoading;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = NbtUtils.class, remap = false)
public abstract class SharedNbtStreamMixin {
    @Redirect(method = "readNbtFromFile(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;",
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;newInputStream(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;"), require = 2)
    private static InputStream opened(Path path, OpenOption[] options) throws IOException {
        return SharedSourceLoading.observe(path, Files.newInputStream(path, options));
    }
}
