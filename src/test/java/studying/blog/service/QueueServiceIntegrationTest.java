package studying.blog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.annotation.Transactional;

import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.repository.LectureRepository;
import studying.blog.support.RedisTestSupport;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class QueueServiceIntegrationTest extends RedisTestSupport {

    @Autowired
    private QueueService queueService;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private Lecture lecture;

    @BeforeEach
    void setUp() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();

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
    void enqueue_순서대로_등록되면_순번이_반환된다() {
        Long pos1 = queueService.enqueue(lecture.getId(), 1L);
        Long pos2 = queueService.enqueue(lecture.getId(), 2L);
        Long pos3 = queueService.enqueue(lecture.getId(), 3L);

        assertThat(pos1).isEqualTo(1L);
        assertThat(pos2).isEqualTo(2L);
        assertThat(pos3).isEqualTo(3L);
    }

    @Test
    void getPosition_enqueue_후_순번이_조회된다() {
        queueService.enqueue(lecture.getId(), 10L);
        queueService.enqueue(lecture.getId(), 20L);

        assertThat(queueService.getPosition(lecture.getId(), 10L)).isEqualTo(1L);
        assertThat(queueService.getPosition(lecture.getId(), 20L)).isEqualTo(2L);
    }

    @Test
    void popAndGrantAdmitted_후_claimAdmitted_성공한다() {
        queueService.enqueue(lecture.getId(), 1L);
        queueService.enqueue(lecture.getId(), 2L);

        List<String> granted = queueService.popAndGrantAdmitted(lecture.getId(), 2, 600);
        assertThat(granted).hasSize(2);

        long ttl = queueService.claimAdmitted(lecture.getId(), 1L);
        assertThat(ttl).isGreaterThan(0);
    }

    @Test
    void claimAdmitted_두번_호출하면_두번째는_마이너스1_반환() {
        queueService.enqueue(lecture.getId(), 1L);
        queueService.popAndGrantAdmitted(lecture.getId(), 1, 600);

        long first = queueService.claimAdmitted(lecture.getId(), 1L);
        long second = queueService.claimAdmitted(lecture.getId(), 1L);

        assertThat(first).isGreaterThan(0);
        assertThat(second).isEqualTo(-1L);
    }

    @Test
    void restoreAdmitted_후_claimAdmitted_재소비_가능() {
        queueService.enqueue(lecture.getId(), 1L);
        queueService.popAndGrantAdmitted(lecture.getId(), 1, 600);

        long ttl = queueService.claimAdmitted(lecture.getId(), 1L);   // 소비
        queueService.restoreAdmitted(lecture.getId(), 1L, ttl);        // 복구

        long reClaimed = queueService.claimAdmitted(lecture.getId(), 1L);  // 재소비
        assertThat(reClaimed).isGreaterThan(0);
    }
}
