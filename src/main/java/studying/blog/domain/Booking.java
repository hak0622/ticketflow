package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@Table(
        name = "booking",
        uniqueConstraints = {@UniqueConstraint(name = "uk_booking_concert_user", columnNames = {"concert_id","user_id"})},
        indexes = {@Index(name = "idx_booking_concert", columnList = "concert_id"), @Index(name = "idx_booking_user", columnList = "user_id")}
)
public class Booking {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist(){
        this.createdAt = LocalDateTime.now();
    }

    public static Booking create(Concert concert, Long userId){
        return Booking.builder()
                .concert(concert)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
