package studying.blog.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class ConcertDecreaseBookedTest {

    private Concert concert(int bookedCount, int totalSeats, ConcertStatus status) {
        return Concert.builder()
                .title("테스트")
                .totalSeats(totalSeats)
                .bookedCount(bookedCount)
                .status(status)
                .eventAt(LocalDateTime.now())
                .price(10000)
                .build();
    }

    @Test
    void 정상_감소() {
        Concert c = concert(3, 10, ConcertStatus.OPEN);
        c.decreaseBooked();
        assertThat(c.getBookedCount()).isEqualTo(2);
        assertThat(c.getStatus()).isEqualTo(ConcertStatus.OPEN);
    }

    @Test
    void bookedCount가_0이면_예외() {
        Concert c = concert(0, 10, ConcertStatus.OPEN);
        assertThatThrownBy(c::decreaseBooked)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약된 인원이 없습니다.");
    }

    @Test
    void SOLD_OUT에서_감소시_OPEN으로_복원() {
        Concert c = concert(10, 10, ConcertStatus.SOLD_OUT);
        c.decreaseBooked();
        assertThat(c.getBookedCount()).isEqualTo(9);
        assertThat(c.getStatus()).isEqualTo(ConcertStatus.OPEN);
    }

    @Test
    void CLOSED에서_감소해도_CLOSED_유지() {
        Concert c = concert(5, 10, ConcertStatus.CLOSED);
        c.decreaseBooked();
        assertThat(c.getBookedCount()).isEqualTo(4);
        assertThat(c.getStatus()).isEqualTo(ConcertStatus.CLOSED);
    }

    @Test
    void OPEN에서_감소해도_OPEN_유지() {
        Concert c = concert(5, 10, ConcertStatus.OPEN);
        c.decreaseBooked();
        assertThat(c.getBookedCount()).isEqualTo(4);
        assertThat(c.getStatus()).isEqualTo(ConcertStatus.OPEN);
    }
}
