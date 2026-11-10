package studying.blog.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import studying.blog.repository.BookingAttemptRepository;
import studying.blog.service.BookingSeatReleaseService;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class BookingAttemptRecoveryWorker {
    private final BookingAttemptRepository attempts;
    private final BookingSeatReleaseService release;

    @Scheduled(fixedDelay = 5_000)
    public void recoverIncompleteRequests() {
        // Multiple instances may select the same id; the shared DB row lock serializes them.
        for (String id : attempts.findRecoveryCandidates(LocalDateTime.now(), PageRequest.of(0, 100))) {
            release.recover(id);
        }
    }
}
