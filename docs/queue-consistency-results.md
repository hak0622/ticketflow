# 대기열 재등록·입장권·동시 발급 검증

> 아래는 수정 전 특성 검증 기록이다. 후속 수정·검증은 [대기열 1차 수정 결과](queue-consistency-fix-results.md)를 확인한다.

2026-11-17, 기준 `99a9bc3`. 운영 코드 수정 없이 실제 동작을 재현한 특성 검증이다. **테스트 통과는 아래 문제 동작을 재현했다는 뜻이며 대기열 품질 합격을 의미하지 않는다.**

## 실행과 범위

- MySQL 8.4·Redis 7 Testcontainers, test 프로파일, 자동 스케줄러 OFF. fixture는 커밋하고 병렬 작업을 수행했다.
- 실제 JWT를 생성해 MockMvc의 Security Filter → Controller → 예외 처리 경로를 검증했다. 실제 TCP HTTP 서버나 브라우저 결합 시험은 아니다.
- QueueService에 테스트 전용 Redis delegate spy를 주입했다. 실제 ZADD/ZRANK를 callRealMethod로 실행하면서 입력·결과·게이트만 관측하고 정리 시 원래 template을 복원했다. Redis 결과를 Mock 값으로 대체하지 않았다.
- 발급·Scheduler 메서드는 테스트에서 직접 호출했다. 실제 앱 3대 자동 주기나 초당 처리량 검증이 아니다.
- 개발·시연 데이터는 사용하지 않았으며 src/main 전 파일의 전후 SHA-256 일치를 확인했다.

## 재현 결과

| 항목 | 실제 관측 | 판단 |
|---|---|---|
| A→B→A 재등록 | 항목 수는 2지만 A score 갱신, A 순번 2·B 순번 1 | 재요청으로 최초 순서가 바뀜 |
| 동시 재등록 | 먼저 계산한 score가 늦게 반영되면 더 새로운 score를 덮어씀 | 등록 시각과 반영 순서가 다를 수 있음 |
| 입장권 보유자 재등록 | 대기 항목·입장권 공존, GET은 QUEUED 우선 | 공존 시 응답 정책 결정 필요 |
| 후속 재발급 | 약 60초 잔여 TTL이 600초 발급으로 증가 | 재등록 후 발급을 통해 기한 연장 가능. POST 자체가 연장한 것은 아님 |
| ZADD→발급→ZRANK | POST 200/QUEUED/position=0, 이후 GET ADMITTED | 프론트의 양의 순번 검증과 맞지 않는 응답 가능 |
| 발급→재등록→발급 | 같은 사용자가 다음 배치에서도 선택됨 | 고정 대기열의 단일 발급 보장이 재등록까지 확장되지 않음 |
| Booking 3상태 | PENDING_PAYMENT/CONFIRMED/CANCELLED 모두 재등록 시 QUEUED, Booking 상태·재고 유지 | Controller 설명의 BOOKED와 다름. 중복 예매를 입증한 시험은 아님 |
| 고정 350명/발급 3호출 | 총 300명·합집합 300명·잔여 50명 | 재등록 없는 고정 대기열에서 중복 선택 없음 |
| 빈 큐·17명 | 중복 없이 전체 가능한 인원만 발급 | 배치 경계 정상 |
| 동일 score의 10·2 | 문자열 정렬로 10이 먼저 | 숫자 ID 순서·엄밀한 전역 FIFO 아님 |
| CLOSED/SOLD_OUT/종료 | 캐시 히트·미스 모두 POST 403 | 현재 예외 처리 순서의 실제 결과. SOLD_OUT=409로 가정하면 안 됨 |
| 관리자 마감 | adminClose 커밋 후 캐시 CLOSED, 등록 거절 | 실제 관리자 경로 캐시 갱신 확인 |
| DB만 직접 마감한 fixture | 이전 OPEN 캐시가 있으면 등록 허용, 캐시 제거 후 거절 | 관리자 변경 경로와 다른 fixture 시험 |
| 미인증·미존재 | POST/GET 미인증 401, 미존재 POST 400·GET NOT_IN_QUEUE | 경로별 검증 차이 확인 |
| GET·격리·폴링 | 1000위 5초/1001위 10초, 공연·사용자 분리, 조회로 선점·재고 변경 없음 | 해당 경계 회귀 확인 |

동일 score와 더 낮은 score를 직접 준비한 정렬 시험은 데이터 특성을 검증한 것이며 실제 앱 시계 오차를 재현한 것은 아니다. GET의 TTL 검사는 자연 감소를 허용하되 연장·소비하지 않음을 검사했다.

## 실행 결과

| 실행 | 결과 |
|---|---|
| 최초 신규 특성 테스트 | 22건 통과, 실패 0 |
| 상세 rank/TTL 기록 및 HELD 조회 무변경 보완 후 신규 | 23건 통과, 실패 0 |
| 선택 백엔드 회귀 | 총 58건 통과, 실패 0 |
| 독립 리뷰 보강 후 신규 재실행 | 23건 통과, 실패 0. 운영 코드 변경 없음 |
| 프론트 | 51건 통과, 실패 0 |

58건은 신규 23 + 기존 Queue 8 + 예매 선점 복구 6 + 장애 2단계 13 + 예매/결제 API 8이다. 최초 22건과 최종 58건을 합쳐 서로 다른 80개 사례라고 표현하지 않는다. 기존 Queue 8건은 H2+Redis, 신규·핵심 예매 장애는 MySQL+Redis이며 옛 반환/입장권 메서드 테스트는 운영 경로 검증과 구분한다.

```bash
./gradlew test --offline --tests studying.blog.fault.QueueConsistencyIntegrationTest
./gradlew test --offline --tests studying.blog.fault.QueueConsistencyIntegrationTest --tests studying.blog.service.QueueServiceIntegrationTest --tests studying.blog.fault.BookingHoldRecoveryIntegrationTest --tests studying.blog.fault.BookingFailureStageTwoIntegrationTest --tests studying.blog.integration.BookingPaymentApiIntegrationTest
cd frontend
npm test
```

Docker Desktop·Java 17·의존성 캐시가 필요하다. 초기화는 Testcontainers의 폐기 가능한 데이터만 대상으로 한다. raw 결과는 Git 제외 `test-results/queue-consistency/` 실행 디렉터리에 로그·XML·score·게이트·HTTP 본문·Redis PTTL·DB 스냅샷으로 보존한다. JWT는 관측 이벤트에 기록하지 않는다.

## 남은 결정과 한계

다음은 별도 수정 계획에서 합의해야 한다: 재등록의 최초 순서 보존, 입장권 보유자의 재등록·기한 연장 허용 여부, Booking 상태별 응답, 대기·입장 상태 우선순위, position=0 경합의 목표 API 계약, 공정성 기준.

전역 초당 100명 제한은 현재 구현의 보장이 아니다. 이번 3개 동시 호출 결과를 초당 300명 처리 성능이나 3앱 자동 스케줄러 검증으로 해석하지 않는다. 캐시 TTL 실제 만료 대기·다중 앱 시계 차이·큐 발급 Lua 부분 쓰기·네트워크 장애·전체 k6·실제 PG·재배포·커밋·push는 수행하지 않았다. 기존 Lua 부분 쓰기 보호는 예매 재고 경로에 대한 것으로 큐 발급 Lua까지 확장했다고 주장하지 않는다.

## 독립 리뷰와 테스트 보강

별도 리뷰 채팅은 실제 Redis 호출·게이트 순서·XML·원본 스냅샷과 운영 코드 무변경을 확인했다. 테스트 P2 보강 3건을 지적하여 운영 코드 변경 없이 반영했다.

- 작업 스레드 종료 검사가 실패해도 원래 Redis template 복원을 finally로 보장하고, 종료·증거 수집 오류는 별도 기록한다. 이 정리 실패 경로를 고의로 주입하는 추가 시험은 하지 않았다.
- Booking 보유자 재등록 시 실제 membership·순번, pop 반환 사용자, 이후 ADMITTED·입장권 존재를 assertion으로 고정했다.
- 직접 pop과 Scheduler 발급 모두 600초 PTTL을 확인한다. 실제 호출·조회 경과시간과 1초 관측 여유를 적용하고 관측 구간은 5초 이내로 제한했다.

보강 후 읽기 전용 재리뷰에서도 추가 P1/P2는 발견되지 않았다. 재리뷰는 신규 23건의 재실행 로그·XML·증거를 확인했으며 직접 실행하거나 수정하지 않았다. 58건 회귀와 프론트 51건은 테스트 보강 전 실행이고, 보강 후에는 변경한 신규 클래스 23건을 재실행했다.

Booking 3상태 fixture는 정상 예매 후 상태 열만 변경한다. 따라서 취소 상태의 시험은 CANCELLED 행이 존재할 때의 Queue 동작을 검사한 것이며, 정상 취소·보상 완료 후 재진입을 검증한 것은 아니다. 최초 실행도 특성 assertion은 통과했으므로 수정 전 실패(Red)를 확보했다고 표현하지 않는다. 이번 변경은 테스트와 검증 문서만 포함하며 운영 문제의 수정은 포함하지 않는다.
