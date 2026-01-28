package studying.blog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import studying.blog.domain.Article;
import studying.blog.dto.ArticleSearchCondition;

public interface BlogRepositoryCustom {
    Page<Article> search(ArticleSearchCondition condition, Pageable pageable);
}
