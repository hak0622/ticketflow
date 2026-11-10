package studying.blog.fault;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import studying.blog.domain.BookingAttempt;
import java.util.concurrent.*;

/** Only pauses after the REAL reserve Lua. It never substitutes a service/DB/Redis result. */
@Aspect
public class BookingBoundaryGate {
    private volatile String requestId;
    private volatile Runnable afterReserve;
    private volatile CountDownLatch reached = new CountDownLatch(1);
    private volatile CountDownLatch resume = new CountDownLatch(0);
    public void arm(String id, Runnable action, boolean pause) {
        requestId = id; afterReserve = action;
        reached = new CountDownLatch(1); resume = new CountDownLatch(pause ? 1 : 0);
    }
    public boolean await(long seconds) throws InterruptedException { return reached.await(seconds, TimeUnit.SECONDS); }
    public void resume() { resume.countDown(); }
    @Around("execution(* studying.blog.service.BookingSeatStore.reserve(..)) && args(attempt)")
    public Object reserve(ProceedingJoinPoint call, BookingAttempt attempt) throws Throwable {
        Object result = call.proceed();
        if (attempt.getId().equals(requestId)) {
            Runnable action = afterReserve;
            if (action != null) action.run();
            reached.countDown();
            if (!resume.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test boundary gate timed out");
        }
        return result;
    }
}
