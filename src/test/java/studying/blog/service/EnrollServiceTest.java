package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import studying.blog.domain.Booking;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.BookingResult;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class EnrollServiceTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ConcertRepository concertRepository;

    @SpyBean
    private BookingRepository bookingRepository;

    @MockBean
    private QueueService queueService;

    private Concert concert;
    private Long userId = 1L;

    @BeforeEach
    void setUp(){
        concert = Concert.builder()
                .title("테스트 콘서트")
                .totalSeats(30)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .build();
        concertRepository.save(concert);
    }

    @Test
    void 이미_신청한_경우_ALREADY_BOOKED(){
        //given
        Booking booking = Booking.builder()
                .concert(concert)
                .userId(userId)
                .build();
        bookingRepository.save(booking);

        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(-1L);

        BookingResult result = bookingService.book(concert.getId(), userId);
        assertThat(result.getStatus()).isEqualTo("ALREADY_BOOKED");
    }

    @Test
    void 입장권_없으면_예외발생(){
        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(-1L);

        assertThatThrownBy(()->bookingService.book(concert.getId(),userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("입장 권한이 없습니다.");
    }

    @Test
    void 정상_신청이면_BOOKED_AND_DB에_저장된다(){
        //given
        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(600L);

        //when
        BookingResult result = bookingService.book(concert.getId(), userId);

        //then 1) 응답 상태
        assertThat(result.getStatus()).isEqualTo("BOOKED");

        //then 2) Booking이 1개 저장되었는지
        assertThat(bookingRepository.existsByConcertIdAndUserId(concert.getId(), userId)).isTrue();

        //then 3) bookedCount가 1 증가했는지
        Concert reloaded = concertRepository.findById(concert.getId()).orElseThrow();
        assertThat(reloaded.getBookedCount()).isEqualTo(1);
    }

    @Test
    void DB_저장_실패하면_restoreAdmitted가_호출된다(){
        long ttl = 600L;

        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(ttl);

        doThrow(new RuntimeException("DB fail for test"))
                .when(bookingRepository)
                .save(any(Booking.class));

        // when & then
        assertThatThrownBy(() -> bookingService.book(concert.getId(), userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB fail for test");

        // restoreAdmitted가 호출되었는지 확인
        verify(queueService, times(1))
                .restoreAdmitted(concert.getId(), userId, ttl);
    }
}
