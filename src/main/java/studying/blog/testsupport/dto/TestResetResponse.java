package studying.blog.testsupport.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TestResetResponse {

    private final int deletedBookings;
    private final int restoredSeats;

    public static TestResetResponse of(int deletedBookings, int restoredSeats) {
        return new TestResetResponse(deletedBookings, restoredSeats);
    }
}
