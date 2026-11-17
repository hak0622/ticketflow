package studying.blog.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** Deadline for accepting a new payment, not for finishing an accepted Saga. */
@Component
public class PaymentDeadlinePolicy {
    public static final Duration WINDOW = Duration.ofMinutes(10);
    private final Clock clock;

    public PaymentDeadlinePolicy(Clock clock) { this.clock = clock; }
    public LocalDateTime cutoff() { return LocalDateTime.now(clock).minus(WINDOW); }
    public boolean expired(LocalDateTime createdAt) { return !createdAt.isAfter(cutoff()); }
}
