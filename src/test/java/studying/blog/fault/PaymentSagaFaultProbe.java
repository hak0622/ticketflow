package studying.blog.fault;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import studying.blog.domain.BookingAttempt;
import studying.blog.repository.PaymentRepository;
import studying.blog.service.TossPaymentClient;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Added only to the disposable fault JAR; never included in the production bootJar. */
@Configuration(proxyBeanMethods = false)
@Profile("payment-saga-fault")
public class PaymentSagaFaultProbe {
    private static final Path ROOT = Path.of("/fault");
    private static final String INSTANCE = System.getenv("FAULT_INSTANCE");
    private static final ThreadLocal<String> ORIGIN = new ThreadLocal<>();

    @Bean static BeanPostProcessor localPgOnly() {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (!(bean instanceof TossPaymentClient)) return bean;
                try {
                    Field field = TossPaymentClient.class.getDeclaredField("restClient");
                    field.setAccessible(true);
                    RestClient original = (RestClient) field.get(bean);
                    field.set(bean, original.mutate().requestInterceptor((request, body, execution) -> {
                        if (!"api.tosspayments.com".equals(request.getURI().getHost()))
                            throw new IllegalStateException("Unexpected PG destination");
                        return execution.execute(new HttpRequestWrapper(request) {
                            @Override public URI getURI() {
                                return URI.create("http://pg:5000" + request.getURI().getRawPath());
                            }
                            @Override public HttpHeaders getHeaders() {
                                HttpHeaders headers = new HttpHeaders();
                                headers.putAll(request.getHeaders());
                                headers.set("X-Fault-Instance", INSTANCE);
                                return headers;
                            }
                        }, body);
                    }).build());
                } catch (Exception failure) { throw new IllegalStateException("Fail closed: PG redirect unavailable", failure); }
                return bean;
            }
        };
    }

    @Bean Probe paymentProbe(PaymentRepository payments) { return new Probe(payments); }

    private static synchronized void event(String key, String phase, String detail) throws Exception {
        Files.writeString(ROOT.resolve("events-" + INSTANCE + ".tsv"),
                System.currentTimeMillis() + "\t" + INSTANCE + "\t" + phase + "\t" + key + "\t"
                        + Thread.currentThread().getName() + "\t" + detail + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void boundary(String key, String phase) throws Exception {
        event(key, phase, "reached");
        Path gate = ROOT.resolve(key + "-" + phase + "-" + INSTANCE);
        if (!Files.exists(Path.of(gate + ".arm"))) return;
        try { Files.writeString(Path.of(gate + ".claimed"), "claimed", StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException alreadyClaimed) { return; }
        event(key, "paused_" + phase, "gate");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(phase.startsWith("lock_") ? 5 : 240);
        while (!Files.exists(Path.of(gate + ".resume"))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Fault gate timed out: " + phase);
            Thread.sleep(10);
        }
        event(key, "resumed_" + phase, "gate");
    }

    @Aspect
    public static class Probe {
        private final PaymentRepository payments;
        public Probe(PaymentRepository payments) { this.payments = payments; }
        private String bookingKey(Long bookingId) throws Exception {
            Path path = ROOT.resolve("booking-" + bookingId + ".key");
            return Files.exists(path) ? Files.readString(path).trim() : null;
        }
        @Around("execution(* studying.blog.service.TossPaymentClient.approve(..)) && args(key,order,amount,token)")
        public Object approval(ProceedingJoinPoint call, String key, String order, Integer amount, String token) throws Throwable {
            Object result = call.proceed();
            boundary(key, "after_approve");
            return result;
        }
        @Around("execution(* studying.blog.service.TossPaymentClient.lookup(..)) && args(key)")
        public Object lookup(ProceedingJoinPoint call, String key) throws Throwable {
            Object result;
            try { result = call.proceed(); }
            catch (Throwable failure) {
                boundary(key, "failed_lookup");
                throw failure;
            }
            boundary(key, "after_lookup");
            return result;
        }
        @Around("execution(* studying.blog.repository.PaymentRepository.findRecoveryDue(..))")
        public Object selected(ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (result instanceof java.util.List<?> rows) {
                event("global", "selected", rows.stream()
                        .map(row -> String.valueOf(((studying.blog.domain.Payment) row).getId()))
                        .collect(java.util.stream.Collectors.joining(",")));
            }
            return result;
        }
        @Around("execution(* studying.blog.service.TossPaymentClient.cancel(..)) && args(key,reason,amount,token)")
        public Object cancellation(ProceedingJoinPoint call, String key, String reason, Integer amount, String token) throws Throwable {
            boundary(key, "before_cancel");
            Object result = call.proceed();
            boundary(key, "after_cancel");
            return result;
        }
        @Around("execution(* studying.blog.repository.PaymentCompensationOutboxRepository.findByStatus(..))")
        public Object outboxScan(ProceedingJoinPoint call) throws Throwable {
            Object result=call.proceed();event("global","outbox_scan","actual candidate query");return result;
        }
        @Around("execution(* studying.blog.scheduler.PaymentRecoveryWorker.recover())")
        public Object scan(ProceedingJoinPoint call) throws Throwable {
            Path blocked = ROOT.resolve("scan-" + INSTANCE + ".block");
            if (Files.exists(blocked)) event("global", "scan_paused", "before candidate query");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
            while (Files.exists(blocked)) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Scan gate timed out");
                Thread.sleep(20);
            }
            event("global", "scan_start", "scheduled");
            try { return call.proceed(); }
            finally { event("global", "scan_end", "scheduled"); }
        }
        @Around("execution(* studying.blog.service.PaymentSaga.recover(..)) && args(paymentId,round)")
        public Object worker(ProceedingJoinPoint call, Long paymentId, studying.blog.service.PaymentRetryRound round) throws Throwable {
            String key = payments.findById(paymentId).orElseThrow().getPgPaymentKey();
            if (key == null) return call.proceed();
            ORIGIN.set("worker");
            event(key, "selected_round", round.retryCount() + "," + round.nextRetryAt() + "," + round.recoveryPhase() + "," + round.version());
            try { boundary(key, "worker_start"); Object result = call.proceed(); event(key,"worker_end","automatic"); return result; }
            finally { ORIGIN.remove(); }
        }
        @Around("execution(* studying.blog.service.BookingAttemptTransactions.cancel(..))")
        public Object cancelBooking(ProceedingJoinPoint call) throws Throwable {
            Long bookingId = (Long) call.getArgs()[0];
            String key = bookingKey(bookingId);
            if (key == null) return call.proceed();
            String kind = call.getArgs().length == 2 && call.getArgs()[1] != null ? "expiry" : "outbox";
            ORIGIN.set(kind);
            try {
                boundary(key, kind);
                Object result = call.proceed(); event(key,kind+"_end","complete"); return result;
            } catch (studying.blog.exception.PaymentDeferredException deferred) {
                event(key,kind+"_deferred","protected"); throw deferred;
            } finally { ORIGIN.remove(); }
        }
        @Around("execution(* studying.blog.repository.BookingRepository.findLockedById(..)) && args(bookingId)")
        public Object bookingLock(ProceedingJoinPoint call, Long bookingId) throws Throwable {
            Object result = call.proceed();
            String key = bookingKey(bookingId);
            if (key != null && result instanceof Optional<?> row && row.isPresent())
                boundary(key, "lock_" + (ORIGIN.get() == null ? "http" : ORIGIN.get()));
            return result;
        }
        @Around("execution(* studying.blog.service.BookingSeatStore.release(..)) && args(attempt)")
        public Object released(ProceedingJoinPoint call, BookingAttempt attempt) throws Throwable {
            Object result = call.proceed();
            String key = bookingKey(attempt.getBookingId());
            if (key != null) event(key,"release_result",String.valueOf(result));
            return result;
        }
    }
}
