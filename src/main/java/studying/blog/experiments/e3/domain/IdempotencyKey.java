package studying.blog.experiments.e3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 전략 A용: event_id를 PK로 사용 → INSERT 시 중복이면 DataIntegrityViolationException
 */
@Entity
@NoArgsConstructor
public class IdempotencyKey {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public IdempotencyKey(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}
