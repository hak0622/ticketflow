# 로컬 앱 3개 자동 장애 복구 검증

> 후속 안내: 아래는 실행 당시 기록입니다. [최신 선점 유지 정책](booking-hold-recovery.md)과 [후속 검증](booking-hold-recovery-results.md)를 함께 확인하세요. 당시 실패·미검증을 현재 전체 범위로 해석하지 않습니다.

> 이 문서는 작성·실행 당시의 계획 또는 검증 기록입니다. 현재 흐름은 [문서 안내](README.md), PG 구현 및 한계는 [결제 결과](payment-saga-results.md)를 확인하세요. 과거 결과는 현재 전체 기능의 검증을 뜻하지 않습니다.

2026-11-07 실행. **6개 시나리오 통과 / 실패 0개, 명시적 검증 80개 통과.**

## 환경과 방법

- 실행 ID: `20261107-130054-dfee3b`
- macOS M4 / RAM 16GB의 Docker Desktop에서 Spring Boot JVM 3개, 공유 MySQL 8.4 / Redis 7 실행.
- 앱마다 1 CPU / 메모리 768MiB / heap 384MiB. 기존 로컬 3개 앱 Compose 설정 재사용.
- 독립된 `tfperf-fault-three-*` 프로젝트와 테스트 데이터 사용. 기존 개발 컨테이너는 유지.
- 실제 JWT와 예매 HTTP API, 실제 Lua와 DB 트랜잭션 사용. 예매 요청은 대상 앱의 개별 포트로 전송해 인스턴스를 지정했다.
- 테스트용 Aspect가 실제 Redis 처리 직후, DB 커밋 직후 등의 경계에서만 실행을 중단한다. 서비스/DB/Redis 결과를 Mock으로 대체하지 않았다.
- `docker compose kill -s SIGKILL`로 총 4회 강제 종료. 모두 종료 코드 137, `OOMKilled=false` 확인.
- Worker 직접 호출 0회. 실제 `scheduling-1` 스레드와 인스턴스 이름을 기록했다. REQUESTED 유예 30초 / Worker fixedDelay 5초 유지. DB의 요청 생성 시각·복구 예정 시각을 앞당기지 않았다.
- 일반 bootJar에 테스트 Probe가 포함되지 않음을 검사하고, 별도 `fault-app.jar`에 해당 테스트 클래스만 추가했다. `src/main` 전체 파일 해시가 실행 전후 동일함도 확인했다.

## 결과

각 공연 초기 수량은 10장이다. Booking 건수는 해당 공연의 실제 DB 행 수이다.

| 시나리오 | 결과 | Redis 잔여 | Booking | DB 요청 상태 | Redis 요청 기록 |
|---|---|---:|---:|---|---|
| 차감 직후 앱 1 종료, 다른 앱 자동 복구 | PASS | 10 | 0 | RELEASED | RELEASED / RETURNED |
| DB 커밋 후 HTTP 응답 전 앱 1 종료 | PASS | 9 | 1 | BOOKED | HELD |
| 동일 요청 ID로 3개 앱에 동시 9회 요청 | PASS | 9 | 1 | BOOKED | HELD |
| 원래 요청과 자동 Worker의 실제 행 잠금 경합 | PASS | 10 | 0 | RELEASED | RELEASED / RETURNED |
| Redis 반환 후 DB 커밋 전 Worker 종료 | PASS | 10 | 0 | RELEASED | RELEASED / RETURNED |
| Worker 종료 처리 후 원래 요청 지연 실행 | PASS | 10 | 0 | RELEASED | RELEASED / NOT_HELD |

### 1. 차감 직후 앱 종료

Redis 수량 9 / HELD, DB REQUESTED / Booking 없음 상태를 직접 확인한 뒤 앱 1을 종료했다.
앱 2·3이 같은 요청의 자동 복구에 참여했고, 실제 반환 Lua는 앱 2가 실행했다.
Redis 수량 10 / Booking 없음 / DB RELEASED로 종료됐다.

실제 차감 완료부터 첫 Worker 처리 종료까지 **33.140초**, SIGKILL 종료 확인부터 **31.041초**였다.
이 값은 이번 저부하 단일 실행 관측값이며 복구 시간 SLA나 p95가 아니다.
소비한 입장권의 절대 만료 시각이 선점 기록의 원래 시각과 같고 TTL이 연장되지 않음을 확인했다.
동일 요청을 3회 재시도해도 HTTP 409 / BOOKING_FAILED이며 DB·Redis 상태가 그대로였다.

### 2. 커밋 후 응답 유실

`TransactionTemplate.execute`가 반환한 뒤 HTTP 응답 전에 멈춰서,
별도 DB 조회로 BOOKED와 실제 연결된 Booking 1건을 확인한 다음 앱 1을 종료했다.
실제 HTTP 연결이 응답 없이 끊겼고, 요청 시작 후 36초 이상을 기다렸다.
앱 2·3으로 동일 ID를 재시도하면 HTTP 200 / BOOKED가 재사용됐다.
수량은 계속 9장이었고 반환 Lua 호출은 없었다.

이 시나리오는 **커밋 성공 후 애플리케이션 응답 유실**이다.
MySQL COMMIT ACK 자체의 유실을 새로 주입한 테스트는 아니다.

### 3. 3개 앱 중복 요청

같은 사용자·공연·요청 ID로 앱마다 3회씩 동시에 전송했다.
9개 응답 모두 기존 성공 결과를 공유했고, 선점 Lua 서비스 호출은 1회,
Booking 1건 / booking_attempt 1건 / Redis 잔여 9장을 확인했다.

### 4. 원래 요청과 Worker 경합

실제 Redis 선점 후 원래 DB 트랜잭션을 멈추고 30초 유예를 그대로 기다렸다.
MySQL `performance_schema.data_lock_waits`와 `data_locks`에서
`booking_attempt` 행에 대한 실제 대기 관계 1건을 확인한 뒤 원래 요청을 재개했다.

원래 트랜잭션은 기존 10초 timeout을 이미 넘겼으므로 정상 성공으로 커밋되지 않았고,
최종 수량 10 / Booking 없음 / RELEASED로 안전하게 종료됐다.
이번 실행에서 경합한 Worker는 앱 1의 별도 스케줄러 스레드였다.
**유예 시간보다 짧게 처리되는 정상 성공 트랜잭션과 다른 앱 Worker가 경합하는 분기는
이번 자동 스케줄러 시나리오로 별도 재현하지 않았다.**

### 5. 반환하던 Worker도 종료

먼저 선점한 앱 1을 종료했다. 앱 3의 자동 Worker가 실제 반환 Lua를 완료한 뒤,
DB는 아직 RELEASE_PENDING인 경계에서 멈췄다.
Redis 수량 10 / RELEASED·RETURNED, DB RELEASE_PENDING 상태를 확인한 다음 앱 3도 종료했다.

남은 앱 2의 자동 Worker가 공통 반환 Lua를 다시 실행하고 DB RELEASED까지 커밋했다.
재실행 후에도 수량은 10장이었고, 동일 요청 3회 재시도에도 변하지 않았다.
따라서 이 시나리오에서는 두 앱이 종료된 상태에서 남은 한 앱이 복구를 마쳤다.

### 6. 복구 종료 뒤 원래 요청 실행

REQUESTED 선커밋 이후 실제 예매 트랜잭션을 시작하기 전에 원래 요청을 멈췄다.
자동 Worker가 DB RELEASED / Redis RELEASED·NOT_HELD 표식을 기록할 때까지 기다렸다.
그 뒤 원래 요청을 재개하면 HTTP 409 / BOOKING_FAILED를 재사용하며 선점 Lua를 호출하지 않았다.
수량·Booking·요청 상태·입장권·활성 요청 키 모두 복구 직후와 같았다.

## 변경 파일

- `src/test/java/studying/blog/fault/LocalThreeFaultProbe.java`: 테스트 전용 경계 중단 및 인스턴스·스레드 이벤트 기록.
- `perf/compose.local-three-fault.yml`: 테스트 프로파일 / 인스턴스 이름 / 경계 제어 폴더만 추가하는 Overlay.
- `perf/local_three_fault.py`: 빌드, 실제 HTTP·SIGKILL, 자동 복구 대기, 상태 검증, 로그 보존, 정리.
- `perf/README.md`: 실행 방법 및 범위.
- 이 결과 문서.

운영 예매·복구 로직, Redis Lua, OSIV, Hikari 설정, 요청 잠금 구조는 변경하지 않았다.

## 재실행과 기록

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/local_three_fault.py
```

원본: `build/fault-local-three/20261107-130054-dfee3b/`

- `results.json`, `summary.json`: 테스트별 판정과 최종 상태, Worker 정보, 종료 기록.
- `timeline.ndjson`: 각 검증, HTTP 결과, DB·Redis 상태 스냅샷 및 실제 잠금 대기 관측.
- `gates/events-*.tsv`: 인스턴스별 실제 실행 경계와 자동 Worker 호출 스레드.
- `app.log`, `app-2.log`, `app-3.log`, `container-inspect.json`, `console.log`: 원본 실행 기록.
- `environment.json`, `production-source-verification.json`, `fault-app.jar`, 테스트 소스·Compose 사본: 재현 정보.
- `cleanup.log`: 해당 프로젝트의 컨테이너·네트워크·MySQL 볼륨 제거. 종료 후 Docker 조회에서도 테스트 컨테이너가 남지 않았음을 확인.

## 검증 범위와 남은 항목

- 정상 복구 유예를 기다리는 소규모 장애 테스트이다. 500명 부하 중 장애, 1만 명 대기열, GCP 네트워크·로드밸런서 자동 우회와는 구분한다.
- Redis 자체의 전체 유실·failover 쓰기 유실, MySQL 장기 장애·failover, 외부 PG 승인 결과 불명확, 앱 3개 전체 동시 종료는 이번 실행에서 검증하지 않았다.
- 기존 Testcontainers 2단계 장애·매진·만료·결제 보상 전체 묶음은 이번 실행에서 재실행하지 않았다. 이 문서의 6개 실제 자동 복구 테스트 결과와 과거 회귀 테스트 결과를 합산하지 않는다.
- 이번 테스트에서 기능상 실패는 관측되지 않았다. 이것만으로 모든 분산 장애 조합의 복구나 운영 처리 용량이 보장되는 것은 아니다.
