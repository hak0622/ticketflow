package studying.blog.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.LectureRepository;

import java.time.LocalDateTime;

@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final LectureRepository lectureRepository;

    @Override
    public void run(String... args){
        //이미 강의가 있으면 중복 생성 방지
        if(lectureRepository.count() > 0) return;

        lectureRepository.save(Lecture.builder()
                .title("수강신청1")
                .openAt(LocalDateTime.now().minusMinutes(1)) //이미 오픈된 상태
                .capacity(100)
                .enrolledCount(0)
                .status(LectureStatus.OPEN)
                .build());

        lectureRepository.save(Lecture.builder()
                .title("Spring Security + JWT 실전")
                .openAt(LocalDateTime.now().plusMinutes(10)) // 10분 뒤 오픈(나중에 필터링에 활용)
                .capacity(50)
                .enrolledCount(0)
                .status(LectureStatus.OPEN)
                .build());

        lectureRepository.save(Lecture.builder()
                .title("마감된 강의(테스트)")
                .openAt(LocalDateTime.now().minusDays(1))
                .capacity(30)
                .enrolledCount(0)
                .status(LectureStatus.CLOSED)
                .build());
    }
}
