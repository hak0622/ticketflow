package studying.blog.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArticleSearchCondition {
    private String keyword;
    private String authorNickName;
}
