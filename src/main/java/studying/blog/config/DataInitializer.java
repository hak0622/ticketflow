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
    @SuppressWarnings("null")
    public void run(String... args) {
        if (concertRepository.count() > 0) return;

        LocalDateTime now = LocalDateTime.now();

        /* ── 할인 공연 (5개) — discountRate != null ── */
        concertRepository.save(Concert.builder()
                .title("아이유 콘서트 : LD")
                .artist("아이유")
                .venue("잠실올림픽주경기장")
                .genre("콘서트")
                .price(132000)
                .eventAt(now.plusDays(30))
                .totalSeats(50000)
                .bookedCount(12000)
                .status(ConcertStatus.OPEN)
                .discountRate(25)
                .build());

        concertRepository.save(Concert.builder()
                .title("뮤지컬 베토벤")
                .artist("극단 하우스")
                .venue("충무아트센터 중극장 블랙")
                .genre("뮤지컬")
                .price(110000)
                .eventAt(now.plusDays(45))
                .totalSeats(500)
                .bookedCount(210)
                .status(ConcertStatus.OPEN)
                .discountRate(10)
                .build());

        concertRepository.save(Concert.builder()
                .title("뮤지컬 로미오와 줄리엣")
                .artist("5th 캐스트 오픈")
                .venue("예술의전당 오페라극장")
                .genre("뮤지컬")
                .price(99000)
                .eventAt(now.plusDays(60))
                .totalSeats(800)
                .bookedCount(560)
                .status(ConcertStatus.OPEN)
                .discountRate(40)
                .build());

        concertRepository.save(Concert.builder()
                .title("발레 오즈 — 국립발레단")
                .artist("국립발레단")
                .venue("NOL 의정부 2관")
                .genre("클래식/무용")
                .price(79000)
                .eventAt(now.plusDays(25))
                .totalSeats(600)
                .bookedCount(430)
                .status(ConcertStatus.OPEN)
                .discountRate(30)
                .build());

        concertRepository.save(Concert.builder()
                .title("빌리 조엘 내한 콘서트")
                .artist("Billy Joel")
                .venue("KSPO돔")
                .genre("콘서트")
                .price(165000)
                .eventAt(now.plusDays(55))
                .totalSeats(15000)
                .bookedCount(3200)
                .status(ConcertStatus.OPEN)
                .discountRate(20)
                .build());

        concertRepository.save(Concert.builder()
                .title("넌버벌 퍼포먼스 점프 — 패밀리 공연")
                .artist("점프 컴퍼니")
                .venue("충무아트센터 대극장")
                .genre("아동/가족")
                .price(45000)
                .eventAt(now.plusDays(20))
                .totalSeats(800)
                .bookedCount(250)
                .status(ConcertStatus.OPEN)
                .discountRate(15)
                .build());

        /* ── 오픈 예정 공연 (4개) — bookingOpenAt != null ── */
        concertRepository.save(Concert.builder()
                .title("전설의 리틀 농구단 10주년 콘서트")
                .artist("극단 리틀")
                .venue("올림픽홀")
                .genre("콘서트")
                .price(88000)
                .eventAt(now.plusDays(20))
                .totalSeats(2500)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .bookingOpenAt(now.plusDays(2))
                .build());

        concertRepository.save(Concert.builder()
                .title("뮤지컬 너를 위한 글자")
                .artist("마마다 타로오스")
                .venue("한전아트센터")
                .genre("뮤지컬")
                .price(77000)
                .eventAt(now.plusDays(35))
                .totalSeats(400)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .bookingOpenAt(now.plusDays(4))
                .build());

        concertRepository.save(Concert.builder()
                .title("뮤지컬 걸프렌즈")
                .artist("3차 앙코르 공연")
                .venue("YES24 라이브홀")
                .genre("뮤지컬")
                .price(110000)
                .eventAt(now.plusDays(40))
                .totalSeats(700)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .bookingOpenAt(now.plusDays(1))
                .build());

        concertRepository.save(Concert.builder()
                .title("오페라 갈라 콘서트")
                .artist("서울시립오페라단")
                .venue("롯데콘서트홀")
                .genre("클래식/무용")
                .price(95000)
                .eventAt(now.plusDays(50))
                .totalSeats(1200)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .bookingOpenAt(now.plusDays(6))
                .build());

        concertRepository.save(Concert.builder()
                .title("뮤지컬 알라딘 주니어")
                .artist("서울 키즈 뮤지컬")
                .venue("대학로 아트원씨어터 1관")
                .genre("아동/가족")
                .price(35000)
                .eventAt(now.plusDays(28))
                .totalSeats(350)
                .bookedCount(0)
                .status(ConcertStatus.OPEN)
                .bookingOpenAt(now.plusDays(3))
                .build());

        /* ── 일반 공연 (3개) ── */
        concertRepository.save(Concert.builder()
                .title("국립극장 특별 기획 연극")
                .artist("국립극단")
                .venue("국립극장 해오름극장")
                .genre("연극")
                .price(55000)
                .eventAt(now.plusDays(15))
                .totalSeats(1000)
                .bookedCount(330)
                .status(ConcertStatus.OPEN)
                .build());

        concertRepository.save(Concert.builder()
                .title("2026 현대미술 특별 전시")
                .artist("국립현대미술관")
                .venue("국립현대미술관 서울관")
                .genre("전시/행사")
                .price(20000)
                .eventAt(now.plusDays(90))
                .totalSeats(5000)
                .bookedCount(1200)
                .status(ConcertStatus.OPEN)
                .build());

        concertRepository.save(Concert.builder()
                .title("클래식 앙상블 — 서울 챔버")
                .artist("서울 챔버 오케스트라")
                .venue("예술의전당 콘서트홀")
                .genre("클래식/무용")
                .price(70000)
                .eventAt(now.plusDays(10))
                .totalSeats(800)
                .bookedCount(640)
                .status(ConcertStatus.OPEN)
                .build());
    }
}
