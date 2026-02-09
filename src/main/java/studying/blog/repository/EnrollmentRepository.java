package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.domain.Enrollment;

import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment,Long> {
    boolean existsByLectureIdAndUserKey(Long lectureId,String userKey);
    Optional<Enrollment> findByLectureIdAndUserKey(Long lectureId,String userKey);
}
