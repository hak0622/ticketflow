package studying.blog.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.ConcertRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Service
public class QueueService {

    private final StringRedisTemplate redisTemplate;
    private final ConcertRepository concertRepository;
    private final MeterRegistry meterRegistry;

    // 느린 Redis 호출 감지 기준(운영에서 병목 찾기용)
    private static final long SLOW_REDIS_MS = 30;

    //대기열 키
    private String queueKey(Long concertId) {
        return "queue:concert:" + concertId;
    }

    private String member(Long userId) {
        return String.valueOf(userId);
    }

    private String admittedKey(Long concertId, Long userId) {
        return "admitted:concert:" + concertId + ":user:" + userId;
    }

    private String admittedKeyPrefix(Long concertId) {
        return "admitted:concert:" + concertId + ":user:";
    }

    //마감/정원 마감 상태에서 큐 등록 차단 (예매는 공연 전에 진행되므로 eventAt 비교 제거)
    private void validateQueueAvailable(Concert concert) {
        if (concert.getStatus() == ConcertStatus.CLOSED) {
            throw new IllegalStateException("마감된 콘서트입니다.");
        }
        if (concert.getStatus() == ConcertStatus.SOLD_OUT) {
            throw new IllegalStateException("정원이 마감된 콘서트입니다.");
        }
        LocalDateTime eventAt = concert.getEventAt();
        if (eventAt != null && LocalDateTime.now().isAfter(eventAt)) {
            throw new IllegalStateException("이미 종료된 공연입니다.");
        }
    }

    //줄서기 등록
    public Long enqueue(Long concertId, Long userId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found : " + concertId));
        validateQueueAvailable(concert);

        long score = Instant.now().toEpochMilli();
        String m = member(userId);

        // Redis ZADD + RANK 시간 측정
        long t0 = System.nanoTime();
        redisTemplate.opsForZSet().add(queueKey(concertId), m, score);
        Long rank = redisTemplate.opsForZSet().rank(queueKey(concertId), m);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        //너무 자주 찍히는 API라서 "느릴 때만" 경고 로그
        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=enqueue concertId={} userId={} tookMs={}", concertId, userId, ms);
        }

        meterRegistry.counter("queue.enqueue").increment();
        return rank != null ? rank + 1 : null;
    }

    //현재 내 순번 조회만
    public Long getPosition(Long concertId, Long userId) {
        String m = member(userId);

        long t0 = System.nanoTime();
        Long rank = redisTemplate.opsForZSet().rank(queueKey(concertId), m);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=getPosition concertId={} userId={} tookMs={}", concertId, userId, ms);
        }

        return rank != null ? rank + 1 : null;
    }

    // 총 몇명(총 원소의 개수)
    public Long getTotal(Long concertId) {
        long t0 = System.nanoTime();
        Long total = redisTemplate.opsForZSet().zCard(queueKey(concertId));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=getTotal concertId={} tookMs={}", concertId, ms);
        }

        return total;
    }

    // Lua 스크립트: queue에서 앞 n명 꺼내서 admitted 키 발급(SETEX)
    private static final String POP_AND_GRANT_LUA = """
            local queueKey = KEYS[1]
            local count = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local admittedPrefix = ARGV[3]
            
            local users = redis.call('ZRANGE', queueKey, 0, count - 1)
            if (#users == 0) then
              return users
            end
            
            redis.call('ZREM', queueKey, unpack(users))
            
            for i = 1, #users do
              local userId = users[i]
              local key = admittedPrefix .. userId
              redis.call('SETEX', key, ttl, '1')
            end
            
            return users
            """;

    private final DefaultRedisScript<List> popAndGrantScript =
            new DefaultRedisScript<>(POP_AND_GRANT_LUA, List.class);

    // Lua 스크립트: admitted 선점(있으면 TTL 반환 + DEL, 없으면 -1)
    private static final String CLAIM_ADMITTED_LUA = """
            local key = KEYS[1]
            
            if redis.call('EXISTS', key) == 0 then
              return -1
            end
            
            local ttl = redis.call('TTL', key)
            redis.call('DEL', key)
            return ttl
            """;

    private final DefaultRedisScript<Long> claimAdmittedScript =
            new DefaultRedisScript<>(CLAIM_ADMITTED_LUA, Long.class);

    // admitted를 원자적으로 선점한다. 키가 있으면 TTL(초) 반환 + 키 삭제(소비), 없으면 -1
    public long claimAdmitted(Long concertId, Long userId) {
        String key = admittedKey(concertId, userId);

        long t0 = System.nanoTime();
        Long ttl = redisTemplate.execute(
                claimAdmittedScript,
                Collections.singletonList(key)
        );
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // claim은 결제/신청 직전에만 호출되니, 느릴 때만 경고
        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=claim key={} concertId={} userId={} tookMs={} ttl={}",
                            key, concertId, userId, ms, ttl);
        }

        return ttl == null ? -1 : ttl;
    }

    // 선점 후 DB실패 등으로 롤백이 필요할 때 admitted 복구
    public void restoreAdmitted(Long concertId, Long userId, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            ttlSeconds = 600; // 기본 10분
        }

        String key = admittedKey(concertId, userId);

        long t0 = System.nanoTime();
        redisTemplate.opsForValue().set(key, "1", ttlSeconds, TimeUnit.SECONDS);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=restore concertId={} userId={} ttlSec={} tookMs={}",
                            concertId, userId, ttlSeconds, ms);
        }
    }

    // 스케줄러 호출: queue 앞 n명 꺼내서 admitted 키 발급(원자적)
    public List<String> popAndGrantAdmitted(Long concertId, int n, int ttlSeconds) {
        long t0 = System.nanoTime();

        @SuppressWarnings("unchecked")
        List<String> granted = (List<String>) redisTemplate.execute(
                popAndGrantScript,
                Collections.singletonList(queueKey(concertId)),
                String.valueOf(n),
                String.valueOf(ttlSeconds),
                admittedKeyPrefix(concertId)
        );

        long ms = (System.nanoTime() - t0) / 1_000_000;

        // 스케줄러는 반복이라 "느릴 때만" 경고 + granted size 같이
        int size = granted == null ? 0 : granted.size();
        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=popAndGrant concertId={} batch={} granted={} ttlSec={} tookMs={}",
                            concertId, n, size, ttlSeconds, ms);
        }

        return granted == null ? List.of() : granted;
    }

    // 입장권 있는지 확인
    public boolean isAdmitted(Long concertId, Long userId) {
        String key = admittedKey(concertId, userId);

        long t0 = System.nanoTime();
        Boolean exists = redisTemplate.hasKey(key);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=isAdmitted concertId={} userId={} tookMs={}", concertId, userId, ms);
        }

        return Boolean.TRUE.equals(exists);
    }

    // ─── 좌석 카운터 (seats:concert:{id}) ────────────────────────────────────

    private String seatKey(Long concertId) {
        return "seats:concert:" + concertId;
    }

    /**
     * 콘서트 좌석 카운터 초기화.
     * 콘서트 생성·수정·서버 기동 시 호출. TTL 없음(공연 삭제 시 명시적 DEL).
     */
    public void initSeatCount(Long concertId, int remaining) {
        redisTemplate.opsForValue().set(seatKey(concertId), String.valueOf(Math.max(0, remaining)));
    }

    /**
     * 좌석 원자적 차감 (Lua).
     *
     * 반환값:
     *   >= 0 : 차감 성공, 남은 좌석 수
     *     -1 : 좌석 없음 (SOLD_OUT)
     *     -2 : 키 미존재 (초기화 누락 — 호출 측에서 오류 처리)
     */
    private static final String DECREMENT_SEAT_LUA = """
            local key = KEYS[1]
            if redis.call('EXISTS', key) == 0 then
              return -2
            end
            local remaining = redis.call('DECRBY', key, 1)
            if remaining < 0 then
              redis.call('INCRBY', key, 1)
              return -1
            end
            return remaining
            """;

    private final DefaultRedisScript<Long> decrementSeatScript =
            new DefaultRedisScript<>(DECREMENT_SEAT_LUA, Long.class);

    public long decrementSeat(Long concertId) {
        long t0 = System.nanoTime();
        Long result = redisTemplate.execute(
                decrementSeatScript,
                Collections.singletonList(seatKey(concertId))
        );
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[SEAT][SLOW] op=decrement concertId={} tookMs={}", concertId, ms);
        }

        return result == null ? -2 : result;
    }

    /** 좌석 복구 (Booking 실패·취소 시 호출). */
    public void restoreSeat(Long concertId) {
        long t0 = System.nanoTime();
        redisTemplate.opsForValue().increment(seatKey(concertId));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[SEAT][SLOW] op=restore concertId={} tookMs={}", concertId, ms);
        }
    }

    /** 현재 남은 좌석 수 조회. 키가 없으면 null 반환. */
    public Long getRemainingSeat(Long concertId) {
        long t0 = System.nanoTime();
        String raw = redisTemplate.opsForValue().get(seatKey(concertId));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[SEAT][SLOW] op=getRemaining concertId={} tookMs={}", concertId, ms);
        }

        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[SEAT][INVALID] concertId={} value={}", concertId, raw);
            return null;
        }
    }

    /** 콘서트 삭제 시 Redis 좌석 키 제거. */
    public void deleteSeatCount(Long concertId) {
        redisTemplate.delete(seatKey(concertId));
    }

    // ─── 콘서트 상태·타이틀 캐시 (concert:status:{id}, TTL 30s) ──────────────

    /** 캐시에서 읽은 콘서트 정보. status + title을 함께 저장해 프록시 초기화 없이 반환 가능. */
    public record ConcertInfo(ConcertStatus status, String title) {}

    private static final String CACHE_SEP = "|";

    private String concertStatusKey(Long concertId) {
        return "concert:status:" + concertId;
    }

    /**
     * Redis에서 콘서트 정보 조회.
     * 키 없으면 null 반환(캐시 미스) → 호출 측에서 DB fallback 후 setConcertInfo() 호출.
     */
    public ConcertInfo getConcertInfo(Long concertId) {
        String val = redisTemplate.opsForValue().get(concertStatusKey(concertId));
        if (val == null) return null;
        int sep = val.indexOf(CACHE_SEP);
        if (sep < 0) return null;
        try {
            ConcertStatus status = ConcertStatus.valueOf(val.substring(0, sep));
            String title = val.substring(sep + 1);
            return new ConcertInfo(status, title);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 콘서트 상태·타이틀을 Redis에 캐시 (TTL 30초).
     * DB 커밋 이후 호출할 것 (트랜잭션 롤백 시 Redis 불일치 방지).
     */
    public void setConcertInfo(Long concertId, ConcertStatus status, String title) {
        String val = status.name() + CACHE_SEP + (title != null ? title : "");
        redisTemplate.opsForValue().set(concertStatusKey(concertId), val, 30, TimeUnit.SECONDS);
    }

    /** 콘서트 삭제 시 상태 캐시도 함께 제거. */
    public void deleteConcertStatus(Long concertId) {
        redisTemplate.delete(concertStatusKey(concertId));
    }

    // 입장권 소모(현재는 claim에서 DEL하므로 보조용)
    public boolean consumeAdmitted(Long concertId, Long userId) {
        String key = admittedKey(concertId, userId);

        long t0 = System.nanoTime();
        Boolean deleted = redisTemplate.delete(key);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=consume concertId={} userId={} tookMs={} deleted={}", concertId, userId, ms, deleted);
        }

        return Boolean.TRUE.equals(deleted);
    }
}
