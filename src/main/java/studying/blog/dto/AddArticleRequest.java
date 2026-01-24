package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import studying.blog.domain.Article;
import studying.blog.domain.User;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AddArticleRequest {
    private String title;
    private String content;

    public Article toEntity(User user){
        return Article.builder()
                .title(title)
                .content(content)
                .author(user)
                .build();
    }
}
