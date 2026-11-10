package studying.blog.fault;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import studying.blog.domain.BookingAttempt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;

/** Included ONLY in the locally assembled fault-test JAR, never the production bootJar. */
@Configuration(proxyBeanMethods = false)
@Profile("local-three-fault")
public class LocalThreeFaultProbe {
    @Bean Probe localThreeProbe() { return new Probe(); }

    @Aspect
    public static class Probe {
        private final Path root = Path.of("/fault");
        private final String instance = System.getenv("FAULT_INSTANCE");

        private synchronized void event(String phase, String id, String detail) throws Exception {
            String row = System.currentTimeMillis() + "\t" + instance + "\t" + phase + "\t" + id
                    + "\t" + Thread.currentThread().getName() + "\t" + detail + "\n";
            Files.writeString(root.resolve("events-" + instance + ".tsv"), row,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.print("LOCAL_THREE_FAULT\t" + row);
        }

        private void boundary(String phase, String id) throws Exception {
            event(phase, id, "reached");
            Path armed = root.resolve(id + "-" + phase + ".arm");
            if (!Files.exists(armed)) return;
            String target = Files.readString(armed).trim();
            if (!target.equals("any") && !target.equals(instance)) return;
            try {
                Files.writeString(root.resolve(id + "-" + phase + ".claimed"), instance,
                        StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException alreadyClaimed) { return; }
            event("paused_" + phase, id, "gate");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(150);
            while (!Files.exists(root.resolve(id + "-" + phase + ".resume"))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Fault gate timed out: " + phase);
                Thread.sleep(25);
            }
            event("resumed_" + phase, id, "gate");
        }

        @Around("execution(* studying.blog.service.BookingSeatStore.reserve(..)) && args(attempt)")
        public Object reserve(ProceedingJoinPoint call, BookingAttempt attempt) throws Throwable {
            Object result = call.proceed();
            boundary("after_reserve", attempt.getId());
            return result;
        }

        @Around("execution(* studying.blog.service.BookingAttemptTransactions.execute(..)) && args(id,concert,user)")
        public Object execute(ProceedingJoinPoint call, String id, Long concert, Long user) throws Throwable {
            boundary("before_execute", id);
            Object result = call.proceed(); // TransactionTemplate has already committed here.
            boundary("after_commit", id);
            return result;
        }

        @Around("execution(* studying.blog.service.BookingSeatStore.release(..)) && args(attempt)")
        public Object release(ProceedingJoinPoint call, BookingAttempt attempt) throws Throwable {
            Object result = call.proceed();
            boundary("after_release", attempt.getId());
            return result;
        }

        @Around("execution(* studying.blog.service.BookingSeatReleaseService.recover(..)) && args(id)")
        public Object recover(ProceedingJoinPoint call, String id) throws Throwable {
            event("worker_start", id, "automatic");
            Object result = call.proceed();
            event("worker_end", id, "automatic");
            return result;
        }
    }
}
