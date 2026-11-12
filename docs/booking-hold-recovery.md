# 선점 유지와 Booking 생성 복구

2026-11-12 문서 점검 기준. [아키텍처의 공통 상태 표](architecture.md#상태와-한계) · [API 응답](api.md#예매-booking) · [검증 결과](booking-hold-recovery-results.md).

지정 좌석 없는 수량 예매다. 대기열 입장권은 예매를 시작하는 권한이고, Redis HELD는 특정 예매 요청이 이미 확보한 티켓이다.

## 정상 및 장애 흐름

REQUESTED 선커밋 → 요청 행 잠금 → Redis Lua 선점 → Booking(PENDING_PAYMENT)과 BOOKED 동시 커밋. Lua는 최초 선점에 holdVersion=2, holdExpiresAt=Redis TIME+5분을 저장한다. 요청 재실행은 기한을 갱신하지 않으며, 유효한 기존 선점은 입장권을 재소비하거나 수량을 다시 차감하지 않는다.

Booking INSERT 또는 DB 커밋 예외 발생 후에는 새 요청 잠금으로 DB 결과부터 확인한다. BOOKED면 기존 결과를 사용한다. REQUESTED이며 유효한 HELD면 요청을 유지하고 재시도를 예약한다. DB·Redis 결과를 확인하지 못하면 반환하지 않는다.

BookingAttemptRecoveryWorker는 5초 간격으로 후보를 조회한다. 기존 선점이 유효하면 동일 요청 행 잠금 안에서 Booking INSERT와 BOOKED 커밋을 재개한다. Worker는 미선점 요청의 신규 재고 차감을 대신 실행하지 않는다. 정상 매진 종료 표식은 기존 REJECTED/SOLD_OUT 복구 경로를 유지한다.

## 기한과 반환

5분은 생성 재시도 시작 기한이다. 기한 안에 시작한 트랜잭션은 기존 10초 timeout 내에서 완료할 수 있다. 커밋된 Booking은 이 기한 때문에 반환하지 않는다. 생성된 Booking의 미결제 만료 30분과 결제 Saga·Outbox 보호는 기존대로 적용한다.

미완료 선점이 만료됐거나 공연이 CLOSED이면 RELEASE_PENDING을 먼저 커밋하고 공통 반환 Lua를 실행한다. 반환 완료 후 RELEASED가 된다. RELEASE_PENDING에서 Booking 생성을 다시 시작하지 않는다. 정상 매진, 실제 반환, 미선점 종료를 구분하며 반환 효과는 요청당 한 번이다. 복원할 입장권의 원래 만료 시각이 지났다면 새로운 권한을 발급하지 않는다.

미선점 REQUESTED는 기존 30초 유예 뒤 종료 표식으로 정리한다. 기한 없는 기존 형식 HELD는 기존 반환 정책을 적용하고, 새 형식에서 기한이 사라진 기록은 손상으로 보류한다. 배포 시 모든 앱을 중지한 뒤 같은 버전을 적용한다. 혼합 버전 실행은 지원하지 않는다. 구 형식 기록이 있는 환경은 이를 식별하고 기존 반환 정책·미완료 요청을 확인해야 한다. 앱을 중지하거나 Redis를 비우는 것만으로 기존 요청 정합성이 해결되는 것은 아니며 이번 문서 정리에서 데이터 이관·초기화는 수행하지 않는다.

## 사용자 흐름과 API

POST /api/concerts/{concertId}/booking은 Idempotency-Key를 유지한다. REQUESTED 및 RELEASE_PENDING은 202/BOOKING_PROCESSING, 생성 실패 후 반환이 끝난 요청은 409/BOOKING_FAILED다. 이미 BOOKED이면 기존 결과를 재사용한다.

GET /api/concerts/{concertId}/booking/attempts/{requestId}는 조회 전용이며 사용자·공연을 검증한다. UUID 형식 오류는 400, 요청 없음/다른 소유자는 404다. status, reason, requestId, concertId와 연결된 bookingId/bookingStatus를 반환하고 처리 중에는 retryAfterMs=5000을 포함한다.

예매 화면은 같은 ID로 5초 간격 조회, 통신 실패 시 10초 간격 조회를 수행한다. 새로고침에도 ID를 유지한다. 404는 최초 요청 접수 여부가 불명확할 수 있어 같은 ID 재전송을 안내한다. 10분 후 자동 조회만 중단하고 수동 확인을 제공한다. 기존 요청은 공연이 SOLD_OUT이어도 확인할 수 있다. PENDING_PAYMENT는 결제 화면으로, CONFIRMED/CANCELLED는 내 예매로 이동한다. 결제 자동 승인은 실행하지 않는다.

## 한계

Redis 전체·부분 유실 재구축, 영구 장애 중의 복구 완료 시각, Worker 공정성은 보장하지 않는다. 손상·장기 미완료 요청은 로그와 기존 요청 테이블에서 확인해야 한다. 기기 간 미완료 요청 검색은 제공하지 않는다. 화면 요청 ID는 sessionStorage에 저장하므로 같은 탭의 새로고침 유지와 다른 기기/새 세션 복원을 구분한다. 화면을 닫아도 Worker가 생성한 Booking은 기존 내 예매에서 결제를 이어갈 수 있다. 가격 고정 등 기존에 없던 구매 정책은 추가하지 않았다.
