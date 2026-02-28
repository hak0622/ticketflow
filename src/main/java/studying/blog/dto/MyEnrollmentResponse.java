package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import studying.blog.domain.Enrollment;
import studying.blog.domain.Lecture;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class MyEnrollmentResponse {
    private Long enrollmentId;
    private Long lectureId;
    private String lectureTitle;
    private LocalDateTime openAt;
    private int capacity;
    private int enrolledCount;
    private String status;
    private LocalDateTime enrolledAt;

    public static MyEnrollmentResponse from(Enrollment e){
        Lecture l = e.getLecture();
        return MyEnrollmentResponse.builder()
                .enrollmentId(e.getId())
                .lectureId(l.getId())
                .lectureTitle(l.getTitle())
                .openAt(l.getOpenAt())
                .capacity(l.getCapacity())
                .enrolledCount(l.getEnrolledCount())
                .status(l.getStatus().name())
                .enrolledAt(e.getCreatedAt())
                .build();
    }
}
