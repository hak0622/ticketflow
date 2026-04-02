package studying.blog.experiments.e3.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import studying.blog.experiments.e3.domain.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
}
