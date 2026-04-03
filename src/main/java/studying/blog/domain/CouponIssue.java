package studying.blog.domain;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    @Column
    private LocalDateTime usedAt;

    public static CouponIssue create(Coupon coupon, Long userId) {
        CouponIssue issue = new CouponIssue();
        issue.coupon = coupon;
        issue.userId = userId;
        issue.issuedAt = LocalDateTime.now();
        return issue;
    }
}
