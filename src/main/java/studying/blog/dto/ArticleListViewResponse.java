package studying.blog.dto;

import lombok.Getter;
import studying.blog.domain.Article;

@Getter
public class ArticleListViewResponse {
    private final long id;
    private final String title;
    private final String content;
    private final String author;

    public ArticleListViewResponse(Article article) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.content = article.getContent();
        this.author = article.getAuthor().getNickname();
    }
}
