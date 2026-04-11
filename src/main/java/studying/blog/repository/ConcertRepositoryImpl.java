package studying.blog.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;
import studying.blog.domain.QConcert;
import studying.blog.dto.ConcertSearchCondition;

import java.util.List;

@RequiredArgsConstructor
public class ConcertRepositoryImpl implements ConcertRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Concert> search(ConcertSearchCondition condition) {
        QConcert concert = QConcert.concert;

        return queryFactory
                .selectFrom(concert)
                .where(
                        genreEq(condition.getGenre()),
                        statusEq(condition.getStatus()),
                        keywordContains(condition.getKeyword())
                )
                .orderBy(concert.eventAt.asc())
                .fetch();
    }

    private BooleanExpression genreEq(String genre) {
        if (genre == null || genre.isBlank()) {
            return null;
        }
        return QConcert.concert.genre.eq(genre.trim());
    }

    private BooleanExpression statusEq(ConcertStatus status) {
        return status == null ? null : QConcert.concert.status.eq(status);
    }

    private BooleanExpression keywordContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        String normalized = keyword.trim();

        return QConcert.concert.title.containsIgnoreCase(normalized)
                .or(QConcert.concert.artist.containsIgnoreCase(normalized));
    }
}
