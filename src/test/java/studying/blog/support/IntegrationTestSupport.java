package studying.blog.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import studying.blog.domain.Booking;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.PaymentRequest;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;
import studying.blog.repository.PaymentCompensationOutboxRepository;
import studying.blog.repository.PaymentRepository;
import studying.blog.service.QueueService;

import java.time.LocalDateTime;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected ConcertRepository concertRepository;

    @Autowired
    protected BookingRepository bookingRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected PaymentCompensationOutboxRepository outboxRepository;

    @Autowired
    protected QueueService queueService;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected RedisConnectionFactory redisConnectionFactory;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearIntegrationState() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        bookingRepository.deleteAll();
        concertRepository.deleteAll();
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    protected Concert savedConcert(String title, int totalSeats, int bookedCount) {
        Concert concert = concertRepository.save(Concert.builder()
                .title(title)
                .totalSeats(totalSeats)
                .bookedCount(bookedCount)
                .status(bookedCount >= totalSeats ? ConcertStatus.SOLD_OUT : ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().plusDays(1))
                .price(90000)
                .build());
        queueService.initSeatCount(concert.getId(), Math.max(0, totalSeats - bookedCount));
        return concert;
    }

    protected Booking savedPendingBooking(Concert concert, Long userId) {
        return bookingRepository.save(Booking.builder()
                .concert(concert)
                .userId(userId)
                .build());
    }

    protected void grantAdmitted(Long concertId, Long userId) {
        queueService.restoreAdmitted(concertId, userId, 600L);
    }

    protected PaymentRequest paymentRequest(String idempotencyKey) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "idempotencyKey", idempotencyKey);
        ReflectionTestUtils.setField(request, "paymentMethod", "CARD");
        return request;
    }
}
