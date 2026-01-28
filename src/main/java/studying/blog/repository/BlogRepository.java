package studying.blog.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import studying.blog.domain.Article;

import java.util.List;

public interface BlogRepository extends JpaRepository<Article,Long> ,BlogRepositoryCustom{
    @Override
    @EntityGraph(attributePaths = {"author"})
    Page<Article> findAll(Pageable pageable);
}
