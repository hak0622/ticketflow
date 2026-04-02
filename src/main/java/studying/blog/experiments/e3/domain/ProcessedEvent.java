package studying.blog.experiments.e3.domain;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 전략 B용: SELECT 후 INSERT 방식 (TOCTOU 레이스 컨디션 노출 목적)
 */
@Entity
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_processed_event_id", columnNames = "event_id"))
public class ProcessedEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}
