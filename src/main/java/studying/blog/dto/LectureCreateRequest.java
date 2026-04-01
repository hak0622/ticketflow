package studying.blog.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class LectureCreateRequest{
    private String title;
    private LocalDateTime openAt;
    private int capacity;
    private String thumbnailUrl;
}