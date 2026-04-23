# 예매 플로우

## 정상 예매 시퀀스

```mermaid
sequenceDiagram
    participant U as User
    participant API as Spring Boot API
    participant Redis as Redis
    participant DB as MySQL
    participant Sched as Scheduler

    U->>API: POST /api/concerts/{id}/queue
    API->>Redis: ZADD queue:concert:{id} (score=timestamp)
    API-->>U: 대기 순번 반환

    loop 폴링
        U->>API: GET /api/concerts/{id}/queue/me
        API->>Redis: ZRANK
        API-->>U: 현재 순번
    end

    Note over Sched,Redis: ConcertQueueScheduler (1s 주기)
    Sched->>Redis: Lua: ZRANGE(상위 100) + ZREM + SETEX admitted:...

    U->>API: POST /api/concerts/{id}/booking
    API->>Redis: Lua CLAIM_ADMITTED (EXISTS → TTL → DEL)
    API->>Redis: Lua DECREMENT_SEAT (원자적 차감)
    API->>Redis: GET concert:status:{id} (캐시 히트 시 DB 생략)
    API->>DB: INSERT Booking (PENDING_PAYMENT)
    API-->>U: 예매 완료 (PENDING_PAYMENT)

    U->>API: POST /api/concerts/{id}/payment
    API->>Redis: SETNX payment:idempotency:{key} (TTL 30s)
    API->>DB: INSERT Payment (PENDING)
    Note over API: Mock PG 결제 처리
    API->>DB: UPDATE Payment → COMPLETED
    API->>DB: UPDATE Booking → CONFIRMED
    API-->>U: 결제 완료
```

---

## 결제 실패 보상 시퀀스

```mermaid
sequenceDiagram
    participant U as User
    participant API as Spring Boot API
    participant DB as MySQL
    participant Sched as PaymentCompensationScheduler

    U->>API: POST /api/concerts/{id}/payment
    Note over API: Mock PG 결제 실패
    API->>DB: UPDATE Payment → FAILED
    API->>DB: INSERT PaymentCompensationOutbox (PENDING)
    API-->>U: 결제 실패 응답

    Note over Sched,DB: PaymentCompensationScheduler (10s 주기)
    Sched->>DB: SELECT Outbox WHERE status=PENDING
    Sched->>DB: SELECT Concert ... FOR UPDATE (PESSIMISTIC_WRITE)
    Sched->>DB: UPDATE Booking → CANCELLED
    Sched->>DB: UPDATE Concert.bookedCount - 1
    Sched->>DB: UPDATE Outbox → PUBLISHED
```

---

## PENDING_PAYMENT 만료 시퀀스

```mermaid
sequenceDiagram
    participant DB as MySQL
    participant Sched as BookingExpiryScheduler

    Note over Sched,DB: BookingExpiryScheduler (60s 주기)
    Sched->>DB: SELECT Booking WHERE status=PENDING_PAYMENT<br/>AND createdAt < now() - 30min
    loop 콘서트별 처리
        Sched->>DB: SELECT Concert ... FOR UPDATE (PESSIMISTIC_WRITE)
        Sched->>DB: UPDATE Booking → CANCELLED
        Sched->>DB: UPDATE Concert.bookedCount - N
    end
```

---

## 멱등성 처리 레이어

결제 요청이 중복으로 들어올 경우 아래 순서로 방어:

```
1. DB 조회: idempotencyKey 기존 Payment 존재 여부 확인
   → 존재하면 기존 결과 반환 (즉시 종료)

2. Redis SETNX: payment:idempotency:{key} (TTL 30s)
   → 동시 요청이 있으면 두 번째 요청은 락 획득 실패
   → "처리 중" 응답 반환

3. DB unique constraint: uk_payment_idempotency_key
   → 1, 2를 통과해도 DB에서 최종 방어
```

---

## BookingStatus 상태 전이

```
PENDING_PAYMENT
    │
    ├─[결제 성공]──────────→ CONFIRMED (최종)
    │
    ├─[결제 실패 보상]──────→ CANCELLED (최종)
    │
    └─[30분 방치]──────────→ CANCELLED (최종)
```
