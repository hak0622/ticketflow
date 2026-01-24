package studying.blog.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Article;

import java.util.List;

public interface BlogRepository extends JpaRepository<Article,Long> {
}
