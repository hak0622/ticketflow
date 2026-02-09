package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Lecture;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class LectureResponse{
    private Long id;
    private String title;
    private LocalDateTime openAt;
    private int capacity;

    public static LectureResponse from(Lecture lecture){
        return new LectureResponse(
                lecture.getId(),
                lecture.getTitle(),
                lecture.getOpenAt(),
                lecture.getCapacity()
        );
    }
}
