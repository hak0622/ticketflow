package studying.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import studying.blog.domain.Concert;
import studying.blog.domain.ConcertStatus;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ConcertResponse {
    private Long id;
    private String title;
    private LocalDateTime eventAt;
    private int totalSeats;
    private ConcertStatus status;
    private int bookedCount;
    private String posterUrl;
    private String artist;
    private Integer price;
    private String genre;

    public static ConcertResponse from(Concert concert){
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getEventAt(),
                concert.getTotalSeats(),
                concert.getStatus(),
                concert.getBookedCount(),
                concert.getPosterUrl(),
                concert.getArtist(),
                concert.getPrice(),
                concert.getGenre()
        );
    }
}
