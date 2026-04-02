package studying.blog.experiments.e4.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class Outbox {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    public static Outbox create(String eventType, String payload) {
        Outbox o = new Outbox();
        o.eventType  = eventType;
        o.payload    = payload;
        o.status     = OutboxStatus.PENDING;
        o.retryCount = 0;
        o.createdAt  = LocalDateTime.now();
        return o;
    }

    public void markPublished() {
        this.status      = OutboxStatus.PUBLISHED;
        this.processedAt = LocalDateTime.now();
    }

    public void incrementRetry(int maxRetry) {
        this.retryCount++;
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
