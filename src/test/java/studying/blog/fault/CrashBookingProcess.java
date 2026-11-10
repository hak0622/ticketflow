package studying.blog.fault;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import studying.blog.BlogApplication;
import studying.blog.service.BookingService;
import java.util.concurrent.CountDownLatch;

/** Separate application JVM killed by the parent test while its booking transaction is open. */
public final class CrashBookingProcess {
    static class ProbeConfig {
        @Bean BookingBoundaryGate boundaryGate() { return new BookingBoundaryGate(); }
    }
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(BlogApplication.class, ProbeConfig.class)
                .profiles("test").run();
        String requestId = System.getenv("FAULT_REQUEST_ID");
        context.getBean(BookingBoundaryGate.class).arm(requestId, () -> {
            System.out.println("FAULT_PROCESS_AFTER_REDIS " + requestId);
            System.out.flush();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, false);
        context.getBean(BookingService.class).book(Long.valueOf(System.getenv("FAULT_CONCERT_ID")), 1L, requestId);
        throw new IllegalStateException("Process was expected to be killed during booking");
    }
}
