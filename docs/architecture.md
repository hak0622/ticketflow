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
| `payment:idempotency:{idempotencyKey}` | String | 30s | 결제 중복 처리 방지 락 |
| `coupon:stock:{couponId}` | String (int) | 없음 | 쿠폰 잔여 재고 |

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

---

## Scheduler 상세

| 클래스 | 주기 | 역할 |
|--------|------|------|
| `ConcertQueueScheduler` | 5s | OPEN 콘서트 대기열에서 상위 50명 입장권 발급 (TTL 600s) |
| `PaymentCompensationScheduler` | 10s | PENDING 상태 Outbox 처리 → Concert 좌석 복원, Booking 취소 (최대 3회 재시도) |
| `BookingExpiryScheduler` | 60s | PENDING_PAYMENT 상태 30분 초과 Booking 자동 취소 + 좌석 복원 |

모든 Scheduler는 Concert 단위로 `PESSIMISTIC_WRITE` 락 획득 후 처리.

---

## experiments/ 패키지 (읽기 전용)

전략 비교를 위한 연구용 패키지. 프로덕션 코드와 독립적으로 유지.

| 패키지 | 주제 | 비교 전략 |
|--------|------|-----------|
| `e1` | 쿠폰 재고 발급 | Redis DECR vs DB 선점 vs 기타 |
| `e3` | 멱등성 처리 | DB PK INSERT vs Redis SETNX vs 기타 |
| `e4` | 보상 패턴 | Fire-and-forget vs Transactional Outbox |

> **절대 import 금지.** 코드 참고 시 복사해서 main 패키지에 재구현.
