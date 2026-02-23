package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Enrollment;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class EnrollmentResponse {
    private Long enrollmentId;
    private Long lectureId;
    private String lectureTitle;
    private Long userId;
    private LocalDateTime createdAt;

    public static EnrollmentResponse from(Enrollment enrollment){
        return new EnrollmentResponse(
                enrollment.getId(),
                enrollment.getLecture().getId(),
                enrollment.getLecture().getTitle(),
                enrollment.getUserId(),
                enrollment.getCreatedAt()
        );
    }
}
