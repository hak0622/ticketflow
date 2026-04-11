package studying.blog.repository;

import io.lettuce.core.dynamic.annotation.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;

import java.util.List;
import java.util.Optional;

public interface ConcertRepository extends JpaRepository<Concert, Long>, ConcertRepositoryCustom {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Concert c where c.id = :id")
    Optional<Concert> findByIdForUpdate(@Param("id") Long id);

    List<Concert> findByStatus(ConcertStatus status);

    List<Concert> findByGenre(String genre);
}
