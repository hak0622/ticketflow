package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Enrollment;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.dto.EnrollResult;
import studying.blog.dto.EnrollmentResponse;
import studying.blog.repository.EnrollmentRepository;
import studying.blog.repository.LectureRepository;

@Service
@RequiredArgsConstructor
public class EnrollService {
    private final LectureRepository lectureRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final QueueService queueService;

    @Transactional
    public EnrollResult enroll(Long lectureId, Long userId){
        boolean consumed = queueService.consumeAdmitted(lectureId, userId);

        // 입장권 소비 먼저 시도 (동시 클릭/중복 요청 방지)
        if(!consumed){
            throw new IllegalStateException("입장 권한이 없습니다.(입장권이 없거나 만료/이미 사용됨)");
        }

        // 2) 강의 row 락(동시성 방지)
        Lecture lecture = lectureRepository.findByIdForUpdate(lectureId).orElseThrow(() -> new IllegalArgumentException("Lecture not found :" + lectureId));

        if(lecture.getStatus() == LectureStatus.CLOSED){
            throw new IllegalStateException("마감된 강의입니다.");
        }
        if(lecture.getStatus() == LectureStatus.SOLD_OUT){
            throw new IllegalStateException("정원이 마감되었습니다.");
        }

        // 3) 중복 신청 방지
        if(enrollmentRepository.existsByLectureIdAndUserId(lectureId,userId)){
            return new EnrollResult("ALREADY_ENROLLED", lecture.getId(), lecture.getTitle());
        }

        // 4) 정원 체크 + 차감
        if (!lecture.hasSeat()) {
            lecture.markSoldOut();
            throw new IllegalStateException("정원이 마감되었습니다.");
        }
        lecture.increaseEnrolled();

        if(!lecture.hasSeat()){
            lecture.markSoldOut();
        }

        // 5) 신청 기록 저장
        Enrollment enrollment = Enrollment.builder()
                .lecture(lecture)
                .userId(userId)
                .build();

        enrollmentRepository.save(enrollment);

        return new EnrollResult("ENROLLED", lecture.getId(), lecture.getTitle());
    }

    @Transactional(readOnly = true)
    public EnrollResult myEnroll(Long lectureId, Long userId) {
        return enrollmentRepository.findByLectureIdAndUserId(lectureId, userId)
                .map(e -> new EnrollResult("ENROLLED", lectureId, e.getLecture().getTitle()))
                .orElseGet(() -> new EnrollResult("NOT_ENROLLED", lectureId, null));
    }

    @Transactional(readOnly = true)
    public EnrollmentResponse getMyEnrollmentDetail(Long lectureId, Long userId) {
        Enrollment enrollment = enrollmentRepository.findByLectureIdAndUserId(lectureId, userId)
                .orElseThrow(() -> new IllegalArgumentException("신청 내역이 없습니다."));
        return EnrollmentResponse.from(enrollment);
    }
}
