package studying.blog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentClockConfig {
    @Bean
    public Clock paymentDeadlineClock() { return Clock.systemDefaultZone(); }
}
