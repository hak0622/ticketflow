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

@RequiredArgsConstructor
@Service
public class QueueService {
    private final StringRedisTemplate redisTemplate;
    private final LectureRepository lectureRepository;

    //대기열 키
    private String queueKey(Long lectureId){
        return "queue:lecture:" + lectureId;
    }

    private String member(Long userId){
        return String.valueOf(userId);
    }

    private String admittedKey(Long lectureId, Long userId){
        return "admitted:lecture:" + lectureId + ":user:" + userId;
    }

    private String admittedKeyPrefix(Long lectureId){
        return "admitted:lecture:" + lectureId + ":user:";
    }

    //오픈 전/마감/정원 마감 상태에서 큐 등록 자체 차단
    private void validateQueueAvailable(Lecture lecture){
        //CLOSED/SOLD_OUT이면 큐 자체 불가
        if(lecture.getStatus() == LectureStatus.CLOSED){
            throw new IllegalStateException("마감된 강의입니다.");
        }
        if(lecture.getStatus() == LectureStatus.SOLD_OUT){
            throw new IllegalStateException("정원이 마감된 강의입니다.");
        }
        LocalDateTime openAt = lecture.getOpenAt();
        if(openAt != null && LocalDateTime.now().isBefore(openAt)){
            throw new IllegalStateException("아직 오픈 전입니다.");
        }
    }

    //줄서기 등록
    public Long enqueue(Long lectureId,Long userId){
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + lectureId));
        validateQueueAvailable(lecture);

        long score = Instant.now().toEpochMilli();
        String m = member(userId);

        //대기열 등록
        redisTemplate.opsForZSet().add(queueKey(lectureId),m,score);

        //내 순번 조회
        Long rank = redisTemplate.opsForZSet().rank(queueKey(lectureId),m);

        return rank != null ? rank + 1 : null;
    }

    //현재 내 순번 조회만
    public Long getPosition(Long lectureId,Long userId){
        String m = member(userId);
        Long rank = redisTemplate.opsForZSet().rank(queueKey(lectureId), m);
        return rank != null? rank + 1 : null;
    }

    // 총 몇명(총 원소의 개수)
    public Long getTotal(Long lectureId){
        return redisTemplate.opsForZSet().zCard(queueKey(lectureId));
    }

    // Lua 스크립트
    private static final String POP_AND_GRANT_LUA = """
    local queueKey = KEYS[1]
    local count = tonumber(ARGV[1])
    local ttl = tonumber(ARGV[2])
    local admittedPrefix = ARGV[3]

    -- 앞에서 count명 가져오기
    local users = redis.call('ZRANGE', queueKey, 0, count - 1)
    if (#users == 0) then
      return users
    end

    -- 가져온 users를 queue에서 제거
    redis.call('ZREM', queueKey, unpack(users))

    -- 각 유저에게 admitted 키 발급 + TTL
    for i = 1, #users do
      local userId = users[i]
      local key = admittedPrefix .. userId
      redis.call('SETEX', key, ttl, '1')
    end

    return users
    """;

    private final DefaultRedisScript<List>popAndGrantScript = new DefaultRedisScript<>(POP_AND_GRANT_LUA,List.class);

    //스케줄러가 호출 : queue 앞 n 명 꺼내서 admitted 키를 TTL과 함께 발급(원자적)
    public List<String>popAndGrantAdmitted(Long lectureId,int n,int ttlSeconds){
        @SuppressWarnings("unchecked")
        List<String>granted = (List<String>) redisTemplate.execute(
                popAndGrantScript,
                Collections.singletonList(queueKey(lectureId)),
                String.valueOf(n),
                String.valueOf(ttlSeconds),
                admittedKeyPrefix(lectureId)
        );

        return granted == null ? List.of() : granted;
    }

    //입장권 있는지 확인
    public boolean isAdmitted(Long lectureId,Long userId){
        Boolean exists = redisTemplate.hasKey(admittedKey(lectureId, userId));
        return Boolean.TRUE.equals(exists);
    }

    //입장권 소모
    public boolean consumeAdmitted(Long lectureId, Long userId){
        Boolean deleted = redisTemplate.delete(admittedKey(lectureId, userId));
        return Boolean.TRUE.equals(deleted);
    }
}
