# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 프로젝트에서 작업할 때 참조하는 지침서입니다.

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
- PENDING_PAYMENT 방치 시 30분 후 자동 만료

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| Backend | Spring Boot 3, JPA, MySQL |
| Cache/Queue | Redis (Sorted Set, Lua 스크립트) |
| Auth | JWT (API 전용), OAuth2/Google (Web UI) |
| Frontend (예정) | React + Vite |
| Test DB | H2 in-memory |

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

현재 환경은 로컬이며 클라우드 구성은 과거 검증 기록이다. 지정 좌석 없이 공연별 수량을 관리한다.

Queue(Redis) → Scheduler(인스턴스별 1s/최대 100명, 입장권 TTL 10분) → 요청 DB 선커밋 → 요청 잠금·Lua 선점 → Booking/요청 완료 동시 커밋 → 결제 Saga.

예매 저장 실패는 최초 선점 기준 5분 동안 기존 선점으로 생성 재시도한다. Worker는 5초마다 due 요청을 처리하며 재차감·기한 연장은 없다. 결과 불명확·손상 기록은 임의 반환하지 않는다. 5분은 TTL 삭제나 복구 완료 SLA가 아니며 Booking 생성 후 미결제 30분과 구분한다. 요청별 상태 GET은 조회 전용이다.

PG는 Payment 선커밋·최초 호출 선점 후 DB 트랜잭션 밖에서 호출한다. Booking→Payment 잠금으로 확정/취소를 적용하며 미확정은 재조회한다. 회차 비교로 여러 Worker의 실패 중복 갱신을 막는다. SETNX는 별도 Mock 결제 경로다. 확정 실패/전액 취소와 반환 Outbox를 함께 저장하며 요청별 공통 Lua로 반환한다.
보안: `/api/**` JWT STATELESS / `/**` OAuth2 세션

→ 상세: [docs/architecture.md](docs/architecture.md), [docs/flow.md](docs/flow.md)

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
| test | H2 in-memory | create-drop | Redis는 localhost:6379 필요 |

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

- [docs/architecture.md](docs/architecture.md) — 패키지 구조, Redis 키 명세, Lua 스크립트, Scheduler, 확장 방향
- [docs/api.md](docs/api.md) — 전체 REST 엔드포인트 목록
- [docs/flow.md](docs/flow.md) — 예매 플로우 시퀀스 다이어그램
- [docs/testing.md](docs/testing.md) — 테스트 전략, 클래스별 설명, 실행 환경
