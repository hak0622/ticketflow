package studying.blog.fault;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.*;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import studying.blog.support.IntegrationTestSupport;
import studying.blog.service.*;
import studying.blog.dto.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

abstract class CircuitPgSupport extends IntegrationTestSupport {
 @Autowired TossPaymentClient pg;
 @Autowired CircuitBreakerRegistry registry;
 @Autowired ObjectMapper json;
 @Autowired BookingService bookings;
 @Autowired PaymentService payments;
 @Autowired PaymentSaga saga;
 CircuitBreaker cb;
 HttpServer server;
 ExecutorService executor;
 Object target;
 RestClient original;
 volatile boolean failing, loseApproval, loseCancel;
 volatile long delay;
 volatile CountDownLatch gate;
 final List<String> arrivals=new CopyOnWriteArrayList<>();
 final List<Map<String,Object>> httpOutcomes=new CopyOnWriteArrayList<>();
 final List<Map<String,Object>> postRequests=new CopyOnWriteArrayList<>();
 final List<String> events=new CopyOnWriteArrayList<>();
 final Map<String,Map<String,Object>> ledger=new ConcurrentHashMap<>();
 int approvals,cancellations;
 String testName;
 @BeforeEach void setupPg(TestInfo info) throws Exception {
  testName=info.getTestMethod().orElseThrow().getName();
  cb=registry.circuitBreaker("toss"); cb.reset();
  assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
  assertThat(cb.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50);
  assertThat(cb.getCircuitBreakerConfig().isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
  assertThat(cb.getCircuitBreakerConfig().getSlowCallDurationThreshold()).isEqualTo(java.time.Duration.ofSeconds(2));
  assertThat(cb.getCircuitBreakerConfig().getSlowCallRateThreshold()).isEqualTo(80);
  assertThat(cb.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
  assertThat(cb.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30000L);
  cb.getEventPublisher().onEvent(e->events.add(System.currentTimeMillis()+" "+e.toString()));
  executor=Executors.newCachedThreadPool();
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
  server.createContext("/",x->{
   try {
    String path=x.getRequestURI().getPath(); boolean post=x.getRequestMethod().equals("POST");
    boolean cancel=path.endsWith("/cancel");
    arrivals.add(System.currentTimeMillis()+" "+x.getRequestMethod()+" "+path+" "+Thread.currentThread().getName());
    if(failing){x.sendResponseHeaders(503,-1);return;}
    String key=path.substring(path.lastIndexOf('/')+1);
    Map<String,Object> result;
    if(post&&!cancel){
     var body=json.readTree(x.getRequestBody());events.add("approval-body="+body+" idempotency="+x.getRequestHeaders().getFirst("Idempotency-Key"));key=body.get("paymentKey").asText();
     postRequests.add(Map.of("kind","approve","paymentKey",key,"body",body,"idempotencyKey",Objects.toString(x.getRequestHeaders().getFirst("Idempotency-Key"),"")));
     final String k=key;
     result=ledger.computeIfAbsent(k,unused->{ synchronized(this){approvals++;} return new ConcurrentHashMap<>(Map.of("paymentKey",k,"orderId",body.get("orderId").asText(),"totalAmount",90000,"balanceAmount",90000,"status","DONE","method","CARD"));});
    }else if(cancel){
     var cancelBody=json.readTree(x.getRequestBody());
     events.add("cancel-body="+cancelBody+" idempotency="+x.getRequestHeaders().getFirst("Idempotency-Key"));
     postRequests.add(Map.of("kind","cancel","paymentKey",path.split("/")[3],"body",cancelBody,"idempotencyKey",Objects.toString(x.getRequestHeaders().getFirst("Idempotency-Key"),"")));
     key=path.split("/")[3]; result=ledger.get(key);
     if(result!=null&&!result.get("status").equals("CANCELED")){result.put("status","CANCELED");result.put("balanceAmount",0);synchronized(this){cancellations++;}}
    }else result=ledger.get(key);
    if(result==null&&!key.equals("probe")){x.sendResponseHeaders(404,-1);return;}
    if(result==null)result=Map.of("paymentKey",key,"orderId","probe-order","totalAmount",90000,"balanceAmount",90000,"status","DONE","method","CARD");
    if(gate!=null&&!gate.await(1500,TimeUnit.MILLISECONDS))throw new IllegalStateException("gate deadline");
    long pause=delay;
    if((post&&!cancel&&loseApproval)||(cancel&&loseCancel))pause=3600;
    if(pause>0)Thread.sleep(pause);
    byte[] bytes=json.writeValueAsBytes(result);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
   }catch(Exception ignored){events.add("PG response interrupted "+ignored.getClass().getSimpleName());}finally{x.close();}
  });server.start();
  assertThat(org.springframework.aop.support.AopUtils.isAopProxy(pg)).isTrue();
  target=AopTestUtils.getUltimateTargetObject(pg);original=(RestClient)ReflectionTestUtils.getField(target,"restClient");
  var redirected=original.mutate().requestInterceptor((request,body,execution)->{
   URI local=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+request.getURI().getPath());
   if(!local.getHost().equals("127.0.0.1"))throw new IllegalStateException("external PG forbidden");
   long started=System.nanoTime();
   try {
    var response=execution.execute(new HttpRequestWrapper(request){@Override public URI getURI(){return local;}},body);
    httpOutcomes.add(Map.of("path",local.getPath(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"outcome","response"));
    return response;
   } catch(java.io.IOException failure) {
    httpOutcomes.add(Map.of("path",local.getPath(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"outcome",failure.getClass().getName()));
    throw failure;
   }
  }).build();ReflectionTestUtils.setField(target,"restClient",redirected);
 }
 @AfterEach void finishPg() throws Exception {
  try {
  try { verifyPgRequests(); } finally {
  Path folder=Path.of(System.getenv().getOrDefault("CB_RESULTS","test-results/payment-circuit-breaker/current"));Files.createDirectories(folder);
  String name=getClass().getSimpleName()+"-"+testName+"-"+UUID.randomUUID();
  Files.writeString(folder.resolve(name+".json"),json.writeValueAsString(Map.of("events",events,"http",arrivals,"ledger",ledger,"approvalEffects",approvals,"cancelEffects",cancellations,"state",cb.getState().name(),"buffered",cb.getMetrics().getNumberOfBufferedCalls(),"failureRate",cb.getMetrics().getFailureRate(),"slowRate",cb.getMetrics().getSlowCallRate())));
  Files.writeString(folder.resolve(name+"-db.json"),json.writeValueAsString(Map.of("payments",jdbcTemplate.queryForList("SELECT id,status,recovery_phase,retry_count,next_retry_at FROM payment"),"bookings",jdbcTemplate.queryForList("SELECT id,status FROM booking"),"attempts",jdbcTemplate.queryForList("SELECT id,status,booking_id FROM booking_attempt"),"outbox",jdbcTemplate.queryForList("SELECT id,status FROM payment_compensation_outbox"))));
  var redisState=new java.util.TreeMap<String,Object>();
  for(String key:redisTemplate.keys("*")){var type=redisTemplate.type(key);if(type==org.springframework.data.redis.connection.DataType.HASH)redisState.put(key,redisTemplate.opsForHash().entries(key));else if(type==org.springframework.data.redis.connection.DataType.STRING)redisState.put(key,redisTemplate.opsForValue().get(key));}
  Files.writeString(folder.resolve(name+"-redis.json"),json.writeValueAsString(redisState));
  Files.writeString(folder.resolve(name+"-http.json"),json.writeValueAsString(Map.of("requests",postRequests,"outcomes",httpOutcomes)));
  }
  } finally { cleanupPg(); }
 }
 void cleanupPg() throws InterruptedException {
  try {
   if(gate!=null)gate.countDown();
   if(server!=null)server.stop(0);
  } finally {
   try {
    if(executor!=null){executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
   } finally {
    try {if(target!=null&&original!=null)ReflectionTestUtils.setField(target,"restClient",original);}
    finally {if(cb!=null)cb.reset();}
   }
  }
 }
 void verifyPgRequests() {
  for(var request:postRequests){
   var rows=jdbcTemplate.queryForList("SELECT idempotency_key,pg_payment_key,amount,confirm_key,cancel_key,cancel_reason FROM payment WHERE pg_payment_key=?",request.get("paymentKey"));
   assertThat(rows).as("PG request must correspond to a durable Payment").hasSize(1);
   var stored=rows.get(0);
   var body=(com.fasterxml.jackson.databind.JsonNode)request.get("body");
   if(request.get("kind").equals("approve")){
    assertThat(body.get("paymentKey").asText()).isEqualTo(stored.get("pg_payment_key"));
    assertThat(body.get("orderId").asText()).isEqualTo(stored.get("idempotency_key"));
    assertThat(body.get("amount").asInt()).isEqualTo(((Number)stored.get("amount")).intValue());
    assertThat(request.get("idempotencyKey")).isEqualTo(stored.get("confirm_key"));
   }else{
    assertThat(body.get("cancelReason").asText()).isEqualTo(stored.get("cancel_reason"));
    assertThat(body.get("cancelAmount").asInt()).isEqualTo(((Number)stored.get("amount")).intValue());
    assertThat(request.get("idempotencyKey")).isEqualTo(stored.get("cancel_key"));
   }
  }
 }
 void lookup(){pg.lookup("probe");}
 void error(){assertThatThrownBy(this::lookup).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);}
 void open(){failing=true;for(int i=0;i<10&&cb.getState()==CircuitBreaker.State.CLOSED;i++)error();assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);failing=false;}
 void waitOpen() throws Exception {Thread.sleep(30100);assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);}
 TossConfirmRequest request(String key)throws Exception{return json.readValue(json.writeValueAsString(Map.of("paymentKey",key,"orderId",UUID.randomUUID().toString(),"amount",90000)),TossConfirmRequest.class);}
}
