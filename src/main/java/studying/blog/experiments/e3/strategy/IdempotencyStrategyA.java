package studying.blog.experiments.e3.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e3.domain.IdempotencyKey;
import studying.blog.experiments.e3.repository.IdempotencyKeyRepository;

/**
 * 전략 A: DB PK(event_id) INSERT → 중복이면 DataIntegrityViolationException
 * - 장점: SELECT 없이 INSERT 한 번으로 원자적 처리
 * - 단점: 예외 처리 필요, 이벤트 보관 기간 관리 필요
 */
@Service
@RequiredArgsConstructor
public class IdempotencyStrategyA {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Transactional
    public boolean tryAcquire(String eventId) {
        try {
            idempotencyKeyRepository.saveAndFlush(new IdempotencyKey(eventId));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
