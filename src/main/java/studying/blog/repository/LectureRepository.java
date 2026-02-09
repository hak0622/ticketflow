package studying.blog.repository;

import io.lettuce.core.dynamic.annotation.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Lecture;

import java.util.Optional;

public interface LectureRepository extends JpaRepository<Lecture,Long> {
    //정원 차감할 때 동시성 방지 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lecture l where l.id = :id")
    Optional<Lecture> findByIdForUpdate(@Param("id")Long id);
}
