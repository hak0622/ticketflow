package studying.blog.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import studying.blog.exception.MockPaymentDisabledException;
import studying.blog.repository.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PaymentMockPolicyTest {
    @ParameterizedTest
    @CsvSource({"docker,false", "local,false", "test,false", "prod,false", "prod,true"})
    void disabledOrProdRejectsBeforeAnyDependencyCall(String profile, boolean enabled) {
        var payments = mock(PaymentRepository.class);
        var bookings = mock(BookingRepository.class);
        var concerts = mock(ConcertRepository.class);
        var outboxes = mock(PaymentCompensationOutboxRepository.class);
        var redis = mock(StringRedisTemplate.class);
        var toss = mock(TossPaymentClient.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        var service = new PaymentService(payments, bookings, concerts, outboxes, redis,
                new ObjectMapper(), new SimpleMeterRegistry(), toss, environment, mock(PaymentSaga.class));
        ReflectionTestUtils.setField(service, "mockEnabled", enabled);
        assertThatThrownBy(() -> service.pay(1L, 1L, null)).isInstanceOf(MockPaymentDisabledException.class);
        verifyNoInteractions(payments, bookings, concerts, outboxes, redis, toss);
    }
}
