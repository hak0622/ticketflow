package studying.blog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Concert {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDateTime eventAt;

    @Column(nullable = false)
    private int totalSeats;

    @Column(nullable = false)
    private int bookedCount;

    @Column
    private String posterUrl;

    @Column
    private String venue;

    @Column
    private String artist;

    @Column
    private Integer price;

    @Column
    private String zone;

    @Column
    private String genre;

    @Column
    private LocalDateTime bookingOpenAt;  // 예매 오픈 일시 (null = 이미 오픈)

    @Column
    private Integer discountRate;         // 할인율 0~100 (null = 할인 없음)

    @Version
    @Column
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConcertStatus status = ConcertStatus.OPEN;

    public boolean hasSeat(){
        return bookedCount < totalSeats;
    }

    public void increaseBooked(){
        if(!hasSeat()){
            throw new IllegalArgumentException("정원이 초과되었습니다.");
        }
        this.bookedCount++;
    }

    public void decreaseBooked() {
        if (this.bookedCount <= 0) {
            throw new IllegalStateException("예약된 인원이 없습니다.");
        }
        this.bookedCount--;
        if (this.status == ConcertStatus.SOLD_OUT) {
            this.status = ConcertStatus.OPEN;
        }
        // CLOSED 상태는 관리자 의도적 마감이므로 건드리지 않는다
    }

    public void markSoldOut(){
        this.status = ConcertStatus.SOLD_OUT;
    }

    public void close(){
        this.status = ConcertStatus.CLOSED;
    }

    public void decreaseBookedBulk(int count) {
        this.bookedCount = Math.max(0, this.bookedCount - count);
        if (this.status == ConcertStatus.SOLD_OUT) {
            this.status = ConcertStatus.OPEN;
        }
    }

    public int getRemainingSeats() {
        return this.totalSeats - this.bookedCount;
    }

    public void updateByAdmin(String title, String artist, String venue, String genre,
                              LocalDateTime eventAt, LocalDateTime bookingOpenAt,
                              int totalSeats, Integer price, Integer discountRate,
                              String posterUrl, String zone, ConcertStatus status) {
        this.title = title;
        this.artist = artist;
        this.venue = venue;
        this.genre = genre;
        this.eventAt = eventAt;
        this.bookingOpenAt = bookingOpenAt;
        this.totalSeats = totalSeats;
        this.price = price;
        this.discountRate = discountRate;
        this.posterUrl = posterUrl;
        this.zone = zone;
        if (status != null) this.status = status;
    }
}
