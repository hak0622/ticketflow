# PG Circuit Breaker 검증 결과

2026-11-12. 현재 Resilience4j 2.2.0과 Spring AOP 프록시를 실제 HTTP PG에 연결해 검증했다. 운영 코드·리소스·timeout·Worker 주기·backoff·DB 풀·Redis Lua는 변경하지 않았다. 기존 미커밋 문서 변경도 보존했다.

## 최종 판정

| 실행 | 성공 | 실패 | 오류 | skip |
|---|---:|---:|---:|---:|
| 회로·결제 안전성·만료/과거 Outbox 보호 | 14 | 0 | 0 | 0 |
| 별도 스케줄러 ON 자동 복구 | 1 | 0 | 0 | 0 |
| 기존 선택 회귀 | 164 | 0 | 0 | 0 |

총 179건은 최종 선택 실행 건수이며 전체 저장소 테스트 수나 독립 장애 조합 수가 아니다. 상속 실행도 포함한다. 앞선 회로 10건·통합 13건·자동 복구 1건 예비 실행은 중복 합산하지 않는다.

원본 실행 ID는 `20261112-102327`, 위치는 `test-results/payment-circuit-breaker/20261112-102327/`다. 최종 회로 증거는 `final-circuit/`, 격리 수정 후 자동·회귀는 `isolation-recheck/`가 기준이다. `verified-summary.json`, 명령 JSON, XML·로그, 회로 이벤트·HTTP 이력·PG 효과 원장·DB/Redis 스냅샷·테스트 소스·해시를 보존했다. 원본은 Git 제외로 clone에서 제공되지 않는다.

## 환경과 확인한 동작

실제 MySQL 8.4·Redis 7 Testcontainers와 loopback HTTP 모의 PG를 사용했다. RestClient.mutate()로 목적지 인터셉터만 추가하고 기존 연결·읽기 3초를 유지했다. Spring 주입 프록시를 호출했으며 실제 상태·이벤트·HTTP 미도착으로 판정했다. 운영 회로는 최근 10건·실패율 50%·느린 호출 2초/80%·Open 대기 30초·Half-open 3건이다. 기본 최소 호출 수와 COUNT_BASED 창 제한을 구분해 유효 최소 표본 10건의 동작을 확인했다.

- 실패 9건까지 비율 미판정·CLOSED, 10건에서 OPEN. 성공 6건·실패 4건 이후 다음 실패가 성공을 밀어내 50%에서 OPEN.
- OPEN의 승인·조회·취소는 CallNotPermittedException과 거부 카운터로 확인했고 PG HTTP 도착 수는 증가하지 않았다.
- 실제 30초 대기 후에도 무호출 상태는 OPEN이며, 후속 호출에서 HALF_OPEN. 시험 3건 성공은 CLOSED, 실패 2/성공 1은 OPEN, 실패 1/성공 2는 CLOSED.
- Half-open 진행 중 3건을 게이트로 유지하고 4번째만 HTTP 도착 없이 차단했다.
- 정상 응답을 지연시켜 느린 호출 70%에서는 CLOSED, 80%에서는 실패율 0%여도 OPEN임을 확인했다. 2초 판정 기준과 3초 HTTP 중단 기준은 다르다.
- PG 승인 효과 후 응답 지연으로 실제 읽기 타임아웃을 발생시켰다. 미확정 결제와 티켓을 유지하고 재조회로 예매를 확정했다.
- 승인 자체가 차단된 신규 결제는 효과 0회이며 회로 대기 후 조회가 404여도 자동 승인·실패 확정·티켓 반환을 하지 않았다.
- 승인 후 조회 차단, 취소 의도 후 차단, 전액 취소 응답 유실 후 차단에서 미확정 상태를 보호하고 이후 실제 결과에 따라 확정 또는 Outbox 반환을 완료했다.
- 이미 31분 지난 예매와 과거 FAILED 결제의 Outbox가 있어도 현재 미확정 결제를 검사해 반환을 보류했다. 과거 Outbox는 PENDING/retry 0, 재고·HELD는 불변이었다.

단일 결제 복구는 시험 호출 3건이 채워지지 않아 HALF_OPEN에 남을 수 있다. 결제 종료와 회로 CLOSED는 별개이며 모든 안전성 사례가 CLOSED로 끝나야 한다고 기대하지 않았다.

## 자동 Worker 검증과 정합성

별도 클래스·별도 Gradle 실행에서 스케줄러를 활성화했다. 서로 다른 사용자 3명이 정상 예매 서비스 경로로 총 5장 중 3장을 확보하고, 승인 효과 후 응답 유실로 미확정 결제를 만들었다. 초기 준비 중 테스트 게이트는 Worker 진입에서만 대기하며 DB 잠금을 점유하지 않았다.

회로를 실제 HTTP 실패로 열고 PG 정상화·게이트 해제 후에는 Worker 수동 호출, DB 재시도 시각 변경, 회로 상태 강제 변경, 직접 PG 호출로 시험 표본 채우기를 하지 않았다. 기존 주기·backoff와 scheduling 스레드의 조회만으로 3건 확정과 CLOSED 복귀를 확인했다. 이후 추가 스캔 2회 이상과 동일 주문 재요청에서도 승인 효과 3회·취소 0회·반환 Outbox 0건·잔여 2장·BOOKED/CONFIRMED 3건·Redis HELD 3건을 유지했다. 제한은 해제부터 180초였으며 완화하지 않았다.

준비·안전성 검사는 BookingService/PaymentService 등 실제 서비스 경로를 명시적으로 호출한다. 인증·브라우저 HTTP 진입점의 재검증은 아니다. 자동 복구만 실제 스케줄링 검증이며 앱 3대 실프로세스 종료 검증과 구분한다. 취소 의도와 과거 실패 결제·Outbox는 테스트 fixture다.

## 발견한 테스트 격리 결함과 수정

첫 회귀 실행에서 자동 Worker용 ObservationConfig가 다른 컨텍스트에도 등록돼 기존 테스트의 명시적 Worker 호출이 ScanGate에서 대기했다. 테스트 JVM 스레드 덤프로 원인을 확인하고 해당 JVM만 중단했다. 이 실행은 일부 XML에 성공 건수가 있어도 회귀 통과로 집계하지 않는다.

테스트 설정을 전용 속성 circuit.worker.observation=true에서만 등록하도록 조건화하고, 실패 시에도 게이트를 해제하는 정리 절차를 추가했다. 운영 코드는 수정하지 않았다. 원본은 `regression/run.log`, `thread-diagnostic.txt`, `diagnosis.json`에 보존했고 수정 후 자동 복구 1건과 기존 선택 회귀 164건을 재실행해 통과했다. 기대값·시간 제한을 완화하지 않았다.

## 실행 방법과 변경 파일

Docker Desktop·Java 17·의존성 캐시가 준비된 전용 환경에서 회로·안전성은 함께 실행할 수 있다.

```bash
./gradlew test --offline --tests studying.blog.fault.PaymentCircuitBreakerIntegrationTest --tests studying.blog.fault.PaymentCircuitBreakerSafetyIntegrationTest --tests studying.blog.fault.PaymentCircuitBreakerGuardIntegrationTest
```

스케줄러 ON은 다른 클래스와 섞지 않고 별도 실행한다.

```bash
./gradlew test --offline --tests studying.blog.fault.PaymentCircuitBreakerWorkerIntegrationTest
```

각 실행에 CB_RESULTS 환경변수로 별도 결과 폴더를 전달할 수 있다. 지정하지 않으면 로컬 test-results/payment-circuit-breaker/current를 사용한다. 테스트 실행은 전용 MySQL·Redis를 초기화하며 일반 개발·시연 Redis에 연결하면 안 된다. H2 회귀 Redis도 전용 임의 포트 인스턴스를 생성하고 해당 인스턴스만 제거했다. 각 회귀의 실제 선택 목록은 command.json에 있다.

추가 테스트는 CircuitPgSupport, PaymentCircuitBreakerIntegrationTest, PaymentCircuitBreakerSafetyIntegrationTest, PaymentCircuitBreakerGuardIntegrationTest, PaymentCircuitBreakerWorkerIntegrationTest이며 모두 src/test에 있다. README의 해당 항목과 이 결과 문서만 이번 검증 설명으로 갱신했다. 일반 JAR·운영 소스에는 테스트 게이트를 추가하지 않았다.

## 미검증과 남은 한계

- 연결 timeout의 실제 네트워크 경계, 전체 API의 3초 완료, 스레드 고갈 방지 효과·타 API 지연 개선·Bulkhead 비교는 미검증.
- 실제 Toss 장애, 여러 앱의 전역 회로, 이번 신규 테스트의 SIGKILL, k6·부하·정확한 최대 복구 시간은 범위 밖.
- 회로는 앱별이며 승인·조회·취소가 공유하므로 OPEN 동안 복구 호출도 차단될 수 있다. DB/Redis 장기 장애와 재시도 backoff의 지연은 남는다.
- PG 승인 자체가 전달되지 않은 주문은 조회만으로 승인되지 않는다. 404를 최종 실패로 판단하지 않아 미확정으로 남을 수 있으며 이번에는 그 정책을 바꾸지 않았다.
- 테스트 PG 원장은 메모리로 유지하고 JSON으로 보존했다. 실제 결제사의 내구성·멱등 처리 전체를 입증한 결과가 아니다.

[현재 흐름](flow.md) · [테스트 환경](testing.md) · [과거 결제 Saga 검증](payment-saga-results.md)

## 커밋 전 검토 후 보완

최초 179건 결과는 위 실행 당시 기록으로 보존한다. 이후 테스트 지원 코드만 다음과 같이 보완했다.

- 결과 파일 저장이나 요청 검증에 실패해도 HTTP 서버·executor 종료, 원래 RestClient 복원과 회로 초기화를 finally에서 실행한다.
- 승인·취소 HTTP 본문과 멱등키를 해당 Payment에 저장된 주문·PG 식별자·금액·승인/취소 멱등키·취소 사유와 비교한다.
- 별도 읽기 타임아웃 테스트에서 ResourceAccessException의 원인이 SocketTimeoutException인지 검증하고 실제 호출 대기 시간을 기록한다. 2.9초 이상 대기를 확인하며, 전체 API의 정확한 3초 완료나 최대 지연 보장은 주장하지 않는다.
- 문서 안내에 신규 결과를 연결하고, bootRun의 .env 로딩과 직접 java -jar 실행 시 별도 주입을 구분한다.

보완 후 재실행 결과는 아래에 별도로 기록하며 최초 실행 건수와 합산하지 않는다.

보완 후 실행 ID: `review-20261112-134811`.

| 재실행 | 성공 | 실패·오류·skip |
|---|---:|---:|
| 회로·결제 안전성·만료/과거 Outbox 보호 (타임아웃 1건 추가) | 15 | 0 |
| 별도 자동 Worker 복구 | 1 | 0 |

이번에는 관련 16건을 재실행했다. 이전 선택 회귀 164건은 재실행하지 않았으며 위 최초 실행 기록으로 구분한다. 읽기 타임아웃 직접 관측은 3,002ms·SocketTimeoutException이었다. 단일 호출 측정값이며 최대 지연 보장이 아니다. 새 요청 본문·멱등키 검증도 함께 통과했다.

`test-results/payment-circuit-breaker/review-20261112-134811/`에 명령·로그·XML·요청/응답 시간·DB/Redis 결과·실행 테스트 소스·해시를 보존했다. 운영 소스 해시는 실행 전후 동일했다. 문서 링크 검사와 git diff --check를 통과했으며 k6·실제 Toss·재배포·커밋·푸시는 실행하지 않았다.
