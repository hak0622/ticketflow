# 취소 완료·Outbox 커밋 응답 유실 검증 결과

2026-11-10. **실프로세스 6회·534개 검사 및 최종 선택 회귀 141건 모두 통과**했다. 실제 MySQL 커밋 이후 JDBC 예외를 전달하는 경계 모사이며 실제 네트워크 COMMIT ACK 차단 검증은 아니다. 서비스 소스 134개 파일의 작업 전후 SHA-256이 동일하다.

## 범위와 실제 동작

격리된 앱 A/B/C·MySQL 8.4·Redis 7·SQLite 원장을 가진 HTTP PG를 사용했다. 정상 Booking/승인 API로 티켓을 확보하고 A의 실제 승인 응답을 적용 전 보류했다. DB가 PENDING/PENDING_PAYMENT인 동안 fixture로 CANCEL_REQUIRED와 취소 사유를 저장하고 B의 자동 Worker가 PG 전액 취소를 진행했다. 이는 공개 취소 API나 CONFIRMED Booking의 일반 환불 API 검증이 아니다.

테스트 Probe는 지정 Payment의 CANCELED UPDATE와 payment.compensation/PENDING Outbox INSERT의 payload ID를 같은 연결에서 추적한다. 실제 commit() 반환 후 게이트에서 멈춘 상태에서 독립 mysql 프로세스로 Payment CANCELED와 Outbox 1건을 확인했다. 그 뒤 SQLRecoverableException(08006)을 한 번 전달하고, 원래 트랜잭션 매니저를 위임 호출하는 래퍼에서 Spring RecoverableDataAccessException 전파를 확인했다. SQL 순서를 가정하거나 generated keys를 먼저 소비하지 않는다.

| 시나리오 | 성공 | 실패 | 실제 순서 |
|---|---:|---:|---|
| 커밋 예외 먼저 | 3 | 0 | A/C 스케줄링 보류 → B 취소 커밋 → 독립 DB 확인 → JDBC/Spring 예외 → 자동 Outbox 처리 |
| 반환 먼저 | 3 | 0 | B 커밋 반환 게이트 유지 → C 자동 스케줄링 허용 → Outbox PUBLISHED·반환 완료 → B에 커밋 예외 전달 |

두 번째 순서에서 B는 스케줄링 스레드가 커밋 게이트에 있고 A는 스케줄링 진입이 보류된 상태였다. C가 실제 Outbox 처리를 실행했고 반환을 마친 뒤 JDBC 예외가 전달됐다. 긴 대기는 Outbox나 Booking 트랜잭션의 잠금 안에 두지 않았다. 복구 제한은 자동 반환 허용 시점부터 180초로 고정했다. 수동 Worker/스케줄러 호출이나 DB 결과 상태 강제 기록으로 복구하지 않았다.

## 상태와 교차 검사

- PG 취소 직전: PG DONE, Payment PENDING, Outbox 없음, Redis HELD/2장.
- 취소 커밋 직후: Payment CANCELED/NONE, Outbox PENDING 1건, Booking PENDING_PAYMENT, attempt BOOKED, Redis HELD/2장. 이때 앱은 아직 커밋 결과를 받지 않았다.
- 최종: Payment CANCELED/NONE, Booking CANCELLED, attempt RELEASED, Outbox PUBLISHED, Redis RELEASED/releaseOutcome=RETURNED·잔여 3장, 활성 선점 키 없음.
- Booking 취소와 반환의 별도 트랜잭션 사이에는 RELEASE_PENDING/미반환 상태를 허용한다. 재고 보존식은 안정된 시작/종료 상태에 적용했다.
- 실제 승인 효과 6회, 전액 취소 효과 6회, Redis RETURNED 효과 6회로 요청당 각각 1회다. HTTP 호출 수와 효과 수를 구분했다. PG 취소 본문의 사유·90000원 금액과 저장된 멱등키를 대조했다.
- 커밋 이후 예외 전후 Payment 상태·version·lastError·retryCount·nextRetryAt이 같았다. A의 오래된 DONE과 B/C 중복 요청 36건도 CANCELED를 유지했다.
- B/C에서 각각 추가 Payment Worker 스캔 2회 이상을 확인하고, 이후 실제 Outbox 후보 조회 2회를 추가 관측한 뒤에도 추가 취소·Outbox·반환이 없었다.
- 런타임 SQL과 바인딩·JDBC 연결 식별자를 저장하고, 6회 모두 커밋 연결에 Outbox INSERT와 CANCELED UPDATE가 있었음을 교차 확인했다. payload의 paymentId/bookingId/concertId/userId도 DB 스냅샷과 일치했다. 연결 식별자는 JDBC 객체 식별자이며 MySQL 물리 CONNECTION_ID는 아니다.

## 통합·회귀 및 초기 실패

최종 선택 회귀는 기존 127건에 신규 취소 클래스 14건을 더한 **141건, 실패/오류/skip 0**이다. 신규 클래스는 기존 11건을 상속하고 신규 3건을 추가하므로 141개의 새로운 고유 시나리오라는 뜻은 아니다. 실제 MySQL·Redis Testcontainers와 HTTP PG를 사용했고 H2 테스트는 전용 임의 포트 Redis를 사용했다.

신규 검증은 취소 커밋 예외 뒤 반환, 반환·오래된 실제 PG DONE·만료·보상의 경합 뒤 예외, PENDING/승인 완료/다른 Payment 취소에 대한 미주입 대조군이다. 통합 경합은 명시적 서비스 호출과 게이트로 제어했으며 실프로세스 자동 스케줄러 검증과 구분한다. 기존 DB 취소 UPDATE 거부→롤백→PG 재조회 복구 테스트도 회귀에 포함했다.

초기 실패 기록은 삭제하지 않았다.

1. `test-results/payment-cancel-integration-initial/`: 신규 경합 1건이 테스트 리플렉션의 잘못된 오버로드 선택으로 실패했다. 실제 서비스 동작 이전의 ClassCastException이며 Object 오버로드로 호출부만 수정했다.
2. `test-results/payment-cancel-regression/`: 최초 전체 141건 중 기존 승인 커밋 게이트 검사 2건 실패. 상속된 테스트가 공유 디렉터리의 이전 이벤트를 읽어 PENDING을 관측하는 문제였다. 신규 취소 클래스 14건은 통과했다. 테스트 메서드마다 새 게이트 디렉터리를 사용하도록 수정했다.
3. `test-results/payment-cancel-regression-final/`: 동일 제한과 기대값으로 전체 141건 재실행 통과. timeout을 늘리거나 실패 기대값을 완화하지 않았다. 전용 Redis는 제거하고 spy 로그는 보존한 뒤 회귀 직전 내용으로 복원했다.

## 변경 파일과 실행

- `src/test/java/studying/blog/fault/PaymentCommitFaultProbe.java`: 취소 모드, Outbox INSERT·SQL 바인딩·연결 관측, Statement/트랜잭션 관측 상태 정리. 기존 승인 완료 모드 유지.
- `src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java`: 취소 호출 직전 게이트와 실제 Outbox 후보 조회 이벤트 추가.
- `src/test/java/studying/blog/fault/PaymentCommitFaultIntegrationTest.java`: 메서드별 게이트 디렉터리 격리.
- `src/test/java/studying/blog/fault/PaymentCancelCommitFaultIntegrationTest.java`: 신규 취소 검증.
- `perf/payment_cancel_commit_fault.py`: 두 순서 각 3회 실행·증거 수집.
- 이 결과 문서, docs/README.md, perf/README.md: 안내.

```bash
PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 perf/payment_cancel_commit_fault.py --repeats 3
```

정식 원본은 `test-results/payment-cancel-commit-fault/20261110-155747-ed0959/`이다. verified-summary.json, results.json, timeline.ndjson, commit-events.json, gates/commit-*.tsv 및 events-*.tsv, PG SQLite 원장·이력, DB/Redis 스냅샷·앱 로그를 보존했다. 실제 실행 Probe 소스는 빌드 시 복사했으며 서비스 소스/JAR 해시와 일반·장애 JAR도 보존한다. 실행기 재사용으로 environment.json의 legacy baseline 항목은 이전 재시도 실험과 비교한 값이며 이번 서비스 변경 목록이 아니다. 이번 기준은 `test-results/payment-cancel-commit-baseline/source.json`이다.

regression/에는 최종 명령·로그·XML·집계를 복사했고 verification-index.json이 초기 실패·기준 기록을 연결한다. 테스트 격리 수정은 JUnit 클래스만 변경했으며 실프로세스 JAR에 포함되는 두 Probe 소스는 빌드 이후 변경하지 않았다.

## 남은 범위

실제 네트워크 ACK 차단, 공개 사용자 환불 API, Outbox PUBLISHED 커밋 응답 유실, Redis 전체 유실, 실제 Toss, k6는 미검증이다. 현재 Hibernate의 실제 SQL 경로를 검증했으며 모든 JDBC 드라이버·미지원 SQL·배치 변형을 전수 테스트한 것은 아니다. 잘못 해석한 경로는 주입 성공으로 추정하지 않는다.

서비스·스키마·잠금·풀·timeout·주기·Redis Lua는 변경하지 않았다. 일반 bootJar의 Probe 미포함을 확인했고 테스트 환경만 정리했다. 마지막 Docker 목록에는 기존 시연·개발·모니터링 컨테이너 9개만 남았다. 로컬 시연 앱은 재배포하지 않았다. 결과 원본은 Git 제외 로컬 파일이며 자동 commit/push나 원격 백업은 하지 않았다.
