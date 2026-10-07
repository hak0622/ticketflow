package studying.blog.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.repository.ConcertRepository;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Actual Redis verification; H2 does not validate SET NX semantics. */
@Tag("integration")
@ActiveProfiles("test")
class SeatCountRedisInitializationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private QueueService queue;
    private SimpleMeterRegistry metrics;
    private Long concertId;
    private String key;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        metrics = new SimpleMeterRegistry();
        queue = new QueueService(redis, mock(ConcertRepository.class), metrics);
        concertId = UUID.randomUUID().getMostSignificantBits();
        key = "seats:concert:" + concertId;
    }

    @AfterEach
    void cleanUp() {
        try {
            redis.delete(key);
        } finally {
            factory.destroy();
            metrics.close();
        }
    }

    @Test
    void missingKeyIsCreatedWithoutExpiryAndRepeatedInitializationPreservesQuantity() {
        assertThat(queue.initSeatCountIfAbsent(concertId, 5)).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("5");
        assertThat(redis.getExpire(key)).isEqualTo(-1L);

        redis.opsForValue().decrement(key);
        assertThat(queue.initSeatCountIfAbsent(concertId, 10)).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("4");
    }

    @Test
    void existingZeroQuantityAndExpiryArePreserved() {
        redis.opsForValue().set(key, "0", Duration.ofMinutes(5));
        assertThat(queue.initSeatCountIfAbsent(concertId, 10)).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("0");
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
    }
}
