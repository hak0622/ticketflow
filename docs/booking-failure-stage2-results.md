# 2단계 Redis–MySQL 장애 테스트 결과

> 후속 안내: 아래는 실행 당시 기록입니다. [최신 선점 유지 정책](booking-hold-recovery.md)과 [후속 검증](booking-hold-recovery-results.md)를 함께 확인하세요. 당시 실패·미검증을 현재 전체 범위로 해석하지 않습니다.

> 이 문서는 작성·실행 당시의 계획 또는 검증 기록입니다. 현재 흐름은 [문서 안내](README.md), PG 구현 및 한계는 [결제 결과](payment-saga-results.md)를 확인하세요. 과거 결과는 현재 전체 기능의 검증을 뜻하지 않습니다.

## 검증 방식

`BookingFailureStageTwoIntegrationTest`는 MySQL 8.4와 Redis 7 Testcontainers를 직접 띄운다. H2와 Mockito를 사용하지 않는다. Docker가 없으면 skipped로 통과시키지 않고 실행 실패로 처리한다.

테스트 전용 TCP 프록시가 MySQL/Redis 실제 서버와 실제 애플리케이션 사이에서 패킷을 전달한다. 응답 유실 시 서버 응답을 받은 뒤 애플리케이션에 전달하지 않는다. DB/Redis 결과를 가짜 반환값으로 대체하지 않는다.

각 테스트는 실제 Redis GET, 새 MySQL SELECT로 아래를 함께 확인한다.

- Redis 잔여석과 요청별 HELD/RELEASED 표식
- Booking 행의 유무, 상태, booking_attempt와의 booking_id 연결
- booking_attempt 상태
- 종료 상태에서는 `Redis 잔여석 + 유효 Booking 수 = 총 10석`

처리 중에는 Redis HELD가 있고 Booking은 아직 없는 상태가 정상적으로 존재한다. 이 상태와 복구 완료 후 상태를 구분한다.

## 결과

**9개 성공 / 실패 0개 / 오류 0개 / skipped 0개.**

최종 실행은 Gradle `BUILD SUCCESSFUL in 33s`로 종료했다. 강제 종료 테스트에서 자식 애플리케이션 PID 9262가 종료 코드 **137**로 종료된 것을 확인했다. 해당 요청은 종료 전 `9 / Booking 없음 / REQUESTED`, Worker 복구 후 `10 / Booking 없음 / RELEASED`였다. 테스트 로그와 XML에 상태 검증 결과가 기록되어 있다.

| 요구 상황 | 실제 재현 방법 | 검증한 최종 상태 (총 10석) | 결과 |
|---|---|---|---|
| 1. Redis 차감 후 Booking INSERT 실패 | MySQL BEFORE INSERT 트리거에서 SQLSTATE 45000 발생. Lua 실행 직후 잔여석 9와 REQUESTED도 확인 | 잔여석 10 / Booking 없음 / RELEASED | 성공 |
| 2. DB 커밋 성공 후 응답 유실 | 실제 COMMIT 응답 패킷을 받은 TCP 프록시가 연결을 끊어 JDBC에 응답을 전달하지 않음 | 잔여석 9 / PENDING_PAYMENT 1건 / BOOKED. 재요청·Worker 실행 후에도 동일 | 성공 |
| 3-a. Redis 선점 성공 후 응답 유실 | reserve Lua 응답을 버리고 TCP를 열어 둔 채 응답을 차단해 실제 Redis 클라이언트 타임아웃 발생 | 복구 후 잔여석 10 / Booking 없음 / RELEASED. 같은 요청 재시도 시 재차감 없음 | 성공 |
| 3-b. Redis 반환 성공 후 응답 유실 | release Lua 응답을 같은 방식으로 차단. DB에 반환 완료를 기록하지 못하도록 함 | 중간: 잔여석 10 / CANCELLED / RELEASE_PENDING → Worker 후 잔여석 10 / CANCELLED / RELEASED | 성공 |
| 3-c. Redis 재연결 시 드라이버 자동 재전송 | Lua 응답 수신 후 TCP 연결을 끊음. Lettuce 재연결·재전송을 실제 수행 | 잔여석 9 / PENDING_PAYMENT 1건 / BOOKED. 재전송으로 중복 차감되지 않음 | 성공 |
| 4. 예매 서버 종료 후 복구 | 별도 Spring Boot JVM에서 실제 book 실행. Redis 선점 후 DB 트랜잭션 진행을 테스트 게이트로 멈추고, 생존 확인 후 destroyForcibly로 강제 종료 | 종료 전: 잔여석 9 / Booking 없음 / REQUESTED → 다른 JVM의 Worker 복구 후 잔여석 10 / Booking 없음 / RELEASED. 늦은 reserve 거부 | 성공 |
| 5. 원래 요청과 Worker 동시 실행 | 실제 Lua 선점 직후 원래 요청을 멈춰 요청 행 잠금 유지. 별도 스레드에서 실제 Worker를 실행하고 대기하는지 확인 | 원래 요청 커밋 후 잔여석 9 / PENDING_PAYMENT 1건 / BOOKED. Worker가 좌석 반환하지 않음 | 성공 |
| 6. 중복 차감 및 중복 반환 | 같은 ID로 12개 스레드의 book 동시 실행. 취소 후 12개 스레드의 반환 복구 동시 실행 | 선점 후 9 / PENDING_PAYMENT 1건 / BOOKED → 반환 후 10 / CANCELLED 1건 / RELEASED | 성공 |
| 7. 만료와 결제 보상 동시 실행 | 실제 실패 Payment와 Outbox 저장. 시작 배리어를 사용해 만료 스케줄러와 Outbox processor를 별도 스레드에서 동시 실행 | 잔여석 10 / CANCELLED 1건 / RELEASED / Outbox PUBLISHED. 반복 실행 후에도 동일 | 성공 |

## 최초 실행에서 실패한 항목과 수정

첫 실행은 8개 중 4개가 실패했다. 애플리케이션의 수량 정합성 버그로 확인된 실패는 없었으며, 아래 테스트 환경/가정을 수정했다.

| 실패 | 원인 | 수정 |
|---|---|---|
| 선점 응답 유실에서 예외를 기대했으나 성공 | 연결을 끊자 Lettuce가 Lua를 자동 재전송했고 멱등 처리 후 BOOKED 반환. 응답 유실이 반드시 애플리케이션 예외로 이어진다는 가정이 잘못됨 | 연결 유지 + 응답 차단으로 타임아웃을 강제하는 경우와, 재연결·자동 재전송으로 성공하는 경우를 별도 테스트로 분리 |
| 반환 응답 유실에서 RELEASE_PENDING을 기대했으나 RELEASED | 같은 자동 재전송으로 반환 결과를 정상 수신해 DB 완료까지 커밋 | 응답 차단을 타임아웃까지 유지하여 실제 미완료 반환 기록과 Worker 재시도를 확인 |
| INSERT 실패 트리거를 생성하지 못함 | 테스트용 MySQL 계정에 SUPER 권한이 없고 binary logging 설정 때문에 트리거 생성 거부 | 해당 테스트 컨테이너에만 log_bin_trust_function_creators=1 적용. 운영 설정은 변경하지 않음 |
| 강제 종료 지점 도달 전 60초 초과 | 자식 JVM에 기본 local 프로파일도 활성화되어 개발용 테스트 사용자 생성 작업이 실행됨 | 자식 프로세스에 SPRING_PROFILES_ACTIVE=test와 APP_TEST_SUPPORT_ENABLED=false를 명시 |

컴파일 준비 단계의 Testcontainers Container import 충돌도 테스트 import를 명시적으로 바꿔 해결했다.

## 변경 범위

애플리케이션의 기존 예매·복구·만료·결제 아키텍처 및 운영 코드는 이번 단계에서 수정하지 않았다.

- `src/test/java/studying/blog/fault/BookingFailureStageTwoIntegrationTest.java`: 실제 DB·Redis 장애/경합 시나리오 9개
- `src/test/java/studying/blog/fault/ResponseLossProxy.java`: RESP/MySQL TCP 응답 유실 프록시
- `src/test/java/studying/blog/fault/BookingBoundaryGate.java`: 실제 Lua 실행 이후에만 작동하는 테스트용 동기화 게이트
- `src/test/java/studying/blog/fault/CrashBookingProcess.java`: 강제 종료 대상인 별도 Spring Boot 애플리케이션 JVM
- `build.gradle`: 자식 JVM이 동일 테스트 runtime classpath로 실행될 수 있도록 test 시스템 속성 1개 추가
- 이 결과 문서 및 기존 검증 기록에 후속 검증 링크 추가

## 재현

Docker Desktop 실행 후:

```bash
./gradlew test --tests studying.blog.fault.BookingFailureStageTwoIntegrationTest --offline
```

의존성이 아직 캐시에 없다면 `--offline`을 제외한다. 테스트가 생성한 전용 컨테이너와 데이터만 사용한다. 기존 개발 DB·Redis를 초기화하지 않는다.

실행 결과는 `build/test-results/test/TEST-studying.blog.fault.BookingFailureStageTwoIntegrationTest.xml` 및 `build/reports/tests/test/index.html`에 기록된다. `[STAGE2_STATE]` 로그에는 검증한 세 저장 상태, `[STAGE2_KILL]`에는 강제 종료한 PID·종료 코드·자식 프로세스 로그 경로를 남긴다.

## 미검증 및 경계

- 예매 JVM 강제 종료는 실제 재현했다. MySQL·Redis 프로세스 자체 강제 종료, 데이터 전체/부분 유실, 복제/failover 쓰기 유실은 미검증이다.
- 응답 유실은 애플리케이션과 저장소 사이 TCP에서 실제 차단했다. 최종 사용자와 웹 서버 사이 HTTP 응답 유실은 별도로 재현하지 않았다.
- Worker는 실제 서비스 Bean을 다른 실행 컨텍스트에서 호출했다. 이 테스트는 5초 스케줄러의 자동 기동·리더 교체를 검증하지 않는다.
- 30초 요청 유예와 30분 만료를 기다리는 대신 DB 시각을 조정하여 해당 조건을 만족시켰다. 서버 종료와 네트워크 응답 차단 자체는 실제 수행했다.
- PG 외부 승인/조회/취소, 재고 전체 재구축, Redis Cluster, 메모리 고갈, 장시간 장애의 대량 backlog, 500명 성능은 미검증이다.
