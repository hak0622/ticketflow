package studying.blog.repository;

import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Enrollment;
import studying.blog.dto.EnrollmentAdminResponse;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment,Long> {
    boolean existsByLectureIdAndUserId(Long lectureId,Long userId);
    Optional<Enrollment> findByLectureIdAndUserId(Long lectureId,Long userId);

    List<Enrollment>findAllByLectureId(Long lectureId);

    @Query("select e from Enrollment e join fetch e.lecture where e.userId = :userId order by e.createdAt desc")
    List<Enrollment>findByUserIdWithLecture(@Param("userId") Long userId);

    @Query("""
    select new studying.blog.dto.EnrollmentAdminResponse(
        e.id,
        e.userId,
        u.email,
        u.nickname,
        e.lecture.id,
        e.lecture.title,
        e.createdAt
    )
    from Enrollment e
    join User u on u.id = e.userId
    where e.lecture.id = :lectureId
    order by e.createdAt desc
""")
    List<EnrollmentAdminResponse>findAdminEnrollments(@Param("lectureId") Long lectureId);
}
