# 예매 성공 경로 계측 결과

> 이 문서는 작성·실행 당시의 계획 또는 검증 기록입니다. 현재 흐름은 [문서 안내](README.md), PG 구현 및 한계는 [결제 결과](payment-saga-results.md)를 확인하세요. 과거 결과는 현재 전체 기능의 검증을 뜻하지 않습니다.

## 실행과 표본

2026-11-04 KST. 최종 실행은 `timing-20261104-130549-2e776e`다. 동일 JAR의 계측 OFF 대조군과 버퍼 계측 ON을 각각 별도 MySQL/Redis에서 실행했다. 각 환경에서 20 VU×25회(500명) 워밍업 후 500 VU×1회 API 500석을 3회 실행했다. 각 요청은 서로 다른 사용자/요청 ID를 사용한다.

기존 k6 booking.js와 compose.yml의 리소스를 재사용했다. Docker 29.3.1, VM 10 CPU/8,217,059,328 bytes, 앱 2 CPU/1GiB/heap 512MiB, MySQL 8.4 2 CPU/1GiB, Redis 7 1 CPU/256MiB, k6 1.0.0 2 CPU/2GiB다. Hikari max 20/획득 timeout 3000ms, Tomcat max threads 100, OSIV false를 사용했다. 이전 최적화의 기존 src/main 파일 SHA-256이 모두 일치함을 확인했다. 추가된 애플리케이션 코드는 선택적 진단 클래스 하나다.

최종 계측 ON의 1,500건 모두 BOOKED, 각 요청의 INSERT/잠금/조회/Redis/COMMIT/획득/반환 필수 항목이 정확히 한 번씩 수집됐다. 요청 종료 시 미반환 커넥션 표식은 0이었다. 워밍업 포함 2,000개 진단 기록과 dropped=0을 확인했다.

## 계측 방식과 한계

- `booking.profiling.enabled=true`일 때만 진단 Bean을 등록한다. ThreadLocal로 API의 3인자 BookingService.book 요청과 create/execute 단계를 연결한다.
- 기존 P6Spy JdbcEventListener를 재사용해 SQL execute, Connection.commit, getConnection과 close를 관측한다. 추가 SELECT, 락, 네트워크 호출을 넣지 않았다.
- 트랜잭션 및 Repository/Redis 메서드의 벽시계 시간은 AOP로 수집한다. 트랜잭션/Repository 타이머에는 하위 JDBC 타이머가 포함되므로 함께 더하면 중복된다.
- JDBC SQL 시간은 드라이버 실행의 클라이언트 벽시계 시간이다. 서버 실행, 네트워크, OS 스케줄링이 포함된다. ResultSet 순회/엔티티 변환은 Repository 타이머에 포함된다.
- COMMIT은 P6Spy가 관측한 Connection.commit 호출 시간이다. DB CPU·디스크 fsync·네트워크·스케줄링 각각으로 분리하지 않았다.
- getConnection 시간은 Hikari 획득 대기와 P6Spy 래퍼/메타데이터 작업을 포함한다. 요청별 순수 Hikari wait는 미분리다. 기존 Hikari acquire timer의 전체 구간 count/sum 평균도 함께 확인했다.
- 커넥션 보유 시간은 P6Spy의 획득 완료 훅부터 close 완료 훅까지다. 획득 전 대기는 제외되며 반환 호출까지의 드라이버/래퍼 비용이 포함된다. Hikari 내부의 정확한 borrow 순간부터 계측 훅까지의 작은 구간은 구분하지 않았다.
- SELECT FOR UPDATE JDBC 시간에는 행 잠금 대기가 포함될 수 있다. 순수 InnoDB row-lock wait는 별도 계측하지 않았으며 0으로 추정하지 않는다.
- 기록은 요청별 메모리에 누적하고 최대 10,000건까지 버퍼링한다. JSON 직렬화/로그는 부하 측정 후 앱 정상 종료 시 수행한다. 강제 종료 시 진단 버퍼가 유실될 수 있다. 기존 DB·Redis 복구 데이터와 별개다.

## HTTP 성능과 계측 오버헤드

표는 각 500건 실행 값의 3회 중앙값이다.

| 조건 | API 평균 ms | API p95 ms | 응답 TPS | 기술 오류 |
|---|---:|---:|---:|---:|
| 계측 OFF | 1169.11 | 1973.09 | 235.74 | 0/1,500 |
| 버퍼 계측 ON | 1459.84 | 2506.56 | 186.99 | 0/1,500 |

ON/OFF 차이는 평균 +24.87%, p95 +27.04%다. 같은 머신에서 OFF→ON 순서로 실행했으므로 이 차이는 계측 부담과 호스트/JIT/실행 순서 변동을 함께 포함한다. 순수 계측 오버헤드나 보정 계수로 확정할 수 없다. 아래 단계 시간도 계측 ON에서 관측한 값이다. 미계측 운영 시간으로 환산하지 않는다.

직전 최적화 측정의 현재 버전 평균 중앙값은 1,515.92ms였다. 이번 OFF는 1,169.11ms로 같은 구현에서도 변동이 크다. 버퍼 ON 1,459.84ms와 과거 값을 비교해 성능 개선으로 해석하지 않는다.

첫 실험 `timing-20261104-130208-f6eecb`는 요청 중 JSON 로그를 출력했다. OFF 평균 중앙값 1,552.02ms, ON 2,768.95ms로 약 78.4% 차이가 관측됐다. 이 6회도 모두 정합성을 통과했으며 원본·단계 분포를 보존했다. 부담을 줄이기 위해 직렬화와 출력을 종료 시점으로 옮겼다. 최종 실험과 첫 실험을 합쳐 분포를 계산하지 않았다.

## 성공 요청 1,500건의 단계 분포

아래는 최종 ON 요청 1,500건을 합친 분포다. 단위는 ms다. 행별 p95를 합산할 수 없다. 모든 지표의 p50/p90/p95/p99/max와 회차별 분포는 stage-distributions-ms.json 및 stage-report.md에 보존했다.

| 단계 | 평균 | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| REQUESTED 트랜잭션 전체 | 315.333 | 288.131 | 706.078 | 1009.006 |
| 첫 커넥션 획득 호출 | 278.215 | 214.842 | 692.207 | 983.399 |
| REQUESTED INSERT JDBC | 3.159 | 0.558 | 6.560 | 70.149 |
| 첫 COMMIT JDBC | 22.120 | 9.088 | 78.299 | 84.567 |
| 첫 커넥션 보유 | 36.993 | 14.982 | 88.730 | 99.403 |
| 예매 트랜잭션 전체 | 159.195 | 89.161 | 579.874 | 823.417 |
| 두 번째 커넥션 획득 호출 | 93.809 | 0.030 | 498.827 | 722.194 |
| SELECT FOR UPDATE JDBC | 2.788 | 0.418 | 4.548 | 70.554 |
| 잠금 Repository 전체 | 4.481 | 0.973 | 7.728 | 73.451 |
| 기존 Booking 조회 JDBC | 3.250 | 0.378 | 4.159 | 70.934 |
| Concert 조회 JDBC | 2.977 | 0.360 | 3.734 | 71.848 |
| Redis reserve 왕복 | 10.977 | 2.033 | 74.397 | 87.327 |
| Booking INSERT JDBC | 2.366 | 0.495 | 3.490 | 69.161 |
| BOOKED UPDATE JDBC | 3.981 | 0.569 | 7.063 | 75.543 |
| 두 번째 COMMIT JDBC | 20.328 | 8.413 | 78.144 | 86.135 |
| 두 번째 커넥션 보유 | 65.308 | 80.075 | 118.884 | 192.399 |
| 두 획득 호출 합계 | 372.024 | 306.657 | 916.229 | 1281.862 |
| 두 커넥션 보유 합계 | 102.302 | 98.830 | 188.309 | 247.642 |
| 전체 SQL JDBC 합계 | 18.525 | 5.271 | 78.581 | 87.832 |
| 두 COMMIT 합계 | 42.448 | 20.430 | 93.098 | 158.417 |
| 보유 구간의 나머지 | 30.351 | 8.544 | 86.921 | 149.528 |
| 서비스 중 획득·보유 이외 | 3.039 | 0.347 | 7.615 | 75.220 |
| 서비스 전체 | 477.364 | 408.654 | 1018.575 | 1387.610 |

서비스 평균 477.36ms에서 획득 호출 합계가 372.02ms(77.9%), 보유 합계가 102.30ms(21.4%)다. 첫 REQUESTED 획득이 평균 278.22ms로 서비스 내부의 가장 큰 단일 구간이다.

보유 구간 안에서는 두 COMMIT 합계 42.45ms가 평균 보유 시간의 41.5%다. SQL 합계는 18.53ms, Redis는 10.98ms이며, 나머지 30.35ms에는 ORM/트랜잭션 준비·정리·래퍼/리스너 비용·스케줄링 등이 섞여 있다. 이 나머지는 타이머 차이로 계산한 잔여분이며 독립적으로 측정한 ORM 시간은 아니다.

3회 HTTP 평균을 합친 평균은 1602.69ms, 서비스 평균은 477.36ms다. 평균 차이 1125.33ms는 HTTP 요청 송수신, Tomcat 처리 시작 전 대기, 보안 필터, 컨트롤러/응답 직렬화, 서비스 계측 진입 전후 비용 등을 포함할 수 있다. 개별 HTTP 표본과 서버 요청 시간을 직접 매칭하지 않았으므로 이 구간의 p95와 각 원인별 시간은 미측정이다. Tomcat 순수 큐 대기로 단정하지 않는다.

## Hikari와 정합성

| 조건 | 회차 | active peak/max | pending peak | 획득 timeout | Redis 잔여석 | Booking | BOOKED 요청 |
|---|---:|---|---:|---:|---:|---:|---:|
| control | 1 | 20/20 | 75 | 0 | 0 | 500 | 500 |
| control | 2 | 20/20 | 76 | 0 | 0 | 500 | 500 |
| control | 3 | 20/20 | 70 | 0 | 0 | 500 | 500 |
| profiled | 1 | 20/20 | 81 | 0 | 0 | 500 | 500 |
| profiled | 2 | 20/20 | 80 | 0 | 0 | 500 | 500 |
| profiled | 3 | 20/20 | 81 | 0 | 0 | 500 | 500 |

Hikari active/pending은 nominal 100ms 간격 샘플이며 실제 scrape 시간이 추가된다. 관측되지 않은 순간 최댓값은 미측정이다. OFF의 회차별 샘플 수는 7/6/4, ON은 6/3/5다. 원본 시계열의 active 평균도 보존했다.

Hikari 자체 acquire timer count/sum의 모니터 전체 구간 평균은 OFF 회차별 165.685/134.912/112.943ms, ON 218.105/173.277/155.722ms였다. 각 구간의 획득 수는 OFF 1,033/1,030/1,027회, ON 1,038/1,030/1,033회로 백그라운드 조회도 포함한다. 이 평균을 요청별·트랜잭션별 Hikari wait 분포로 해석하지 않는다.

최종 6회 모두 HTTP BOOKED 500, Redis 잔여석 0, Booking 500, booking_attempt BOOKED 500이었다. 미완료, 초과 예매, 중복 사용자, 요청 연결/Redis 표식 불일치가 없었다. 타임아웃 카운터와 앱 timeout 로그는 모두 0이다. 첫 실험 6회도 같은 검증을 통과했다.

## 우선순위와 근거

1. **성공 트랜잭션의 커넥션 보유 비용을 먼저 검토한다.** 서비스 내부에서 획득 호출이 77.9%를 차지하고 active 20/pending 최대 81에 도달했다. 풀을 늘리는 변경을 하기 전에 커넥션을 점유하는 두 COMMIT과 ORM/트랜잭션 처리 비용을 좁히는 것이 우선이다. COMMIT은 보유 시간의 41.5%이며, 단일 INSERT/조회 평균보다 크다. DB fsync/네트워크/CPU 스케줄링 중 원인은 이번 자료로 구분하지 못했다. REQUESTED 선커밋을 제거하는 변경은 복구 보장을 다시 설계해야 하므로 단순 삭제 대상으로 제시하지 않는다.
2. **HTTP→서비스 진입·응답 구간을 추가 분해한다.** 평균 차이 1,125ms가 전체 지연의 가장 큰 미분해 구간이다. 요청 진입/보안 필터/서비스 호출/응답 완료 시점을 연결하고 Tomcat busy/queue와 CPU throttling을 확인해야 진입부 최적화 대상을 정할 수 있다. 이 구간을 전부 DB 대기로 중복 계산하지 않는다.
3. **Redis 대기를 포함한 트랜잭션 보유 구간을 검토한다.** Redis 왕복은 평균 10.98ms, p95 74.40ms이며 해당 시간에 요청 행 잠금과 커넥션을 유지한다. 잠금 밖으로 옮기는 변경은 Worker와 지연 요청의 안전 프로토콜을 검토해야 한다.
4. **요청 행 잠금 SQL과 개별 조회는 뒤에서 검토한다.** SELECT FOR UPDATE JDBC 평균 2.79ms/p95 4.55ms, 개별 INSERT/조회 평균 약 2~4ms다. 이번 500개 서로 다른 요청 ID 부하에서는 이 SQL의 전체 시간보다 획득 대기와 COMMIT이 더 크게 관측됐다. 순수 행 잠금 대기는 별도 미측정이다.

위 순위는 계측 ON의 병목 후보를 정리한 것이다. 큰 ON/OFF 차이가 남아 있어 미계측 운영 환경의 정확한 기여율이나 개선 효과로 일반화하지 않는다. 이번 작업은 계측과 분석으로 종료한다.

## 사용 방법과 보존 자료

```bash
python3 perf/timing.py
python3 perf/timing_report.py build/performance/timing-20261104-130549-2e776e
```

기본 앱에서는 계측 Bean이 비활성화된다. 테스트 앱은 BOOKING_PROFILING_ENABLED=true로 켠다. timing.py는 같은 빌드의 OFF/ON 대조군을 각각 기동하며 종료 시 버퍼를 덤프하고 자원을 정리한다.

- 최종 원본은 build/performance/timing-20261104-130549-2e776e/에 보존했다. runs/environment JSON, 원본 k6 JSON, pool.ndjson, verified JSON, 앱/JAR/정리 로그, request-timings.json, stage-samples.csv, stage-distributions-ms.json, stage-report.md, timing-checks.json 및 계측 소스를 포함한다.
- 첫 실험은 build/performance/timing-20261104-130208-f6eecb/에 그대로 보존했다.
- 계측 활성 API/실제 장애 테스트는 초기 19건과 최종 버퍼 코드 19건 모두 통과했다. 최종 XML은 build/timing-validation/final/, 로그는 /tmp/ticketflow-timing-buffered-validation.log다.
- 실행 로그는 /tmp/ticketflow-timing-k6.log 및 /tmp/ticketflow-timing-buffered-k6.log다. 두 runner 모두 종료 코드 0이다.
- 최종 진단 소스는 src/main/java/studying/blog/diagnostics/BookingTiming.java, 실행/분석 도구는 perf/timing.py와 perf/timing_report.py다. 기존 perf/README.md에 실행 안내를 추가했다.
