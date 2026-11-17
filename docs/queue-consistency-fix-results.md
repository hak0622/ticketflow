# 대기열 재등록·입장권·경합 응답 1차 수정

2026-11-17. 기준 HEAD `99a9bc3` 이후의 대기열 변경을 검증했으며 시연 앱에 재배포하지 않았다. [수정 전 특성 검증](queue-consistency-results.md)의 세 문제를 수정했다.

## 바뀐 동작과 코드

| 문제 | 수정 | 근거 |
|---|---|---|
| 재등록이 기존 점수를 덮어써 순번이 변경됨 | 기존 대기 항목은 ZADD NX로 점수를 유지 | [등록 Lua](../src/main/resources/redis/enqueue-concert.lua), QueueService.register |
| 입장권 보유자가 재대기·재발급되어 기한 연장 | 등록·조회는 ADMITTED 우선. 등록은 중복 큐 항목만 제거. 발급은 기존 입장권을 갱신하지 않음 | 등록·[조회 Lua](../src/main/resources/redis/read-concert-queue.lua)·[발급 Lua](../src/main/resources/redis/pop-concert-queue.lua) |
| ZADD와 ZRANK 사이 발급으로 QUEUED/0 반환 | 상태·순번·전체 인원을 같은 Lua 실행에서 읽고 Java에서 검증 | [QueueService](../src/main/java/studying/blog/service/QueueService.java), [Controller](../src/main/java/studying/blog/controller/ConcertQueueApiController.java) |

GET은 읽기 전용이며 공존한 큐 항목도 정리하지 않는다. POST와 GET의 기존 응답 필드는 유지하고 POST에도 기존 상태인 ADMITTED를 사용한다. POST는 QUEUED에 nextPollMs를 추가하지 않는다. 프론트 등록 호출자는 응답 본문을 사용하지 않고 기존 대기열 화면으로 이동하므로 프론트 코드는 수정하지 않았다.

발급 Lua는 선택한 전체 대상과 인자를 첫 쓰기 전에 검증하고, 새 입장권을 저장한 뒤 해당 큐 항목을 제거한다. 한 번에 앞쪽 최대 batchSize개만 검사하며 기존 입장권을 건너뛴 인원을 추가 조회로 채우지 않는다. 반환 목록과 scheduler.queue.granted에는 새 입장권만 포함한다. queue.enqueue 카운터는 기존처럼 성공한 등록 호출 횟수이며 신규 가입 인원 수로 해석하지 않는다.

예매·결제·반환 Lua, 스키마, Worker 주기, 입장권 600초와 폴링 주기/Jitter는 변경하지 않았다. 옛 Long 반환 enqueue는 register의 위치를 반환하는 호환 경로로 유지하며 입장권 보유자는 null을 반환한다. 운영 HTTP는 QueueSnapshot을 사용한다.

## 검증 방식

- MySQL 8.4·Redis 7 Testcontainers와 실제 JWT/MockMvc 보안·Controller 경로. 실제 TCP 서버·브라우저 결합 시험이나 앱 3대 자동 스케줄링은 아니다.
- 테스트 spy는 실제 운영 Lua를 실행하고 그 직전·직후에만 게이트를 둔다. 기존 ZADD/ZRANK 사이 게이트는 더 이상 존재하지 않으므로 등록 Lua 전 발급/후 발급 순서와 병렬 등록·조회·발급으로 대체했다.
- 정상 Redis 실행 직후 Java 예외 주입으로 완료 응답 유실을 검증했다. 실제 네트워크 패킷 차단 검증은 아니다. 손상된 응답 검사만 Mock 결과를 사용하며 Redis의 실제 실패·성공과 구분한다.
- 입장권 기한은 PTTL 자연 감소 범위와 PEXPIRETIME 절대 만료시각 동일 여부를 검사한다. 정상 신규 발급 600초 계약은 호출 경과시간과 관측 오차를 포함해 검사한다.
- 수정 전 테스트 원본과 실행 결과를 보존하고, 변경한 기대 동작은 위 표와 연결했다. 기존 특성 테스트도 PASS였으므로 수정 전 실패 테스트를 확보했다고 표현하지 않는다.

주요 검증은 A→B→A 점수 보존, 먼저 계산한 지연 요청의 기존 점수 덮어쓰기 차단, 입장권 재등록·공존 조회·배치 중복 처리 시 기한 보존, 만료 입장권 재진입, 등록/발급 순서와 병렬 스냅샷, 배치 뒤쪽 자료형 오류 시 무변경, 실제 신규 발급 집계, 완료 후 응답 유실 재시도, 잘못된 Redis 응답의 정상 상태 변환 차단이다. 기존 인증·공연 상태 캐시·폴링 경계·고정 350명 동시 발급·Booking HELD 조회 무변경도 유지한다.

## 실행 결과

| 실행 | 결과 |
|---|---|
| 최초 대기열 수정 검증 | 40건 통과, 실패·오류·스킵 0 |
| 절대 만료시각 assertion 보강 후 선택 백엔드 회귀 | 75건 통과, 실패·오류·스킵 0 |
| 기존 프론트 테스트 | 51건 통과, 실패 0 |
| 정적 검사 | git diff --check 통과. 수정 문서의 로컬 링크·코드 블록 짝 검사 통과 |

75건은 대기열 수정 40 + 기존 Queue 8 + 예매 선점 복구 6 + 장애 2단계 13 + 예매/결제 API 8이다. 대기열 40건은 최초 실행과 반복되므로 별도 115개 사례라고 합산하지 않는다. 기존 Queue 8건은 H2+Redis이며 나머지 선택 회귀는 MySQL+Redis다. 이 결과를 전체 백엔드 회귀·실제 3앱 검증·성능 개선 수치로 확대하지 않는다.

독립 리뷰는 코드·Lua·실행 증거를 읽기 전용으로 검토하며, 구현과 테스트에서 추가 P1/P2를 발견하지 않았다. 최종 수치 확인 결과도 원본 실행 디렉터리에 보존한다. 리뷰어는 테스트를 직접 실행하거나 파일을 수정하지 않았다.

```bash
./gradlew test --offline --tests studying.blog.fault.QueueConsistencyIntegrationTest --tests studying.blog.service.QueueServiceIntegrationTest --tests studying.blog.fault.BookingHoldRecoveryIntegrationTest --tests studying.blog.fault.BookingFailureStageTwoIntegrationTest --tests studying.blog.integration.BookingPaymentApiIntegrationTest
cd frontend
npm test
```

Java 17, Docker Desktop, 오프라인 실행에 필요한 Gradle 의존성·컨테이너 이미지가 필요하다. 초기화는 Testcontainers의 전용 데이터에만 수행한다. 로그·XML·실행 전 소스와 해시·게이트·HTTP·DB/Redis 스냅샷은 Git 제외 `test-results/queue-consistency-fix/20261117-165405/`에 보존한다. 원본은 공개 저장소에서 내려받을 수 없으며 실제 실행 환경에서 재생성해야 한다.

## 유지한 한계

- Booking 상태에 따른 대기열 재등록 차단은 추가하지 않았다. 입장권이 예매에서 소비된 뒤에는 새로 등록될 수 있다. Booking 중복 생성 방지와 대기열 등록 정책은 별개다.
- 점수는 앱 시각이며 동점은 Redis 문자열 정렬이다. 최초 요청의 엄격한 전역 FIFO를 보장하지 않는다. 먼저 Redis에 등록된 점수의 덮어쓰기만 방지한다.
- 신규 입장권은 기존 600초. 기존 TTL 없는 문자열 입장권도 대기열에서는 존재로 인정하고 갱신하지 않는다. 예매 Lua는 별도로 PTTL > 0을 요구하므로 이런 비정상 입장권의 예매 성공을 보장하지 않는다.
- Lua는 오류 시 롤백되지 않는다. 사전 검증은 예상 가능한 입력·자료형 오류를 방어하며 OOM·Redis 전체 유실·큐 부분 쓰기의 자동 복구까지 보장하지 않는다.
- 공연 상태 캐시와 큐 등록을 하나의 DB/Redis 트랜잭션으로 묶지 않는다. 기존 관리자 캐시 갱신과 HTTP 오류 매핑을 유지한다.
- 전체 앱을 합친 입장 속도 제한, k6 성능, 실제 PG, 재배포, 커밋·푸시는 수행하지 않았다.
