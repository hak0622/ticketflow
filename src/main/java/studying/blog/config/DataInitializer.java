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
                .artist("Various Artists")
                .price(55000)
                .eventAt(LocalDateTime.now().minusMinutes(1))
                .totalSeats(100)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .genre("음악")
                .build());

        concertRepository.save(Concert.builder()
                .title("Spring Security + JWT 실전 세미나")
                .artist("박개발")
                .price(30000)
                .eventAt(LocalDateTime.now().plusMinutes(10))
                .totalSeats(50)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .genre("인터뷰")
                .build());

        concertRepository.save(Concert.builder()
                .title("마감된 콘서트(테스트)")
                .artist("테스트 아티스트")
                .price(0)
                .eventAt(LocalDateTime.now().minusDays(1))
                .totalSeats(30)
                .bookedCount(0)
                .status(ConcertStatus.CLOSED)
                .genre("연극")
                .build());

        concertRepository.save(Concert.builder()
                .title("국립극장 특별 기획 뮤지컬")
                .artist("극단 나무")
                .price(45000)
                .eventAt(LocalDateTime.now().plusDays(3))
                .totalSeats(200)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .genre("뮤지컬")
                .build());

        concertRepository.save(Concert.builder()
                .title("현대무용 갈라 쇼")
                .artist("댄스 컴퍼니 하늘")
                .price(35000)
                .eventAt(LocalDateTime.now().plusDays(7))
                .totalSeats(150)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .genre("무용")
                .build());

        concertRepository.save(Concert.builder()
                .title("클래식 앙상블 콘서트")
                .artist("서울 챔버 오케스트라")
                .price(50000)
                .eventAt(LocalDateTime.now().plusDays(14))
                .totalSeats(300)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .genre("음악")
                .build());
    }
}
