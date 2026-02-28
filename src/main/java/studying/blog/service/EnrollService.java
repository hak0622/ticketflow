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

        //입장권을 소비하지말고 있는지만 확인 -> DB처리 중 실패해도 재시도 가능하게 하기 위함(멱등성)
        if(!queueService.isAdmitted(lectureId,userId)){
            //이미 신청 완료면 멱등하게 성공 응답(버튼 연타/재시도 방지) -> (입장권이 만료/삭제되어도, DB에 있으면 신청된 상태가 진실)
            if(enrollmentRepository.existsByLectureIdAndUserId(lectureId,userId)){
                Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + lectureId));
                return new EnrollResult("ALREADY_ENROLLED",lecture.getId(),lecture.getTitle());
            }

            throw new IllegalStateException("입장 권한이 없습니다.(입장권이 없거나 만료됨");
        }

        // 강의 row 락(동시성 방지)
        Lecture lecture = lectureRepository.findByIdForUpdate(lectureId).orElseThrow(() -> new IllegalArgumentException("Lecture not found :" + lectureId));

        if(lecture.getStatus() == LectureStatus.CLOSED){
            throw new IllegalStateException("마감된 강의입니다.");
        }
        if(lecture.getStatus() == LectureStatus.SOLD_OUT){
            throw new IllegalStateException("정원이 마감되었습니다.");
        }

        // 중복 신청 방지(멱등 처리)
        if(enrollmentRepository.existsByLectureIdAndUserId(lectureId,userId)){
            //이미 신청했으면 여기서 입장권을 소비해도 되고(선택),그냥 두어도 TTL로 사라짐.
            queueService.consumeAdmitted(lectureId,userId);

            return new EnrollResult("ALREADY_ENROLLED", lecture.getId(), lecture.getTitle());
        }

        // 정원 체크 + 차감
        if (!lecture.hasSeat()) {
            lecture.markSoldOut();
            throw new IllegalStateException("정원이 마감되었습니다.");
        }
        lecture.increaseEnrolled();

        if(!lecture.hasSeat()){
            lecture.markSoldOut();
        }

        // 신청 기록 저장
        Enrollment enrollment = Enrollment.builder()
                .lecture(lecture)
                .userId(userId)
                .build();

        enrollmentRepository.save(enrollment);

        //DB저장이 성공한 뒤에 입장권 소비(삭제)->DB 실패 시 admitted는 남아서 재시도 가능(멱등성)
        queueService.consumeAdmitted(lectureId,userId);

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
