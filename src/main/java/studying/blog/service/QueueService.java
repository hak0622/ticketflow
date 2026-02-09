package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

import static java.time.Duration.*;
import static java.util.stream.Collectors.*;

@RequiredArgsConstructor
@Service
public class QueueService {
    private final StringRedisTemplate redisTemplate;

    private String key(Long lectureId){
        return "queue:lecture:" + lectureId;
    }

    public Long enqueue(Long lectureId,String userKey){
        long score = Instant.now().toEpochMilli();

        //대기열 등록
        redisTemplate.opsForZSet().add(key(lectureId),userKey,score);

        //내 순번 조회
        Long rank = redisTemplate.opsForZSet().rank(key(lectureId),userKey);

        return rank != null ? rank + 1 : null;
    }

    public Long getPosition(Long lectureId,String userKey){
        Long rank = redisTemplate.opsForZSet().rank(key(lectureId), userKey);
        return rank != null? rank + 1 : null;
    }

    public Long getTotal(Long lectureId){
        return redisTemplate.opsForZSet().zCard(key(lectureId));
    }

    //대기열 앞에서 n명 제거(입장 처리)
    public Set<String> popFront(Long lectureId, int n){
        Set<String> popped = redisTemplate.opsForZSet().popMin(key(lectureId), n)
                .stream()
                .map(tuple -> tuple.getValue())
                .collect(toSet());
        return popped;
    }

    private String admittedKey(Long lectureId){
        return "admitted:lecture:" + lectureId;
    }

    public void markAdmitted(Long lectureId,Set<String>userKeys){
        if(userKeys == null || userKeys.isEmpty()){
            return;
        }
        String key = admittedKey(lectureId);

        redisTemplate.opsForSet().add(key,userKeys.toArray(new String[0]));

        redisTemplate.expire(key, ofMinutes(5));
    }

    public boolean isAdmitted(Long lectureId,String userKey){
        Boolean member = redisTemplate.opsForSet().isMember(admittedKey(lectureId),userKey);
        return Boolean.TRUE.equals(member);
    }

    public void consumeAdmitted(Long lectureId, String userKey){
        redisTemplate.opsForSet().remove(admittedKey(lectureId),userKey);
    }
}
