package studying.blog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import studying.blog.domain.Concert;
import studying.blog.dto.ConcertResponse;
import studying.blog.repository.BookingRepository;
import studying.blog.repository.ConcertRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ConcertServiceGenreFilterTest {

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private QueueService queueService;

    @InjectMocks
    private ConcertService concertService;

    @Test
    void shouldReturnAllConcertsWhenGenreIsMissing() {
        Concert kpopConcert = concert("K-POP Concert", "K-POP");
        Concert musicalConcert = concert("Musical Concert", "뮤지컬");
        given(concertRepository.findAll()).willReturn(List.of(kpopConcert, musicalConcert));
        given(queueService.getRemainingSeat(kpopConcert.getId())).willReturn(100L);
        given(queueService.getRemainingSeat(musicalConcert.getId())).willReturn(100L);

        List<ConcertResponse> result = concertService.findAll(null);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ConcertResponse::getTitle)
                .containsExactly("K-POP Concert", "Musical Concert");
        then(concertRepository).should().findAll();
        then(concertRepository).should(never()).findByGenre(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldFilterConcertsByGenreWhenGenreIsProvided() {
        Concert kpopConcert = concert("K-POP Concert", "K-POP");
        given(concertRepository.findByGenre("K-POP")).willReturn(List.of(kpopConcert));
        given(queueService.getRemainingSeat(kpopConcert.getId())).willReturn(100L);

        List<ConcertResponse> result = concertService.findAll("K-POP");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("K-POP Concert");
        assertThat(result.get(0).getGenre()).isEqualTo("K-POP");
        then(concertRepository).should().findByGenre("K-POP");
        then(concertRepository).should(never()).findAll();
    }

    @Test
    void shouldReturnAllConcertsWhenGenreIsBlank() {
        Concert kpopConcert = concert("K-POP Concert", "K-POP");
        given(concertRepository.findAll()).willReturn(List.of(kpopConcert));
        given(queueService.getRemainingSeat(kpopConcert.getId())).willReturn(100L);

        List<ConcertResponse> result = concertService.findAll("   ");

        assertThat(result).hasSize(1);
        then(concertRepository).should().findAll();
        then(concertRepository).should(never()).findByGenre(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldTrimGenreBeforeFiltering() {
        Concert kpopConcert = concert("K-POP Concert", "K-POP");
        given(concertRepository.findByGenre("K-POP")).willReturn(List.of(kpopConcert));
        given(queueService.getRemainingSeat(kpopConcert.getId())).willReturn(100L);

        List<ConcertResponse> result = concertService.findAll("  K-POP  ");

        assertThat(result).hasSize(1);
        then(concertRepository).should().findByGenre("K-POP");
    }

    @Test
    void shouldReturnEmptyListWhenGenreDoesNotExist() {
        given(concertRepository.findByGenre("재즈")).willReturn(List.of());

        List<ConcertResponse> result = concertService.findAll("재즈");

        assertThat(result).isEmpty();
        then(concertRepository).should().findByGenre("재즈");
    }

    private Concert concert(String title, String genre) {
        return Concert.builder()
                .id((long) title.hashCode())
                .title(title)
                .genre(genre)
                .eventAt(LocalDateTime.now().plusDays(1))
                .totalSeats(100)
                .bookedCount(0)
                .build();
    }
}
