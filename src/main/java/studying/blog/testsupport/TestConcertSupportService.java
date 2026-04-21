package studying.blog.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Concert;
import studying.blog.domain.User;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;
import studying.blog.repository.PaymentRepository;
import studying.blog.testsupport.dto.TestResetResponse;
import studying.blog.testsupport.dto.TestSetupResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public class TestConcertSupportService {

    private final TestUserSeedService testUserSeedService;
    private final ConcertRepository concertRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentCompensationOutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 테스트 직전 호출. 기존 상태를 초기화한 뒤 mode에 따라 admitted 키를 발급한다.
     * - QUEUE  (기본값): 상태 초기화만. 대기열/폴링 테스트에 사용.
     * - BOOKING: 초기화 + 전체 유저 admitted 키 발급. 예매 burst 테스트에 사용.
     */
    @Transactional
    public TestSetupResponse setup(Long concertId, TestSetupMode mode, int ttlSeconds) {
        Concert concert = findConcert(concertId);
        cleanupInternal(concert);

        int admittedCount = 0;
        if (mode == TestSetupMode.BOOKING) {
            List<User> users = testUserSeedService.seedUsers();
            for (User user : users) {
                redisTemplate.opsForValue().set(admittedKey(concertId, user.getId()), "1", Duration.ofSeconds(ttlSeconds));
            }
            admittedCount = users.size();
        }

        redisTemplate.opsForValue().set(seatsKey(concertId), String.valueOf(concert.getRemainingSeats()));
        redisTemplate.delete(statusKey(concertId));

        return TestSetupResponse.of(admittedCount);
    }

    /**
     * 테스트 후 호출. booking/payment/outbox 및 Redis 상태를 전부 초기화한다.
     */
    @Transactional
    public TestResetResponse reset(Long concertId) {
        Concert concert = findConcert(concertId);
        int deletedCount = cleanupInternal(concert);
        return TestResetResponse.of(deletedCount, deletedCount);
    }

    /**
     * 삭제 순서: Outbox → Payment (FK→Booking) → Booking → Concert 복구 → Redis 정리
     * Outbox는 payload의 concertId 필드로 스코프를 제한한다.
     */
    private int cleanupInternal(Concert concert) {
        Long concertId = concert.getId();

        outboxRepository.deleteAllByConcertId(concertId);
        paymentRepository.deleteAllByConcertId(concertId);

        int deletedCount = bookingRepository.countByConcertId(concertId);
        bookingRepository.deleteAllByConcertId(concertId);

        concert.decreaseBookedBulk(deletedCount);

        redisTemplate.delete(queueKey(concertId));
        deleteAdmittedKeys(concertId);
        redisTemplate.delete(seatsKey(concertId));
        redisTemplate.delete(statusKey(concertId));

        return deletedCount;
    }

    private void deleteAdmittedKeys(Long concertId) {
        String pattern = "admitted:concert:" + concertId + ":user:*";
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(200).build();

        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(opts)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Concert findConcert(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new IllegalArgumentException("Concert not found: " + concertId));
    }

    private String admittedKey(Long concertId, Long userId) {
        return "admitted:concert:" + concertId + ":user:" + userId;
    }

    private String queueKey(Long concertId) {
        return "queue:concert:" + concertId;
    }

    private String seatsKey(Long concertId) {
        return "seats:concert:" + concertId;
    }

    private String statusKey(Long concertId) {
        return "concert:status:" + concertId;
    }
}
