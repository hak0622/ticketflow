# 결제 Orchestration Saga 구현 및 검증

> 후속 안내: 아래는 실행 당시 기록입니다. [현재 설계](architecture.md), [후속 실프로세스 장애](payment-saga-fault-results.md), [커밋 예외](payment-cancel-commit-fault-results.md), [실제 Toss 취소](toss-live-cancel-final-results.md)를 함께 확인하세요. 당시 실패·미검증을 현재 전체 범위로 해석하지 않습니다.

> 이 결과는 결제 Saga 구현 당시 검증입니다. 현재 안내는 [문서 목록](README.md), 이후 가독성 정리는 [문서 안내](README.md)를 참고하세요.

2026-11-09. 로컬 환경만 사용. 실제 외부 PG·클라우드·k6는 사용하지 않았다.

## 변경 내용

- PaymentService.tossConfirm은 PaymentSaga로 위임한다. PG HTTP 호출을 감싼 기존 전체 DB 트랜잭션을 제거했다.
- PaymentSaga는 REQUIRES_NEW/READ_COMMITTED의 짧은 트랜잭션을 사용한다. Payment 요청을 선커밋하고 외부 승인/조회/취소를 DB 트랜잭션 밖에서 호출한다.
- 첫 승인 전 별도 dispatch 기록 트랜잭션을 사용한다. retryCount=0인 요청만 최초 승인 POST를 시작한다. HTTP를 아직 보내지 않았어도 이 기록이 남을 수 있으므로 Worker는 무조건 재승인하지 않고 조회한다.
- 같은 주문 재시도는 사용자/공연/주문/금액/paymentKey/provider를 확인한다. 식별자가 없는 기존 종료 건은 소유자·주문·금액을 검사해 캐시 결과를 유지하지만 자동 PG 복구 대상으로 삼지 않는다.
- 승인 결과가 DONE이면 식별자·금액·잔액·지원 결제 수단을 확인한 뒤 Payment COMPLETED와 Booking CONFIRMED를 함께 커밋한다. 매진 상태를 기존 확보 티켓의 확정 거부 조건으로 추가하지 않는다.
- 예외는 즉시 FAILED로 바꾸지 않는다. 선커밋된 PENDING을 유지하고 재시도 정보를 남긴다. 공급자가 ABORTED/EXPIRED 최종 상태를 반환한 경우에만 FAILED와 기존 반환 Outbox를 함께 저장한다. 4xx/404를 일괄 실패로 해석하지 않는다.
- 이미 CANCELLED인 Booking에서 승인이 발견되면 취소 의도를 저장하고 PG 카드 전액 취소를 수행한다. 취소 의도 이후 DONE 응답은 예매 확정을 일으키지 않는다. 부분 취소·미지원 결제 수단은 자동 취소하지 않는다.
- PG 전액 취소를 확인한 뒤 Payment CANCELED와 반환 Outbox를 같은 트랜잭션에서 저장한다. 같은 Payment의 종료 전이는 Booking→Payment 잠금으로 직렬화되므로 Outbox를 반복 생성하지 않는다.
- PaymentRecoveryWorker는 5초 간격으로 due PENDING을 최대 100건 조회한다. backoff, 오류·확인 시각·점검 플래그와 pending/review/oldest_seconds 지표를 추가했다. Worker 중복 조회는 허용하되 변경은 잠금으로 보호한다.
- 예약 만료/보상은 Booking 잠금 안에서 Payment PENDING/COMPLETED를 검사한다. 보류 예외를 Scheduler가 따로 처리해 PUBLISHED로 기록하거나 3회 실패를 소진하지 않는다.
- 신규 Outbox는 paymentId가 같은 Booking의 FAILED/CANCELED인지 검증한다. paymentId가 없는 기존 형식은 기존 경로를 사용하되 동일한 잠금 안의 Payment 보호를 통과해야 한다. Outbox @Version과 종료 상태 재검사로 오래된 재시도가 완료 결과를 덮어쓰지 않게 했다.
- Mock 신규 결제도 같은 Booking에 기존 결제가 있으면 차단한다. 기본 REPEATABLE_READ의 오래된 스냅샷을 사용하지 않도록 이 검사는 Booking 잠금 이후 Payment 잠금 조회로 수행한다. 외부 결제 진행 중 Mock으로 확정할 수 없다.
- API 결제 조회는 COMPLETED → PENDING → 최근 종료 건 순서이며 다중 성공/미확정 이상은 숨기지 않는다. 응답에 recoveryPhase를 추가하고 프론트에서 취소 확인 중/CANCELED를 표시한다. PaymentPage는 기존 결제 조회 후 새 결제창을 열고 SDK 예외만으로 주문 ID를 교체하지 않는다.

## 파일 목록

운영 코드:

- `src/main/java/studying/blog/domain/Payment.java`
- `src/main/java/studying/blog/domain/PaymentStatus.java`
- `src/main/java/studying/blog/domain/PaymentRecoveryPhase.java` (추가)
- `src/main/java/studying/blog/domain/PaymentCompensationOutbox.java`
- `src/main/java/studying/blog/repository/PaymentRepository.java`
- `src/main/java/studying/blog/service/PaymentSaga.java` (추가)
- `src/main/java/studying/blog/service/PaymentService.java`
- `src/main/java/studying/blog/service/TossPaymentClient.java`
- `src/main/java/studying/blog/service/BookingAttemptTransactions.java`
- `src/main/java/studying/blog/service/BookingSeatReleaseService.java`
- `src/main/java/studying/blog/scheduler/PaymentRecoveryWorker.java` (추가)
- `src/main/java/studying/blog/scheduler/PaymentCompensationOutboxProcessor.java`
- `src/main/java/studying/blog/scheduler/PaymentCompensationScheduler.java`
- `src/main/java/studying/blog/exception/PaymentDeferredException.java` (추가)
- `src/main/java/studying/blog/dto/PaymentResponse.java`
- `frontend/src/features/booking/api.js`
- `frontend/src/pages/PaymentPage.jsx`
- `frontend/src/pages/PaymentSuccessPage.jsx`
- `docs/migrations/20261109-payment-saga.sql` (추가)

테스트:

- `src/test/java/studying/blog/fault/PaymentSagaIntegrationTest.java` (추가)
- `src/test/java/studying/blog/fault/PaymentPgBoundaryCharacterizationTest.java` (과거 위험 재현으로 Disabled 표시, 본문 보존)
- `src/test/java/studying/blog/service/PaymentMockPolicyTest.java` (생성자 의존성 반영)
- `src/test/java/studying/blog/integration/PaymentMockDisabledIntegrationTest.java` (캐시 요청의 필수 정보 보완)
- `src/test/java/studying/blog/scheduler/PaymentCompensationSchedulerTest.java` (실제 실패 Payment fixture 및 FK 정리)
- `src/test/java/studying/blog/scheduler/BookingExpirySchedulerTest.java` (공유 H2의 Outbox/Payment 정리 순서)

`experiments/`, 재고 Lua, OSIV/풀/timeout 설정은 변경하지 않았다. 기존에 떠 있는 시연 앱은 새 JAR로 교체하지 않았다.

## 검증 시나리오

신규 PaymentSagaIntegrationTest는 실제 MySQL 8.4/Redis 7 Testcontainers와 상태를 보관하는 로컬 HTTP PG를 사용한다. TossPaymentClient의 HTTP 목적지만 테스트에서 바꾸며 승인 결과를 Mockito로 대체하지 않는다.

1. 승인 후 응답 유실 → PENDING/반환 0 → 재조회 후 확정. 만료 시각을 지나도 먼저 반환되지 않음.
2. 승인 후 MySQL COMPLETED UPDATE를 trigger로 거부 → Payment 선커밋 유지 → 오류 제거 후 확정.
3. PG 취소 후 응답 유실 → 취소 확인 전 Outbox/반환 0 → 조회 후 CANCELED → 반복 보상에도 반환 1.
4. 조회 503 + 과거 Outbox 실행 → PENDING과 BOOKED 유지, 수량 불변, Outbox 보류.
5. 공급자 최종 실패 → FAILED + Outbox → 반복 보상에도 반환 1.
6. 같은 주문 8개 동시 요청 → Payment 1/승인 POST 1. Mock 우회 차단, 복구와 만료 동시 실행 후 확정.
7. nextRetryAt을 지난 결제를 실제 Worker.recover의 대상 조회로 찾아 확정.
8. PG 취소 후 MySQL CANCELED UPDATE 거부 → PG 취소 이력으로 재조회 복구, 추가 취소 없이 반환.
9. 이미 만료된 Booking의 신규 승인 요청 차단 → PG 호출/Payment 생성 0, 기존 만료 경로 반환.

각 시나리오에서 PG 이력, Payment, Booking, attempt, 잔여 수량, Outbox를 검사한다. 스케줄러의 자동 시간 경과/SLA 대신 메서드 호출과 테스트 시간 갱신을 사용했다.

기존 예매 장애·동시성·요청별 복구·매진·Mock 정책·소유권·API·예약 만료·결제 보상 회귀를 함께 실행했다. 최초 실패 및 후속 실행 로그/XML은 `test-results/payment-saga/`에 보존한다. 최종 숫자는 같은 폴더 `summary.json`과 최종 XML을 기준으로 한다.

초기 실패와 수정:

- legacy Outbox fixture에 paymentId가 없어 실패: 기존 payload 호환 경로를 복구했고 Payment 보호는 그대로 적용.
- 필수 승인 정보 없는 기존 캐시 테스트: 유효한 요청을 보내도록 fixture 수정.
- timeout 테스트 사이 Circuit Breaker 상태 공유로 뒤의 테스트에서 PG 호출 0: 테스트 BeforeEach에서 Breaker 초기화. 운영 Breaker 설정 변경 없음.
- 존재하지 않는 Booking 테스트가 새 Payment fixture 생성에서 먼저 실패: 해당 장애 fixture는 없는 Booking을 그대로 유지.
- H2 테스트가 공유한 Payment FK로 다음 만료 테스트의 초기 정리 실패: Outbox→Payment→Attempt→Booking 순으로 정리.

## 명시적인 한계 / 미검증

- PG 구간에서 앱 프로세스 SIGKILL 및 다른 앱의 자동 재개는 이번 신규 테스트에서 실제 재현하지 않았다. 기존 예매 구간 SIGKILL 테스트 통과와 구분해야 한다.
- 결제 TX-A/TX-B/TX-D의 COMMIT ACK 유실은 직접 재현하지 않았다. MySQL trigger 장애는 UPDATE 거부이지 COMMIT 응답 유실이 아니다.
- 취소 의도와 늦은 원래 응답의 모든 interleaving, PG 취소의 다중 Worker 동시 실행, 부분 취소·식별자 불일치의 전체 장애 조합은 아직 전용 통합 테스트 미검증이다.
- 실제 Toss 테스트 키 연동, 결제 수단/취소 계약과 외부 PG 내구성은 미검증이다.
- 선커밋 후 HTTP 전송 전에 종료된 요청은 조회에 없을 수 있다. 무조건 재승인하지 않으므로 점검 필요 상태로 오래 남을 수 있다. 조회 404/오류/횟수 초과로 자동 반환하지 않는다.
- 초기 커밋 오류는 호출자에 오류를 반환하고 PG를 호출하지 않는다. 그 자리에서 fresh-primary 확인 후 PG 호출을 이어가는 최적화는 하지 않았다. 실제 커밋됐다면 Worker가 기록을 찾지만 미호출 PG 요청의 자동 완료까지 보장하지 않는다.
- 이번 구현은 정상 확보 Booking과 이미 CANCELLED인 Booking을 구분한다. 공연 CLOSED 자체를 기존 보유 Booking의 자동 환불 사유로 추가하지 않는다. 임의 DB 변조나 부분 Redis 유실 등 별도 정합성 훼손의 자동 수선은 범위 밖이다.
- 기존 식별자 없는 PG 거래는 새 컬럼으로 복원할 수 없다. 신규 요청만 복구 정보를 갖고 시작하며 기존 데이터는 별도 확인해야 한다. migration SQL은 제공했지만 실제 기존 데이터가 있는 DB에 적용 시험하지 않았다.
- 전체 PG 거래 정기 대사, 전이 이력 테이블, 일반 환불/부분 환불/가상계좌/웹훅, Kafka/Stream은 구현하지 않았다.
- metrics pending/review/oldest는 추가했지만 전 단계별 대시보드/알림 규칙과 Payment 수동 처리 API는 추가하지 않았다. 기존 Outbox 관리자 재처리는 유지한다.
- 프론트 빌드는 확인하되 브라우저 결제 흐름 전체/실제 SDK 테스트를 실행했다고 주장하지 않는다. 성능 수치는 측정하지 않았다.

이 결과만으로 모든 결제 장애의 안전성을 검증했다고 표현하면 안 된다. 로그와 통과 사례, 위 미검증 범위를 함께 제시해야 한다.

## 최종 실행 결과

선택한 11개 클래스 총 100건, 실패 0건, 오류 0건, skip 0건. PaymentSagaIntegrationTest 9건과 기존 회귀 91건이다. 프론트 npm run build 통과. 전체 프로젝트의 모든 테스트를 실행한 것은 아니다.

최종 실행 명령은 test-results/payment-saga/final/command.txt에 보존했다. 운영 DB/시연 DB는 변경하지 않았다.
