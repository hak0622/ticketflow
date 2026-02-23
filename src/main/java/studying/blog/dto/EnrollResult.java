package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EnrollResult {
    private final String status;
    private final Long lectureId;
    private final String lectureTitle;
}
