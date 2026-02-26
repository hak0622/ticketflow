package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Lecture;
import studying.blog.domain.LectureStatus;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class LectureResponse{
    private Long id;
    private String title;
    private LocalDateTime openAt;
    private int capacity;
    private LectureStatus status;
    private int enrolledCount;

    public static LectureResponse from(Lecture lecture){
        return new LectureResponse(
                lecture.getId(),
                lecture.getTitle(),
                lecture.getOpenAt(),
                lecture.getCapacity(),
                lecture.getStatus(),
                lecture.getEnrolledCount()
        );
    }
}
