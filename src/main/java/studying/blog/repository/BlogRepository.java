package studying.blog.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.parameters.P;
import studying.blog.domain.Article;

import java.util.List;

public interface BlogRepository extends JpaRepository<Article,Long> ,BlogRepositoryCustom{
    @Override
    @EntityGraph(attributePaths = {"author"})
    Page<Article> findAll(Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("update Article a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int incrementViewCount(@Param("id")Long id);

    @Modifying(clearAutomatically = true)
    @Query("update Article a set a.likeCount = a.likeCount + 1 where a.id = :id")
    int incrementLikeCount(@Param("id")Long id);


    @Modifying(clearAutomatically = true)
    @Query("update Article a set a.likeCount = a.likeCount - 1 where a.id = :id and a.likeCount > 0")
    int decrementLikeCount(@Param("id")Long id);

    @Query("select a.likeCount from Article a where a.id = :id")
    long getLikeCount(@Param("id")Long id);
}
