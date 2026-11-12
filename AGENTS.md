# AGENTS.md

이 파일은 Codex(Codex.ai/code)가 이 프로젝트에서 작업할 때 참조하는 지침서입니다.

---

## 절대 규칙

- Spring Boot 코드베이스를 최대한 유지한다. 불필요한 리팩터링 금지.
- `experiments/` 패키지(e1, e3, e4)는 **읽기 전용 레퍼런스**. import 또는 수정 금지.
- 모든 구현은 예매 핵심 플로우(Queue → Booking → Payment) 우선.
- 좌석맵(Seat Map) 기능은 2차 범위. 현재는 구역(Zone) 선택 기반 예매.
- 프론트엔드는 **React + Vite** 기준. Vue 코드나 설명 사용 금지.
- 변경 전에는 반드시 **Plan mode** 로 초안 확인 후 승인받아 진행.

---

## 프로젝트 개요

콘서트 티켓 예매 플랫폼. 고동시성 환경에서 공정한 예매를 보장하는 것이 핵심 목표.

- Redis 대기열 → 입장권 발급 → 예매(Booking) → 결제(Payment)
- 결제 실패 시 Outbox 기반 보상 처리
- PENDING_PAYMENT 방치 시 30분 후 자동 만료. 미확정/완료 결제가 있으면 잠금 안에서 반환 보류

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| Backend | Spring Boot 3, JPA, MySQL |
| Cache/Queue | Redis (Sorted Set, Lua 스크립트) |
| Auth | JWT (API 전용), OAuth2/Google (Web UI) |
| Frontend | React + Vite |
| Test DB | H2 in-memory / 핵심 장애 테스트는 MySQL·Redis Testcontainers |

---

## 빌드 & 테스트 명령

```bash
./gradlew build              # 전체 빌드 (테스트 포함)
./gradlew build -x test      # 테스트 제외 빌드
./gradlew bootRun            # 로컬 실행 (local 프로파일)
./gradlew test               # 전체 테스트

# 핵심 테스트 클래스 단독 실행
./gradlew test --tests "studying.blog.service.EnrollServiceTest"
./gradlew test --tests "studying.blog.service.PaymentServiceFailRateTest"
./gradlew test --tests "studying.blog.scheduler.BookingExpirySchedulerTest"
./gradlew test --tests "studying.blog.scheduler.PaymentCompensationSchedulerTest"
./gradlew test --tests "studying.blog.concurrency.EnrollConcurrencyTest"
```

---

## 환경 변수 (필수)

앱 기동에 아래 변수가 없으면 시작 불가:

```
JWT_SECRET_KEY=<base64-encoded-secret>
DB_URL=jdbc:mysql://localhost:3306/blog
DB_USERNAME=<username>
DB_PASSWORD=<password>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=<id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=<secret>
```

로컬 개발: DB_URL이 가리키는 MySQL과 Redis(local 설정 localhost:6380)가 필요하다. 루트 Compose 호스트 포트는 MySQL 3307/Redis 6380이며 Docker 앱 내부 포트와 구분한다. TOSS_SECRET_KEY도 실행 환경에 주입한다. 테스트 Redis는 별도 인스턴스를 사용한다.

선택:
```
payment.mock.fail-rate=0   # Mock 결제 실패율 (정수 0~100, 기본 0)
```

---

## 아키텍처 요약

### 예매 플로우

```
[1] POST /api/concerts/{concertId}/queue        → Redis 대기열 등록
[2] GET  /api/concerts/{concertId}/queue/me     → 대기 순번 폴링
    ConcertQueueScheduler (1s, 인스턴스별)      → 상위 100명 입장권 발급 (Lua)
[3] POST /api/concerts/{concertId}/booking      → REQUESTED 선커밋 → 요청 잠금/Lua 선점 → Booking + BOOKED 커밋
    GET /api/concerts/{concertId}/booking/attempts/{requestId} → 조회 전용 상태 확인
    BookingAttemptRecoveryWorker (5s)           → 최초 선점부터 5분간 생성 재개 가능, 기한 연장·추가 차감 없음
[4] POST /api/concerts/{concertId}/payment/toss-confirm → Payment 선커밋/최초 호출 선점 → PG → Booking(CONFIRMED)
    PaymentRecoveryWorker (5s)                  → 승인/취소 결과 재조회
    POST /payment는 설정으로 허용하는 별도 Mock 경로
    실패 시: PaymentCompensationOutbox 생성
             → PaymentCompensationScheduler (10s) 보상 처리
    방치 시: BookingExpiryScheduler (60s) → 30분 초과 자동 취소
```

### 동시성 제어

- Concert 잔여석: BookingSeatStore의 요청별 Redis Lua. 공연 행 비관적 락·매 예매 bookedCount UPDATE 없음
- 예매: booking_attempt 요청 행 잠금·Booking 유니크 제약·공통 멱등 반환. 복구 Worker 5초. 5분은 생성 재시도 기한이며 TTL 자동 반환/완료 SLA가 아님. Booking 생성 후 미결제 30분과 구분
- PG 결제: Booking→Payment 잠금·DB 주문 유니크·저장된 승인/취소 멱등키. PG HTTP는 트랜잭션 밖. 후보 선택 회차 비교로 실패 중복 갱신 방지
- Mock 결제: Redis SETNX (30s TTL) + DB unique key (`uk_payment_idempotency_key`)
- 대기열: Lua 스크립트 (원자적 ZADD / ZRANGE+ZREM+SETEX)

### 보안 2-chain

- `/api/**` → JWT Bearer (STATELESS)
- `/**` → OAuth2/Google + 세션 (Thymeleaf Web UI)

> 현재 환경은 로컬이며 GCP/AWS 구성은 과거 기록이다.

> 상세: [docs/architecture.md](docs/architecture.md)

---

## 확장 방향

현재 `PaymentCompensationOutbox`는 스케줄러 폴링 방식으로 처리되지만,
향후 **Kafka 기반 이벤트 드리븐 아키텍처**로 전환 가능한 구조로 설계되어 있음.

```
현재: Outbox(DB) → PaymentCompensationScheduler (폴링, 10s)
확장: Outbox(DB) → Kafka Producer → Topic → Consumer → 보상 처리
```

새 기능을 설계할 때 이벤트 확장 가능성을 고려해 서비스 간 결합도를 낮게 유지.

---

## 도메인 용어 맵

| 구 용어 (Lecture 시스템) | 현 용어 (Concert 시스템) |
|--------------------------|--------------------------|
| Lecture | Concert |
| Enrollment | Booking |
| EnrollService | BookingService |
| EnrollStatus | BookingStatus (PENDING_PAYMENT / CONFIRMED / CANCELLED) |
| — | Payment |
| — | PaymentCompensationOutbox |

> 주의: 테스트 파일 일부(`EnrollServiceTest`, `EnrollConcurrencyTest`)는 파일명이 구 용어로 남아있으나 내부 로직은 Concert 기준.

---

## 프로파일

| 프로파일 | DB | ddl-auto | 비고 |
|----------|----|----------|------|
| local | MySQL | update | SQL 로그 ON |
| prod | MySQL | none | 스키마 수동 관리 |
| test | 기본 H2 / 핵심 장애 테스트 MySQL Testcontainers | create-drop | 기존 H2 Scheduler 테스트는 전용 localhost:6379, RedisTestSupport는 Redis Testcontainers |

---

## 코딩 컨벤션

- 서비스 레이어는 트랜잭션 단위로 명확히 구분
- Redis 원자성이 필요한 연산은 반드시 Lua 스크립트 사용
- 타이밍 계측 로그 패턴 유지 (`System.nanoTime()`, 슬로우 임계치 30ms)
- 새 엔티티 추가 시 인덱스/유니크 제약 명시 필수
- 테스트는 `@ActiveProfiles("test")` + H2 기준 작성

---

## 프론트엔드 계획 (React + Vite)

- 1차 MVP: 구역(Zone) 선택 → 예매 → 결제 핵심 플로우
- 좌석맵(Seat Map) UI는 2차 범위
- OAuth/로그인 화면은 기존 Thymeleaf 유지 (당분간 대수정 없음)

---

## 참조 문서

- [docs/architecture.md](docs/architecture.md) — 패키지 구조, Redis 키 명세, Scheduler, experiments/
- [docs/api.md](docs/api.md) — 전체 REST 엔드포인트 목록
- [docs/flow.md](docs/flow.md) — 예매 플로우 시퀀스 다이어그램
- [docs/testing.md](docs/testing.md) — 테스트 전략, 클래스별 설명, 실행 환경
