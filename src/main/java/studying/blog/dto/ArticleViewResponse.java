package studying.blog.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import studying.blog.domain.Article;

import java.time.LocalDateTime;

@NoArgsConstructor
@Getter
public class ArticleViewResponse {
    private Long id;
    private String title;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String author;
    private String authorEmail;
    private long viewCount;
    private long likeCount;
    private boolean liked;

    public ArticleViewResponse(Article article){
        this(article,false);
    }

    public ArticleViewResponse(Article article,boolean liked) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.content = article.getContent();
        this.createdAt = article.getCreatedAt();
        this.updatedAt = article.getUpdatedAt();
        this.author = article.getAuthor().getNickname();
        this.authorEmail = article.getAuthor().getEmail();
        this.viewCount = article.getViewCount();
        this.likeCount = article.getLikeCount();
        this.liked = liked;
    }
}
