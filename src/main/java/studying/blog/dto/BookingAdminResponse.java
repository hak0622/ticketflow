package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class BookingAdminResponse {
    private Long bookingId;
    private Long userId;
    private String email;
    private String nickname;
    private Long concertId;
    private String concertTitle;
    private LocalDateTime createdAt;
}
