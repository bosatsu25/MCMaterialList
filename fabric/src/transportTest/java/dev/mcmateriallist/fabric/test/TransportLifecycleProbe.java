package dev.mcmateriallist.fabric.test;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.shared.transport.BoundedOutgoing;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Test-only single held genuine completion; synthetic initiating token is never a native client. */
public final class TransportLifecycleProbe {
    private static final Map<SharedProjectService, SharedProcessorControl> PROCESSORS = new IdentityHashMap<>();
    private static UUID pending;
    private static Runnable callback;
    private TransportLifecycleProbe() {}
    public static synchronized void register(SharedProjectService service, SharedProcessorControl control) {
        if (PROCESSORS.size() >= 4) throw new AssertionError("Test runtime observation capacity");
        PROCESSORS.put(service, control);
    }
    public static synchronized void unregister(SharedProjectService service) { PROCESSORS.remove(service); }
    public static synchronized boolean hold(UUID token, Runnable value) {
        if (!token.equals(pending)) return false;
        if (callback != null) throw new AssertionError("Duplicate test completion");
        callback = value; return true;
    }
    public static synchronized boolean held() { return callback != null; }
    public static UUID start(SharedProjectService service, UUID creator, ProjectScope scope, SharedMessage message) {
        SharedProcessorControl control;
        UUID token = UUID.randomUUID();
        synchronized (TransportLifecycleProbe.class) {
            if (pending != null) throw new AssertionError("Test completion gate occupied");
            control = PROCESSORS.get(service); pending = token;
        }
        if (control == null) throw new AssertionError("Test runtime missing");
        if (!control.wireWork(() -> {
            control.liveTokens().add(token); control.capableTokens().add(token); control.subscriptions().put(token, scope);
            control.dispatchSynthetic(token, creator, message);
        })) throw new AssertionError("Test wire queue capacity");
        return token;
    }
    public static synchronized void holdNextActual(SharedProjectService service) {
        SharedProcessorControl control = PROCESSORS.get(service);
        if (pending != null || control == null || control.liveTokens().size() != 1)
            throw new AssertionError("Test actual connection is not unique");
        pending = control.liveTokens().iterator().next();
    }
    public static CompletableFuture<Void> forgetInitiator(SharedProjectService service, UUID token) {
        SharedProcessorControl control;
        synchronized (TransportLifecycleProbe.class) { control = PROCESSORS.get(service); }
        if (control == null) throw new AssertionError("Test runtime missing");
        var finished = new CompletableFuture<Void>();
        if (!control.wireWork(() -> {
            control.liveTokens().remove(token); control.capableTokens().remove(token); control.subscriptions().remove(token);
            finished.complete(null);
        })) throw new AssertionError("Test wire queue capacity");
        return finished;
    }
    public static void release() {
        Runnable value;
        synchronized (TransportLifecycleProbe.class) { value = callback; callback = null; pending = null; }
        if (value == null) throw new AssertionError("Genuine test completion missing");
        value.run();
    }
    public static long slots(MinecraftServer server) {
        Object runtime = SharedServerAccess.runtimes().get(server);
        if (!(runtime instanceof SharedRuntimeCounts counts)) throw new AssertionError("Test runtime count bridge missing");
        return counts.connectionBudget().used();
    }
    public static BoundedOutgoing outgoing(MinecraftServer server) {
        SharedProcessorControl control;
        synchronized (TransportLifecycleProbe.class) { control = PROCESSORS.get(SharedServerNetworking.service(server)); }
        if (control == null) throw new AssertionError("Test processor outgoing bridge missing");
        return control.outgoing();
    }
}
