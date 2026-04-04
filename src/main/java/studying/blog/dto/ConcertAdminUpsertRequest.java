package studying.blog.dto;

import lombok.Getter;
import lombok.Setter;
import studying.blog.domain.ConcertStatus;

import java.time.LocalDateTime;

@Getter
@Setter
public class ConcertAdminUpsertRequest {
    private String title;
    private LocalDateTime eventAt;
    private int totalSeats;
    private ConcertStatus status;
    private String posterUrl;
}
