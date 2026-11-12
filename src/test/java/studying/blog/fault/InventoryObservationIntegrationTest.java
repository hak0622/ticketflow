package studying.blog.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import studying.blog.service.*;
import studying.blog.domain.BookingAttempt;
import studying.blog.support.IntegrationTestSupport;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class InventoryObservationIntegrationTest extends IntegrationTestSupport {
    @Autowired BookingSeatStore store;
    @Autowired InventoryObservation observation;
    @Test void paginatedPendingIsIndependentOfRetriesAndReadOnly() {
        var concert = savedConcert("observation", 300, 0);
        for (int i=0; i<105; i++) {
            var attempt = attemptRepository.saveAndFlush(new BookingAttempt(UUID.randomUUID().toString(), concert.getId(), (long)i));
            redisTemplate.opsForHash().putAll("booking:attempt:"+attempt.getId(), Map.of(
                "owner", concert.getId()+":"+i, "state", "HELD", "inventoryVersion", "1", "inventoryPhase", i<103?"RESERVE_PENDING":"RELEASE_PENDING"));
        }
        var before=jdbcTemplate.queryForList("select * from booking_attempt order by id");
        observation.observe();
        assertThat(observation.snapshot().reserve()).isEqualTo(103);
        assertThat(observation.snapshot().release()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("select * from booking_attempt order by id")).isEqualTo(before);
        assertThat(queueService.getRemainingSeat(concert.getId())).isEqualTo(300);
        var restarted=new InventoryObservation(attemptRepository,store,new SimpleMeterRegistry(),false);
        assertThat(restarted.snapshot().reserve()).isNaN();
        restarted.observe();assertThat(restarted.snapshot().reserve()).isEqualTo(103);
    }
    @Test void normalLuaIntermediatePendingIsNotVisible() throws Exception {
        var concert=savedConcert("atomic observation",10,0);
        var a=attemptRepository.saveAndFlush(new BookingAttempt(UUID.randomUUID().toString(),concert.getId(),1L));
        String key="booking:attempt:"+a.getId();
        var lua=new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
            "redis.call('HSET',KEYS[1],'owner',ARGV[1],'state','HELD','inventoryVersion','1','inventoryPhase','RESERVE_PENDING'); " +
            "local t=redis.call('TIME'); local start=t[1]*1000000+t[2]; repeat t=redis.call('TIME') until t[1]*1000000+t[2]-start>800000; " +
            "redis.call('HSET',KEYS[1],'inventoryPhase','HELD'); return 1",Long.class);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running=executor.submit(()->redisTemplate.execute(lua,List.of(key),concert.getId()+":1"));
            Thread.sleep(150);
            assertThat(running.isDone()).isFalse();
            long start=System.nanoTime();
            assertThat(store.observeInventory(a.getId(),concert.getId(),1L)).isEqualTo("COMPLETE_OR_LEGACY");
            assertThat((System.nanoTime()-start)/1_000_000).isGreaterThan(200);
            assertThat(running.get(3,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void completedAndLegacyAreNotPartialAndFailedSweepRetainsSnapshot() {
        var concert=savedConcert("complete",10,0);
        var a=attemptRepository.saveAndFlush(new BookingAttempt(UUID.randomUUID().toString(),concert.getId(),1L));
        observation.observe();assertThat(observation.snapshot().reserve()).isZero();
        grantAdmitted(concert.getId(),1L); store.reserve(a);
        observation.observe();assertThat(observation.snapshot().reserve()).isZero();
        store.release(a);observation.observe();assertThat(observation.snapshot().release()).isZero();
        var good=observation.snapshot();
        redisTemplate.opsForHash().put("booking:attempt:"+a.getId(),"inventoryVersion","99");
        observation.observe();assertThat(observation.snapshot()).isEqualTo(good);
    }
}
