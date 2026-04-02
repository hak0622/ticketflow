package studying.blog.experiments.e1.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Coupon {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int totalQty;

    @Column(nullable = false)
    private int issuedCount;

    @Version
    private Long version;

    public boolean hasStock() {
        return issuedCount < totalQty;
    }

    public void increaseIssuedCount() {
        if (!hasStock()) throw new IllegalStateException("재고 없음");
        this.issuedCount++;
    }
}
