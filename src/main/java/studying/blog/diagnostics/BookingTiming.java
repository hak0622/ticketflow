package studying.blog.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p6spy.engine.common.*;
import com.p6spy.engine.event.JdbcEventListener;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import studying.blog.dto.BookingResult;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in request-local timings. No SQL parameters, extra queries, locks, or network calls. */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "booking.profiling.enabled", havingValue = "true")
public class BookingTiming extends JdbcEventListener {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<Map<String, Object>> completed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger recorded = new AtomicInteger();
    private static final int MAX_RECORDS = 10_000;
    private final Map<String, String> sqlKinds = new ConcurrentHashMap<>();

    private static class Trace {
        final Map<String, Long> ns = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<Integer, Long> borrowed = new HashMap<>();
        String phase = "outside";
        void add(String key, long nanos) {
            ns.merge(key, nanos, Long::sum);
            counts.merge(key, 1, Integer::sum);
        }
    }

    @Around("execution(* studying.blog.service.BookingService.book(..)) && args(concertId,userId,requestId)")
    public Object request(ProceedingJoinPoint call, Long concertId, Long userId, String requestId) throws Throwable {
        Trace trace = new Trace();
        CURRENT.set(trace);
        String outcome = "EXCEPTION";
        long start = System.nanoTime();
        try {
            Object result = call.proceed();
            if (result instanceof BookingResult booking) outcome = booking.getStatus();
            return result;
        } finally {
            trace.add("service.total", System.nanoTime() - start);
            CURRENT.remove();
            if (recorded.incrementAndGet() <= MAX_RECORDS) {
                completed.add(Map.of("requestId", requestId, "concertId", concertId, "outcome", outcome,
                        "ns", trace.ns, "counts", trace.counts, "unclosed", trace.borrowed.size()));
            }
        }
    }

    /** Dump bounded samples after the measured workload, when the app is stopped gracefully. */
    @jakarta.annotation.PreDestroy
    public void dump() {
        for (Map<String, Object> record : completed) {
            try {
                log.info("[BOOKING_TIMING] {}", json.writeValueAsString(record));
            } catch (JsonProcessingException error) {
                log.error("[BOOKING_TIMING] serialization failed", error);
            }
        }
        log.info("[BOOKING_TIMING_BUFFER] recorded={} dropped={}", completed.size(),
                Math.max(0, recorded.get() - MAX_RECORDS));
    }

    @Around("execution(* studying.blog.service.BookingAttemptTransactions.*(..))")
    public Object transaction(ProceedingJoinPoint call) throws Throwable {
        Trace t = CURRENT.get();
        if (t == null) return call.proceed();
        String previous = t.phase;
        t.phase = call.getSignature().getName();
        long start = System.nanoTime();
        try { return call.proceed(); }
        finally {
            t.add(t.phase + ".transaction", System.nanoTime() - start);
            t.phase = previous;
        }
    }

    @Around("bean(bookingAttemptRepository) || bean(bookingRepository) || bean(concertRepository)")
    public Object repository(ProceedingJoinPoint call) throws Throwable {
        Trace t = CURRENT.get();
        if (t == null) return call.proceed();
        long start = System.nanoTime();
        try { return call.proceed(); }
        finally { t.add(t.phase + ".repository." + call.getSignature().getName(), System.nanoTime() - start); }
    }

    @Around("execution(* studying.blog.service.BookingSeatStore.reserve(..))")
    public Object redis(ProceedingJoinPoint call) throws Throwable {
        Trace t = CURRENT.get();
        if (t == null) return call.proceed();
        long start = System.nanoTime();
        try { return call.proceed(); }
        finally { t.add(t.phase + ".redis.reserve", System.nanoTime() - start); }
    }

    @Override public void onAfterGetConnection(ConnectionInformation c, SQLException error) {
        Trace t = CURRENT.get();
        if (t == null) return;
        t.add(t.phase + ".connection.acquire", c.getTimeToGetConnectionNs());
        if (error == null) t.borrowed.put(c.getConnectionId(), System.nanoTime());
        else t.add(t.phase + ".connection.acquire_error", 0);
    }
    @Override public void onAfterConnectionClose(ConnectionInformation c, SQLException error) {
        Trace t = CURRENT.get();
        if (t == null) return;
        Long start = t.borrowed.remove(c.getConnectionId());
        if (start != null) t.add(t.phase + ".connection.hold", System.nanoTime() - start);
    }
    @Override public void onAfterCommit(ConnectionInformation c, long nanos, SQLException error) {
        record("commit", nanos);
    }
    @Override public void onAfterRollback(ConnectionInformation c, long nanos, SQLException error) {
        record("rollback", nanos);
    }
    private void record(String key, long nanos) {
        Trace t = CURRENT.get();
        if (t != null) t.add(t.phase + "." + key, nanos);
    }
    private void sql(StatementInformation statement, long nanos) {
        if (CURRENT.get() == null) return;
        String kind = sqlKinds.computeIfAbsent(statement.getSql(), BookingTiming::kind);
        record("sql." + kind, nanos);
    }
    private static String kind(String raw) {
        String sql = raw.toLowerCase(Locale.ROOT).stripLeading();
        if (sql.startsWith("insert into booking_attempt")) return "attempt_insert";
        if (sql.startsWith("insert into booking ")) return "booking_insert";
        if (sql.startsWith("update booking_attempt")) return "attempt_update";
        if (sql.startsWith("update concert")) return "concert_update";
        if (sql.contains("booking_attempt") && sql.contains("for update")) return "attempt_lock";
        if (sql.startsWith("select") && sql.contains("from booking ")) return "booking_lookup";
        if (sql.startsWith("select") && sql.contains("from concert ")) return "concert_lookup";
        return "other";
    }
    @Override public void onAfterExecute(PreparedStatementInformation s, long n, SQLException e) { sql(s,n); }
    @Override public void onAfterExecute(StatementInformation s, long n, String q, SQLException e) { sql(s,n); }
    @Override public void onAfterExecuteQuery(PreparedStatementInformation s, long n, SQLException e) { sql(s,n); }
    @Override public void onAfterExecuteQuery(StatementInformation s, long n, String q, SQLException e) { sql(s,n); }
    @Override public void onAfterExecuteUpdate(PreparedStatementInformation s, long n, int count, SQLException e) { sql(s,n); }
    @Override public void onAfterExecuteUpdate(StatementInformation s, long n, String q, int count, SQLException e) { sql(s,n); }
}
