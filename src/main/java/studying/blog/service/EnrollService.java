package studying.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Enrollment;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.dto.EnrollResult;
import studying.blog.dto.EnrollmentResponse;
import studying.blog.repository.EnrollmentRepository;
import studying.blog.repository.LectureRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollService {

    private final LectureRepository lectureRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final QueueService queueService;

    @Transactional
    public EnrollResult enroll(Long lectureId, Long userId) {

        // (1)전체 처리 시간 측정 시작
        long t0 = System.nanoTime();

        // (2) admitted 원자적 선점 (있으면 삭제 + ttl 반환, 없으면 -1)
        long tClaimStart = System.nanoTime();
        long ttl = queueService.claimAdmitted(lectureId, userId);
        long claimMs = (System.nanoTime() - tClaimStart) / 1_000_000;

        if (ttl < 0) {
            // 이미 신청되어 있으면 멱등 성공 처리
            boolean already = enrollmentRepository.existsByLectureIdAndUserId(lectureId, userId);
            if (already) {
                Lecture lecture = lectureRepository.findById(lectureId)
                        .orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + lectureId));

                log.info("[ENROLL][RESULT] userId={} lectureId={} status=ALREADY_ENROLLED reason=already_in_db claimMs={}",
                        userId, lectureId, claimMs);

                return new EnrollResult("ALREADY_ENROLLED", lecture.getId(), lecture.getTitle());
            }

            log.info("[ENROLL][CLAIM_FAIL] userId={} lectureId={} reason=no_admitted_or_expired claimMs={}",
                    userId, lectureId, claimMs);

            throw new IllegalStateException("입장 권한이 없습니다. (입장권이 없거나 만료/이미 사용됨)");
        }

        // claim 성공
        log.info("[ENROLL][CLAIM_OK] userId={} lectureId={} ttlSec={} claimMs={}",
                userId, lectureId, ttl, claimMs);

        try {
            // (3) DB row lock 구간 시간 측정
            long tLockStart = System.nanoTime();
            Lecture lecture = lectureRepository.findByIdForUpdate(lectureId)
                    .orElseThrow(() -> new IllegalArgumentException("Lecture not found :" + lectureId));
            long lockMs = (System.nanoTime() - tLockStart) / 1_000_000;

            // 상태 체크
            if (lecture.getStatus() == LectureStatus.CLOSED) {
                log.info("[ENROLL][RESULT] userId={} lectureId={} status=CLOSED lockMs={}", userId, lectureId, lockMs);
                throw new IllegalStateException("마감된 강의입니다.");
            }
            if (lecture.getStatus() == LectureStatus.SOLD_OUT) {
                log.info("[ENROLL][RESULT] userId={} lectureId={} status=SOLD_OUT lockMs={}", userId, lectureId, lockMs);
                throw new IllegalStateException("정원이 마감되었습니다.");
            }

            // (4) 중복 신청 방지(멱등 처리) - 여기서 한번만 확인
            long tExistsStart = System.nanoTime();
            boolean already = enrollmentRepository.existsByLectureIdAndUserId(lectureId, userId);
            long existsMs = (System.nanoTime() - tExistsStart) / 1_000_000;

            if (already) {
                log.info("[ENROLL][RESULT] userId={} lectureId={} status=ALREADY_ENROLLED lockMs={} existsMs={}",
                        userId, lectureId, lockMs, existsMs);

                return new EnrollResult("ALREADY_ENROLLED", lecture.getId(), lecture.getTitle());
            }

            // 정원 체크 + 차감
            if (!lecture.hasSeat()) {
                lecture.markSoldOut();
                log.info("[ENROLL][RESULT] userId={} lectureId={} status=SOLD_OUT lockMs={} existsMs={}",
                        userId, lectureId, lockMs, existsMs);

                throw new IllegalStateException("정원이 마감되었습니다.");
            }

            lecture.increaseEnrolled();
            if (!lecture.hasSeat()) {
                lecture.markSoldOut();
            }

            // (5) save 시간 측정
            long tSaveStart = System.nanoTime();
            Enrollment enrollment = Enrollment.builder()
                    .lecture(lecture)
                    .userId(userId)
                    .build();

            enrollmentRepository.save(enrollment);
            long saveMs = (System.nanoTime() - tSaveStart) / 1_000_000;

            long totalMs = (System.nanoTime() - t0) / 1_000_000;

            log.info("[ENROLL][RESULT] userId={} lectureId={} status=ENROLLED enrollmentId={} lockMs={} existsMs={} saveMs={} totalMs={}",
                    userId, lectureId, enrollment.getId(), lockMs, existsMs, saveMs, totalMs);

            // 성공: claim에서 admitted를 삭제(소비)했으므로 추가 소비 없음
            return new EnrollResult("ENROLLED", lecture.getId(), lecture.getTitle());

        } catch (RuntimeException e) {
            // DB처리 실패 시 admitted 복구 -> 재시도 가능하게
            queueService.restoreAdmitted(lectureId, userId, ttl);

            long totalMs = (System.nanoTime() - t0) / 1_000_000;

            log.warn("[ENROLL][RESTORE] userId={} lectureId={} ttlSec={} errorType={} msg={} totalMs={}",
                    userId, lectureId, ttl, e.getClass().getSimpleName(), e.getMessage(), totalMs);

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
