package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Coupon {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private int discountAmount;

    @Column(nullable = false)
    private int totalQty;

    @Column(nullable = false)
    @Builder.Default
    private int issuedCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean hasSeat() {
        return issuedCount < totalQty;
    }

    public void increaseIssuedCount() {
        if (!hasSeat()) throw new IllegalStateException("재고 없음");
        this.issuedCount++;
    }
}
