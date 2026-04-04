package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BookingResult {
    private final String status;
    private final Long concertId;
    private final String concertTitle;
}
