# Testing

## 실행 환경

| 항목 | 값 |
|------|-----|
| 프로파일 | `@ActiveProfiles("test")` |
| DB | H2 in-memory (`ddl-auto: create-drop`) |
| Redis | localhost:6379 (실제 Redis 인스턴스 필요) |
| JWT | `JwtFactory`로 테스트용 토큰 생성 |

> Redis 없이 테스트를 실행하면 `QueueServiceIntegrationTest` 등 Redis 의존 테스트가 실패함.

---

## 테스트 클래스 목록

### 서비스 단위 테스트

| 클래스 | 주요 검증 내용 |
|--------|---------------|
| `EnrollServiceTest` | 예매 성공, 중복 예매, 잔여석 없음 시나리오 |
| `PaymentServiceFailRateTest` | 결제 실패율 적용, 멱등성(Redis/DB), Outbox 생성 여부 |
| `CouponServiceTest` | 쿠폰 발급 성공, 중복 발급 방지, 재고 소진 |

### 스케줄러 통합 테스트

| 클래스 | 주요 검증 내용 |
|--------|---------------|
| `BookingExpirySchedulerTest` | 30분 초과 PENDING_PAYMENT 취소, Concert 좌석 복원 |
| `PaymentCompensationSchedulerTest` | PENDING Outbox 처리, 보상 멱등성(이미 취소된 경우), 재시도 |

### 동시성 테스트

| 클래스 | 주요 검증 내용 |
|--------|---------------|
| `EnrollConcurrencyTest` | 1석 콘서트에 10스레드 동시 예매 → 정확히 1건만 성공 |
| `CouponIssueConcurrencyTest` | 고동시성 쿠폰 발급 → 재고 초과 발급 없음 |

### 리포지토리/도메인 테스트

| 클래스 | 주요 검증 내용 |
|--------|---------------|
| `ConcertDecreaseBookedTest` | `decreaseBooked()` 도메인 메서드 경계값 |
| `CouponRepositoryTest` | 쿠폰 조회 쿼리 |
| `CouponIssueRepositoryTest` | 쿠폰 발급 이력 조회 |

### API/컨트롤러 테스트

| 클래스 | 주요 검증 내용 |
|--------|---------------|
| `CouponApiControllerTest` | 쿠폰 발급 API 응답 코드 |
| `TokenApiControllerTest` | Access Token 재발급 흐름 |
| `TokenProviderTest` | JWT 생성/파싱/만료 검증 |

### 기타

| 클래스 | 설명 |
|--------|------|
| `QueueServiceIntegrationTest` | Redis 실제 연동 — 대기열 enqueue/dequeue/admitted |
| `BlogApplicationTests` | 스프링 컨텍스트 로드 스모크 테스트 |

---

## experiments/ 전용 테스트

프로덕션 코드와 무관한 전략 비교 테스트. 결과를 보고 main 패키지 구현 방식을 결정하는 용도.

| 클래스 | 비교 대상 |
|--------|-----------|
| `CouponStockExperimentTest` | e1: 쿠폰 재고 관리 전략 A/B/C 비교 |
| `IdempotencyExperimentTest` | e3: 멱등성 처리 전략 A/B/C 비교 |
| `CompensationExperimentTest` | e4: 보상 패턴 (Fire-and-forget vs Outbox) 비교 |

---

## 테스트 지원 클래스

| 클래스 | 위치 | 역할 |
|--------|------|------|
| `JwtFactory` | `config/jwt/JwtFactory.java` | 테스트용 JWT 토큰 생성 헬퍼 |
| `RedisTestSupport` | `RedisTestSupport.java` | 테스트 전후 Redis 키 초기화 |

---

## 빠른 실행 가이드

```bash
# 전체 테스트
./gradlew test

# 특정 클래스만
./gradlew test --tests "studying.blog.service.EnrollServiceTest"
./gradlew test --tests "studying.blog.service.PaymentServiceFailRateTest"
./gradlew test --tests "studying.blog.scheduler.BookingExpirySchedulerTest"
./gradlew test --tests "studying.blog.scheduler.PaymentCompensationSchedulerTest"
./gradlew test --tests "studying.blog.concurrency.EnrollConcurrencyTest"

# experiments/ 테스트만 (전략 비교 시)
./gradlew test --tests "studying.blog.experiments.*"
```
