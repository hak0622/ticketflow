package studying.blog.experiments.e1.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    uniqueConstraints = @UniqueConstraint(
        name = "uk_coupon_issue",
        columnNames = {"coupon_id", "user_id"}
    )
)
public class CouponIssue {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    public static CouponIssue create(Long couponId, Long userId) {
        CouponIssue issue = new CouponIssue();
        issue.couponId = couponId;
        issue.userId = userId;
        issue.issuedAt = LocalDateTime.now();
        return issue;
    }
}
