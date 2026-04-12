package studying.blog.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 설정 — 멀티 인스턴스 환경에서 스케줄러 중복 실행 방지.
 * 기존 Redis를 LockProvider로 재사용하므로 별도 인프라 추가 없음.
 *
 * defaultLockAtMostFor: 인스턴스가 죽었을 때 락이 해제되는 안전망 타임아웃.
 * 각 스케줄러에서 메서드 단위로 override한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }
}
