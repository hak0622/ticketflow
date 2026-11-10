# 테스트 실행과 결과 읽기

2026-11-10 기준. [문서 안내](README.md) · [로컬 부하·장애 스크립트](../perf/README.md).

## 환경 구분

- 일반 test 프로파일은 H2 in-memory/create-drop이다. 일부 기존 Scheduler 테스트는 localhost:6379의 실제 Redis가 필요하다.
- IntegrationTestSupport 기반 PG/예매 장애 테스트는 실제 MySQL 8.4·Redis 7 Testcontainers를 사용한다.
- RedisTestSupport 기반 QueueServiceIntegrationTest는 Redis Testcontainers와 H2를 사용한다.
- 테스트는 Redis flushDb 등을 호출한다. 기존 개발·시연 Redis를 테스트에 연결하지 않고 전용 인스턴스를 사용한다. Docker Desktop, Java 17 및 필요한 Gradle 의존성이 필요하다.

## 현재 검증 경로

| 클래스/자료 | 목적 |
|---|---|
| BookingAttemptMySqlIntegrationTest | 요청별 복구·잠금·매진·멱등 반환 |
| BookingFailureStageTwoIntegrationTest | MySQL/Redis 장애·응답 유실·예매 구간 JVM 종료 |
| PaymentSagaIntegrationTest | 실제 HTTP 로컬 PG의 승인/취소 응답 유실·DB 갱신 실패·경합·Worker 조회 |
| PaymentStageOneIntegrationTest / PaymentMockDisabledIntegrationTest | 소유권·캐시 응답·Mock 비활성화 |
| PaymentFlowIntegrationTest / BookingPaymentApiIntegrationTest | 예매·결제 API 회귀 |
| BookingExpirySchedulerTest / PaymentCompensationSchedulerTest | 만료·반환 Outbox·재처리 |
| QueueServiceIntegrationTest | 대기열 및 유지하는 옛 Redis 메서드 계약 |
| EnrollServiceTest / EnrollConcurrencyTest | 구 이름이 남아 있는 예매 테스트 |

PaymentMockPolicyTest, PaymentServiceFailRateTest는 Mock 정책 등의 검증이다. Mockito 테스트만 통과한 것을 외부 PG 정합성 검증이라고 표현하지 않는다. PG 테스트는 실제 Toss를 호출하지 않고 HTTP 목적지만 로컬 서버로 바꾼다.

## 과거 재현과 미검증

PaymentPgBoundaryCharacterizationTest는 수정 전 위험한 동작을 기대하는 과거 기준이며 @Disabled 상태다. 원본 결과는 유지하고 현재 안전 회귀는 PaymentSagaIntegrationTest를 실행한다. 과거 2건 PASS는 안전성 통과가 아니다.

PG 구간의 실제 프로세스 종료·COMMIT 응답 유실 및 실제 Toss 연동은 [결제 결과 문서](payment-saga-results.md)의 미검증 범위를 따른다. 예매 구간의 강제 종료 검증과 구분한다. `experiments/` 테스트는 전략 비교 자료이며 이번 정리에서 수정하지 않는다.

## 실행

```bash
./gradlew compileJava --offline
./gradlew test --offline --tests studying.blog.fault.PaymentSagaIntegrationTest
./gradlew test --offline --tests studying.blog.service.QueueServiceIntegrationTest
```

전체 `test`와 `ciTest`의 범위는 다르다. ciTest는 experiment/integration 태그를 제외한다. 로컬에 의존성 캐시가 없으면 offline 옵션을 빼고 의존성을 준비한다. 선택 회귀 테스트의 정확한 명령·XML·로그는 해당 결과 폴더에 보존한다. 전체 테스트를 실행하지 않았다면 전체 프로젝트 통과라고 기록하지 않는다.

## 기록

[PG Saga 100건 결과](payment-saga-results.md), [문서 안내](README.md), [성능 계측](booking-success-path-timing-results.md)을 분리해 읽는다. 이번 정리는 프론트·k6·실 PG 검증이 아니다. `test-results/`와 `build/`는 Git 제외 로컬 원본이며 원격 보관을 뜻하지 않는다.
