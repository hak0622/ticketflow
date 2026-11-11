package studying.blog.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_attempt", uniqueConstraints =
        @UniqueConstraint(name = "uk_attempt_booking", columnNames = "booking_id"),
        indexes = {@Index(name = "idx_attempt_recovery", columnList = "status,next_retry_at"),
                   @Index(name = "idx_attempt_concert", columnList = "concert_id")})
public class BookingAttempt implements org.springframework.data.domain.Persistable<String> {
    @Transient private boolean newEntity = true;
    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist void markPersisted() { newEntity = false; }
    @Id @Column(length = 36) private String id;
    @Column(name = "concert_id", nullable = false) private Long concertId;
    @Column(name = "user_id", nullable = false) private Long userId;
    // Scalar identifier deliberately avoids a FK cycle during booking creation.
    @Column(name = "booking_id") private Long bookingId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private BookingAttemptStatus status;
    @Column(nullable = false, length = 32) private String resultStatus;
    @Column(nullable = false) private String concertTitle;
    @Column(length = 500) private String lastError;
    @Column(nullable = false) private int retryCount;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime nextRetryAt;

    public BookingAttempt(String id, Long concertId, Long userId) {
        this.id = id;
        this.concertId = concertId;
        this.userId = userId;
        this.status = BookingAttemptStatus.REQUESTED;
        this.resultStatus = "BOOKING_PROCESSING";
        this.concertTitle = "";
        this.createdAt = LocalDateTime.now();
        this.nextRetryAt = createdAt.plusSeconds(30);
    }
    public void booked(Long bookingId, String title) {
        this.bookingId = bookingId;
        this.concertTitle = title;
        this.resultStatus = "BOOKED";
        this.status = BookingAttemptStatus.BOOKED;
    }
    public void alreadyBooked(String title) {
        this.status = BookingAttemptStatus.REJECTED;
        this.resultStatus = "ALREADY_BOOKED";
        this.concertTitle = title;
    }
    public void soldOut() {
        this.status = BookingAttemptStatus.REJECTED;
        this.resultStatus = "SOLD_OUT";
    }
    public void releasePending(String reason) {
        this.status = BookingAttemptStatus.RELEASE_PENDING;
        if (bookingId == null) this.resultStatus = "BOOKING_FAILED";
        this.lastError = truncate(reason);
        this.nextRetryAt = LocalDateTime.now();
    }
    public void released() { this.status = BookingAttemptStatus.RELEASED; }
    public void retry(Throwable error) {
        this.retryCount++;
        this.lastError = truncate(error.getMessage());
        this.nextRetryAt = LocalDateTime.now().plusSeconds(Math.min(60, 5L << Math.min(retryCount - 1, 4)));
    }
    public void limitRetry(long remainingMillis) {
        LocalDateTime deadline = LocalDateTime.now().plusNanos(remainingMillis * 1_000_000);
        if (nextRetryAt.isAfter(deadline)) nextRetryAt = deadline;
    }
    private static String truncate(String text) {
        return text == null ? "Unknown failure" : text.substring(0, Math.min(500, text.length()));
    }
}
