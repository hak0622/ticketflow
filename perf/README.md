# TicketFlow k6 성능·정합성 검증

## 로컬 앱 3개 검증 시작

```bash
python3 perf/local_three.py --users 100
# 100명 검증 후 부하를 단계적으로 늘린다.
python3 perf/local_three.py --users 300
python3 perf/local_three.py --users 500
python3 perf/local_three.py --users 500 --capacity 300
python3 perf/local_three.py --users 100 --mode flow
```

`compose.local-three.yml`은 앱 3개(각 1 CPU/768MiB, heap 384MiB), MySQL(2 CPU/1GiB),
Redis(0.5 CPU/256MiB), Nginx(0.25 CPU/64MiB), k6(2 CPU/2GiB)를 사용한다.
모니터링은 각 앱의 관리 포트 직접 수집으로 수행하며 Prometheus/Grafana 컨테이너를 추가하지 않는다.
Hikari는 기존 최대 20개/timeout 3000ms를 유지하므로 앱 3개의 최대 합계는 60개다.
macOS와 Docker 전체 메모리 압박을 확인하고 기존 개발 스택과 함께 실행할 때의 자원 중복도 고려한다.

첫 앱이 스키마 및 fixture 생성을 마친 뒤 나머지 앱을 `ddl-auto=validate`로 시작한다.
공통 MySQL/Redis는 실행별 전용 볼륨과 네트워크를 사용하고 관리/API 포트는 loopback 임의 포트다.
Nginx는 세 앱으로 분산하고 실패한 POST를 다른 앱으로 자동 재전송하지 않는다.
worker_connections는 4096이며 error 로그를 stderr에 남긴다.
초기 1024 설정에서는 500명 burst 시 연결 부족 경고와 20건 reset이 재현되어 테스트 gateway 설정을 수정했다.
종료 시 실행한 `tfperf-local-three-...` 프로젝트만 정리한다. 기존 개발 컨테이너는 조작하지 않는다.

기존 JWT 발급·입장권 fixture·booking.js·DB/Redis 정합성 검사를 재사용한다.
100/300/500명 모두 서로 다른 사용자가 한 번씩 예매한다. fixture JWT는 500명분 생성되며 필요한 앞 N명만 사용한다.
flow는 매진 후 대기 종료 정책을 이 테스트가 다루지 않으므로 사용자 수와 좌석 수가 같은 조건만 지원한다.

이 실행은 워밍업 없는 1회 smoke 검증이다. 기존 3회 성능 결과와 직접 비교하거나 성능 개선 근거로 사용하지 않는다.
`build/performance/local-three-<ID>/result.json`에서 최종 수량/Booking/요청 상태와
세 upstream의 예매 POST 분산을 확인한다. 요청 ID별 Redis 표식과 Booking 연결도 검증한다.
Hikari active/pending 요약은 세 앱 모두 k6 사용자 시작~종료 구간으로 정렬한다.
timeout 누적 카운터 차이는 각 monitor의 전체 수집 구간이다.
resources.ndjson에 Docker CPU/메모리 스트림, host-memory.ndjson에 2초 간격 macOS vm_stat/스왑을 저장한다.
resources-summary.json의 peak는 기동부터 검증까지 전체 구간이며 load_window_samples로 부하 구간 표본 수를 표시한다.
Docker 자원 표본은 짧은 burst를 놓칠 수 있다. 표본이 없으면 부하 순간 최댓값을 추정하지 않는다.
원본 시계열을 보존하며 인스턴스별 peak를 합산해 순간 총 사용량으로 해석하지 않는다.
소스/JAR hash, 실행 컨테이너 이미지 정보, Compose·스크립트 스냅샷,
k6 JSON, 앱별 로그와 Hikari 시계열을 보존한다.
실패 회차도 보존하고 정합성·분산·메트릭 수집 실패 시 종료 코드 1을 반환한다.
다중 인스턴스 강제 종료/자동 Worker 복구와 1만 명 유입·polling은 이 smoke 검증에 포함하지 않는다.

운영 DB/Redis 또는 기존 docker-compose 스택을 사용하지 않는다. `run.py`가 실행마다 `tfperf-...` 프로젝트와 전용 MySQL 볼륨/Redis를 만들고, 종료 시 해당 프로젝트만 `down -v`로 정리한다. 애플리케이션과 관리 포트는 임의의 loopback 포트에만 공개한다. 기존 `.env`와 CSV/JWT를 읽지 않는다.

## 실행

2026-11-12 문서 정적 점검: 아래 baseline `092d272`는 현재 로컬 Git에서 commit으로 해석된다. 다른 clone에서도 `git rev-parse --verify '092d272^{commit}'`로 확인한다. 이력 정리나 shallow clone으로 참조가 없으면 임의의 다른 버전으로 대체하지 않는다.

`--historical-results` 예시는 로컬 보존 JAR·해시가 있을 때만 가능하다. `build/`·`test-results/`는 Git 제외라 clone으로 내려받을 수 없다. 현재 동작·재현 범위는 [테스트 안내](../docs/testing.md), 최신 생성 복구는 문서 아래 전용 실행기를 따른다. 문서 정리에서 명령 실행·부하·실제 PG 호출은 하지 않았다.

필요: Docker Desktop(권장 8GB 이상, 이번 실행 VM 10 CPU), Java 17, Python 3.9+, Gradle wrapper의 의존성 캐시. 로컬 k6/MySQL/Redis 설치는 필요 없다. k6는 `grafana/k6:1.0.0` 컨테이너에서 실행한다.

```bash
python3 perf/run.py --baseline-ref 092d272 --repeats 3
```

세 버전을 같은 실행에서 비교하려면 이전 실행에 보존된 baseline/current JAR를 재사용한다. JAR SHA-256을 검증하고 baseline/recovery/current를 각각 독립 DB·Redis에서 재실행한다.

```bash
python3 perf/run.py --historical-results build/performance/20261103-221647-78d9fd --repeats 3
```

현재 버전만 검증하려면:

```bash
python3 perf/run.py --current-only --repeats 3
```

Gradle 빌드는 캐시를 사용하는 `--offline`이다. 의존성 캐시가 없다면 먼저 프로젝트 및 비교 기준의 Gradle 의존성을 내려받아야 한다. Docker 이미지가 없다면 Docker가 다운로드한다.

runner는 부하·정합성·텔레메트리 검증이 하나라도 실패하면 종료 코드 1을 반환하며, 실패한 회차도 결과에 보존한다. 원본을 Markdown으로 정리하려면:

```bash
python3 perf/report.py build/performance/<실행-ID> > build/performance/<실행-ID>/report.md
```

결과 경로는 `build/performance/<실행 ID>/`다. 실행 중 해당 폴더에 원본 k6 JSON, 커넥션 시계열, 정합성 검사, 각 버전의 JAR와 SHA-256이 저장된다. JWT CSV는 테스트 전용이며 결과 폴더는 Git의 build/ 제외 규칙에 포함된다.

## 부하 조건

| 구분 | 좌석 | 사용자/예매 POST | 방식 |
|---|---:|---:|---|
| API 단독 | 500 | 500 | 입장권 사전 발급 후 500 VU가 1회씩 예매 |
| 초과 수요 | 300 | 500 | 동일 조건. BOOKED 300건 + 정상 매진 200건 기대 |
| 대기열 포함 | 500 | 500 | 실제 대기열 등록 → 순번 폴링 → 스케줄러 입장권 발급 → 예매 |

각 조건을 기본 3회 반복한다. 측정 전 각 버전에서 20 VU × 25회로 500명의 서로 다른 사용자를 워밍업한다. 본 측정은 **500 VU × 1회**다. 사용자 선택은 `scenario.iterationInTest`로 매핑하므로 각 실행에서 500개의 서로 다른 JWT가 한 번씩 사용된다. 예매 요청은 실패/202여도 자동으로 재시도하지 않는다.

사용자 500명은 격리 DB의 fixture로 생성한다. 비밀번호 로그인은 사용하지 않는다. 기존 `/api/test-support/csv`에서 새 JWT를 발급받고, JWT의 사용자 ID가 fixture ID와 일치하는지도 검사한다. API 단독은 기존 `/api/test-support/concerts/{id}/setup?mode=BOOKING`으로 입장권을 발급한다. 대기열 포함은 `mode=QUEUE`를 사용하며 입장권을 미리 발급하지 않는다.

대기열은 실제 서버의 1초/100명 스케줄러와 입장권 600초 TTL을 사용한다. `nextPollMs`와 React와 같은 ±250ms jitter를 따른다. Queue→Booking까지 측정하며, PG 결제는 포함하지 않는다.

300석 대기열 전체 흐름은 이번 기본 매트릭스에 포함하지 않는다. 매진되면 현재 스케줄러가 입장권 발급을 중단하므로, 그 조건은 대기 중인 사용자의 매진 종료 처리 검증이 별도로 필요하다. 500명 모두 예매 API를 한 번 호출하는 초과 수요 검증은 API 단독 300석 조건에서 수행한다.

## 동일한 환경

| 구성 | 고정값 |
|---|---|
| App | 1대, Temurin Java 17, 2 CPU / 1GiB, heap 512MiB |
| MySQL | 8.4, 2 CPU / 1GiB, 전용 볼륨 |
| Redis | 7-alpine, 1 CPU / 256MiB, maxmemory 192MiB / noeviction |
| k6 | 1.0.0, 2 CPU / 2GiB |
| Hikari | 최대 20, connection-timeout 3000ms |
| Tomcat | 기존 docker 프로파일: worker 최대 100, accept-count 500 |
| 로그 | P6Spy OFF, 서비스 INFO/WARN 등 기본 로그 단계 유지 |
| 관리 지표 | 별도 관리 HTTP 포트(8081), nominal 100ms 간격 수집 |

이전 버전은 Git ref를 별도 임시 디렉터리에 archive해서 빌드한다. 현재 작업 트리를 checkout/reset하지 않는다. 현재 JAR는 작업 트리의 미커밋/미추적 구현 파일도 포함한다. baseline ref, source SHA-256 및 JAR SHA-256을 기록한다. 각 버전은 새 DB/Redis에서 동일한 워밍업·실행 순서로 측정한다.

기본 baseline 092d272는 **요청별 복구 추가 전 Redis Lua 구현**이다. 과거의 비관적 락 구현은 아니다. README의 과거 3,778ms/1,445ms 또는 이력서 수치는 당시 부하 생성기/환경/로그 설정 등이 동일하다고 확인되지 않아 이번 수치와 직접 비교하지 않는다.

## 지표 정의

- `booking_duration`: 예매 POST의 평균/p95 HTTP 시간. 대기열 폴링 HTTP 시간과 섞지 않는다.
- `flow_duration`: 사용자별 대기열 진입 시작부터 예매 응답까지. API 단독이면 예매 한 번의 클라이언트 경과 시간이다.
- 응답 TPS: 완료 사용자 수 ÷ 첫 사용자 시작~마지막 사용자 완료 시간. 500명 한 번의 **burst 처리량**이며 지속 가능한 최대 TPS가 아니다.
- 성공 TPS: BOOKED 수 ÷ 같은 구간. 300석 조건에서는 응답 TPS와 구분한다.
- 정상 매진: 409이며 실제 본문에 정원 마감 메시지가 있는 경우만 인정한다. 정상 매진 40%와 기술 오류율을 별도 표시한다.
- 예상 밖 오류율: 네트워크/인증/5xx/다른 4xx, 중복 성공(ALREADY_BOOKED), 202 등을 모두 오류로 분류한다. BOOKED와 기대한 매진만 정상이다.
- DB 커넥션: Hikari active 평균/관측 peak, pending 관측 peak, max를 기록한다. 시계열은 전용 관리 포트에서 수집해 예매 요청의 HTTP 큐 뒤에 밀리지 않게 한다. 100ms는 대기 간격이며 실제 scrape 시간만큼 간격이 늘어난다. 샘플링으로 관측하지 못한 순간 peak를 추정하지 않는다.

## 부하 후 정합성

각 실행마다 고유 공연을 사용한다. 모든 k6 요청이 끝난 뒤, 현재 구현에서는 500개 요청 기록이 모두 존재하고 REQUESTED/RELEASE_PENDING이 없어질 때까지 최대 120초 대기한다. 5초 간격 두 번의 안정된 조회를 확인한다.

검사 항목:

1. 유효 Booking이 좌석 수를 초과하지 않음.
2. Booking 사용자 수와 Booking 행 수가 같음.
3. 잔여석이 음수가 아니며 `Redis 잔여석 + 유효 Booking = 총 좌석`.
4. HTTP BOOKED 건수와 실제 Booking 건수 일치.
5. 현재 버전은 500석이면 BOOKED 500, 300석이면 BOOKED 300 / REJECTED 200이며 거절 결과는 SOLD_OUT이다. historical-results의 recovery 버전은 기존 BOOKED 300 / RELEASED 200을 검증한다.
6. 각 booking_attempt의 Booking ID·사용자·공연 연결 유효.
7. Redis 요청별 소유자·HELD/RELEASED 표식과 활성 사용자 선점 기록이 DB 상태와 일치. 현재 매진은 RELEASED/result=SOLD_OUT이고 선점 이력과 활성 사용자 기록이 없어야 한다.

기준 버전에는 booking_attempt가 없으므로 해당 검사는 N/A이며 Redis–Booking 수량을 검증한다. 본 테스트는 정상 부하 검증이다. 오류를 일부러 주입해 복구 지연을 측정하는 테스트는 2단계 장애 테스트와 구분한다.

## 결과 파일

- `environment.json`: baseline commit, 리소스, 소스/JAR hash 및 실행 환경
- `runs.json`: 측정 회차별 성능과 정합성
- `comparison.json`: 버전/조건별 3회 중앙값 및 min/max
- `<variant>/<label>.json`: 원본 k6 summary
- `<variant>/<label>-verified.json`: 해당 회차의 후처리 검증
- `<variant>/<label>-pool.ndjson`: Hikari 원본 시계열
- `<variant>/app.log`, `cleanup.log`: 예외와 격리 리소스 정리 확인

3회 중앙값은 전체 1,500건을 합친 p95가 아니라 **각 500건 실행 p95의 중앙값**이다. 같은 개발 머신에서 버전을 순서대로 실행하므로 JIT, Docker/호스트 스케줄링, jitter에 따른 변동이 있다. 단일 환경 측정으로 통계적 우위나 운영 용량을 단정하지 않는다.

k6 executor와 데이터 매핑 참고: [per-vu-iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/per-vu-iterations/), [execution](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/).

실제 측정 결과 및 실패 회차 분석: [관련 검증 결과](../docs/booking-performance-optimized-results.md).

커넥션 timeout은 pool 원본 시계열의 첫·마지막 누적 카운터 차이를 `observed_acquire_timeout_delta`로 보존한다. API burst 앞뒤의 모니터 구간을 포함한다. 앱 종료 후 실제 timeout 로그와 함께 확인한다. 수집 실패나 샘플 부족은 0으로 추정하지 않는다.


## 성공 경로 단계별 계측

```bash
python3 perf/timing.py
python3 perf/timing_report.py build/performance/timing-<실행 ID>
```

같은 JAR의 계측 OFF/ON을 별도 환경에서 각각 동일 워밍업과 500명/500석 API 3회로 실행한다.
BOOKING_PROFILING_ENABLED=true일 때만 JDBC/AOP 요청 계측을 활성화한다.
요청당 수집한 시간은 메모리에 최대 10,000건을 보관하고 앱 정상 종료 후 로그로 덤프한다.
원본 요청별 시간, 단계별 분포, 수집 완전성 검증은 결과 폴더에 저장된다.
중첩된 SQL/Repository/트랜잭션 타이머는 합산하지 않는다.
[측정값과 한계](../docs/booking-success-path-timing-results.md)를 참고한다.

## 로컬 앱 3개 자동 장애 복구

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/local_three_fault.py
```

Docker와 Java 17이 필요하다. 기존 개발 컨테이너를 유지하고, 독립된 Compose 프로젝트와
MySQL/Redis 볼륨을 생성한 뒤 테스트 종료 시 해당 프로젝트만 정리한다.
각 앱은 기존 로컬 3개 앱 설정인 1 CPU / 메모리 768MiB / Java heap 384MiB를 사용한다.

실제 예매 API/JWT, 실제 Redis Lua 및 MySQL 트랜잭션을 사용한다. 차감 후 종료,
DB 커밋 후 응답 전 종료, 3개 앱 중복 요청, 원래 요청과 Worker의 행 잠금 경합,
Redis 반환 후 DB 커밋 전 Worker 종료, Worker 종료 처리 뒤 원래 요청 지연 실행을 검증한다.
SIGKILL 종료 코드 137과 OOM 여부를 확인하며 Worker를 직접 호출하지 않는다.
REQUESTED 유예 30초와 자동 Worker fixedDelay 5초도 변경하지 않는다.

`src/test`의 `LocalThreeFaultProbe` 클래스만 별도 `fault-app.jar`에 추가한다.
이 클래스는 `local-three-fault` 프로파일에서만 활성화되며 공유 테스트 폴더 파일로
실제 연산 전후 실행을 잠시 멈춘다. 일반 `bootJar`에는 이 클래스가 들어가지 않음을 검사한다.
운영 API와 예매·복구 코드는 수정하지 않는다. 원래 요청/Worker 경합 테스트에서는
실제 30초 유예를 기다리므로 원래 트랜잭션의 기존 10초 timeout을 넘긴 실패 경로를 검증한다.

`build/fault-local-three/<실행 ID>/`에 테스트별 성공/실패(`results.json`),
모든 검증·상태 스냅샷과 HTTP 결과(`timeline.ndjson`), 인스턴스별 Worker 이벤트
(`gates/events-*.tsv`), 앱 로그, 소스/JAR 해시, 테스트용 JAR 및 정리 로그를 보존한다.
장애 주입으로 인한 HTTP 연결 종료와 실패 응답은 예상 결과이며 성능 측정값으로 사용하지 않는다.

## 300명 / 500명 부하 중 앱 종료와 자동 복구

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/load_fault.py
```

두 조건을 각각 독립된 앱 3개·MySQL·Redis 환경에서 실행한다. 운영 코드는 유지하고
앞서 사용한 테스트 전용 Probe를 별도 JAR에만 포함한다. 각 사용자는 기존 JWT 및 입장권
발급 방식을 사용하며, 대기열을 제외한 예매 API를 요청한다.

- 수량은 사용자 수와 동일하다. 초기 70%는 burst, 나머지 30%는 20~40초에 분산한다.
- 지정한 사용자 한 명이 실제 Lua 차감을 마친 경계에서 앱 1을 SIGKILL한다.
- 앱 2의 다른 실제 예매 트랜잭션도 해당 순간 열려 있게 하고, 앱 1 종료 이후 정상 커밋했는지 필수 검증한다.
- 초기 요청은 세 앱에 직접 분배한다. 실패·처리 중 응답은 같은 UUID로 살아 있는 앱에 재시도한다.
  클라이언트가 생존 앱을 명시적으로 선택하므로 로드밸런서의 자동 장애 감지를 검증하는 테스트는 아니다.
- 지정한 장애 요청은 첫 실패 후 40초간 재시도를 보류해 실제 자동 Worker가 먼저 복구하도록 한다.
- 최종 Booking N-1건 / RELEASED 요청 1건 / 잔여 1장, 전체 요청·사용자 매핑,
  Booking 연결 및 Redis 선점·반환·입장권 표식을 검사한다.
- 동일 장애 요청을 두 생존 앱에 재시도해도 종료 결과와 수량이 유지되는지 검사한다.
- Worker 처리 종료 전후 5초에 다른 예매 성공이 있었는지도 기록한다.
  이것은 복구 시간대의 트래픽 존재 확인이며 DB 작업의 정확한 동시 실행 증명과는 구분한다.

`build/load-fault/<실행 ID>/`에 JAR, 소스 해시, 실행 스크립트·설정 사본과 전체 결과를 보존한다.
하위 `300/`, `500/`에는 k6 요약·전체 응답/재시도, Worker 이벤트, Hikari 시계열,
컨테이너 자원·호스트 메모리 기록, DB·Redis 검증 원본과 정리 로그가 남는다.
일시적인 연결 오류율과 최종 실패율을 구분한다. 종료된 앱의 Hikari 카운터는 마지막 수집
시점 이후 확인할 수 없으므로 종료 순간까지 타임아웃이 없었다고 단정하지 않는다.
조건별 워밍업 없는 1회 장애 시나리오이며 기존 정상 burst 성능 수치와 직접 비교하지 않는다.
실행이 끝나면 실패를 포함한 원본 폴더를 `test-results/load-fault/<실행 ID>/`에 자동 복사한다.
이 로컬 폴더는 Git에서 제외하며 Gradle clean의 영향을 받지 않는다. 실행 요약은 docs에도 남긴다.
[2026-11-08 실제 결과와 초기 실행의 한계](../docs/booking-load-fault-results.md)를 참고한다.
# 로컬 화면 시연

`python3 perf/local_demo.py start`는 기존 개발 데이터와 분리된 앱 1개/MySQL/Redis를 실행합니다.
먼저 `./gradlew bootJar --offline`으로 JAR를 만들고, frontend에서 `npm run dev -- --host 127.0.0.1 --port 5173 --strictPort`를 실행합니다.
API는 localhost:8080, 화면은 localhost:5173입니다. 이미 생성된 환경은 `restart`, 중지는 `stop`을 사용합니다.
테스트 지원 API와 Mock 결제를 명시적으로 허용하는 로컬 전용 구성입니다.
검증 결과와 제한은 `docs/payment-saga-results.md`에 기록했습니다.


## 결제 Saga 실제 프로세스 장애 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_saga_fault.py
```

기본값은 승인 SIGKILL, 취소 SIGKILL, 취소 확인 중 오래된 DONE 도착,
취소·반환 후 오래된 DONE 도착의 네 순서를 각 3회(총 12회) 실행합니다.
`--repeats 1 --cases race_pending,race_terminal` 등으로 장치 점검을 분리할 수 있습니다.
단축 실행을 전체 반복 검증으로 보고하지 않습니다.

최신 bootJar를 복사하고 `src/test`의 PaymentSagaFaultProbe만 추가한 별도 fault-app.jar를
사용합니다. 일반 bootJar에 Probe가 없는지 검사합니다. 운영 소스·잠금·timeout·Hikari·
스케줄러 주기를 바꾸지 않습니다. 기존 로컬 3앱 리소스와 MySQL/Redis를 재사용하는
새 Compose 프로젝트를 만들며 Python HTTP PG는 SQLite에 효과를 먼저 커밋합니다.
실제 PG 요청은 테스트 Probe가 로컬 PG로만 전달합니다.

Worker를 수동 호출하지 않습니다. Probe의 후보 조회/응답 반환/취소 진입 게이트로
자동 스케줄러가 도달한 위치를 확인합니다. 앱 A 종료 후에는 기존 주기/backoff 그대로
B가 복구하도록 기다립니다. 회차 사이 JVM 재시작은 Circuit Breaker 상태를 분리하기
위한 준비 과정이며, 장애 후 A 재시작으로 복구를 대신하지 않습니다.

취소 시나리오는 테스트 DB의 초기 Payment 취소 의도를 준비합니다. 만료 경합에는
31분 전 Booking과 과거 실패 Payment/Outbox도 준비합니다. 과거 Outbox는 B/C가
같은 결제를 선택한 뒤 넣어 단일 스케줄링 스레드를 준비 게이트로 막는 간섭을 피합니다.
이는 테스트 fixture이며 공개 API가 취소 의도를 생성했다는 검증이 아닙니다.

실제 MySQL Booking 행 대기와 binlog의 결제/예약 상태 갱신을 수집합니다.
binlog 구간은 취소 의도 커밋 이후이며, 해당 결제 COMPLETED/예약 CONFIRMED 전이가
없었는지 확인합니다. 시각이 비슷한 로그만으로 동시 실행을 판정하지 않습니다.

`test-results/payment-saga-fault/<실행 ID>/`에 실행 스크립트/Probe 사본, 소스/JAR 해시,
PG SQLite/요청 이력, 스케줄러·게이트 이벤트, DB/Redis 스냅샷, 잠금 대기와 binlog,
회차별 성공·실패 및 초기 장치 문제를 보존합니다. 이 폴더는 Git 제외 로컬 원본입니다.
기존 시연 데이터와 컨테이너는 변경하지 않으며 종료 시 이 테스트 프로젝트만 정리합니다.

선행 도구: Docker Desktop, Java 17, Python 3, `mysqlbinlog`가 필요합니다.
이번 환경의 호스트 디코더는 Homebrew mysqlbinlog 8.0.42이며 테스트 MySQL 8.4의
해당 ROW binlog를 판독했습니다. 다른 버전의 모든 이벤트 호환성을 뜻하지 않습니다.
디코더 부재/판독 오류는 상태 역전 검증을 완료한 것으로 처리하지 않습니다.

## 결제 Worker 공유 재시도 시각 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_retry_fault.py --repeats 3
```

`payment_saga_fault.py`의 격리 환경·HTTP PG·Probe를 재사용합니다. 현재 실행기는 같은
결제를 선택한 Worker들의 실패, 정상 Worker의 후보 조회 지연, 완료 뒤 늦은 HTTP 실패,
Worker 순서를 제어하지 않은 비대칭 조회와 3회차 연속 실패를 각각 3회(총 15회) 검증합니다.
실행 중 서비스 코드나 DB retry 기한은 변경하지 않습니다. 실패한 HTTP 응답의 게이트는 실제 HTTP 호출이 끝난
뒤이며, 후보 조회 결과는 테스트 Probe에서 기록합니다. 수동 Worker 호출은 없습니다.

결과는 `test-results/payment-retry-fault/<실행 ID>/`에 보존합니다. 정상 JAR의 Probe
미포함과 서비스 소스 해시를 검사하고 해당 테스트 프로젝트만 종료합니다. 이 테스트는
mysqlbinlog를 사용하지 않습니다. Docker Desktop·Java 17·Python 3 및 기존 빌드 의존성이
필요합니다. 테스트 통과는 의도한 지연 재현의 성공도 포함하며, 문제를 수정했다는 뜻이
아닙니다. [관련 검증 결과](../docs/payment-retry-round-fix-results.md) 원본에는 당시의 실행기와
기대값이 보존되어 있습니다. 현재 실행기의 회차별 중복 갱신 방지 기준과 수정 전후 비교는
[수정 결과](../docs/payment-retry-round-fix-results.md)를 참고합니다.

## 결제 완료 COMMIT 예외 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_commit_fault.py --repeats 3
```

실제 MySQL 커밋 직후 JDBC 예외를 주입하는 테스트다. 실제 네트워크 ACK 차단은 아니다.
API와 자동 Worker 경로를 각 3회 검증하며 서비스 코드는 바꾸지 않는다.
결과·초기 실패·검증 한계는 [결과 문서](../docs/payment-commit-fault-results.md)를 참고한다.

## 취소·Outbox COMMIT 예외 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_cancel_commit_fault.py --repeats 3
```

실제 취소 커밋 뒤 JDBC 예외를 먼저 전달하는 순서와 자동 Outbox 반환을 먼저 끝내는
순서를 각 3회 실행한다. 실제 네트워크 ACK 차단은 아니다.
[결과·초기 실패·검증 한계](../docs/payment-cancel-commit-fault-results.md)를 참고한다.


## 실제 Toss 사용자 보조 취소 검증 기록

`perf/toss_live_cancel.py`는 위의 로컬 HTTP PG 테스트와 달리 실제 Toss **테스트 API**를 호출한다. 승인·카카오페이 인증은 사용자가 브라우저에서 진행하며 이후 취소는 Worker가 수행한다. 결과는 [마지막 두 항목 검증](../docs/toss-live-cancel-final-results.md)을 참고한다.

이 실행기는 이번 시연 환경을 재사용하는 검증용이며 새 clone에서 바로 시작하는 일반 초기화 도구가 아니다. 다음 조건을 먼저 준비해야 한다.

- 전용 `tfperf-toss-visible-*` Compose 프로젝트, 실제 MySQL·Redis, 동작 중인 앱과 프론트.
- Git에서 제외된 `test-results/toss-visible/latest.json`에 해당 프로젝트·폴더·API·화면 주소. 폴더의 Compose overlay도 필요하다.
- 같은 환경의 Payment 1이 COMPLETED인 기존 기준 예매. 실행기는 이 건과 기존 결제들의 보존을 검사한다.
- 로컬 `.env`의 테스트 Toss 시크릿 키와 같은 세트의 프론트 테스트 클라이언트 키. 실제 값은 커밋하지 않는다.
- 실행 앱 JAR와 소스를 일치시킨 뒤 `./gradlew testClasses bootJar --offline`으로 테스트 Probe를 빌드.

조건이 충족된 전용 시연 환경에서만 다음을 실행한다.

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/toss_live_cancel.py
```

실행기는 새 공연을 생성하고 테스트 전용 JAR로 전환한다. 사용자는 출력된 공연 주소에서 1원 카카오페이 테스트 결제를 진행한다. 취소 의도는 fixture로 준비하며 공개 사용자 환불 API를 검증하는 것은 아니다. 최종 상태를 DB에 직접 쓰거나 Worker를 수동 호출하지 않는다. 원래 승인 처리 완료·추가 스케줄러 실행·중복 요청을 확인한 후 일반 JAR로 복원한다.

이번 작업의 최초 환경 준비 메타데이터·실행 원본은 로컬 결과 폴더에 보존되어 있고 Git에는 포함하지 않는다. 다른 환경의 DB를 이 실행기와 임의로 연결하지 않는다. 실행 이력은 재현 범위와 한계를 포함하며, 모든 PG 장애나 성능을 검증한 결과로 해석하지 않는다.

## 예매 선점 유지·자동 생성 복구 검증

현재 동작은 [선점 유지 설계](../docs/booking-hold-recovery.md)를 따른다. 예전 `local_three_fault.py`는 실패 시 반환 정책의 역사적 기대값을 포함하므로, 새 정책은 다음 전용 실행기로 검증한다.

```bash
python3 perf/booking_hold_fault.py
./gradlew test --offline --tests studying.blog.fault.BookingHoldRecoveryIntegrationTest
# 스케줄러를 켜는 실제 5분 테스트는 다른 클래스와 섞지 않고 별도 실행한다.
./gradlew test --offline --tests studying.blog.fault.BookingHoldLeaseClockIntegrationTest
```

실프로세스 실행기는 기존 test-only Probe를 재사용하고 새 Compose 프로젝트에서 앱 3대·MySQL·Redis를 실행한다. SIGKILL 후 자동 Worker 복구, 커밋 후 응답 유실, 동시 중복 요청, 미선점 요청 종료 뒤 늦은 실행을 검사한다. 기한·Worker를 수동 조작하지 않으며 종료 후 해당 프로젝트만 정리한다. 원본은 `test-results/booking-hold-recovery/process/<실행 ID>/`에 저장한다. 5분 테스트는 DB INSERT 거부를 유지한 채 기본 선점 기한을 실제로 기다리므로 5분 이상 걸린다. 전체 k6나 실제 PG 호출은 포함하지 않는다.
