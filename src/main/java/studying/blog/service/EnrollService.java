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

        //admitted 원자적 선점 (있으면 삭제 + ttl 반환, 없으면 -1)
        long ttl = queueService.claimAdmitted(lectureId, userId);

        if(ttl < 0){
            //이미 신청되어 있으면 멱등 성공 처리
            if(enrollmentRepository.existsByLectureIdAndUserId(lectureId,userId)){
                Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(()->new IllegalArgumentException("Lecture not found : " + lectureId));
                return new EnrollResult("ALREADY_ENROLLED", lecture.getId(),lecture.getTitle());
            }
            throw new IllegalStateException("입장 권한이 없습니다. (입장권이 없거나 만료/이미 사용됨)");
        }

        try{
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

            //성공 : 이미 claim에서 admitted를 삭제(소비)했으므로 여기서 추가 소비 필요 없음
            return new EnrollResult("ENROLLED", lecture.getId(), lecture.getTitle());
        }catch (RuntimeException e){
            // DB처리 실패 시 admitted 복구 -> 재시도 가능하게
            queueService.restoreAdmitted(lectureId,userId,ttl);
            throw e;
        }
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
