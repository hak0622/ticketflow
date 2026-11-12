package studying.blog.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import studying.blog.repository.BookingAttemptRepository;
import java.util.concurrent.*;

/** Independent, read-only observation. A failed sweep never publishes partial counts. */
@Slf4j
@Component
public class InventoryObservation {
    public record Snapshot(double reserve, double release, double lastSuccess) {}
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, 0);
    private final BookingAttemptRepository attempts;
    private final BookingSeatStore seats;
    private final boolean scheduling;
    private ScheduledExecutorService executor;

    public InventoryObservation(BookingAttemptRepository attempts, BookingSeatStore seats,
            MeterRegistry registry, @Value("${spring.task.scheduling.enabled:true}") boolean scheduling) {
        this.attempts = attempts;
        this.seats = seats;
        this.scheduling = scheduling;
        Gauge.builder("booking.inventory.incomplete.current", this, x -> x.snapshot.reserve())
                .tag("phase", "RESERVE_PENDING").register(registry);
        Gauge.builder("booking.inventory.incomplete.current", this, x -> x.snapshot.release())
                .tag("phase", "RELEASE_PENDING").register(registry);
        Gauge.builder("booking.inventory.observation.last.success.timestamp.seconds", this,
                x -> x.snapshot.lastSuccess()).register(registry);
    }
    public Snapshot snapshot() { return snapshot; }
    @PostConstruct void start() {
        if (!scheduling) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "inventory-observation"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(this::observe, 0, 30, TimeUnit.SECONDS);
    }
    @PreDestroy void stop() { if (executor != null) executor.shutdownNow(); }
    public synchronized void observe() {
        long start = System.nanoTime();
        int examined = 0, reserve = 0, release = 0;
        try {
            String after = "";
            while (true) {
                // Repository transaction ends before any Redis call. No row locks.
                var page = attempts.findInventoryCandidates(after, PageRequest.of(0, 100));
                if (page.isEmpty()) break;
                for (var candidate : page) {
                    String phase;
                    try { phase = seats.observeInventory(candidate.getId(), candidate.getConcertId(), candidate.getUserId()); }
                    catch (RuntimeException failure) {
                        log.warn("[INVENTORY][OBSERVATION_UNKNOWN] requestId={} concertId={} cause={}",
                                candidate.getId(), candidate.getConcertId(), failure.getClass().getSimpleName());
                        throw failure;
                    }
                    examined++;
                    if ("RESERVE_PENDING".equals(phase)) reserve++;
                    if ("RELEASE_PENDING".equals(phase)) release++;
                    if (phase.endsWith("_PENDING")) log.error("[INVENTORY][INCOMPLETE] requestId={} concertId={} phase={}",
                            candidate.getId(), candidate.getConcertId(), phase);
                }
                after = page.get(page.size() - 1).getId();
            }
            snapshot = new Snapshot(reserve, release, System.currentTimeMillis() / 1000.0);
        } catch (RuntimeException failure) {
            log.warn("[INVENTORY][OBSERVATION_FAILED] cause={}; previous snapshot retained", failure.getClass().getSimpleName());
        } finally {
            log.info("[INVENTORY][OBSERVATION] examined={} elapsedMs={}", examined, (System.nanoTime()-start)/1_000_000);
        }
    }
}
