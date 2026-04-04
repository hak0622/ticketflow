package studying.blog.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.repository.ConcertRepository;

import java.time.LocalDateTime;

@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final ConcertRepository concertRepository;

    @Override
    public void run(String... args){
        //이미 콘서트가 있으면 중복 생성 방지
        if(concertRepository.count() > 0) return;

        concertRepository.save(Concert.builder()
                .title("2026 봄 페스티벌")
                .eventAt(LocalDateTime.now().minusMinutes(1)) //이미 오픈된 상태
                .totalSeats(100)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .build());

        concertRepository.save(Concert.builder()
                .title("Spring Security + JWT 실전 세미나")
                .eventAt(LocalDateTime.now().plusMinutes(10)) // 10분 뒤 오픈
                .totalSeats(50)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .build());

        concertRepository.save(Concert.builder()
                .title("마감된 콘서트(테스트)")
                .eventAt(LocalDateTime.now().minusDays(1))
                .totalSeats(30)
                .bookedCount(0)
                .status(ConcertStatus.CLOSED)
                .build());
    }
}
