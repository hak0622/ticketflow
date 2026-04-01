package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.EnrollmentRepository;
import studying.blog.repository.LectureRepository;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class EnrollConcurrencyTest {

    @Autowired
    private EnrollService enrollService;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @MockBean
    private QueueService queueService;

    private Lecture lecture;

    @BeforeEach
    void setUp() {
        enrollmentRepository.deleteAll();
        lectureRepository.deleteAll();

        lecture = Lecture.builder()
                .title("동시성 테스트 강의")
                .capacity(1)
                .enrolledCount(0)
                .status(LectureStatus.OPEN)
                .openAt(LocalDateTime.now().minusMinutes(1))
                .build();

        lectureRepository.save(lecture);
    }

    @Test
    void 동시에_여러명이_신청해도_1명만_성공한다() throws Exception{
        int threadCount = 10;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        //모든 요청은 claim 성공했다고 가정
        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(600L);

        for(int i=0; i<threadCount; i++){
            final Long userId = (long) i;

            executorService.submit(()->{
                try{
                    enrollService.enroll(lecture.getId(),userId);
                }catch (Exception e){

                }finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        Lecture reloaded = lectureRepository.findById(lecture.getId()).orElseThrow();

        //정원 1명이므로 enrolledCount는 1이어야 한다.
        assertThat(reloaded.getEnrolledCount()).isEqualTo(1);

        // Enrollment도 1개만 저장되어야 한다.
        assertThat(enrollmentRepository.count()).isEqualTo(1);
    }
}
