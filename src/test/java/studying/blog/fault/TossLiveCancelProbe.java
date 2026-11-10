package studying.blog.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import studying.blog.domain.BookingAttempt;
import studying.blog.dto.TossConfirmRequest;
import studying.blog.repository.PaymentRepository;
import studying.blog.service.PaymentRetryRound;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** Test JAR only. Observes real Toss HTTP; never redirects or replaces PG results. */
@Aspect
@Component
@Profile("toss-live-cancel")
public class TossLiveCancelProbe {
    private final PaymentRepository payments;
    private final ObjectMapper json;
    private final Path root = Path.of("/fault-live");
    private final long concertId = Long.parseLong(System.getenv("FAULT_LIVE_CONCERT_ID"));
    private volatile String targetPaymentKey;
    private final ThreadLocal<Boolean> initialApproval = new ThreadLocal<>();

    public TossLiveCancelProbe(PaymentRepository payments, ObjectMapper json) {
        if (!System.getenv("TOSS_SECRET_KEY").startsWith("test_")) {
            throw new IllegalStateException("Real PG probe requires a test key");
        }
        this.payments = payments;
        this.json = json;
    }

    private boolean target(String order) {
        return order.startsWith("dev-order-TICKETFLOW-" + concertId + "-");
    }

    private synchronized void event(String phase, Object value) throws Exception {
        Files.writeString(root.resolve("events.ndjson"), json.writeValueAsString(java.util.Map.of(
                "timeMs", System.currentTimeMillis(), "phase", phase,
                "thread", Thread.currentThread().getName(), "value", String.valueOf(value))) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void await(String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (!Files.exists(root.resolve(name))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Live test gate timeout: " + name);
            Thread.sleep(20);
        }
    }

    @Around("execution(* studying.blog.service.TossPaymentClient.approve(..)) && args(key,order,amount,token)")
    public Object approval(ProceedingJoinPoint call, String key, String order, Integer amount, String token) throws Throwable {
        if (!target(order)) return call.proceed();
        if (amount != 1) throw new IllegalStateException("Live cancellation fixture requires one won");
        targetPaymentKey = key;
        initialApproval.set(true);
        event("approve_http_start", order);
        Object result = call.proceed();
        Files.writeString(root.resolve("approve-result.json"), json.writeValueAsString(result));
        event("approve_http_returned", order);
        await("approval.resume");
        event("old_approval_delivered", order);
        return result;
    }

    @Around("execution(* studying.blog.service.PaymentSaga.confirm(..)) && args(cid,uid,request)")
    public Object confirmation(ProceedingJoinPoint call, Long cid, Long uid, TossConfirmRequest request) throws Throwable {
        if (cid != concertId) return call.proceed();
        try {
            Object result = call.proceed();
            if (Boolean.TRUE.equals(initialApproval.get())) {
                // confirm() returned only after applyResult and the response transaction finished.
                event("original_confirm_completed", json.writeValueAsString(result));
                Files.writeString(root.resolve("original-confirm-result.tmp"), json.writeValueAsString(result));
                Files.move(root.resolve("original-confirm-result.tmp"), root.resolve("original-confirm-result.json"),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            return result;
        } finally {
            initialApproval.remove();
        }
    }

    @Around("execution(* studying.blog.service.PaymentSaga.recover(..)) && args(paymentId,round)")
    public Object worker(ProceedingJoinPoint call, Long paymentId, PaymentRetryRound round) throws Throwable {
        var payment = payments.findById(paymentId).orElseThrow();
        if (payment.getConcertId() != concertId) return call.proceed();
        event("automatic_worker_selected", paymentId);
        await("worker.resume");
        return call.proceed();
    }

    @Around("execution(* studying.blog.service.TossPaymentClient.cancel(..)) && args(key,reason,amount,token)")
    public Object cancel(ProceedingJoinPoint call, String key, String reason, Integer amount, String token) throws Throwable {
        if (!key.equals(targetPaymentKey)) {
            return call.proceed();
        }
        event("cancel_http_start", amount + ":" + reason);
        Object result = call.proceed();
        Files.writeString(root.resolve("cancel-result.json"), json.writeValueAsString(result));
        event("cancel_http_returned", key);
        await("cancel.resume");
        return result;
    }

    @Around("execution(* studying.blog.service.BookingSeatStore.release(..)) && args(attempt)")
    public Object release(ProceedingJoinPoint call, BookingAttempt attempt) throws Throwable {
        Object result = call.proceed();
        if (attempt.getConcertId() == concertId) event("release_result", result);
        return result;
    }

    @Around("execution(* studying.blog.scheduler.PaymentRecoveryWorker.recover())")
    public Object scan(ProceedingJoinPoint call) throws Throwable {
        event("automatic_scan", "scheduled");
        Object result = call.proceed();
        event("automatic_scan_completed", "scheduled");
        return result;
    }

    @Around("execution(* studying.blog.repository.PaymentCompensationOutboxRepository.findByStatus(..))")
    public Object outboxScan(ProceedingJoinPoint call) throws Throwable {
        Object result = call.proceed();
        event("automatic_outbox_query_completed", "actual candidate query");
        return result;
    }
}
