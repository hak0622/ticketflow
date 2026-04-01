package studying.blog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.domain.Enrollment;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;
import studying.blog.dto.EnrollmentAdminResponse;
import studying.blog.dto.LectureAdminUpsertRequest;
import studying.blog.dto.LectureCreateRequest;
import studying.blog.dto.LectureResponse;
import studying.blog.repository.EnrollmentRepository;
import studying.blog.repository.LectureRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class LectureService {
    private final LectureRepository lectureRepository;
    private final EnrollmentRepository enrollmentRepository;

    @Transactional
    public LectureResponse create(LectureCreateRequest req){
        Lecture lecture = Lecture.builder()
                .title(req.getTitle())
                .openAt(req.getOpenAt())
                .capacity(req.getCapacity())
                .enrolledCount(0)
                .status(LectureStatus.OPEN)
                .thumbnailUrl(req.getThumbnailUrl())
                .build();

        return LectureResponse.from(lectureRepository.save(lecture));
    }

    @Transactional(readOnly = true)
    public LectureResponse findById(Long id){
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + id));

        return LectureResponse.from(lecture);
    }

    @Transactional(readOnly = true)
    public List<LectureResponse> findAll(){
        List<Lecture> lectures = lectureRepository.findAll();
        List<LectureResponse>result = new ArrayList<>();

        for (Lecture lecture : lectures) {
            result.add(LectureResponse.from(lecture));
        }
        return result;
    }

    @Transactional
    public LectureResponse adminCreate(LectureAdminUpsertRequest req){
        validateAdminUpsert(req);

        Lecture lecture = Lecture.builder()
                .title(req.getTitle())
                .openAt(req.getOpenAt())
                .capacity(req.getCapacity())
                .enrolledCount(0)
                .status(req.getStatus() == null ? LectureStatus.OPEN : req.getStatus())
                .thumbnailUrl(req.getThumbnailUrl())
                .build();
        return LectureResponse.from(lectureRepository.save(lecture));
    }

    @Transactional
    public LectureResponse adminUpdate(Long id, LectureAdminUpsertRequest req){
        validateAdminUpsert(req);

        Lecture lecture = lectureRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + id));

        if(req.getCapacity() < lecture.getEnrolledCount()){
            throw new IllegalArgumentException("capacity는 현재 신청 인원(enrolledCount)보다 작을 수 없습니다. 현재 신청 인원: " + lecture.getEnrolledCount());
        }

        lecture.updateByAdmin(req.getTitle(),req.getOpenAt(),req.getCapacity(),req.getStatus());
        return LectureResponse.from(lecture);
    }

    @Transactional
    public LectureResponse adminClose(Long id){
        Lecture lecture = lectureRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + id));
        lecture.close();
        return LectureResponse.from(lecture);
    }

    @Transactional
    public void adminDelete(Long id){
        Lecture lecture = lectureRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Lecture not found : " + id));
        enrollmentRepository.deleteByLecture(lecture);
        lectureRepository.delete(lecture);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentAdminResponse> adminEnrollments(Long lectureId) {
        return enrollmentRepository.findAdminEnrollments(lectureId);
    }

    private void validateAdminUpsert(LectureAdminUpsertRequest req){
        if(req.getTitle() == null || req.getTitle().isBlank()){
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if(req.getOpenAt() == null){
            throw new IllegalArgumentException("openAt은 필수입니다.");
        }
        if(req.getCapacity() <= 0){
            throw new IllegalArgumentException("capacity는 1 이상이어야 합니다.");
        }
    }

}
