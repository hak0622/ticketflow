package studying.blog.scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import studying.blog.repository.PaymentRepository;
import studying.blog.service.PaymentSaga;
import java.time.LocalDateTime;
@Component @RequiredArgsConstructor @Slf4j
public class PaymentRecoveryWorker {
    private final PaymentRepository payments;
    private final PaymentSaga saga;
    private final io.micrometer.core.instrument.MeterRegistry registry;
    @jakarta.annotation.PostConstruct void metrics() {
        registry.gauge("payment.recovery.pending",payments,p -> p.countByProviderAndStatus("TOSS",studying.blog.domain.PaymentStatus.PENDING));
        registry.gauge("payment.recovery.review",payments,p -> p.countByProviderAndStatusAndReviewRequiredTrue("TOSS",studying.blog.domain.PaymentStatus.PENDING));
        registry.gauge("payment.recovery.oldest_seconds",payments,p -> p.findFirstByProviderAndStatusOrderByCreatedAtAsc("TOSS",studying.blog.domain.PaymentStatus.PENDING)
            .map(x -> (double)Math.max(0,java.time.Duration.between(x.getCreatedAt(),LocalDateTime.now()).getSeconds())).orElse(0.0));
    }
    @Scheduled(fixedDelay=5000)
    public void recover() {
        for(var p:payments.findRecoveryDue(LocalDateTime.now(),PageRequest.of(0,100))) {
            try { saga.recover(p.getId(), studying.blog.service.PaymentRetryRound.from(p)); }
            catch(RuntimeException e) { log.warn("[PAYMENT_RECOVERY] id={}",p.getId(),e); }
        }
    }
}
