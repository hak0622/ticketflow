package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.domain.Enrollment;

import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment,Long> {
    boolean existsByLectureIdAndUserId(Long lectureId,Long userId);
    Optional<Enrollment> findByLectureIdAndUserId(Long lectureId,Long userId);
}
