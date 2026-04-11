package studying.blog.repository;

import studying.blog.domain.Concert;
import studying.blog.dto.ConcertSearchCondition;

import java.util.List;

public interface ConcertRepositoryCustom {
    List<Concert> search(ConcertSearchCondition condition);
}
