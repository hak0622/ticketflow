> 후속 변경: 유효한 선점은 즉시 반환하지 않고 Booking 생성을 복구합니다. 현재 정책은 [선점 유지 및 복구](booking-hold-recovery.md)를 확인하세요. 아래 장애 흐름 설명은 변경 전 반환 정책을 포함합니다.

# 요청별 예매 정합성 복구

지정 좌석 없는 공연별 수량 예매다. Redis가 잔여 수량과 요청별 선점 결과를 관리하고 MySQL은 요청 상태와 Booking을 기록한다. 공연 행의 비관적 락과 매 요청 bookedCount UPDATE는 사용하지 않는다.

## 예매 처리

`BookingApiController` → `BookingService.book` → `BookingAttemptTransactions` → `BookingSeatStore` 순서다.

1. REQUESTED를 별도 DB 트랜잭션으로 먼저 커밋한다.
2. 같은 요청 행을 잠그고 소유자·공연 및 기존 결과를 확인한다.
3. Lua로 입장권·수량·요청별 선점을 원자적으로 처리한다.
4. Booking INSERT와 attempt BOOKED를 같은 DB 트랜잭션에 커밋한다. BOOKED는 재고 확보·Booking 생성 상태이며 결제 완료와 다르다.
5. DB 결과가 불명확하면 새 잠금 조회로 먼저 확인한다. 확인할 수 없으면 반환하지 않고 복구 대상으로 남긴다.

## 상태와 반환

| 저장소 | 상태 | 의미 |
|---|---|---|
| MySQL attempt | REQUESTED | 선커밋된 요청, 결과 미확정 |
| MySQL attempt | BOOKED | Booking 연결, 결제 대기 또는 확정 |
| MySQL attempt | REJECTED | SOLD_OUT 또는 ALREADY_BOOKED 종료 결과 |
| MySQL attempt | RELEASE_PENDING | 반환·후처리 필요 |
| MySQL attempt | RELEASED | 반환 경로 종료 |
| Redis 요청 | HELD | 해당 요청의 수량 선점 |
| Redis 요청 | RELEASED / SOLD_OUT | 선점 없는 정상 매진 종료 |
| Redis 요청 | RELEASED / releaseOutcome=RETURNED | 실제 수량 반환 완료 |

실패·만료·결제 보상·복구 Worker는 공통 반환 경로를 사용한다. 반환 Lua는 NOT_HELD / RETURNED / ALREADY_RETURNED / LEGACY_UNKNOWN을 구분한다. 정상 매진 표식은 수량·입장권을 변경하지 않는다. 실제 반환 이후 후처리는 현재 잔여 수량과 공연 상태를 확인하며 CLOSED를 임의로 OPEN으로 바꾸지 않는다.

DB의 REJECTED 커밋에 실패해도 Worker는 소유자가 일치하는 Redis SOLD_OUT 표식을 확인해 수량 반환 없이 종료 결과를 복구한다. 결과 손상·조회 실패·소유자 불일치는 매진으로 추정하지 않는다.

## 장애 복구와 잠금

BookingAttemptRecoveryWorker는 5초 간격으로 기한이 지난 미완료 요청을 확인한다. 원래 요청과 동일한 attempt 행을 잠그므로 커밋 결과를 확인하지 않고 티켓을 반환하지 않는다. Worker가 먼저 종료한 요청은 Redis 종료 표식 때문에 뒤늦게 재선점하지 못한다.

Booking 취소는 Booking → Payment 이력 → attempt 순서의 잠금을 사용한다. 미확정·완료 Payment가 있거나 이미 CONFIRMED인 Booking이면 부당 반환을 차단한다. 취소 상태와 RELEASE_PENDING을 먼저 저장하고, Lua 반환 및 공연 후처리가 끝나면 RELEASED를 커밋한다. 중간 상태는 별도 트랜잭션 사이에 존재할 수 있다.

## 실행·마이그레이션·한계

- [스키마와 기존 Booking 이관 지침](migrations/20261103_booking_attempt.sql): 앱을 멈추고 기존 선점을 차감 없이 연결한다. 운영 데이터 이관 검증 완료를 의미하지 않는다.
- [DB·Redis 장애 검증](booking-failure-stage2-results.md), [3개 앱 복구](booking-local-three-fault-results.md), [부하 중 종료](booking-load-fault-results.md).
- [성능 비교](booking-performance-optimized-results.md), [계측 결과](booking-success-path-timing-results.md), [결제 복구](payment-saga-results.md).

Redis 내부 Lua 원자성은 MySQL과의 분산 트랜잭션이 아니다. Redis 전체 유실 복구는 범위 밖이며, 기존 attempt가 있는 공연의 카운터가 사라지면 초기화를 차단한다. 수량 변경과 공연 삭제도 복구 기록을 보존하도록 제한한다.
