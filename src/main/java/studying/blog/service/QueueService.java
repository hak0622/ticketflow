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

    //대기열 키
    private String key(Long lectureId){
        return "queue:lecture:" + lectureId;
    }

    private String member(Long userId){
        return String.valueOf(userId);
    }

    //줄서기 등록
    public Long enqueue(Long lectureId,Long userId){
        long score = Instant.now().toEpochMilli();
        String m = member(userId);

        //대기열 등록
        redisTemplate.opsForZSet().add(key(lectureId),m,score);

        //내 순번 조회
        Long rank = redisTemplate.opsForZSet().rank(key(lectureId),m);

        return rank != null ? rank + 1 : null;
    }

    //현재 내 순번 조회만
    public Long getPosition(Long lectureId,Long userId){
        String m = member(userId);
        Long rank = redisTemplate.opsForZSet().rank(key(lectureId), m);
        return rank != null? rank + 1 : null;
    }

    // 총 몇명(총 원소의 개수)
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

    //입장권 키
    private String admittedKey(Long lectureId){
        return "admitted:lecture:" + lectureId;
    }

    public void markAdmitted(Long lectureId,Set<String>userIdsAsString){
        if(userIdsAsString == null || userIdsAsString.isEmpty()){
            return;
        }
        String key = admittedKey(lectureId);

        redisTemplate.opsForSet().add(key,userIdsAsString.toArray(new String[0]));

        //시간 제한 5분
        redisTemplate.expire(key, ofMinutes(5));
    }

    //입장권 있는지 확인
    public boolean isAdmitted(Long lectureId,Long userId){
        String m = member(userId);
        Boolean member = redisTemplate.opsForSet().isMember(admittedKey(lectureId),m);
        return Boolean.TRUE.equals(member);
    }

    //입장권 소모
    public void consumeAdmitted(Long lectureId, Long userId){
        String m = member(userId);
        redisTemplate.opsForSet().remove(admittedKey(lectureId),m);
    }
}
