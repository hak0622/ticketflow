# REST API 엔드포인트

2026-11-10 기준. [현재 처리 흐름](flow.md) · [문서 안내](README.md).

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
| POST | `/api/concerts/{concertId}/booking` | JWT | 예매 생성 (입장권 소비) |
| GET | `/api/concerts/{concertId}/booking/me` | JWT | 예매 여부 확인 |
| GET | `/api/concerts/{concertId}/booking/detail` | JWT | 예매 상세 조회 |

---

예매 POST의 `Idempotency-Key`는 UUID다. 생략하면 서버가 생성하고 응답 헤더로 반환한다. 결과 불명확은 HTTP 202/BOOKING_PROCESSING이며 같은 ID로 확인한다. 정상 매진은 HTTP 409/`정원이 마감되었습니다.`다. 요청 소유자 불일치는 400이다. `/booking/me`는 단순 예매 존재 결과이며 결제/취소 상태 확인은 상세 조회를 사용한다.

## 결제 (Payment)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts/{concertId}/payment` | JWT | 설정으로 허용하는 Mock 결제 |
| POST | `/api/concerts/{concertId}/payment/toss-confirm` | JWT | Toss 승인·기존 주문 결과 재사용 |
| GET | `/api/concerts/{concertId}/payment/me` | JWT | 내 결제 내역 조회 |

---

PG 승인 본문은 `orderId`, `paymentKey`, `amount`다. Payment 상태는 PENDING/COMPLETED/FAILED/CANCELED이며 응답의 recoveryPhase는 복구 단계를 나타낸다. HTTP 200이 곧 결제 완료를 뜻하지 않는다. 결과가 불명확하면 PENDING을 유지하고 Worker가 재확인한다. 같은 주문 재시도는 소유자와 저장된 요청 정보를 검증한다. PG 키·오류만으로 실패/환불 완료를 추정하지 않는다.

Mock은 기본 비활성화이며 prod에서는 설정이 true여도 거부한다. GET payment/me는 성공 → 진행 중 → 최근 종료 결과 순서로 선택하고 다중 성공/미확정 이상은 오류로 드러낸다. 구체적 분기는 PaymentService/PaymentSaga가 기준이다.

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
