package studying.blog.experiments.e3.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e3.domain.ProcessedEvent;
import studying.blog.experiments.e3.repository.ProcessedEventRepository;

/**
 * 전략 B: SELECT EXISTS → INSERT (Check-then-Act)
 * - 장점: 명시적으로 처리 여부 확인 가능, 디버깅 용이
 * - 단점: SELECT-INSERT 사이 TOCTOU 레이스 컨디션 존재
 *         → UNIQUE 제약으로 최종 방어하지만 예외 발생 가능
 */
@Service
@RequiredArgsConstructor
public class IdempotencyStrategyB {

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public boolean tryAcquire(String eventId) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return false;
        }
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(eventId));
            return true;
        } catch (DataIntegrityViolationException e) {
            // TOCTOU: 두 스레드가 동시에 SELECT → 둘 다 not exists → 먼저 INSERT한 쪽만 성공
            return false;
        }
    }
}
