package dev.mcmateriallist.fabric.client.stock;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockLedger;
import dev.mcmateriallist.core.stock.StockSnapshot;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.client.location.LocationWorld;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.LoggerFactory;

/**
 * Client-thread owner of Check Materials for the world the client is in; memory only. The server path is primary everywhere
 * (singleplayer included); the client fallback (StockObservations) is used only when the server lacks the stock channel, refuses
 * the request, or answers a position UNAUTHORIZED. A fallback location is OBSERVED with its time, never CHECKED. Nothing here
 * references work, completion, counts or .litematic files.
 */
public final class StockClient {
    public enum Mode { NONE, SERVER, FALLBACK }
    /** A scan with no frame for this long is completed locally; its unreported locations stay unchecked. */
    public static final Duration SILENCE = Duration.ofSeconds(30);
    private static final StockLedger ledger = new StockLedger(Clock.systemDefaultZone());
    private static LocationWorld.Resolution world;
    private static LocationProfile known, scanned;
    private static UUID active;
    private static long lastFrame;
    private static Mode mode = Mode.NONE;
    private static StockMessage.RefusalReason refusal;
    private static StockMessage.Done done;
    private static int unauthorized, observed;
    /** Bumped on every ledger change; the rendered snapshot is cached per change, profile object and second. */
    private static long version, cachedVersion = -1, cachedSecond;
    private static LocationProfile cachedProfile;
    private static Optional<StockSnapshot> cached = Optional.empty();
    private StockClient() {}

    public static void initialize() {
        StockClientNetworking.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> { StockObservations.tick(client); expire(); });
    }
    /** The last Location File a material screen showed in this world; observations are attributed only to its positions. */
    public static LocationProfile known() { return known; }
    public static void know(LocationProfile profile) {
        if (profile == null) return;
        if (world != null && !LocationWorld.same(LocationWorld.resolve(), world)) discard();
        known = profile;
    }
    public static boolean scanning() { return ledger.scanning(); }
    public static Mode mode() { return mode; }
    public static StockMessage.RefusalReason refusal() { return refusal; }
    public static StockMessage.Done done() { return done; }
    public static int unauthorized() { return unauthorized; }
    public static int observed() { return observed; }
    public static int total() { return scanned == null ? 0 : scanned.positions().size(); }
    public static LocationProfile scanned() { return scanned; }

    /** Starts a check of profile in the current world; false when one is running or the world does not match. */
    public static boolean start(LocationProfile profile) {
        if (profile == null || scanning()) return false;
        var here = LocationWorld.resolve();
        if (!(here instanceof LocationWorld.Available available) || !available.world().equals(profile.world())) return false;
        if (world != null && !LocationWorld.same(here, world)) discard();
        world = here; known = profile; scanned = profile;
        ledger.begin(profile); version++;
        refusal = null; done = null; unauthorized = 0; observed = 0;
        UUID request = StockClientNetworking.send(id -> new StockMessage.ScanRequest(id, profile.id(), profile.generation(), profile.binding(),
            profile.dimension(), profile.positions()), StockClient::received);
        if (request == null) { fallback(available); return true; }
        active = request; mode = Mode.SERVER; lastFrame = System.nanoTime();
        return true;
    }
    /** Unreported locations stay unchecked; the server's final Done for the cancelled request is dropped. */
    public static void cancel() {
        if (!scanning()) return;
        StockClientNetworking.cancel(active); StockClientNetworking.forget(active);
        finish();
    }
    /** Empty without a scan; another world (LocationWorld.same fails) or another profile discards everything. */
    public static Optional<StockSnapshot> snapshot(LocationProfile current) {
        if (current == null || world == null) return Optional.empty();
        var here = LocationWorld.resolve();
        if (!LocationWorld.same(here, world) || !(here instanceof LocationWorld.Available available)) { discard(); return Optional.empty(); }
        long second = System.currentTimeMillis() / 1000;
        if (cachedVersion != version || cachedProfile != current || cachedSecond != second) {
            cached = ledger.snapshot(available.world(), current);
            cachedVersion = version; cachedProfile = current; cachedSecond = second;
        }
        return cached;
    }
    /** The scanned generation moved on, or the scan is older than five minutes: every reading is shown as previous. */
    public static boolean stale(LocationProfile current, StockSnapshot snapshot) {
        return scanned == null || current == null || current.generation() != scanned.generation()
            || snapshot.stale(java.time.Instant.now(), snapshot.profileGeneration());
    }
    public static void discard() {
        if (active != null) StockClientNetworking.forget(active);
        ledger.discard(); StockObservations.discard(); version++;
        world = null; known = scanned = null; active = null; mode = Mode.NONE; refusal = null; done = null; unauthorized = observed = 0;
    }

    private static void received(StockMessage message) {
        if (active == null || !active.equals(message.requestId()) || scanned == null) return;
        lastFrame = System.nanoTime();
        switch (message) {
            case StockMessage.Report frame -> {
                LocationReport report = frame.report();
                if (report.status() == LocationStatus.UNAUTHORIZED) {
                    unauthorized++;
                    if (observe(report.index())) return;
                }
                if (ledger.accept(report)) version++;
            }
            case StockMessage.Done value -> {
                done = value; finish();
                LoggerFactory.getLogger("mcmateriallist").info("Check Materials {} reports={} checked={} unauthorized={} observed={} maxContainersPerTick={} maxNanosPerTick={}",
                    value.outcome(), value.reports(), value.checked(), unauthorized, observed, value.maxContainersPerTick(), value.maxNanosPerTick());
            }
            case StockMessage.Refused value -> {
                refusal = value.reason(); active = null;
                if (LocationWorld.resolve() instanceof LocationWorld.Available available) fallback(available); else finish();
            }
            default -> { }
        }
    }
    /** Client fallback for every location: OBSERVED from an opened container, OTHER_DIMENSION when away, else unchecked. */
    private static void fallback(LocationWorld.Available here) {
        mode = Mode.FALLBACK; active = null;
        for (int index = 0; index < scanned.positions().size(); index++) {
            if (observe(index)) continue;
            if (!here.dimension().equals(scanned.dimension()))
                if (ledger.accept(LocationReport.empty(index, LocationStatus.OTHER_DIMENSION, ContainerBlocks.kind(scanned.positions().get(index).blockId())))) version++;
        }
        finish();
    }
    /** Fills an otherwise empty location with the player's own observation; never replaces or removes other evidence. */
    private static boolean observe(int index) {
        ContainerPosition position = scanned.positions().get(index);
        var value = StockObservations.of(scanned, position);
        if (value == null) return false;
        boolean accepted = ledger.fill(new LocationReport(index, LocationStatus.OBSERVED, ContainerBlocks.kind(position.blockId()), value.counts()), value.at());
        if (accepted) { observed++; version++; }
        return accepted;
    }
    private static void finish() { ledger.complete(); active = null; version++; }
    private static void expire() {
        if (active != null && scanning() && System.nanoTime() - lastFrame > SILENCE.toNanos()) { StockClientNetworking.forget(active); finish(); }
    }
}
