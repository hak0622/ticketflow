package studying.blog.repository;

import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;

import java.util.List;

public interface ConcertRepository extends JpaRepository<Concert, Long>, ConcertRepositoryCustom {
    List<Concert> findByStatus(ConcertStatus status);

    List<Concert> findByGenre(String genre);

    /** 락 없는 멱등 상태 변경. 마지막 좌석 차감 시 사용. */
    @Modifying
    @Query("UPDATE Concert c SET c.status = studying.blog.domain.ConcertStatus.SOLD_OUT WHERE c.id = :id")
    void markSoldOutById(@Param("id") Long id);

    /** 좌석 복구 후 SOLD_OUT 상태만 OPEN으로 되돌린다. */
    @Modifying
    @Query("""
        UPDATE Concert c
        SET c.status = studying.blog.domain.ConcertStatus.OPEN
        WHERE c.id = :id
          AND c.status = studying.blog.domain.ConcertStatus.SOLD_OUT
    """)
    void reopenIfSoldOutById(@Param("id") Long id);
}
