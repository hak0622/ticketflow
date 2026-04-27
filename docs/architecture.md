# Architecture

## 패키지 구조

```
studying.blog/
├── config/
│   ├── jwt/          TokenProvider, TokenAuthenticationFilter, JwtConfig
│   └── oauth/        OAuth2UserCustomService, OAuth2SuccessHandler,
│                     OAuth2AuthorizationRequestBasedOnCookieRepository,
│                     WebOAuthSecurityConfig
├── controller/
│   ├── api/          BookingApiController, PaymentApiController,
│   │                 ConcertApiController, ConcertQueueApiController,
│   │                 AdminConcertApiController, CouponApiController,
│   │                 TokenApiController, UserApiController, MyPageApiController
│   └── web/          ConcertViewController, AdminConcertViewController,
│                     UserViewController
├── domain/           Concert, Booking, Payment, PaymentCompensationOutbox,
│                     Coupon, CouponIssue, User, RefreshToken
│                     + 열거형: ConcertStatus, BookingStatus, PaymentStatus,
│                               OutboxStatus, Role
├── dto/              요청/응답 DTO (13개)
├── repository/       ConcertRepository, BookingRepository, PaymentRepository,
│                     PaymentCompensationOutboxRepository, CouponRepository,
│                     CouponIssueRepository, UserRepository, RefreshTokenRepository
├── service/          BookingService, PaymentService, QueueService, ConcertService,
│                     CouponService, TokenService, MyPageService,
│                     UserService, RefreshTokenService, UserDetailService
├── scheduler/        ConcertQueueScheduler, PaymentCompensationScheduler,
│                     BookingExpiryScheduler
├── exception/        GlobalExceptionHandler 및 커스텀 예외
├── util/             CookieUtil
└── experiments/      e1 (쿠폰), e3 (멱등성), e4 (보상 패턴) — 읽기 전용
```

---

## 보안 2-chain (WebOAuthSecurityConfig)

### Chain 1 — `/api/**` (JWT, STATELESS)

- 세션 없음 (STATELESS)
- `TokenAuthenticationFilter` → Authorization 헤더에서 Bearer 토큰 추출 → 검증 후 SecurityContext 등록
- 인증 규칙:
  - `/api/token` — permitAll
  - `/api/admin/**` — ROLE_ADMIN 필요
  - 그 외 `/api/**` — authenticated()
- CSRF 비활성화

### Chain 2 — `/**` (OAuth2, 세션)

- OAuth2 로그인 (`/login`) → Google 인증 → `OAuth2SuccessHandler`가 JWT 발급
- 로그아웃 `/logout` 활성화
- 인가 상태는 쿠키 기반(`OAuth2AuthorizationRequestBasedOnCookieRepository`)으로 저장
- `CustomPrincipal` (userId + email)이 `@AuthenticationPrincipal`로 주입됨

---

## Redis 키 명세

| 키 | 타입 | TTL | 용도 |
|----|------|-----|------|
| `queue:concert:{concertId}` | Sorted Set | 없음 | 대기열 (score = 입장 timestamp) |
| `admitted:concert:{concertId}:user:{userId}` | String | 600s | 입장권 (Scheduler 발급) |
| `seats:concert:{concertId}` | String (int) | 없음 | 잔여 좌석 수 — BookingService Lua 원자적 차감 |
| `concert:status:{concertId}` | String (JSON) | 30s | 콘서트 상태/제목/일시 캐시 — BookingService DB 조회 생략용 |
| `payment:idempotency:{idempotencyKey}` | String | 30s | 결제 중복 처리 방지 락 |

---

## Lua 스크립트

### POP_AND_GRANT (ConcertQueueScheduler → QueueService)

```lua
-- 대기열 상위 N명을 원자적으로 꺼내 입장권 발급
local users = redis.call('ZRANGE', KEYS[1], 0, count - 1)
redis.call('ZREM', KEYS[1], unpack(users))
for i = 1, #users do
  redis.call('SETEX', admittedPrefix .. users[i], ttl, '1')
end
return users
```

### CLAIM_ADMITTED (BookingService → QueueService)

```lua
-- 입장권 존재 확인 후 원자적으로 소비 (TTL 반환)
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
local ttl = redis.call('TTL', KEYS[1])
redis.call('DEL', KEYS[1])
return ttl
```

### DECREMENT_SEAT (BookingService → QueueService)

```lua
-- 잔여 좌석 원자적 차감 (반환: 차감 후 남은 수, -1=매진, -2=키 없음)
if redis.call('EXISTS', KEYS[1]) == 0 then return -2 end
local remaining = tonumber(redis.call('GET', KEYS[1]))
if remaining <= 0 then return -1 end
return redis.call('DECR', KEYS[1])
```

---

## Scheduler 상세

| 클래스 | 주기 | 역할 |
|--------|------|------|
| `ConcertQueueScheduler` | **1s** (fixedDelay) | OPEN 콘서트 대기열에서 상위 **100명** 입장권 발급 (TTL 600s) |
| `PaymentCompensationScheduler` | 10s (fixedDelay) | PENDING 상태 Outbox 처리 → Concert 좌석 복원, Booking 취소 (최대 3회 재시도) |
| `BookingExpiryScheduler` | 60s (fixedDelay) | PENDING_PAYMENT 상태 30분 초과 Booking 자동 취소 + 좌석 복원 |

- `ConcertQueueScheduler` / `BookingExpiryScheduler`: ShedLock 사용 (다중 인스턴스 중복 실행 방지)
- `PaymentCompensationScheduler` / `BookingExpiryScheduler`: `PESSIMISTIC_WRITE` 락 획득 후 처리

---

## BookingService 예매 최적화

book() 메서드의 핵심 최적화 포인트:

1. **Redis 좌석 차감** (`DECREMENT_SEAT_LUA`): DB SELECT/UPDATE 없이 Lua로 원자적 처리
2. **콘서트 상태 캐시** (`concert:status:{id}`, TTL 30s): 캐시 히트 시 DB SELECT 생략, `getReferenceById()`로 FK 프록시만 생성
3. **claimAdmitted 원자적 소비**: 입장권 존재 확인 + 삭제를 단일 Lua로 처리 (race condition 방지)

캐시 미스 흐름: DB 조회 → `setConcertInfo()` 호출 → 30s TTL 캐시 갱신

---

## 인프라 & 배포

### GCP 2서버 구성 (현재)

```
[Client] → GCP HTTP(S) Global Load Balancer
               ├── Server1 (e2-medium): Spring Boot app + MySQL + Redis + Prometheus + Grafana
               └── Server2 (e2-medium): Spring Boot app only (MySQL/Redis는 Server1 내부 IP 참조)
```

- Server1 내부 IP: `10.178.0.2` (Server2의 DB_HOST, REDIS_HOST)
- 세션: Redis 기반 공유 세션 (`spring.session.store-type=redis`)

### docker-compose 파일

| 파일 | 용도 |
|------|------|
| `docker-compose.yml` | 로컬 개발 (app×2 + Nginx LB + MySQL + Redis + Prometheus + Grafana) |
| `docker-compose.prod.yml` | Server1 프로덕션 (app + MySQL + Redis + Prometheus + Grafana) |
| `docker-compose.prod-app2.yml` | Server2 프로덕션 (app only, Server1 MySQL/Redis 연결) |

### 주요 운영 설정 (docker-compose.prod.yml)

| 항목 | 값 |
|------|-----|
| App 메모리 제한 | 1GB |
| JAVA_OPTS | `-Xms256m -Xmx800m` |
| Tomcat max-threads | 100 |
| Tomcat max-connections | 2000 |
| Tomcat accept-count | 500 |
| HikariCP pool-size | 20 |
| MySQL 메모리 | 1GB |
| Redis 메모리 | 64MB |

---

## experiments/ 패키지 (읽기 전용)

전략 비교를 위한 연구용 패키지. 프로덕션 코드와 독립적으로 유지.

| 패키지 | 주제 | 비교 전략 |
|--------|------|-----------|
| `e1` | 쿠폰 재고 발급 | Redis DECR vs DB 선점 vs 기타 |
| `e3` | 멱등성 처리 | DB PK INSERT vs Redis SETNX vs 기타 |
| `e4` | 보상 패턴 | Fire-and-forget vs Transactional Outbox |

> **절대 import 금지.** 코드 참고 시 복사해서 main 패키지에 재구현.

---

## 확장 방향

현재 `PaymentCompensationOutbox`는 스케줄러 폴링 방식으로 처리되지만,
향후 **Kafka 기반 이벤트 드리븐 아키텍처**로 전환 가능한 구조로 설계되어 있음.

```
현재: Outbox(DB) → PaymentCompensationScheduler (폴링, 10s)
확장: Outbox(DB) → Kafka Producer → Topic → Consumer → 보상 처리
```

새 기능을 설계할 때 이벤트 확장 가능성을 고려해 서비스 간 결합도를 낮게 유지.
