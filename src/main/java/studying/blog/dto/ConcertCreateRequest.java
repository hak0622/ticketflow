package studying.blog.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class ConcertCreateRequest {
    private String title;
    private LocalDateTime eventAt;
    private int totalSeats;
    private String posterUrl;
}
