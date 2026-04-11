package studying.blog.dto;

import lombok.Getter;
import lombok.Setter;
import studying.blog.domain.ConcertStatus;

@Getter
@Setter
public class ConcertSearchCondition {
    private String genre;
    private String keyword;
    private ConcertStatus status;
}
