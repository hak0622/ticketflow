# 결제 Worker 재시도 회차 중복 갱신 방지

2026-11-10. 기존 [관련 검증 결과](payment-retry-round-fix-results.md)을 기준으로 서비스 코드를 최소 수정했다. **실프로세스 15회·747개 검사 및 회귀 116건 모두 통과**했다. 실프로세스 실행 결과는 `test-results/payment-retry-fault/20261110-143138-b08b6e/`, 기준 소스·초기 검사와 회귀 기록은 `test-results/payment-retry-fix/`에 보존한다.

## 기준과 수정 내용

수정 전 src/main 133개 파일의 SHA-256이 기존 12회 재현 실행 `20261110-105436-721815`와 모두 일치했다. 기존 실행 결과·코드 사본·기대값은 덮어쓰지 않았다. 기준 확인 JSON과 변경 전 소스 및 이번 작업만의 diff는 `payment-retry-fix/baseline/`에 있다.

- `PaymentRetryRound` 불변 record에 ID·retryCount·nextRetryAt·recoveryPhase·version을 저장한다. Worker는 후보 SELECT 결과에서 만들고 `recover(id, round)`로 전달한다. 중간 재조회로 다른 Worker의 새 회차를 받아들이지 않는다. 기존 `recover(id)` 진입점도 유지하며 기존과 같은 1회 진입 조회 후 공통 처리를 호출한다.
- 기존 Booking→Payment 잠금 안에서 PENDING 및 ID/카운터/기한/단계 일치를 검사한 뒤 실패 재시도를 저장한다. version은 관측·상태 변경에도 증가하므로 회차 비교 조건에서는 제외하고 진단에만 사용한다.
- HTTP 예외뿐 아니라 미확정 PG 상태·미지원 수단·잔액 불일치의 `retry()`도 공통 조건을 사용한다. 오래된 실패는 카운터·기한·lastError를 바꾸지 않는다. 유효한 PG 응답의 관측 정보는 기록할 수 있으므로 모든 오래된 응답이 SQL 무변경이라는 의미는 아니다.
- 정상 승인·취소 결과에는 회차 일치를 요구하지 않는다. 현재 잠금 상태에서 기존 식별자·금액·수단 및 취소 의도를 검증한다. 완료/취소 결과는 다시 뒤집지 않으며 취소 Outbox는 기존 같은 트랜잭션에서 저장한다.
- 자기 트랜잭션이 취소 단계로 실제 전이할 때만 그 결과를 새 단계의 비교 기준으로 반환한다. 트랜잭션 execute가 성공적으로 반환한 뒤에만 이 값을 사용한다. 동일 CANCEL_CHECK에 다시 진입해 기한을 재설정하지 않는다. 다른 Worker가 이미 회차를 변경했다면 그 값을 채택하지 않는다.
- 최초 승인 호출 선점 트랜잭션도 성공 시 선점 이후의 회차를 반환한다. 최초 승인 HTTP 실패를 선점 이전 기준으로 비교하지 않는다.
- 자체 트랜잭션 안에서 생성한 기한 스냅샷과 DB 재조회 값의 정밀도를 맞추기 위해 기한 생성값을 DATETIME(6)에 맞는 마이크로초로 절삭한다. DB 타입·스키마·초 단위 대기 정책은 변경하지 않는다.

서비스 변경은 `PaymentSaga`, `PaymentRecoveryWorker`, `Payment`와 신규 `PaymentRetryRound` 네 파일이다. 기존 프로세스 시연 앱을 새 JAR로 교체하지 않는다.

## 트랜잭션 경계

후보 조회 방식은 그대로이며 배타적 선점/lease는 추가하지 않았다. Worker 진입 조회, 결과 적용, 취소 단계 준비, 실패 기록, 응답 조회의 기존 경계를 유지한다. 비교만을 위한 새 조회·트랜잭션·flush는 추가하지 않는다. private helper는 새로운 프록시를 만들지 않는다. PG HTTP는 모든 경우 DB 트랜잭션 밖이며 Booking→Payment 잠금 순서와 REQUIRES_NEW/READ_COMMITTED는 유지한다.

정상 PG 결과 적용과 실패 회차 갱신은 구분한다. 이미 다른 Worker가 실패 기한을 변경했더라도 먼저 선택한 정상 Worker의 성공은 반영할 수 있다. DB 커밋이 불명확하거나 실패 기록 자체가 실패하면 기존처럼 결과를 추정하거나 티켓을 반환하지 않고 다음 복구를 기다린다. 이번 작업에서 COMMIT ACK 유실을 새로 재현한 것은 아니다.

## 통합·회귀 검증

기존 Saga 통합 테스트 9건과 신규 `PaymentRetryRoundIntegrationTest` 8건을 각각 실행해 통과했다. 이어 기존 108건에 신규 8건을 포함한 **116건 전체 선택 회귀가 통과**했다. 실패·오류·skip은 모두 0이다. 전체 프로젝트의 모든 테스트를 실행한 것은 아니다.

신규 통합 검증은 실제 MySQL 8.4·Redis 7 Testcontainers 및 로컬 HTTP PG를 사용한다. 동일 후보 동시 실패, 실패 뒤 정상 승인, 새 회차 이후 오래된 후보 실패, 미확정 응답/HTTP 실패 교차, 미지원 수단·잔액 불일치, 자체 취소 단계 전이 후 실패, 오래된 후보 취소 성공 및 Outbox 중복 방지, 실제 HTTP 오류 응답 보류 후 완료를 포함한다. 이전 회차→새 회차 비교는 DB 기한을 강제로 바꾸지 않고 캡처한 후보를 직접 전달하는 통합 테스트이며 실제 자동 스케줄링 경합은 별도 실행으로 구분한다.

기존 회귀에는 예매 장애·요청 잠금·매진·멱등 반환·결제 Saga·만료·Outbox·Mock 보호·Queue가 포함된다. H2 테스트에는 새 임의 포트의 전용 Redis만 전달했고 종료 후 해당 Redis를 제거했다. Testcontainers는 자체 연결을 사용했다. 명령·로그·13개 JUnit XML·집계·테스트 생성 spy 로그는 `payment-retry-fix/regression/`에 있다. 회귀 실행 직전 spy 로그는 보존하고 회귀 후 그 내용으로 복원했다.

## 실프로세스 비교

| 시나리오 | 성공 | 실패 | 미검증 | 결과 |
|---|---:|---:|---:|---|
| 동일 후보 선택 후 실패 두 건 | 3 | 0 | 0 | 카운터 1→2. 두 번째 실패 후 기한·오류·버전 포함 Payment 스냅샷 불변. 먼저 선택한 정상 Worker가 확정 |
| 실패 선행 두 회차 반복 | 3 | 0 | 0 | 실패 네 건에 카운터 1→3. 기존 1→5와 구분. 각 회차 두 번째 실패는 무변경 |
| 완료 뒤 오래된 HTTP 실패 | 3 | 0 | 0 | 완료 상태·카운터·기한·오류·버전 유지 |
| Worker 순서 무제어 비대칭 조회 | 3 | 0 | 0 | 세 회차 모두 자동 복구 |
| 실패 선행 세 회차 반복 | 3 | 0 | 0 | 실패 여섯 건에 카운터 1→4. 각 회차 두 번째 실패는 무변경, 정상 Worker 최종 복구 |

원래 네 시나리오 각 3회에 세 회차 연속 실패 3회를 추가했다. **15회 성공/실패 0, 검사 747개 성공/실패 0**이다. 기존 자동 스케줄러와 주기/backoff를 유지하고 실패 앱의 신규 조회 중단부터 정상 복구까지 100초를 적용했다. 제한이나 기대값을 완화하지 않았다. 테스트 기대값은 결함을 허용하던 수정 전 기준에서 승인된 회차당 1회 갱신 계약으로 변경했으며, 수정 전 코드·기대값은 원본에 보존했다.

### 실제 관측 시간

| 관측 구간 | 1회차 | 2회차 | 3회차 |
|---|---:|---:|---:|
| 수정 전 두 회차 제어 구간 | 95.566초 | 95.687초 | 95.524초 |
| 수정 후 두 회차 제어 구간 | 74.809초 | 74.972초 | 74.991초 |
| 수정 후 세 회차 제어 구간 | 120.336초 | 114.954초 | 118.108초 |
| 두 회차 후 정상 Worker만 허용→처리 종료 | 40.188초 | 40.305초 | 40.328초 |
| 세 회차 후 정상 Worker만 허용→처리 종료 | 40.245초 | 40.454초 | 40.288초 |
| 수정 후 순서 무제어 조회 허용→DB 최종 검증 | 11.562초 | 11.708초 | 11.930초 |

제어 구간은 최초 실패 앱 B/C의 조회 허용부터 A Worker 종료 이벤트까지이며, 후보 선택 게이트·기존 backoff·스케줄 대기를 포함한다. 정상 Worker만 허용하는 구간은 마지막으로 실패 앱의 신규 스캔을 차단한 시점부터 측정했다. 순서 무제어 측정은 DB 검증까지 포함하므로 끝점도 다르다. 이 수치는 고부하 API 성능·p95·운영 SLA가 아니며 k6 성능 개선률로 해석하지 않는다.

중복 증가로 생기던 대기는 줄었지만, 정상 Worker가 이미 변경된 기한을 기다리는 상황은 남는다. 실제로 두 회차와 세 회차 모두 마지막 정상 복구에는 약 40초가 필요했다. 같은 회차의 중복 실패를 막았다는 것과 정상 Worker가 언제나 먼저 선택된다는 보장은 다르다.

### 정합성·환경 교차 검사

- 종료 및 중복 결제 재요청 후 총 30개 스냅샷에서 Payment COMPLETED/NONE, Booking CONFIRMED, attempt BOOKED, Redis HELD/잔여 2 및 전체 3장 보존, Outbox 0을 검사했다. 회차마다 Booking/attempt/주문별 Payment는 각각 1개다.
- PG 실제 승인 효과는 15회, 취소 효과 0회다. 중복 결제 API 요청 90건은 기존 완료 응답을 재사용했다. 저장된 PG 멱등키와 본문도 검사했다. 이 15회는 승인 복구 시나리오이며 취소 경로는 앞선 통합·회귀 테스트로 구분한다.
- 정상 Worker의 후보 제외 스캔 30회를 실제 후보 SELECT 이벤트로 확인했다. 모든 실프로세스 복구는 자동 스케줄러가 수행했으며 수동 Worker 호출은 0회다.
- 일반 bootJar에는 Probe가 없음을 검사했고, 복사한 별도 fault JAR에만 Probe를 넣었다. 수정 후 src/main 134개 파일은 실행 도중 해시가 동일했다. 기준 대비 변경은 앞서 명시한 네 서비스 파일뿐이다.
- 해당 테스트 Compose 프로젝트의 컨테이너·네트워크·볼륨을 정리했다. 마지막 Docker 목록에는 기존 시연·개발·모니터링 컨테이너 9개만 남았고, 전용 회귀 Redis 및 Testcontainers도 남지 않았다. 기존 앱은 재배포하지 않았다.

회차별 원본은 `results.json`, 교차 집계 및 시간 비교는 `verified-summary.json`, 인과 순서는 `timeline.ndjson`과 `gates/events-*.tsv`를 본다. PG SQLite 원장·요청 이력, 앱 로그, DB/Redis 스냅샷, JAR/소스 해시, 실행 코드 사본, `console.log`, `container-inspect.json`, `cleanup.log`를 보존했다. 동일 폴더의 `baseline/`, `regression/`, `verification-index.json`으로 기준 비교와 116건 회귀 명령·로그·XML도 찾을 수 있다.

## 변경 파일 목록

| 구분 | 파일 |
|---|---|
| 서비스 | `src/main/java/studying/blog/service/PaymentRetryRound.java` (신규), `PaymentSaga.java` |
| Worker | `src/main/java/studying/blog/scheduler/PaymentRecoveryWorker.java` |
| 기한 정밀도 | `src/main/java/studying/blog/domain/Payment.java` |
| 통합·관측 테스트 | `src/test/java/studying/blog/fault/PaymentRetryRoundIntegrationTest.java` (신규), `PaymentSagaFaultProbe.java` |
| 실프로세스 실행기 | `perf/payment_retry_fault.py` |
| 문서 | `docs/payment-retry-round-fix-results.md` (신규), `docs/payment-retry-fault-results.md` (과거 기록 안내만 추가), `docs/README.md`, `perf/README.md` |

## 남은 범위

보장은 같은 회차 실패의 중복 갱신 방지다. 매번 정상 Worker가 먼저 후보를 선택하는 공정성, PG 중복 조회/취소 HTTP 호출 자체의 제거, 큰 배치의 처리 지연까지 해결한 것은 아니다. 실제 Toss·COMMIT ACK 유실·Redis 전체 유실·고부하 성능/k6는 이번 검증에 포함하지 않는다. 스키마·외부 API·Redis Lua·대기열·풀·timeout·Worker 주기·backoff 초 단위 정책은 변경하지 않았다.

테스트 원본은 Git 제외 로컬 파일이며 원격 백업된 것은 아니다. 자동 commit/push는 하지 않는다.
