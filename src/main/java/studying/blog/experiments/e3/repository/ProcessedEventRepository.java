package studying.blog.experiments.e3.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import studying.blog.experiments.e3.domain.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);

    @Modifying
    @Transactional
    @Query("delete from ProcessedEvent p where p.eventId = :eventId")
    void deleteByEventId(@Param("eventId") String eventId);
}
