package studying.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import studying.blog.domain.BookingAttempt;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingSeatStore {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RESERVE = script("redis/reserve-booking.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("redis/release-booking.lua");
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
    private List<String> keys(BookingAttempt a) {
        return List.of("seats:concert:" + a.getConcertId(),
                "admitted:concert:" + a.getConcertId() + ":user:" + a.getUserId(),
                "booking:attempt:" + a.getId(),
                "booking:active:concert:" + a.getConcertId() + ":user:" + a.getUserId());
    }
    private String owner(BookingAttempt a) { return a.getConcertId() + ":" + a.getUserId(); }
    private static final DefaultRedisScript<Long> INSPECT = script("redis/inspect-booking-hold.lua");
    // Positive: live lease milliseconds; zero: expired; -1: no hold; -2: legacy hold.
    public long holdRemaining(BookingAttempt a) {
        return execute(INSPECT, a, owner(a), a.getId());
    }
    public long reserve(BookingAttempt a) {
        return execute(RESERVE, a, owner(a), a.getId());
    }
    /** A single atomic HMGET; missing records retain the legacy recovery path. */
    public boolean isSoldOut(BookingAttempt a) {
        List<Object> fields = redis.opsForHash().multiGet(keys(a).get(2),
                List.of("owner", "state", "result", "remaining", "admissionExpires", "releaseOutcome", "inventoryVersion", "inventoryPhase"));
        if (fields.stream().allMatch(java.util.Objects::isNull)) return false;
        if (!owner(a).equals(fields.get(0))) throw new IllegalStateException("Attempt owner mismatch");
        validateInventory(a, fields);
        Object state = fields.get(1), result = fields.get(2);
        if ("RELEASED".equals(state) && "SOLD_OUT".equals(result)
                && fields.get(3) == null && fields.get(4) == null && fields.get(5) == null) return true;
        if (result != null || !("HELD".equals(state) || "RELEASED".equals(state)))
            throw new IllegalStateException("Invalid attempt outcome");
        if ("HELD".equals(state) && (fields.get(3) == null || fields.get(4) == null))
            throw new IllegalStateException("Invalid held attempt");
        return false;
    }
    /** Read-only observation of the same primary used by the booking scripts. */
    public String observeInventory(String requestId, Long concertId, Long userId) {
        List<Object> values = redis.opsForHash().multiGet("booking:attempt:" + requestId,
                List.of("owner", "state", "result", "releaseOutcome", "inventoryVersion", "inventoryPhase"));
        if (values.stream().allMatch(java.util.Objects::isNull)) return "LEGACY_OR_ABSENT";
        if (!(concertId + ":" + userId).equals(values.get(0)))
            throw new InventoryStateException("Observation owner mismatch");
        if ("1".equals(values.get(4)) && ("RESERVE_PENDING".equals(values.get(5))
                || "RELEASE_PENDING".equals(values.get(5)))) {
            if (!"HELD".equals(values.get(1)) || values.get(2) != null || values.get(3) != null)
                throw new InventoryStateException("Observation invalid pending combination");
            return values.get(5).toString();
        }
        // Match the existing guard's field order; no lease/business operation is executed.
        validateInventory(new BookingAttempt(requestId, concertId, userId), java.util.Arrays.asList(
                values.get(0), values.get(1), values.get(2), null, null, values.get(3), values.get(4), values.get(5)));
        return "COMPLETE_OR_LEGACY";
    }

    public enum ReleaseResult { NOT_HELD, RETURNED, ALREADY_RETURNED, LEGACY_UNKNOWN }
    public ReleaseResult release(BookingAttempt a) {
        long result = execute(RELEASE, a, owner(a), a.getId(), a.getBookingId() == null ? "1" : "0",
                a.getBookingId() == null ? "0" : "1");
        return switch ((int) result) {
            case 0 -> ReleaseResult.NOT_HELD;
            case 1 -> ReleaseResult.RETURNED;
            case 2 -> ReleaseResult.ALREADY_RETURNED;
            case 3 -> ReleaseResult.LEGACY_UNKNOWN;
            default -> throw new IllegalStateException("Unknown Redis release result: " + result);
        };
    }
    public static class InventoryStateException extends IllegalStateException {
        public InventoryStateException(String message) { super(message); }
        public InventoryStateException(String message, Throwable cause) { super(message, cause); }
    }
    private void validateInventory(BookingAttempt attempt, List<Object> fields) {
        Object version = fields.get(6), phase = fields.get(7);
        if (version == null && phase == null) return;
        String problem = null;
        if (!"1".equals(version) || phase == null) problem = "INVENTORY_INVALID version/phase";
        else if ("RESERVE_PENDING".equals(phase) || "RELEASE_PENDING".equals(phase))
            problem = "INVENTORY_INCOMPLETE " + phase;
        else {
            Object state = fields.get(1), result = fields.get(2), outcome = fields.get(5);
            boolean valid = "HELD".equals(phase) && "HELD".equals(state) && result == null && outcome == null
                || "RELEASED".equals(state) && (
                    "RETURNED".equals(phase) && "RETURNED".equals(outcome) && result == null
                    || "NOT_HELD".equals(phase) && "NOT_HELD".equals(outcome) && result == null
                    || "SOLD_OUT".equals(phase) && "SOLD_OUT".equals(result) && outcome == null);
            if (!valid) problem = "INVENTORY_INVALID state combination";
        }
        if (problem != null) {
            log.error("[INVENTORY][BLOCKED] requestId={} concertId={} operation=inspect phase={} reason={}",
                    attempt.getId(), attempt.getConcertId(), phase, problem);
            throw new InventoryStateException(problem);
        }
    }
    private long execute(DefaultRedisScript<Long> script, BookingAttempt a, String... args) {
        long start = System.nanoTime();
        try {
            Long result = redis.execute(script, keys(a), (Object[]) args);
            if (result == null) throw new IllegalStateException("Redis booking result unknown");
            return result;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String message = cause.getMessage();
                if (message != null && (message.startsWith("INVENTORY_INCOMPLETE ")
                        || message.startsWith("INVENTORY_INVALID "))) {
                    log.error("[INVENTORY][BLOCKED] requestId={} concertId={} operation={} reason={}",
                            a.getId(), a.getConcertId(), script == RESERVE ? "reserve" : script == RELEASE ? "release" : "inspect", message);
                    throw new InventoryStateException(message, failure);
                }
            }
            throw failure;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= 30) log.warn("[BOOKING_REDIS][SLOW] requestId={} tookMs={}", a.getId(), ms);
        }
    }
}
