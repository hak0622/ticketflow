package studying.blog.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import studying.blog.support.IntegrationTestSupport;
import studying.blog.domain.*;
import studying.blog.service.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Historical unsafe behavior characterization, not a safety pass. Requires preserved pre-change Lua files. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named="LUA_BASELINE", matches="true")
class RedisLuaBaselineIntegrationTest extends IntegrationTestSupport {
 @Autowired BookingAttemptTransactions transactions;
 @Autowired BookingSeatReleaseService recovery;
 @Autowired BookingSeatStore store;
 List<String> keys(BookingAttempt a){return List.of("seats:concert:"+a.getConcertId(),"admitted:concert:"+a.getConcertId()+":user:1","booking:attempt:"+a.getId(),"booking:active:concert:"+a.getConcertId()+":user:1");}
 @Test void oldReserveCanCreateBookingWithoutDecrement() throws Exception {
  var c=savedConcert("baseline reserve",10,0);grantAdmitted(c.getId(),1L);
  String id=UUID.randomUUID().toString();transactions.create(id,c.getId(),1L);var a=attemptRepository.findById(id).orElseThrow();
  String script=Files.readString(Path.of(System.getenv("LUA_BASELINE_DIR"),"reserve-booking.lua"));
  String target="redis.call('DECR', KEYS[1])";assertThat(script).contains(target);
  script=script.replace(target,"error('injected ordinary Lua failure')\n"+target);
  var fault=new DefaultRedisScript<Long>(script,Long.class);
  assertThatThrownBy(()->redisTemplate.execute(fault,keys(a),c.getId()+":1",id)).isInstanceOf(RuntimeException.class);
  jdbcTemplate.update("UPDATE booking_attempt SET next_retry_at=? WHERE id=?",java.time.LocalDateTime.now().minusSeconds(1),id);
  recovery.recover(id);
  assertThat(bookingRepository.count()).isEqualTo(1);assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(10);
 }
 @Test void oldReleaseCanIncrementTwice() throws Exception {
  var c=savedConcert("baseline return",10,0);var b=savedPendingBooking(c,1L);queueService.initSeatCount(c.getId(),9);
  var a=attemptRepository.findByBookingId(b.getId()).orElseThrow();
  String script=Files.readString(Path.of(System.getenv("LUA_BASELINE_DIR"),"release-booking.lua"));
  String target="redis.call('INCR', KEYS[1])";assertThat(script).contains(target);
  script=script.replace(target,target+"\nerror('injected ordinary Lua failure')");
  var fault=new DefaultRedisScript<Long>(script,Long.class);
  assertThatThrownBy(()->redisTemplate.execute(fault,keys(a),c.getId()+":1",a.getId(),"0","1")).isInstanceOf(RuntimeException.class);
  store.release(a);assertThat(queueService.getRemainingSeat(c.getId())).isEqualTo(11);
 }
}
