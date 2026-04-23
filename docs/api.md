# REST API 엔드포인트

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

## 결제 (Payment)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts/{concertId}/payment` | JWT | 결제 처리 |
| GET | `/api/concerts/{concertId}/payment/me` | JWT | 내 결제 내역 조회 |

---

## 콘서트 (Concert)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/concerts` | JWT | 콘서트 생성 |
| GET | `/api/concerts/{id}` | JWT | 콘서트 상세 조회 |

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

## 쿠폰 (Coupon)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/coupons/{code}/issue` | JWT | 쿠폰 발급 |

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
