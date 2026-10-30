package dev.mcmateriallist.fabric.client.stock;

import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.stock.StockSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/**
 * Per material screen view of Check Materials. It only presents StockClient's memory-only results next to the material list:
 * Total, Missing and Available, progress and completion are never read or changed here. An unchecked location is never shown as 0.
 */
public final class StockSession {
    public static final int TEXT = 0xFFFFFFFF, PREVIOUS = 0xFFAAAAAA;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    public record Line(String text, int color) {}
    private final Supplier<LocationProfile> profile;
    /** profile supplies the screen's current Location File (null without one). */
    public StockSession(Supplier<LocationProfile> profile) { this.profile = profile; }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.stock." + key, args).getString(); }
    public static String time(Instant at) { return at == null ? "--:--" : TIME.format(at); }
    public static long minutesAgo(Instant at) { return at == null ? 0 : Math.max(0, Duration.between(at, Instant.now()).toMinutes()); }

    public LocationProfile profile() { LocationProfile value = profile.get(); StockClient.know(value); return value; }
    /** Enabled only with a current Location File, or to cancel the running check. */
    public boolean enabled() { return StockClient.scanning() || profile() != null; }
    public String label() {
        if (!StockClient.scanning()) return text("button");
        return text("checking", snapshot().map(value -> value.reports().size()).orElse(0), StockClient.total());
    }
    public void click() { if (StockClient.scanning()) StockClient.cancel(); else StockClient.start(profile()); }
    public Optional<StockSnapshot> snapshot() { return StockClient.snapshot(profile.get()); }
    /** A finished or running check exists for the current Location File, so its details can be shown. */
    public boolean details() { return snapshot().isPresent(); }
    public boolean stale() { return snapshot().map(value -> StockClient.stale(profile.get(), value)).orElse(false); }

    /** The footer summary, or null without a check of the current Location File. */
    public Line footer() {
        var value = snapshot().orElse(null); LocationProfile current = profile.get();
        if (value == null || current == null) return null;
        if (StockClient.scanning()) return new Line(text("footer.checking", current.displayName(), value.reports().size(), StockClient.total()), TEXT);
        List<String> parts = new ArrayList<>();
        boolean stale = StockClient.stale(current, value);
        if (StockClient.mode() == StockClient.Mode.FALLBACK) {
            parts.add(text("fallback"));
            if (StockClient.refusal() != null) parts.add(text("refused." + StockClient.refusal().name()));
        } else if (stale) parts.add(text("footer.stale", current.displayName(), time(value.startedAt()), minutesAgo(value.startedAt())));
        else {
            var coverage = value.coverage();
            parts.add(text("footer.summary", current.displayName(), coverage.checked(), coverage.total(), time(value.completedAt() == null ? value.startedAt() : value.completedAt())));
        }
        parts.add(text("footer.unchecked", value.unchecked()));
        if (value.previousLocations() > 0) parts.add(text("footer.previous", value.previousLocations()));
        if (StockClient.mode() == StockClient.Mode.SERVER && StockClient.unauthorized() > 0) parts.add(text("footer.unauthorized", StockClient.unauthorized(), text("fallback")));
        return new Line(String.join(" · ", parts), stale || StockClient.mode() == StockClient.Mode.FALLBACK ? PREVIOUS : TEXT);
    }
    /** The Show Info suffix for one item id, or "" without a check. */
    public String info(String itemId) {
        var value = snapshot().orElse(null);
        if (value == null) return "";
        String stock = value.coverage().checked() == 0 ? text("info.unchecked") : text("info.stock", value.confirmed(itemId));
        List<String> extras = new ArrayList<>();
        var previous = value.previous(itemId);
        if (previous.locations() > 0) extras.add(text("info.previous", previous.count()));
        long machine = value.machine(itemId);
        if (machine > 0) extras.add(text("info.machines", machine));
        return stock + (extras.isEmpty() ? "" : " (" + String.join(", ", extras) + ")") + " · " + text("info.inventory", inventory(itemId));
    }
    /** The per-source breakdown for the row tooltip, or null without a check. */
    public String tooltip(String itemId) {
        var value = snapshot().orElse(null);
        if (value == null) return null;
        var coverage = value.coverage(); var previous = value.previous(itemId);
        List<String> parts = new ArrayList<>();
        parts.add(coverage.checked() == 0 ? text("tooltip.unchecked_all") : text("tooltip.checked", value.confirmed(itemId), coverage.checked(), coverage.total()));
        if (previous.locations() > 0) parts.add(text("tooltip.previous", previous.count(), previous.locations(), time(previous.oldest()), minutesAgo(previous.oldest())));
        parts.add(text("tooltip.machines", value.machine(itemId)));
        parts.add(text("tooltip.inventory", inventory(itemId)));
        parts.add(text("tooltip.unchecked", value.unchecked()));
        return String.join(" | ", parts);
    }
    public static long inventory(String itemId) { return StockObservations.inventory().getOrDefault(itemId, 0L); }
}
