package studying.blog.dto;

import studying.blog.domain.CouponIssue;

import java.time.LocalDateTime;

public record CouponIssueResponse(
        Long couponIssueId,
        String couponCode,
        int discountAmount,
        LocalDateTime issuedAt
) {
    public static CouponIssueResponse from(CouponIssue issue) {
        return new CouponIssueResponse(
                issue.getId(),
                issue.getCoupon().getCode(),
                issue.getCoupon().getDiscountAmount(),
                issue.getIssuedAt()
        );
    }
}
