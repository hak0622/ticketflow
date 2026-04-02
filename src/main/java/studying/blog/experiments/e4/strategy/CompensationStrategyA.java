package studying.blog.experiments.e4.strategy;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 전략 A: 즉시 보상 이벤트 발행 (fire-and-forget)
 *
 * 결제 실패 catch 블록에서 바로 보상 로직 호출.
 * Kafka 발행 or 외부 호출이 실패하면 보상 자체가 유실된다.
 *
 * 실험에서는 N번째 호출마다 강제로 실패시켜 유실 시나리오를 재현한다.
 */
@Service
public class CompensationStrategyA {

    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * @param failEveryN N번째 호출마다 실패 시뮬레이션 (0이면 항상 성공)
     * @param logic      실제 보상 비즈니스 로직
     */
    public void compensate(int failEveryN, Runnable logic) {
        int n = callCount.incrementAndGet();
        if (failEveryN > 0 && n % failEveryN == 0) {
            // Kafka 발행 실패 or 네트워크 오류 시뮬레이션
            throw new RuntimeException("보상 이벤트 발행 실패 [call=" + n + "]");
        }
        logic.run();
    }

    public void reset() {
        callCount.set(0);
    }
}
