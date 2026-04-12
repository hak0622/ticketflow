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
