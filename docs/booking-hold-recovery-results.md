# 선점 유지 및 예매 자동 복구 구현 결과

- 완료: 최초 Redis 선점부터 5분간 재고를 유지하고 Booking 생성 장애를 재시도. 기본 선점 기한 연장 없음.
- BookingAttemptTransactions의 기존 잠금과 REQUESTED/BOOKED/RELEASE_PENDING/RELEASED 상태, 공통 반환 Lua를 유지했다. 정상 매진 REJECTED/SOLD_OUT 및 PG·Outbox 보호 유지.
- 신규 요청 상태 GET과 기존 요청 ID 유지/조회/404 재전송 안내/자동 결제 화면 이동 추가. PG 자동 승인은 없다.
- DB 스키마·풀·timeout·Worker 주기는 변경하지 않았다. Redis 신규 HELD에는 holdVersion과 holdExpiresAt만 추가한다.

## 결과

| 검증 | 성공 | 실패 | skip |
|---|---:|---:|---:|
| 실제 MySQL·Redis 포함 관련 선택 회귀 | 164 | 0 | 0 |
| 기본 5분 실제 경과·자동 Worker 반환 별도 JVM | 1 | 0 | 0 |
| 최신 JAR·앱 3대 실프로세스 장애 | 4 시나리오 | 0 | 0 |

5분 검증 XML 실행 시간: 302.08초. 사용자 재전송, Worker를 명시적으로 호출하는 통합 검증과 실제 스케줄링 검증을 구분했다. 테스트 164건은 실행 건수이며 상속 테스트를 포함한다. 저장소 전체 테스트를 실행한 것은 아니다. 프론트 production build와 git diff --check 통과.

실프로세스 4건: Lua 선점 후 SIGKILL 및 다른 앱 자동 생성, DB 커밋 후 SIGKILL 응답 유실, 앱 3대 중복 요청, 미선점 요청 종료 후 늦은 원래 요청. SIGKILL과 자동 Worker 실행 증거·DB/Redis 단계 스냅샷은 test-results/booking-hold-recovery/process/20261111-000537-aa5809에 있다. 원본은 Git 제외 로컬 자료다.

실제 UI: MySQL CHECK 제약으로 INSERT 실패 → 처리 중 표시 → 새로고침 후 같은 요청 유지 → SOLD_OUT 상태에서도 복구 계속 → 제약 제거 후 Worker가 생성하고 결제 페이지로 자동 이동. 최종 Booking PENDING_PAYMENT / attempt BOOKED / Redis 잔여 9 (총10). PG 호출은 하지 않았다. waiting.jpg가 대기 화면 증거이며 recovered.jpg는 중간 관측 화면이므로 최종 결제 화면 캡처로 해석하지 않는다.

## 변경 파일

```text
M docs/README.md
M docs/booking-attempt-recovery.md
M frontend/src/features/booking/api.js
M frontend/src/pages/BookingPage.jsx
M perf/README.md
M src/main/java/studying/blog/controller/BookingApiController.java
M src/main/java/studying/blog/domain/BookingAttempt.java
M src/main/java/studying/blog/service/BookingAttemptTransactions.java
M src/main/java/studying/blog/service/BookingSeatStore.java
M src/main/java/studying/blog/service/BookingService.java
M src/main/resources/redis/reserve-booking.lua
M src/test/java/studying/blog/fault/BookingFailureStageTwoIntegrationTest.java
M src/test/java/studying/blog/integration/BookingPaymentApiIntegrationTest.java
M src/test/java/studying/blog/service/BookingAttemptRecoveryTest.java
?? docs/booking-hold-recovery.md
?? perf/booking_hold_fault.py
?? src/main/resources/redis/inspect-booking-hold.lua
?? src/test/java/studying/blog/fault/BookingHoldLeaseClockIntegrationTest.java
?? src/test/java/studying/blog/fault/BookingHoldRecoveryIntegrationTest.java
```

## 초기 실패와 수정

초기 72건 중 기존 기대값 6건 실패는 선점 유지 정책 변경과 필요한 선점 확인 조회 때문이었다. 163건 실행에서 5분 테스트의 스케줄러 OFF 설정과 입장권 없는 일반 거절의 202 응답 회귀를 발견했다. 자동 만료는 스케줄러 ON 별도 JVM으로 분리하고, 일반 거절은 반환이 완료됐음을 확인한 뒤 기존 409를 유지했다. 최종 164건 회귀와 별도 5분 검증은 이 수정 후 실행했다. 초기 로그·XML은 삭제하지 않았다.

손상 기록의 Redis 확인 오류가 backoff 저장까지 막지 않도록 수정했고 새 형식 기한/버전 누락 검증을 추가했다. UI 전용 Origin·임의 포트 문제는 테스트 설정만 수정했다. 상세는 known-initial-failures.md를 참조한다.

## 남은 위험·미검증

- Redis 전체/부분 유실, failover·메모리 고갈·장기간 복구 backlog의 정합성 재구축은 미구현/미검증.
- DB/Redis가 계속 불가하면 5분 이후에도 안전한 확인·반환이 지연될 수 있다. Worker 공정성·최대 복구 지연 보장 없음.
- 새 형식 손상 기록은 자동 반환하지 않는다. 로그와 DB 요청 기록으로 운영 확인이 필요하다. 신규 전용 알림·관리자 복구 API는 추가하지 않았다.
- 기기 간 미완료 요청 검색, 가격 고정, 일반 사용자 환불 API, 최신 성능 재측정은 범위 밖.
- 실제 화면은 핵심 복구와 새로고침/404/SOLD_OUT을 확인했다. 모든 UI 오류 분기를 브라우저에서 전수 재현한 것은 아니다.
- 실제 PG 결제·전체 k6는 실행하지 않았다. 기존 결제 Saga·Outbox는 회귀로 검증했다.
- 새 버전은 모든 앱을 중지하고 함께 적용해야 한다. 이번에는 독립 검증 환경만 배포했고 기존 시연 백엔드는 교체하지 않았다.

원본 백업과 과거 검증 기록을 유지했다. 자동 commit/push는 하지 않았다.
