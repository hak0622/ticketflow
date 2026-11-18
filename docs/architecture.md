# 현재 아키텍처

2026-11-12 문서 점검 기준. 동작은 현재 소스와 대조했다. [문서 안내](README.md) · [처리 흐름](flow.md) · [테스트와 한계](testing.md).

## 구조와 읽는 순서

Spring Boot, MySQL, Redis, 외부 Toss PG를 사용한다. 프론트는 React + Vite이며 지정 좌석 대신 공연별 수량을 관리한다. 코드의 컨트롤러/설정은 `studying.blog.controller`, `config`에 있으며 가상의 api/web 하위 패키지로 나뉘지 않는다.

| 역할 | 진입점과 호출 대상 |
|---|---|
| 대기열 | ConcertQueueApiController → QueueService |
| 입장권 발급 | ConcertQueueScheduler → QueueService.popAndGrantAdmitted |
| 예매 | BookingApiController → BookingService → BookingAttemptTransactions → BookingSeatStore |
| 예매 복구·반환 | BookingAttemptRecoveryWorker / BookingExpiryScheduler → BookingSeatReleaseService → BookingAttemptTransactions |
| 결제 | PaymentApiController → PaymentService.tossConfirm → PaymentSaga → TossPaymentClient |
| 결제 재확인 | PaymentRecoveryWorker → PaymentSaga.recover |
| 티켓 보상 | PaymentCompensationScheduler → PaymentCompensationOutboxProcessor → BookingSeatReleaseService |

주요 도메인은 Concert, Booking, BookingAttempt, Payment, PaymentCompensationOutbox다. `experiments/`는 읽기 전용 전략 비교 자료이며 운영 코드에서 import하지 않는다. `testsupport/`는 일반 local에서는 기본 비활성화하며 격리 환경에서 명시적으로 켜는 테스트 API, `diagnostics/`는 선택적 계측 코드다. 일반 사용자 플로우와 구분한다.

## 대기열과 재고

ConcertQueueScheduler는 인스턴스별 fixedDelay 1초, 회당 공연별 최대 100명을 입장시킨다. `redis/pop-concert-queue.lua`가 앞쪽 최대 100개를 검사하고 이미 입장권이 있는 중복 항목은 제거만 한다. 새 입장권만 발급·집계하므로 실제 신규 발급은 100명보다 적을 수 있고 추가 항목을 조회해 채우지 않는다. 전체 선택 대상의 자료형·인자를 첫 쓰기 전에 검사하고, 새 입장권 저장 후 큐 항목을 제거한다. 전역 100명 제한이나 입장권에 의한 재고 선점을 뜻하지 않는다. 입장권 TTL은 600초다.

등록은 `redis/enqueue-concert.lua`에서 입장권을 먼저 확인하고 기존 대기 항목은 ZADD NX로 점수를 유지한다. 조회는 읽기 전용 `redis/read-concert-queue.lua`로 상태·순번·인원 수를 함께 읽는다. 입장권이 있으면 ADMITTED가 우선이며 GET은 공존한 큐 항목도 수정하지 않는다. 등록·발급은 기존 입장권 TTL을 연장하지 않는다. Redis 오류·잘못된 응답을 정상 대기나 순번 0으로 변환하지 않는다.

이 보장은 입장권이 존재하는 동안의 재발급과 대기 중 점수 덮어쓰기를 방지한다. 입장권 소비 후 Booking 상태에 따른 재등록 차단은 이번 수정에 포함하지 않는다. 앱 타임스탬프·동점 문자열 정렬은 유지하며 엄격한 전역 도착 순서를 보장하지 않는다. Lua의 일반 실행 오류는 앞선 쓰기를 롤백하지 않으므로 OOM·Redis 유실까지 자동 복구한다고 주장하지 않는다.

| Redis 키 | 역할 |
|---|---|
| queue:concert:{concertId} | 대기열 Sorted Set |
| admitted:concert:{concertId}:user:{userId} | 입장권, 600초 TTL |
| seats:concert:{concertId} | 잔여 티켓 수량 |
| booking:attempt:{requestId} | 요청별 HELD/RELEASED, 소유자·매진/반환 결과 |
| booking:active:concert:{concertId}:user:{userId} | 사용자별 활성 선점 요청 |
| concert:status:{concertId} | 공연 정보 캐시, 30초 TTL |
| payment:idempotency:{key} | Mock 결제의 SETNX 처리 중 표식, 30초 TTL |

운영 재고 프로토콜은 `redis/reserve-booking.lua`, 읽기 전용 `redis/inspect-booking-hold.lua`, `redis/release-booking.lua`다. reserve는 입장권·잔여 수량·요청 표식을 확인하고 차감과 입장권 소비를 원자 처리한다. 정상 매진은 수량·입장권을 바꾸지 않고 RELEASED/SOLD_OUT을 기록한다. release는 요청별 반환을 한 번만 반영한다. HELD는 아직 반환하지 않은 차감 이력이며 결제 완료 뒤에도 남는다. 최초 선점에 holdVersion=2와 Redis TIME 기준 holdExpiresAt을 기록하며 재시도는 이를 연장하지 않는다. 요청 해시와 활성 선점 키가 5분 뒤 TTL로 자동 삭제되는 구조가 아니다. 전체 수량에서 Redis 잔여 수량을 뺀 값은 결제 전 선점도 포함한 확보 수량이며 결제 완료 건수가 아니다.

QueueService.claimAdmitted/restoreSeatOnceForCompensation은 옛 테스트용 경로다. restoreAdmitted는 테스트 입장권 준비에도 사용한다. 운영 예매가 이 메서드들을 호출한다고 해석하면 안 된다.

## 트랜잭션과 잠금

구현 근거: [예매 트랜잭션](../src/main/java/studying/blog/service/BookingAttemptTransactions.java), [Redis 선점](../src/main/resources/redis/reserve-booking.lua)·[반환](../src/main/resources/redis/release-booking.lua), [결제 Saga](../src/main/java/studying/blog/service/PaymentSaga.java), [재시도 회차](../src/main/java/studying/blog/service/PaymentRetryRound.java). HTTP 응답은 [API 안내](api.md)를 따른다.

- 예매: REQUESTED INSERT 선커밋 → 별도 트랜잭션에서 요청 행 잠금·Redis reserve·Booking INSERT와 BOOKED 갱신. 공연 행의 비관적 락이나 매 예매 bookedCount UPDATE를 재도입하지 않는다. 마지막 좌석 SOLD_OUT 상태 변경은 별도 의미다.
- 예매 저장 실패/응답 유실: 새 요청 잠금 조회로 DB 결과를 먼저 확인한다. 이미 BOOKED면 기존 결과를 사용하고, 유효한 선점이 남으면 REQUESTED를 유지해 생성 재시도를 예약한다. Worker도 동일 요청 행 잠금 안에서 Booking 생성과 BOOKED를 함께 커밋한다. 미선점 요청을 Worker가 새로 차감하지 않으며, 만료/공연 종료 등 완료 불가 요청은 반환 경로로 보낸다. 결과 불명확·손상 기록은 임의 반환하지 않는다. 정상 매진 REJECTED/SOLD_OUT 복구는 반환 Lua를 실행하지 않는다.
- PG: Payment PENDING 준비 커밋 → 최초 승인 호출 선점 커밋 → 외부 승인 HTTP → Booking/Payment 확정 커밋 → 응답 조회. 상태·오류에 따라 재시도 트랜잭션이 추가될 수 있으며 '항상 2회'가 아니다. 최초 호출 선점은 현재 retryCount도 사용한다.
- PG 승인·조회·취소 HTTP는 DB 트랜잭션 밖에서 실행한다. 결과 적용은 Booking→Payment 잠금으로 직렬화한다. Payment COMPLETED와 Booking CONFIRMED는 같은 트랜잭션이다.
- 취소·반환: Booking 잠금 안에서 결제를 보호하고 Booking CANCELLED + attempt RELEASE_PENDING을 먼저 커밋한다. 별도 요청 잠금 트랜잭션에서 Redis 반환과 후처리·RELEASED를 처리한다. 공연 재개는 현재 잔여 수량과 SOLD_OUT 조건부 UPDATE를 사용해 CLOSED를 보호한다.
- Outbox Processor는 바깥 REQUIRES_NEW를 사용하며 공통 반환 내부에도 독립 트랜잭션이 있다. 외부 PG 취소는 이 Processor 안에서 실행하지 않는다.

## Worker와 스케줄러

| 클래스 | 주기 | 역할 |
|---|---|---|
| ConcertQueueScheduler | 1초 | 공연별 입장권 발급. ShedLock 없음 |
| BookingAttemptRecoveryWorker | 5초 | due 미완료 최대 100건. 유효 선점은 생성 재개, 만료/미선점은 종료·반환. 최초 REQUESTED 유예 30초, 요청 행 잠금 |
| PaymentRecoveryWorker | 5초 | due PENDING 최대 100건 PG 재조회, backoff |
| PaymentCompensationScheduler | 10초 | 기존 반환 Outbox 처리, 보류와 일반 오류 구분 |
| BookingExpiryScheduler | 60초 | 10분 초과 Booking 취소 후보 처리, 잠금 안에서 미확정/완료 결제 보호 |

Outbox와 만료 Scheduler는 ShedLock을 사용하지만 안전성은 만료되지 않는 전역 락이 아니라 상태 재검사·행 잠금·멱등 반환에도 의존한다. 결제 Worker는 중복 조회를 허용하고 잠금·저장된 PG 멱등키로 보호한다. 후보 선택 때 retryCount/nextRetryAt/recoveryPhase 스냅샷을 보존하고 실패 저장 시 현재 DB와 비교해 같은 회차를 한 번만 갱신한다. version은 진단용이며 단독 회차 식별자가 아니다. 정상 결과는 스냅샷이 오래됐다는 이유만으로 버리지 않고 현재 상태와 PG 정보를 검증한다.

## 상태와 한계

| 저장 위치 | 기록 | 의미 |
|---|---|---|
| Redis 요청 해시 | HELD | 요청이 차감한 티켓을 아직 반환하지 않음. Booking/결제 완료 후에도 유지 |
| Redis 요청 해시 | RELEASED + releaseOutcome=RETURNED | 실제 재고 증가와 반환 기록이 같은 Lua에서 완료 |
| Redis 요청 해시 | RELEASED + releaseOutcome=NOT_HELD | 미선점 요청 종료. 재고 증가 없음 |
| Redis 요청 해시 | RELEASED + result=SOLD_OUT | 정상 매진 종료. 재고·입장권 변경 없음 |
| MySQL 예매 요청 | REQUESTED / BOOKED / REJECTED | 처리 미확정 / Booking 저장 완료 / 매진 또는 기존 예매 결과로 종료 |
| MySQL 예매 요청 | RELEASE_PENDING / RELEASED | 반환·후처리 필요 / 반환 경로 종료 |
| MySQL Booking | PENDING_PAYMENT / CONFIRMED / CANCELLED | 결제 대기 / 최종 확정 / 취소 |
| MySQL Payment | PENDING / COMPLETED / FAILED / CANCELED | 결과 미확정 / 승인 확정 / 최종 실패 / 전액 취소 확정 |
| MySQL Outbox | PENDING / PUBLISHED / FAILED | 보상 작업 미완료 / 처리 종료 / 재시도 소진 |

RELEASED만으로 실제 티켓 반환을 추정하지 않는다. 반환 Lua는 NOT_HELD / RETURNED / ALREADY_RETURNED / LEGACY_UNKNOWN을 구분한다. 종료 이력이 불완전한 옛 표식은 보수적으로 후처리하며 새 형식 손상은 재확인 대상으로 남긴다.

Redis 반환 성공 후 DB 커밋 전에 종료되면 다음 실행이 RETURNED 기록으로 중복 증가를 막고 후처리를 재개한다. DB 요청이 이미 RELEASED면 Lua 실행도 생략할 수 있다. Outbox PUBLISHED는 이보다 뒤의 별도 DB 커밋이므로 Redis·요청·Outbox 완료는 동일 시점이 아니다.

### 서로 다른 세 가지 시간

| 기준 | 기산점·처리 |
|---|---|
| 입장권 10분 | 발급 시점부터 예매 시작 권한의 TTL. 재고 선점 아님 |
| 예매 생성 복구 5분 | 최초 Redis 선점 시점부터 생성 재시도 시작 기한. TTL 자동 반환 아님 |
| 미결제 만료 10분 | Booking 생성 시점 기준 만료 후보. 60초 Scheduler와 결제 상태 보호를 거쳐 취소 |

5분 내 생성 재시도를 시작해도 커밋은 기한 뒤에 끝날 수 있다. 요청 트랜잭션 설정은 10초이며 정확한 복구 완료 시각의 보장이 아니다. Worker 주기·backoff·DB/Redis 장애에 따라 반환이 지연될 수 있다. [상세 정책·배포 조건](booking-hold-recovery.md)을 따른다.

PaymentRecoveryPhase의 FINALIZE는 선언돼 있지만 현재 코드가 그 상태를 새로 저장하지는 않는다. 승인 후 DB 확정 실패는 기존 APPROVAL_CHECK 기록에서 재조회해 복구한다. 선언된 상태를 전부 실행되는 상태라고 설명하지 않는다.

PG 미확정/취소 미확정은 반환하지 않는다. 취소 의도를 저장한 요청은 늦은 DONE으로 확정하지 않는다. 카드·간편결제의 전액 취소를 자동 보상 범위로 처리한다. 실제 Toss 테스트 환경에서 [간편결제 승인·조회](payment-easypay-fix-results.md)와 [전액 취소·늦은 승인 결과·추가 Worker](toss-live-cancel-final-results.md)를 확인했다. 로컬 모의 PG의 [SIGKILL 12회](payment-saga-fault-results.md), JDBC commit 성공 후 예외 주입([완료](payment-commit-fault-results.md)·[취소](payment-cancel-commit-fault-results.md))와 구분한다. 실제 Toss 장애·네트워크 COMMIT ACK 차단·일반 환불 API·Redis 전체 유실 복구·완료 거래 정기 대사는 검증 완료가 아니다. 취소 검증은 취소 의도 fixture를 사용했다. 최신 범위는 [문서 안내](README.md), 개별 한계는 각 결과를 따른다.

## 보안과 실행 환경

`/api/**`는 JWT Bearer, API 외 경로는 OAuth2/Google 등 별도 SecurityFilterChain을 사용한다. 구체적인 공개 경로·권한은 WebOAuthSecurityConfig가 기준이다. React/Vite 화면과 기존 Web UI 경로를 혼동하지 않는다.

Google 로그인 요청은 웹 체인의 서버 세션에 저장하고 브라우저에는 세션 식별자를 보낸다. 로그인 시작과 콜백이 다른 앱에 도착할 수 있는 구성에서는 같은 Redis 세션 저장소를 사용한다. 세션마다 최신 로그인 요청 하나를 유지하며 콜백의 `state`와 일치하는 요청을 사용한다. 세션이 없거나 만료됐거나 이전 버전에서 시작한 로그인은 다시 시작한다. 로컬 실행 주소와 검증 방법은 [테스트 안내](testing.md#개발-실행과-테스트-실행의-차이)를 따른다.

Google 인증이 끝나면 인증 세션·사용자에 묶인 60초 교환 기록을 Redis에 저장하고 프론트의 `/auth/callback`으로 이동한다. 프론트는 `POST /oauth2/exchange`로 접근 토큰을 받는다. 웹 OAuth 세션과 프론트 Origin을 확인한 뒤 Redis `GETDEL`로 교환 기록을 한 번만 소비한다. 응답 유실로 교환 완료 여부를 알 수 없으면 로그인부터 다시 시작한다.

접근 토큰과 갱신 토큰은 서명된 `token_use`로 용도를 구분한다. 보호 API는 발급자·서명·만료·접근 용도를 검증하고, 재발급 API는 갱신 용도 검증 후 서버에 등록된 값인지 확인한다. 적용 전에 발급한 JWT는 재로그인으로 교체하며, 테스트용 JWT·CSV도 다시 발급한다. 여러 앱은 동일 버전으로 적용한다.

갱신 토큰은 발급마다 무작위 식별자를 갖는다. 갱신 쿠키의 발급과 삭제에는 `HttpOnly`, `SameSite=Lax`, `Path=/`를 동일하게 적용하고, 설정된 프론트 URL이 HTTPS이면 `Secure`도 설정한다. 로그아웃은 행 잠금 안에서 쿠키의 토큰과 DB의 현재 값을 비교해 일치하는 경우에만 폐기한다. 웹 세션을 무효화한 뒤 프론트가 `204`를 받으면 로컬 인증 상태를 정리한다.

프론트는 접근 토큰을 메모리에 보관한다. 새로고침 또는 접근 토큰의 `401` 응답 이후 갱신 쿠키로 접근 토큰을 요청한다. 같은 탭에서 진행 중인 갱신 요청은 공유하며, 같은 사용자의 조회 GET만 한 번 자동 재시도한다. 결제창을 열 때 탭에 주문 식별 정보와 사용자 ID를 저장하고, 첫 승인 시도 전에 기록을 갱신한다. 새로고침이나 재로그인으로 확인을 재개하면 기존 주문을 조회한다. 다시 로그인하더라도 이전 승인 요청을 자동 전송하지 않는다.

현재 환경은 로컬이다. 개발 compose, perf의 독립 테스트 compose, 화면 시연 구성은 목적이 다르다. [실행 명령](../perf/README.md)을 따른다. GCP 배포 파일과 README의 클라우드 측정은 과거 기록이며 현재 가동 중인 인프라를 뜻하지 않는다. Kafka/Redis Stream/Saga 프레임워크는 도입하지 않았다.

## 관리자 변경 제한

두 공연 생성 POST는 ADMIN 전용이며 공개 공연 GET은 유지한다. 생성 후 총 수량은 변경 불가다. `ConcertService.adminUpdate()`는 DB 저장 수량과 다르면 엔티티 변경·Redis 조회/쓰기 전에 거절한다. 같은 수량으로 기존 메타데이터 수정은 가능하며 커밋 이후 공연 캐시를 갱신한다. 관리자 수정에서 Redis 재고를 재계산·초기화하지 않는다. 예매 요청 기록이 있는 공연의 기존 삭제 차단은 유지하며, 삭제 경합·생성 DB/Redis 장애의 새 복구 보장은 별도다. [검증 범위](admin-concert-safety-results.md).
