package studying.blog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.dto.ConcertResponse;
import studying.blog.dto.ConcertSearchCondition;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ConcertServiceSearchTest {

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private ConcertService concertService;

    @Test
    void shouldMapSearchResultToConcertResponses() {
        ConcertSearchCondition condition = new ConcertSearchCondition();
        condition.setGenre("음악");
        condition.setKeyword("아이유");
        condition.setStatus(ConcertStatus.OPEN);

        Concert concert = Concert.builder()
                .id(1L)
                .title("아이유 콘서트")
                .artist("아이유")
                .genre("음악")
                .status(ConcertStatus.OPEN)
                .eventAt(LocalDateTime.now().plusDays(1))
                .totalSeats(100)
                .bookedCount(10)
                .build();

        given(concertRepository.search(condition)).willReturn(List.of(concert));

        List<ConcertResponse> result = concertService.search(condition);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("아이유 콘서트");
        assertThat(result.get(0).getArtist()).isEqualTo("아이유");
        assertThat(result.get(0).getGenre()).isEqualTo("음악");
        assertThat(result.get(0).getStatus()).isEqualTo(ConcertStatus.OPEN);
    }

    @Test
    void shouldPassSearchConditionToRepository() {
        ConcertSearchCondition condition = new ConcertSearchCondition();
        condition.setGenre("뮤지컬");
        condition.setKeyword("갈라");
        condition.setStatus(ConcertStatus.SOLD_OUT);

        given(concertRepository.search(condition)).willReturn(List.of());

        concertService.search(condition);

        ArgumentCaptor<ConcertSearchCondition> captor = ArgumentCaptor.forClass(ConcertSearchCondition.class);
        then(concertRepository).should().search(captor.capture());

        ConcertSearchCondition captured = captor.getValue();
        assertThat(captured.getGenre()).isEqualTo("뮤지컬");
        assertThat(captured.getKeyword()).isEqualTo("갈라");
        assertThat(captured.getStatus()).isEqualTo(ConcertStatus.SOLD_OUT);
    }
}
