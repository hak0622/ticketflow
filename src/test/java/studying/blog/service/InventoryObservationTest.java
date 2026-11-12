package studying.blog.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import studying.blog.repository.BookingAttemptRepository;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class InventoryObservationTest {
    @Test void failedRedisOrDatabaseSweepNeverPublishesPartialCounts() {
        var repository=mock(BookingAttemptRepository.class);
        var store=mock(BookingSeatStore.class);
        var candidate=mock(BookingAttemptRepository.InventoryCandidate.class);
        when(candidate.getId()).thenReturn("request");
        when(candidate.getConcertId()).thenReturn(1L);when(candidate.getUserId()).thenReturn(2L);
        var observer=new InventoryObservation(repository,store,new SimpleMeterRegistry(),false);
        when(repository.findInventoryCandidates(anyString(),any())).thenReturn(List.of());
        observer.observe();var good=observer.snapshot();
        when(repository.findInventoryCandidates(eq(""),any())).thenReturn(List.of(candidate));
        when(repository.findInventoryCandidates(eq("request"),any())).thenThrow(new IllegalStateException("database unavailable"));
        when(store.observeInventory(anyString(),anyLong(),anyLong())).thenReturn("RESERVE_PENDING");
        observer.observe();assertThat(observer.snapshot()).isEqualTo(good);
        when(store.observeInventory(anyString(),anyLong(),anyLong())).thenThrow(new IllegalStateException("redis timeout"));
        observer.observe();assertThat(observer.snapshot()).isEqualTo(good);
        var restarted=new InventoryObservation(repository,store,new SimpleMeterRegistry(),false);
        restarted.observe();assertThat(restarted.snapshot().reserve()).isNaN();assertThat(restarted.snapshot().lastSuccess()).isZero();
    }
}
