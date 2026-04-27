package studying.blog.dto;

import lombok.Getter;
import lombok.Setter;
import studying.blog.domain.ConcertStatus;

import java.time.LocalDateTime;

@Getter
@Setter
public class ConcertAdminUpsertRequest {
    private String title;
    private String artist;
    private String venue;
    private String genre;
    private LocalDateTime eventAt;
    private LocalDateTime bookingOpenAt;
    private int totalSeats;
    private Integer price;
    private Integer discountRate;
    private String posterUrl;
    private String zone;
    private ConcertStatus status;
}
