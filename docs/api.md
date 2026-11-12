# REST API 엔드포인트

2026-11-12 소스 대조 기준. [현재 처리 흐름](flow.md) · [문서 안내](README.md).

인증 컬럼: `JWT` = Bearer 토큰 필요, `ADMIN` = ROLE_ADMIN 필요, `-` = 불필요

---

## 대기열 (Queue)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts/{concertId}/queue` | JWT | 대기열 등록 |
| GET | `/api/concerts/{concertId}/queue/me` | JWT | 내 대기 순번 조회 (`QUEUED`일 때 `nextPollMs` 포함) |

`GET /api/concerts/{concertId}/queue/me` 응답 예시:

```json
{
  "concertId": 10,
  "status": "QUEUED",
  "position": 8234,
  "total": 10000,
  "nextPollMs": 10000
}
```

---

## 예매 (Booking)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts/{concertId}/booking` | JWT | 예매 요청 생성 또는 같은 요청 결과 재사용 |
| GET | `/api/concerts/{concertId}/booking/attempts/{requestId}` | JWT | 본인의 해당 공연 요청 처리 상태 조회 |
| GET | `/api/concerts/{concertId}/booking/me` | JWT | 예매 여부 확인 |
| GET | `/api/concerts/{concertId}/booking/detail` | JWT | 예매 상세 조회 |

---

예매 POST의 `Idempotency-Key`는 UUID다. 생략하면 서버가 생성한다. Controller의 정상 반환 경로에서 응답 헤더로 전달하지만, 예외 처리기로 응답하는 매진·오류에서는 이 헤더를 보장하지 않는다. 클라이언트는 보내기 전에 ID를 생성·보관하는 것이 현재 프론트 방식이다.

| 상황 | POST 예매 | GET 요청 상태 |
|---|---|---|
| 생성 완료 또는 기존 예매 | 200, BOOKED/ALREADY_BOOKED | 200, 저장 결과와 연결된 bookingId/bookingStatus |
| 생성 복구 또는 반환 진행 중 | 202, BOOKING_PROCESSING | 202, BOOKING_PROCESSING와 retryAfterMs=5000 |
| 반환 경로 종료 후 실패 | 409, BOOKING_FAILED | 409, BOOKING_FAILED |
| 정상 매진 | 409, 숫자 status=409와 `정원이 마감되었습니다.` 메시지 | 200, 문자열 status=SOLD_OUT 및 reason=REJECTED |
| 요청 소유자·공연 불일치 | 400 | 404 (미존재와 구분하지 않음) |
| UUID 형식 오류 | 400 | 400 |
| JWT 미인증 | 401 | 401 |

POST Controller의 반환 본문에는 status/requestId/concertId/concertTitle/message가 있다. BOOKING_FAILED 메시지는 `종료된 예매 요청입니다. 새 요청 ID로 다시 시도해주세요.`다. 정상 매진은 Service가 커밋 후 예외를 발생시켜 GlobalExceptionHandler가 숫자 status와 message를 반환하므로 본문 형식이 다르다. 입장권 없음 등 일반 거절은 별도 409, 마감된 공연 예외는 403이며 상황이 불명확하면 202로 남을 수 있다.

GET은 읽기 전용이다. 기본 필드는 status/reason/requestId/concertId이며 처리 중에만 retryAfterMs, 예매가 연결된 경우에만 bookingId/bookingStatus가 추가된다. reason은 처리 중 RECOVERING, 그 외 DB 요청 상태명이다. POST 본문의 concertTitle/message나 Idempotency-Key 응답 헤더를 GET에서 보장하지 않는다. 404 오류 본문은 고정된 도메인 응답 형식으로 보장하지 않는다.

처리 중 GET 예시(가상의 요청 ID):

```json
{
  "status": "BOOKING_PROCESSING",
  "reason": "RECOVERING",
  "requestId": "00000000-0000-4000-8000-000000000001",
  "concertId": 10,
  "retryAfterMs": 5000
}
```

GET은 복구나 재차감을 실행하지 않는다. 404가 최초 POST 미접수를 뜻한다고 단정하지 않으며, 현재 화면은 같은 ID로 명시적 POST 재전송을 안내한다. 성공 결과가 있어도 bookingStatus가 CONFIRMED/CANCELLED일 수 있어 결제 이동은 이를 확인한다. `/booking/me`는 단순 존재 결과이며 결제·취소 상태는 상세나 요청 상태를 확인한다. [사용자 흐름](flow.md)과 [기한·반환 정책](booking-hold-recovery.md)을 함께 읽는다.

코드 근거: `BookingApiController.book/attemptStatus`, `BookingService.book/attemptStatus`, `GlobalExceptionHandler`와 JWT 보안 설정. API 변경이 아니라 현재 분기를 문서화한 것이다.

## 결제 (Payment)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts/{concertId}/payment` | JWT | 설정으로 허용하는 Mock 결제 |
| POST | `/api/concerts/{concertId}/payment/toss-confirm` | JWT | Toss 승인·기존 주문 결과 재사용 |
| GET | `/api/concerts/{concertId}/payment/me` | JWT | 내 결제 내역 조회 |

---

PG 승인 본문은 `orderId`, `paymentKey`, `amount`다. Payment 상태는 PENDING/COMPLETED/FAILED/CANCELED이며 응답의 recoveryPhase는 복구 단계를 나타낸다. HTTP 200이 곧 결제 완료를 뜻하지 않는다. 결과가 불명확하면 PENDING을 유지하고 Worker가 재확인한다. 같은 주문 재시도는 소유자와 저장된 요청 정보를 검증한다. PG 키·오류만으로 실패/환불 완료를 추정하지 않는다.

Mock은 공통 설정에서 기본 비활성화지만 local 프로파일에서는 활성화한다. prod에서는 설정이 true여도 거부한다. GET payment/me는 성공 → 진행 중 → 최근 종료 결과 순서로 선택하고 다중 성공/미확정 이상은 오류로 드러낸다. 구체적 분기는 PaymentService/PaymentSaga가 기준이다.

## 콘서트 (Concert)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/concerts/{id}` | - | 콘서트 상세 조회 |

---

## 어드민 - 콘서트 (Admin Concert)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/admin/concerts` | ADMIN | 콘서트 생성 (관리자) |
| GET | `/api/admin/concerts` | ADMIN | 전체 콘서트 목록 |
| PUT | `/api/admin/concerts/{id}` | ADMIN | 콘서트 수정 |
| PATCH | `/api/admin/concerts/{id}/close` | ADMIN | 콘서트 종료 처리 |
| GET | `/api/admin/concerts/{id}/bookings` | ADMIN | 콘서트별 예매 목록 |
| DELETE | `/api/admin/concerts/{id}` | ADMIN | 콘서트 삭제 |

---

## 어드민 - 결제 보상 (Admin Payment Compensation)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/admin/payment-compensations?status=FAILED` | ADMIN | 상태별 결제 보상 Outbox 조회 |
| POST | `/api/admin/payment-compensations/{outboxId}/retry` | ADMIN | FAILED Outbox를 PENDING으로 전환해 수동 재시도 |

수동 재시도는 `FAILED` 상태에서만 가능하며, 동일 Outbox에 대한 동시 요청 중 한 건만 성공한다.

---

## 인증 (Auth)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/token` | - | 액세스 토큰 재발급 (Refresh Token 필요) |
| POST | `/user` | - | 회원가입 |
| GET | `/logout` | - | 로그아웃 |

---

## 마이페이지 (MyPage)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/me/bookings` | JWT | 내 전체 예매 목록 |
