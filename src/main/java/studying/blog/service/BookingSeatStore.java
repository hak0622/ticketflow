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
                List.of("owner", "state", "result", "remaining", "admissionExpires", "releaseOutcome"));
        if (fields.stream().allMatch(java.util.Objects::isNull)) return false;
        if (!owner(a).equals(fields.get(0))) throw new IllegalStateException("Attempt owner mismatch");
        Object state = fields.get(1), result = fields.get(2);
        if ("RELEASED".equals(state) && "SOLD_OUT".equals(result)
                && fields.get(3) == null && fields.get(4) == null && fields.get(5) == null) return true;
        if (result != null || !("HELD".equals(state) || "RELEASED".equals(state)))
            throw new IllegalStateException("Invalid attempt outcome");
        if ("HELD".equals(state) && (fields.get(3) == null || fields.get(4) == null))
            throw new IllegalStateException("Invalid held attempt");
        return false;
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
    private long execute(DefaultRedisScript<Long> script, BookingAttempt a, String... args) {
        long start = System.nanoTime();
        try {
            Long result = redis.execute(script, keys(a), (Object[]) args);
            if (result == null) throw new IllegalStateException("Redis booking result unknown");
            return result;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= 30) log.warn("[BOOKING_REDIS][SLOW] requestId={} tookMs={}", a.getId(), ms);
        }
    }
}
