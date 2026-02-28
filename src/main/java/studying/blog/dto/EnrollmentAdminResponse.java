package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import studying.blog.domain.Enrollment;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class EnrollmentAdminResponse {
    private Long enrollmentId;
    private Long userId;
    private String email;
    private String nickname;
    private Long lectureId;
    private String lectureTitle;
    private LocalDateTime createdAt;

}
