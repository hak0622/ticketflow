package studying.blog.experiments.e4.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.experiments.e4.domain.Outbox;
import studying.blog.experiments.e4.domain.OutboxStatus;

import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    List<Outbox> findByStatus(OutboxStatus status);
}
