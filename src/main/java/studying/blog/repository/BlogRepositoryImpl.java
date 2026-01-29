package studying.blog.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import studying.blog.domain.Article;
import studying.blog.dto.ArticleSearchCondition;

import java.util.List;

import static org.springframework.util.StringUtils.hasText;
import static studying.blog.domain.QArticle.*;
import static studying.blog.domain.QUser.*;

public class BlogRepositoryImpl implements BlogRepositoryCustom{
    private final JPAQueryFactory queryFactory;

    public BlogRepositoryImpl(EntityManager em){
        this.queryFactory = new JPAQueryFactory(em);
    }

    @Override
    public Page<Article> search(ArticleSearchCondition condition, Pageable pageable){
        List<Article> content = queryFactory
                .select(article)
                .from(article)
                .leftJoin(article.author, user).fetchJoin()
                .where(titleOrContentContains(condition.getKeyword()), authorNicknameEq(condition.getAuthorNickName()))
                .orderBy(article.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(article.count())
                .from(article)
                .where(titleOrContentContains(condition.getKeyword()), authorNicknameEq(condition.getAuthorNickName()))
                .fetchOne();

        long totalCount = (total == null) ? 0 : total;
        return new PageImpl<>(content,pageable,totalCount);
    }

    private BooleanExpression titleOrContentContains(String keyword) {
        if(!hasText(keyword)){
            return null;
        }
        return article.title.containsIgnoreCase(keyword)
                .or(article.content.containsIgnoreCase(keyword));

    }

    private BooleanExpression authorNicknameEq(String authorNickName) {
        if(!hasText(authorNickName)){
            return null;
        }
        return article.author.nickname.eq(authorNickName);
    }
}
