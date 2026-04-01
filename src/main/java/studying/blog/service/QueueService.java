package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.LectureRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Service
public class QueueService {

    private final StringRedisTemplate redisTemplate;
    private final LectureRepository lectureRepository;

    // 느린 Redis 호출 감지 기준(운영에서 병목 찾기용)
    private static final long SLOW_REDIS_MS = 30;

    //대기열 키
    private String queueKey(Long lectureId) {
        return "queue:lecture:" + lectureId;
    }

    private String member(Long userId) {
        return String.valueOf(userId);
    }

    private String admittedKey(Long lectureId, Long userId) {
        return "admitted:lecture:" + lectureId + ":user:" + userId;
    }

    private String admittedKeyPrefix(Long lectureId) {
        return "admitted:lecture:" + lectureId + ":user:";
    }

    //오픈 전/마감/정원 마감 상태에서 큐 등록 자체 차단
    private void validateQueueAvailable(Lecture lecture) {
        if (lecture.getStatus() == LectureStatus.CLOSED) {
            throw new IllegalStateException("마감된 강의입니다.");
        }
        if (lecture.getStatus() == LectureStatus.SOLD_OUT) {
            throw new IllegalStateException("정원이 마감된 강의입니다.");
        }
        LocalDateTime openAt = lecture.getOpenAt();
        if (openAt != null && LocalDateTime.now().isBefore(openAt)) {
            throw new IllegalStateException("아직 오픈 전입니다.");
        }
    }

    //줄서기 등록
    public Long enqueue(Long lectureId, Long userId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + lectureId));
        validateQueueAvailable(lecture);

        long score = Instant.now().toEpochMilli();
        String m = member(userId);

        // Redis ZADD + RANK 시간 측정
        long t0 = System.nanoTime();
        redisTemplate.opsForZSet().add(queueKey(lectureId), m, score);
        Long rank = redisTemplate.opsForZSet().rank(queueKey(lectureId), m);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        //너무 자주 찍히는 API라서 "느릴 때만" 경고 로그
        if (ms >= SLOW_REDIS_MS) {
            // log는 EnrollService만 쓰고 싶으면 여기 로그는 빼도 됨.
            // 하지만 병목 찾기에는 이게 진짜 유용함.
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=enqueue lectureId={} userId={} tookMs={}", lectureId, userId, ms);
        }

        return rank != null ? rank + 1 : null;
    }

    //현재 내 순번 조회만
    public Long getPosition(Long lectureId, Long userId) {
        String m = member(userId);

        long t0 = System.nanoTime();
        Long rank = redisTemplate.opsForZSet().rank(queueKey(lectureId), m);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=getPosition lectureId={} userId={} tookMs={}", lectureId, userId, ms);
        }

        return rank != null ? rank + 1 : null;
    }

    // 총 몇명(총 원소의 개수)
    public Long getTotal(Long lectureId) {
        long t0 = System.nanoTime();
        Long total = redisTemplate.opsForZSet().zCard(queueKey(lectureId));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=getTotal lectureId={} tookMs={}", lectureId, ms);
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
    public long claimAdmitted(Long lectureId, Long userId) {
        String key = admittedKey(lectureId, userId);

        long t0 = System.nanoTime();
        Long ttl = redisTemplate.execute(
                claimAdmittedScript,
                Collections.singletonList(key)
        );
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // claim은 결제/신청 직전에만 호출되니, 느릴 때만 경고
        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=claim key={} lectureId={} userId={} tookMs={} ttl={}",
                            key, lectureId, userId, ms, ttl);
        }

        return ttl == null ? -1 : ttl;
    }

    // 선점 후 DB실패 등으로 롤백이 필요할 때 admitted 복구
    public void restoreAdmitted(Long lectureId, Long userId, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            ttlSeconds = 600; // 기본 10분
        }

        String key = admittedKey(lectureId, userId);

        long t0 = System.nanoTime();
        redisTemplate.opsForValue().set(key, "1", ttlSeconds, TimeUnit.SECONDS);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=restore lectureId={} userId={} ttlSec={} tookMs={}",
                            lectureId, userId, ttlSeconds, ms);
        }
    }

    // 스케줄러 호출: queue 앞 n명 꺼내서 admitted 키 발급(원자적)
    public List<String> popAndGrantAdmitted(Long lectureId, int n, int ttlSeconds) {
        long t0 = System.nanoTime();

        @SuppressWarnings("unchecked")
        List<String> granted = (List<String>) redisTemplate.execute(
                popAndGrantScript,
                Collections.singletonList(queueKey(lectureId)),
                String.valueOf(n),
                String.valueOf(ttlSeconds),
                admittedKeyPrefix(lectureId)
        );

        long ms = (System.nanoTime() - t0) / 1_000_000;

        // 스케줄러는 반복이라 "느릴 때만" 경고 + granted size 같이
        int size = granted == null ? 0 : granted.size();
        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[QUEUE][SLOW] op=popAndGrant lectureId={} batch={} granted={} ttlSec={} tookMs={}",
                            lectureId, n, size, ttlSeconds, ms);
        }

        return granted == null ? List.of() : granted;
    }

    // 입장권 있는지 확인
    public boolean isAdmitted(Long lectureId, Long userId) {
        String key = admittedKey(lectureId, userId);

        long t0 = System.nanoTime();
        Boolean exists = redisTemplate.hasKey(key);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=isAdmitted lectureId={} userId={} tookMs={}", lectureId, userId, ms);
        }

        return Boolean.TRUE.equals(exists);
    }

    // 입장권 소모(현재는 claim에서 DEL하므로 보조용)
    public boolean consumeAdmitted(Long lectureId, Long userId) {
        String key = admittedKey(lectureId, userId);

        long t0 = System.nanoTime();
        Boolean deleted = redisTemplate.delete(key);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (ms >= SLOW_REDIS_MS) {
            org.slf4j.LoggerFactory.getLogger(QueueService.class)
                    .warn("[ADMITTED][SLOW] op=consume lectureId={} userId={} tookMs={} deleted={}", lectureId, userId, ms, deleted);
        }

        return Boolean.TRUE.equals(deleted);
    }
}
