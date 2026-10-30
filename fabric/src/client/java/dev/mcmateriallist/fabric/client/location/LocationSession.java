package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.CurrentLocation;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.LocationResult;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Per material screen: the remembered current Location File of the world the client is in. Reading never writes. */
public final class LocationSession {
    private final Runnable changed;
    private LocationWorld.Resolution shown;
    private CurrentLocation current;
    private boolean loading;
    private long request;
    public LocationSession(Runnable changed) { this.changed = changed; }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.location." + key, args).getString(); }

    /** Loads the current profile once per world/listener; the label never describes another world. */
    public void ensure() {
        var now = LocationWorld.resolve();
        if (shown != null && LocationWorld.same(now, shown)) {
            // A read dropped while the world was briefly unresolved is retried instead of leaving the label loading.
            if (shown instanceof LocationWorld.Available available && current == null && !loading) load(available);
            return;
        }
        shown = now; current = null; request++; loading = false;
        if (now instanceof LocationWorld.Available available) load(available);
    }
    /** After a durable selection/name change; a superseded read is discarded. */
    public void reload() { if (shown instanceof LocationWorld.Available available && available.current()) load(available); }
    public boolean stale() { return shown != null && !LocationWorld.same(LocationWorld.resolve(), shown); }
    public boolean pending() { return loading; }
    public LocationWorld.Resolution shown() { return shown; }
    public LocationProfile profile() {
        return current != null && current.profile() != null && current.profile().ok() ? current.profile().value() : null;
    }
    /** Derived only from the confirmed state, so it always matches the button built from it. */
    public String label() {
        String value;
        if (!(shown instanceof LocationWorld.Available)) value = text("unavailable");
        else if (loading || current == null) value = text("loading");
        else if (current.profile() != null) value = current.profile().ok() ? current.profile().value().displayName() : text("unreadable");
        else value = current.selection().ok() || current.selection().status() == StoreStatus.MISSING ? text("none") : text("unreadable");
        return text("button", value);
    }
    private void load(LocationWorld.Available context) {
        long token = ++request; loading = true;
        // schedule() always queues, so a completed read cannot reenter the screen's initGui().
        MCMaterialListClient.locations().current(context.world()).whenComplete((value, failure) -> Minecraft.getInstance().schedule(() -> {
            if (token != request) return;
            loading = false;
            if (!context.current() || !LocationWorld.same(context, shown)) return;
            current = failure == null ? value : new CurrentLocation(LocationResult.failure(StoreStatus.IO_FAILURE), null);
            changed.run();
        }));
    }
}
