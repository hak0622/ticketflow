# 결제 Saga 실제 프로세스 장애 검증

2026-11-10. 운영 코드는 변경하지 않고 테스트 전용 Probe·HTTP PG·실행 스크립트를 추가했다. 기존 시연/개발 컨테이너와 데이터는 유지한다. [문서 안내](README.md) · [실행 방법](../perf/README.md#결제-saga-실제-프로세스-장애-검증).

## 환경과 판정 범위

최신 bootJar를 복사한 별도 fault-app.jar에 src/test의 PaymentSagaFaultProbe만 추가한다. 일반 bootJar에는 해당 클래스가 없음을 검사한다. 앱 A/B/C는 기존 로컬 3앱의 각 1 CPU·768MiB·heap 384MiB를 사용한다. MySQL 8.4, Redis 7, SQLite 원장을 사용하는 별도 Python HTTP PG를 독립 Compose 프로젝트에서 실행한다.

회차 사이에는 JVM을 재시작해 Circuit Breaker 등의 상태를 분리한다. 장애를 주입한 회차 안에서는 A를 SIGKILL로 종료한 뒤 A를 재시작하지 않고 B의 정상 스케줄러로 복구한다. 기존 주기·backoff·Hikari·timeout·행 잠금·Lua를 변경하지 않는다. 앱 외 HTTP 실행기가 요청과 관측을 조정하며 Worker/만료/Outbox 메서드를 수동 호출하지 않는다.

PG는 효과·멱등키·본문·요청 이력을 SQLite WAL/FULL 동기화로 저장한 뒤 응답한다. Probe는 실제 HTTP 응답을 받은 이후 서비스에 반환하기 직전 멈춘다. 따라서 PG 커밋 완료→응답 수신 게이트→SIGKILL이라는 인과 순서를 확인할 수 있다. SIGKILL 종료 137과 OOM 아님을 각각 기록한다. 실제 PG는 호출하지 않는다.

취소 의도는 테스트 fixture다. 정상 API로 Booking과 Payment 선커밋·PG 승인을 만든 뒤 독립 DB에서 CANCEL_REQUIRED를 준비한다. 공개 API가 이 상태를 생성했다는 검증은 아니다. 만료 후보는 기존 Booking 시각에서 31분을 빼 JDBC/서버 시간대 차이에 의존하지 않게 한다. 과거 FAILED Payment/Outbox는 B/C가 PG 응답을 확보한 뒤 준비한다. 이는 완료 상태를 SQL로 대신 쓰는 것이 아니다. SIGKILL 이후에는 상태나 nextRetryAt을 수정하지 않는다.

## 시나리오와 증거

| 시나리오 | 확인할 결과 |
|---|---|
| 승인 응답 수신 직후 A SIGKILL | B 자동 Worker가 PG 조회 후 Payment COMPLETED/Booking CONFIRMED, attempt BOOKED, Redis HELD/잔여 2, Outbox 0 |
| PG 전액 취소 응답 수신 직후 A SIGKILL | B 자동 Worker가 CANCELED+Outbox를 저장하고 자동 보상이 Booking CANCELLED/attempt RELEASED/잔여 3으로 종료. 실제 PG 취소와 티켓 반환 효과 각각 1회 |
| 취소 확인 중 오래된 DONE 도착 | B/C Worker가 같은 결제를 선택하고 만료·과거 Outbox와 Booking 잠금 경합. 취소 의도 이후 CONFIRMED로 역전되지 않고 취소 확인 전 반환 없음 |
| 취소·반환 완료 후 오래된 DONE 도착 | 오래된 응답과 중복 재요청 모두 기존 CANCELED 유지. 새 승인·차감·반환·취소 Outbox 중복 없음 |

각 variant는 3회, 총 12회가 정식 검증이다. 매번 별도 공연·요청 UUID·주문·PG key를 사용하고 티켓 3장 중 한 장만 확보한다. 실제 PG 효과 횟수와 HTTP 호출 횟수는 구분한다. 같은 주문을 B/C에 각각 3번 재요청하고 두 앱의 실제 Worker 스캔이 2회 이상 끝날 때까지 관측한다. 자동 복구의 전체 제한은 장애 후(경합은 PG 취소 허용 후) 180초다.

경합에는 두 관측 구간이 있다. 우선 B/C Worker의 PG 조회 결과를 동시에 보류해 같은 결제의 복구가 겹쳤음을 확인한다. 이어 C가 미확정 취소로 한 차례 종료하면 C의 단일 스케줄러를 Outbox에 사용한다. A의 만료 트랜잭션이 Booking 행을 보유한 동안 B Worker·C Outbox와, 취소 확인 중 variant에서는 A의 원래 HTTP도 대기하게 만든다. 스케줄러 스레드가 앱당 하나라는 현재 구조를 바꾸지 않는다. 모든 작업이 한 앱에서 동시에 실행된다고 주장하지 않는다.

performance_schema.data_lock_waits의 **서로 다른 요청 트랜잭션 수**와 실제 Booking PK 대기를 저장한다. 취소 의도 커밋 이후 binlog 구간을 원본 복사하고 호스트 mysqlbinlog로 해석해 해당 Payment의 COMPLETED 및 Booking의 CONFIRMED 갱신이 없는지 검사한다. 종료 스냅샷만으로 순간적 역전을 없었다고 추정하지 않는다. 현재 호스트 디코더는 8.0.42이며 이 MySQL 8.4 ROW 이벤트 판독에 사용한 것으로 다른 모든 버전 호환성을 뜻하지 않는다.

## 초기 장치 점검에서 발견한 문제

초기 실행은 삭제하지 않고 정식 결과와 구분한다.

1. `20261110-094102-f8dc8a`: 승인 종료 통과, 취소 게이트 도달 실패. 다른 앱의 의도적인 PG 조회 503이 공유 nextRetryAt을 밀어 A가 후보를 선택하지 못했다. A 스레드 덤프는 락 대기가 아닌 idle이었다. 테스트에서는 선택하지 않은 자동 Worker를 후보 조회 전에 일시 보류하도록 변경했다. 비대칭 PG 접근 장애에서 건강한 Worker가 밀릴 수 있다는 가용성 위험은 별도로 남는다.
2. `20261110-095140-2a359f`: mysql CLI의 JSON 이중 이스케이프로 Outbox 등장 이후 수집기 파싱 실패. JSON을 HEX로 전송해 읽도록 수집기만 보완했다.
3. `20261110-095507-5c7aa5`: 승인/취소 종료 통과. 과거 Outbox가 C의 단일 스케줄러를 먼저 점유해 C Worker 준비가 불가능했다. 실행기만 SIGINT로 중단하고 미완료 경합은 미검증으로 남겼다. 과거 Outbox를 B/C 응답 준비 뒤 삽입하도록 fixture 순서를 변경했다.
4. `20261110-100422-72d087`: 두 경합의 상태·수량·중복 검사는 통과했으나 컨테이너에 mysqlbinlog가 없어 구간 감사 실패(exit 127). 이 실행을 binlog 감사 성공으로 취급하지 않는다. 원본을 복사해 호스트 디코더로 해석하는 관측 경로로 보완했다.

원인·재현 조건은 각 실행 폴더의 diagnosis.json/console.log 및 원본 결과를 따른다. 위 과정에서 운영 코드·검사 기대 상태·timeout·풀·Worker 주기를 변경하지 않았다.

## 산출물과 한계

원본은 `test-results/payment-saga-fault/<실행 ID>/`의 PG SQLite/요청 이력, 코드/설정 사본, JAR·소스 해시, 이벤트 타임라인, 컨테이너 종료 상태, DB/Redis 스냅샷, binlog 원본·해석·상태 목록이다. 로컬 Git 제외 폴더이며 원격 백업을 뜻하지 않는다. 실패·중단 점검도 보존한다. 해당 테스트 프로젝트만 종료·정리한다.

이번은 한 주문씩 결정적으로 경계를 제어하는 정합성 검증이며 부하/성능/로드밸런서 자동 failover 검증이 아니다. 실제 Toss, COMMIT ACK 유실, Redis 전체 유실, 일반 환불 API·부분 환불·가상계좌는 범위 밖이다. 과거 실패 Outbox와 취소 의도는 fixture로 준비했으므로 공개 API에서 그 상태까지 도달하는 정책을 검증한 것으로 설명하지 않는다. 게이트와 초기 PG 503 등 통제 조건에서의 결과이며 모든 장애 조합이나 Worker 공정성을 보장한다는 결론을 내리지 않는다.

최종 회차별 판정 및 기존 108건 회귀 결과는 아래 실행 결과에 기록한다.

## 정식 12회 실행 결과

원본: `test-results/payment-saga-fault/20261110-101020-cf6e94/`.

| 시나리오 | 성공 | 실패 | 미검증 | 판정 |
|---|---:|---:|---:|---|
| 승인 완료 직후 A SIGKILL → B 자동 확정 | 3 | 0 | 0 | 성공 |
| 전액 취소 완료 직후 A SIGKILL → B 자동 보상 | 3 | 0 | 0 | 성공 |
| 취소 확인 중 오래된 DONE + Worker/만료/Outbox | 3 | 0 | 0 | 성공 |
| 취소·반환 완료 뒤 오래된 DONE | 3 | 0 | 0 | 성공 |

- SIGKILL 6회 모두 종료 137/OOM 아님. 해당 회차에서 A 재기동 없이 B의 스케줄링 스레드가 복구했다.
- PG 실제 승인 효과 12건, 전액 취소 효과 9건. 승인만 확정된 3건은 반환 0회, 취소된 9건은 반환 Lua의 RETURNED 효과가 요청별 1회다. HTTP 재시도 횟수와 구별했다.
- 경합 6회의 Booking PK 대기는 서로 다른 요청 트랜잭션 기준 `[3,3,3,2,2,2]`였다. 취소 확인 전에는 미확정 결제와 잔여 2장, 과거 Outbox PENDING/retry 0을 유지했다.
- 6개 binlog 감사 모두 취소 의도 이후 해당 Payment COMPLETED/Booking CONFIRMED 갱신 없음. ROW/FULL 설정·실제 컬럼/ENUM 정의와 원본 binlog를 보존했다.
- 매 회차 종료 및 중복 요청 후 총 24개 스냅샷에서 Payment 복구 단계 NONE, Redis 소유자 및 Booking/attempt/주문별 Payment 각각 1건을 추가 확인했다.
- 정상 bootJar의 Probe 미포함 및 전체 src/main 133개 파일의 작업 전후 SHA-256 동일을 확인했다. 운영 소스·리소스 변경 0건.
- 정식 실행은 외부 PG 호출/수동 Worker 호출/DB 결과 상태 강제 기록 없이 통과했다. fixture 변경과 게이트 해제는 테스트 준비·제어이며 재처리 메서드 호출을 대신하지 않는다.

요약은 `summary.json`, 추가 교차 검사는 `verified-summary.json`, 회차별 상세는 `results.json`과 `timeline.ndjson`이다. 초기 실패·중단 점검은 별도 폴더에 남겨 정식 12회의 성공과 섞지 않았다.

## 변경 파일

테스트 지원 코드만 추가: `src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java`, `perf/payment_saga_fault.py`, `perf/payment_pg.py`, `perf/compose.payment-saga-fault.yml`.

실행 안내는 perf/README.md, 문서 연결은 docs/README.md에 추가했다. 이 결과 문서는 신규 작성했다. 기존 운영 코드·기존 테스트 본문·시연 앱은 수정하지 않았다.

## 기존 회귀 결과

정식 장애 실행 종료 후 기존 12개 클래스 108건을 다시 실행했다. 실패 0, 오류 0, skip 0이며 선택 테스트 전체가 통과했다. 기존 예매 장애·동시성·매진·결제·소유권·만료·Outbox·Queue 계약을 포함한다. 모든 프로젝트 테스트를 실행한 것은 아니다.

원본은 정식 폴더의 `regression/command.txt`, `regression/run.log`, JUnit XML 및 `regression/summary.json`에 있다. H2 기반 Scheduler 테스트는 새 전용 Redis의 임의 localhost 포트를 환경 변수로 전달했고 종료 후 그 Redis만 정리했다. MySQL/Redis Testcontainers는 자체 연결을 사용했다. 테스트가 생성한 spy.log는 보존하고 작업 전 내용으로 복원했다.

최종적으로 신규 운영 기능 추가·운영 코드 수정·시연 앱 교체·성능/k6 재측정·실제 PG 호출·자동 커밋/push는 수행하지 않았다. 초기 장치 점검의 실패/중단을 지우거나 운영 버그 수정으로 포장하지 않았다. 비대칭 PG 조회 실패와 공유 재시도 시각의 공정성 문제는 이번 통제된 성공 결과로 해결됐다고 볼 수 없다.
