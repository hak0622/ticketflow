package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import studying.blog.domain.Enrollment;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.dto.EnrollResult;
import studying.blog.repository.EnrollmentRepository;
import studying.blog.repository.LectureRepository;

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
    private EnrollService enrollService;

    @Autowired
    private LectureRepository lectureRepository;

    @SpyBean
    private EnrollmentRepository enrollmentRepository;

    @MockBean
    private QueueService queueService;

    private Lecture lecture;
    private Long userId = 1L;

    @BeforeEach
    void setUp(){
        lecture = Lecture.builder()
                .title("테스트 강의")
                .capacity(30)
                .enrolledCount(0)
                .status(LectureStatus.OPEN)
                .openAt(LocalDateTime.now().minusMinutes(1))
                .build();
        lectureRepository.save(lecture);
    }

    @Test
    void 이미_신청한_경우_ALREDY_ENROLLED(){
        //given
        Enrollment enrollment = Enrollment.builder()
                .lecture(lecture)
                .userId(userId)
                .build();
        enrollmentRepository.save(enrollment);

        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(-1L);

        EnrollResult result = enrollService.enroll(lecture.getId(), userId);
        assertThat(result.getStatus()).isEqualTo("ALREADY_ENROLLED");
    }

    @Test
    void 입장권_없으면_예외발생(){
        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(-1L);

        assertThatThrownBy(()->enrollService.enroll(lecture.getId(),userId)).isInstanceOf(IllegalStateException.class).hasMessageContaining("입장 권한이 없습니다.");
    }

    @Test
    void 정상_신청이면_ENROLLED_AND_DB에_저장된다(){
        //given
        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(600L);

        //when
        EnrollResult result = enrollService.enroll(lecture.getId(), userId);

        //then 1) 응답 상태
        assertThat(result.getStatus()).isEqualTo("ENROLLED");

        //then 2) Enrollment가 1개 저장되었는지
        assertThat(enrollmentRepository.existsByLectureIdAndUserId(lecture.getId(), userId)).isTrue();

        //then 3) enrolledCount가 1증가했는지 (DB에서 다시 조회해서 확인)
        Lecture reloaded = lectureRepository.findById(lecture.getId()).orElseThrow();
        assertThat(reloaded.getEnrolledCount()).isEqualTo(1);
    }

    @Test
    void DB_저장_실패하면_restoreAdmiited가_호출된다(){
        long ttl = 600L;

        given(queueService.claimAdmitted(anyLong(),anyLong())).willReturn(ttl);

        doThrow(new RuntimeException("DB fail for test"))
                .when(enrollmentRepository)
                .save(any(Enrollment.class));

        // when & then
        assertThatThrownBy(() -> enrollService.enroll(lecture.getId(), userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB fail for test");

        // restoreAdmitted가 호출되었는지 확인
        verify(queueService, times(1))
                .restoreAdmitted(lecture.getId(), userId, ttl);
    }
}