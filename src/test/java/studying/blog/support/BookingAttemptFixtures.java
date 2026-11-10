package studying.blog.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import studying.blog.domain.Booking;
import studying.blog.domain.BookingAttempt;
import studying.blog.repository.BookingAttemptRepository;
import java.util.Map;
import java.util.UUID;

/** Emulates the offline migration of an existing booking; never changes stock. */
public final class BookingAttemptFixtures {
    private BookingAttemptFixtures() {}
    public static void seed(Booking booking, BookingAttemptRepository attempts, StringRedisTemplate redis) {
        BookingAttempt a = new BookingAttempt(UUID.randomUUID().toString(), booking.getConcert().getId(), booking.getUserId());
        a.booked(booking.getId(), booking.getConcert().getTitle());
        attempts.saveAndFlush(a);
        redis.opsForHash().putAll("booking:attempt:" + a.getId(), Map.of(
                "owner", a.getConcertId() + ":" + a.getUserId(), "state", "HELD",
                "remaining", "0", "admissionExpires", "0"));
        redis.opsForValue().set("booking:active:concert:" + a.getConcertId() + ":user:" + a.getUserId(), a.getId());
    }
}
