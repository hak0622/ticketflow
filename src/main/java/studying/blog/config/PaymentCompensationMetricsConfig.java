package studying.blog.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import studying.blog.domain.OutboxStatus;
import studying.blog.repository.PaymentCompensationOutboxRepository;

@Configuration
public class PaymentCompensationMetricsConfig {

    public PaymentCompensationMetricsConfig(
            MeterRegistry meterRegistry,
            PaymentCompensationOutboxRepository outboxRepository
    ) {
        Gauge.builder(
                        "outbox.failed.current",
                        outboxRepository,
                        repository -> repository.countByStatus(OutboxStatus.FAILED)
                )
                .description("Current number of payment compensation outboxes awaiting operator action")
                .register(meterRegistry);
    }
}
