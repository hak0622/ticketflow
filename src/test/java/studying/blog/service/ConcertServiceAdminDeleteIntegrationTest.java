package studying.blog.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import studying.blog.domain.Concert;
import studying.blog.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ConcertServiceAdminDeleteIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ConcertService concertService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void adminDelete_committedTransaction_deletesSeatAndStatusCacheAfterCommit() {
        Concert concert = savedConcert("삭제 테스트", 100, 0);
        queueService.setConcertInfo(concert.getId(), concert.getStatus(), concert.getTitle());

        transactionTemplate.executeWithoutResult(status -> concertService.adminDelete(concert.getId()));

        assertThat(concertRepository.findById(concert.getId())).isEmpty();
        assertThat(redisTemplate.opsForValue().get(seatKey(concert.getId()))).isNull();
        assertThat(redisTemplate.opsForValue().get(statusKey(concert.getId()))).isNull();
    }

    @Test
    void adminDelete_rolledBackTransaction_keepsSeatAndStatusCache() {
        Concert concert = savedConcert("롤백 테스트", 80, 0);
        queueService.setConcertInfo(concert.getId(), concert.getStatus(), concert.getTitle());
        String expectedSeat = redisTemplate.opsForValue().get(seatKey(concert.getId()));
        String expectedStatus = redisTemplate.opsForValue().get(statusKey(concert.getId()));

        transactionTemplate.executeWithoutResult(status -> {
            concertService.adminDelete(concert.getId());
            status.setRollbackOnly();
        });

        assertThat(concertRepository.findById(concert.getId())).isPresent();
        assertThat(redisTemplate.opsForValue().get(seatKey(concert.getId()))).isEqualTo(expectedSeat);
        assertThat(redisTemplate.opsForValue().get(statusKey(concert.getId()))).isEqualTo(expectedStatus);
    }

    @Test
    void adminDelete_withBookings_removesConcertAndRedisState() {
        Concert concert = savedConcert("예매 삭제 테스트", 50, 0);
        savedPendingBooking(concert, 101L);
        queueService.setConcertInfo(concert.getId(), concert.getStatus(), concert.getTitle());

        transactionTemplate.executeWithoutResult(status -> concertService.adminDelete(concert.getId()));

        assertThat(concertRepository.findById(concert.getId())).isEmpty();
        assertThat(bookingRepository.findAllByConcertId(concert.getId())).isEmpty();
        assertThat(redisTemplate.opsForValue().get(seatKey(concert.getId()))).isNull();
        assertThat(redisTemplate.opsForValue().get(statusKey(concert.getId()))).isNull();
    }

    private static String seatKey(Long concertId) {
        return "seats:concert:" + concertId;
    }

    private static String statusKey(Long concertId) {
        return "concert:status:" + concertId;
    }
}
