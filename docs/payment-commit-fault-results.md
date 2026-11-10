# 결제 완료 COMMIT 응답 유실 검증

2026-11-10. 실제 MySQL 커밋 성공 뒤 JDBC 호출자에게 예외를 전달하는 경계 모사다. **실프로세스 6회·282개 검사와 최종 선택 회귀 127건이 통과**했다. 실제 네트워크 COMMIT ACK 차단 검증은 아니다. 서비스 소스 134개 파일은 작업·실행 전후 동일하며 서비스 로직, 스키마, 잠금, 풀, timeout, Worker 주기, Redis Lua는 변경하지 않았다.

## 실제 검증과 트랜잭션 경계

격리된 앱 A/B/C, MySQL 8.4·Redis 7, SQLite 원장을 가진 HTTP PG를 사용했다. 정상 Booking/승인 API를 호출한다. 테스트 전용 JDBC DataSource/Connection/PreparedStatement 프록시가 지정한 Payment COMPLETED와 Booking CONFIRMED UPDATE의 테이블·상태·ID 바인딩을 같은 연결에서 확인한다. 해당 트랜잭션의 실제 commit()이 반환된 뒤 게이트에서 멈춘다.

앱과 별도 프로세스인 docker exec mysql 조회로 완료 상태가 커밋됐음을 확인하고 게이트를 해제한다. 한 번만 SQLRecoverableException(08006)을 던지고, 원래 트랜잭션 매니저의 commit()을 위임 호출하는 테스트 래퍼에서 호출자에게 전달된 예외를 기록한다. 여섯 번 모두 Spring RecoverableDataAccessException을 관측했다. 관리자 래퍼는 트랜잭션을 추가하거나 결과·예외를 바꾸지 않는다. PG·서비스 응답을 가짜 완료 값으로 대체하지 않는다.

DB 커밋 뒤 애플리케이션의 트랜잭션은 실패 경로를 거치지만, 서비스의 실패 기록은 완료 상태를 확인해 재시도를 만들지 않는다. API는 DB를 재조회해 HTTP 200/COMPLETED를 반환했다. 내부 커밋 예외가 발생했다는 것과 최종 HTTP 실패는 같은 의미가 아니다.

| 실제 앱 시나리오 | 성공 | 실패 | 현재 동작 |
|---|---:|---:|---|
| 승인 API의 완료 커밋 예외 | 3 | 0 | DB 완료 유지, 실패 기록 무변경, 중복 요청은 완료 응답 재사용 |
| 자동 Worker의 완료 커밋 예외 | 3 | 0 | B 자동 Worker의 예외 이후 완료 유지, A의 오래된 승인 응답도 결과 역전 없음 |

각 실행에서 Payment COMPLETED/NONE, Booking CONFIRMED, booking_attempt BOOKED, Redis HELD/잔여 2, 전체 3장 보존, 승인 효과 1/취소 효과 0, 보상 Outbox 0을 확인했다. B/C에 동일 주문을 각 3번 재요청하고 두 앱의 자동 Worker 스캔이 2회 이상 실행되는 동안 검사했다. 이미 완료된 결제는 후보에서 제외되는 것이 정상이다. 실제 PG 승인 효과 총 6회/취소 효과 0회, 완료 이후 티켓 반환 효과 0회다.

일반 bootJar에 Probe가 없는지 검사하고 복사한 장애 JAR에만 추가했다. 기존 시연 앱은 재배포하지 않았다.

## 통합·회귀 결과와 초기 실패

- 신규 커밋 통합 클래스는 기존 회차 테스트 8건을 상속하고 새 검증 3건을 추가해 11건이다. 실제 MySQL·Redis Testcontainers와 HTTP PG를 사용했다.
- 신규 검증은 커밋 게이트에서 별도 DB 조회→JDBC 예외→트랜잭션 예외 전달, 오래된 Worker·만료 처리·과거 FAILED Payment Outbox의 동시 처리, PENDING 커밋 및 다른 Payment에 대한 장애 미주입이다.
- 경합 통합 테스트는 래치/게이트와 명시적인 서비스 호출을 사용한다. 자동 만료·Outbox 스케줄러까지 실프로세스에서 동시에 실행했다고 주장하지 않는다. 과거 fixture Outbox는 완료된 Booking을 변경하지 않고 PUBLISHED가 되며, 신규 보상은 생성되지 않았다.
- 기존 116건과 새 클래스 11건을 합친 **127건 선택 회귀가 실패 0/오류 0/skip 0으로 통과**했다. 상속으로 반복 실행된 8건을 포함한 실행 건수이며 새로 만든 고유 시나리오 127개라는 뜻은 아니다. 전체 프로젝트 전수 테스트는 아니다.
- 기존 실제 DB UPDATE 거부 테스트도 통과해 커밋 전 롤백과 커밋 후 예외를 구분했다. 커밋 예외 이후 후속 DB 조회와 다른 테스트/요청이 정상적으로 이어졌다. 풀 크기·timeout은 변경하지 않았다.

초기 실패는 원본을 보존한다.

1. `20261110-152141-a92477`: 2회 모두 커밋·JDBC 예외는 관측했지만 Spring afterCommit(error) 콜백 가정이 잘못돼 실패했다. Spring은 예외 유형에 따라 롤백 경로로 갈 수 있고, ThreadLocal 표식이 후속 정상 커밋에 남아 NONE을 관측했다. 트랜잭션 매니저 commit()의 실제 예외 전파를 관측하도록 테스트 장치를 수정했다. 성공 기대를 완화한 것은 아니다.
2. `test-results/payment-commit-regression/`: 최초 전체 회귀 126건 중 새 게이트 검사 2건 실패, 기존 116건과 상속 8건 통과. Probe의 정적 디렉터리 초기화에 의존하던 테스트 격리를 수정하고, 같은 5초 게이트 도달 제한을 유지해 전체를 재실행했다. 재실행에는 미주입 대조군도 추가했다.
3. `test-results/payment-commit-regression-retest/`: 최종 127건 통과. 전용 임의 포트 Redis를 사용한 뒤 제거했다. spy 로그는 보존하고 회귀 직전 내용으로 복원했다. 초기 실패를 삭제하거나 최종 성공으로 덮어쓰지 않았다.

## 파일과 재현

변경 파일은 테스트 지원 코드와 안내뿐이다.

- `src/test/java/studying/blog/fault/PaymentCommitFaultProbe.java`: JDBC 대상 매칭·커밋 이후 게이트·트랜잭션 매니저 예외 관측.
- `src/test/java/studying/blog/fault/PaymentCommitFaultIntegrationTest.java`: 실제 DB/Redis 커밋·경합·대조군 검증.
- `perf/payment_commit_fault.py`: 기존 격리 환경·PG·Suite를 재사용하는 실프로세스 실행기.
- 이 문서와 docs/README.md, perf/README.md: 실행·검증 안내.

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_commit_fault.py --repeats 3
```

정식 원본은 `test-results/payment-commit-fault/20261110-152421-2431b0/`이다. verified-summary.json, results.json, timeline.ndjson, commit-events.json 및 gates/commit-*.tsv에 판정과 순서를 남겼다. PG 원장·앱 로그·DB/Redis 스냅샷, 소스/일반JAR/장애JAR 해시와 실제 JAR, 실행기 사본도 보존한다. regression/에 최종 명령·로그·XML·집계를 복사했다. 초기 로그와 spy 원본은 위 별도 폴더에도 남는다.

실프로세스 JAR은 테스트 경로 /fault를 정적으로 선택하던 Probe를 포함한다. 이후 같은 JVM의 회귀 격리를 위해 경로를 실행 시점에 읽도록만 변경했다. 현재 소스 사본에는 current라는 이름과 probe-version-note.txt를 붙여 실행 당시 바이너리와 구분했다. 실제 검증 바이너리는 fault-app.jar가 기준이다. 서비스 코드는 두 실행에서 동일하다.

## 미검증·남은 범위

실제 MySQL 네트워크 ACK 차단, PENDING/최초 호출 선점/취소 완료 커밋의 응답 유실, 실제 Toss, Redis 전체 유실, SIGKILL 및 k6는 이번에 검증하지 않았다. 물리 MySQL 연결 ID와 SQL 바인딩 전문을 별도 추적 로그로 보존한 것은 아니며, 런타임 대상 매칭과 독립 mysql 프로세스의 완료 조회를 증거로 사용했다. 이것을 네트워크 장애 실증이나 전체 장애 보장으로 확대하지 않는다.

실행 후 신규 Compose 프로젝트와 전용 Redis/Testcontainers를 정리했고 기존 시연·개발·모니터링 컨테이너 9개만 남음을 확인했다. 원본 폴더는 Git 제외 로컬 기록이며 자동 commit/push나 원격 백업은 하지 않았다.
