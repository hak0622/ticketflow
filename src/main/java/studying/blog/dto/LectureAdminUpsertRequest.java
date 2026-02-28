package studying.blog.dto;

import lombok.Getter;
import lombok.Setter;
import studying.blog.domain.LectureStatus;

import java.time.LocalDateTime;

@Getter
@Setter
public class LectureAdminUpsertRequest {
    private String title;
    private LocalDateTime openAt;
    private int capacity;
    private LectureStatus status;
}
