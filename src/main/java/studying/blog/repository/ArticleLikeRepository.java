package studying.blog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import studying.blog.domain.ArticleLike;

import java.util.Optional;

public interface ArticleLikeRepository extends JpaRepository<ArticleLike,Long> {

    @Modifying
    @Query("delete from ArticleLike al where al.user.id = :userId and al.article.id = :articleId")
    int deleteOne(@Param("userId") Long userId, @Param("articleId") Long articleId);

    boolean existsByUserIdAndArticleId(Long userId, Long articleId);

    long countByArticleId(Long articleId); //좋아요 row 수 검증
}
