# Toss 간편결제 회귀 수정 및 실제 1원 결제 복구

2026-11-10. 실행 기록: `test-results/toss-easypay-fix/20261110-165054/`.

## 원인과 변경

실제 Toss 테스트 결제창에서 카카오페이로 1원 승인 후 PG는 `DONE`, `method=간편결제`, `totalAmount=balanceAmount=1`을 반환했다. 기존 PaymentSaga는 `CARD` 또는 `카드`만 허용해 Payment를 PENDING으로 남겼다. 과거 승인 구현은 응답의 결제수단을 그대로 저장했으므로 기존 사용 범위를 좁힌 회귀다. 기존 로컬 PG 테스트의 CARD 응답만으로 이 차이를 검증하지 못했다.

- `PaymentSaga.supportedMethod`: 실제 관측한 `간편결제`를 허용 목록에 추가. 알 수 없는 수단·가상계좌는 추가하지 않는다.
- `PaymentSagaIntegrationTest`: 기본 CARD 응답은 유지하고 결제수단·식별자·금액을 테스트별로 제어하도록 HTTP fixture를 보완.
- `PaymentEasyPayIntegrationTest`: 기존 장애 테스트 9건을 간편결제 응답으로 반복하고 새 6건을 추가.
- 문서 안내와 현재 지원 범위를 갱신. 요청 잠금, 트랜잭션 경계, Worker 주기, DB 풀·timeout, 스키마, Redis Lua, Outbox는 변경하지 않았다.

초기 화면 실행 당시의 134개 운영 소스 해시와 비교해 이번 변경은 `src/main/java/studying/blog/service/PaymentSaga.java` 하나다. `changed-main-files.json` 참조. 기존 사용자 변경은 유지했다.

## 회귀 결과

**156건 통과, 실패 0, 오류 0, 생략 0.** 기존 141건과 새 클래스 15건을 실행했다. 새 클래스의 15건은 상속된 9건과 새 검증 6건이며 15개의 신규 독립 장애를 의미하지 않는다.

새 검증은 정상 승인·동일 주문 재요청, paymentKey/주문/금액 불일치 3건, 가상계좌 차단, 취소 의도 및 취소 완료 이후 오래된 DONE 방어다. 상속 테스트는 승인 응답 유실, 승인 후 DB 실패, 취소 응답 유실, 취소 DB 실패, 만료·동시 요청 경합 등을 간편결제로 다시 실행했다.

통합 테스트는 실제 MySQL 8.4·Redis 7 Testcontainers와 로컬 HTTP PG를 사용한다. H2 기반 회귀의 Redis도 별도 인스턴스를 사용하고 종료했다. 명령은 `command.txt`, 실행·XML·집계는 `run.log`, `TEST-*.xml`, `summary.json`, 재현 실행기는 `runner.py`에 보존했다. `clean`과 k6는 실행하지 않았다.

## 실제 Toss 및 화면 결과

기존 격리 프로젝트 `tfperf-toss-visible-20261110-163827`의 앱만 일반 bootJar로 재생성했다. 기존 DB·Redis와 테스트 시크릿 키를 유지했고 PG Probe는 사용하지 않았다. 기존 시연 앱과 데이터는 변경하지 않았다.

1. 적용 직전 Payment `PENDING/APPROVAL_CHECK`, Booking `PENDING_PAYMENT`, 재고 2장을 확인.
2. 별도 승인 요청이나 DB 결과 수동 변경 없이 자동 스케줄링 스레드의 Worker가 PG DONE을 조회했다. 기존 `nextRetryAt`을 앞당기지 않았다.
3. Payment `COMPLETED/NONE`, Booking `CONFIRMED`, 기존 요청 `BOOKED`, Redis 요청 표식 `HELD`, 재고 2장, Outbox 0건을 확인.
4. 실제 Toss 조회도 `DONE/간편결제/카카오페이/1원`으로 확인했다.
5. 브라우저 예매내역을 새로고침해 **예매 확정** 표시를 확인하고 화면을 열어뒀다.

`live-before-restart.json`, `live-after-recovery.json`, `live-db-utf8.tsv`, `real-toss-after-recovery.json`, `live-app.log`에 근거를 보존했다. 초기 JSON의 MySQL CLI 한글 출력은 `????`로 표시되므로 정확한 결제수단 문자열은 UTF-8 재조회 TSV를 따른다. 추가 REJECTED attempt 1건은 재시작 전부터 존재했고 기존 BOOKED 요청과 구분했다.

일반 JAR 해시는 `jar.sha256`, 운영 소스 해시는 `source-hashes.json`, 적용 명령과 전후 조회는 `deploy.py`, 재시작 결과는 `restart.log`에 남겼다. 시연 환경은 사용자 확인을 위해 유지한다. DB 결과는 수동으로 확정하지 않았다.

## 한계와 남은 확인

- 실제 Toss 간편결제 승인은 사용자 진행 1건과 이후 자동 조회 복구를 검증했다. 모든 카드사·지갑 제공자의 성공이나 실제 Toss 취소·취소 응답 유실을 검증한 것은 아니다. 취소·장애 테스트는 로컬 HTTP PG 기반이다.
- 기존 `Payment.complete()`는 과거 `lastError`, `retryCount`, `reviewRequired`를 초기화하지 않는다. 이 건은 오류 이력과 reviewRequired=true가 남았지만 `COMPLETED/NONE`, nextRetryAt=NULL로 재시도는 종료됐다. 현재 수동 확인 지표는 PENDING만 집계한다. 이번 수정에서 이 진단 필드 정책을 변경하지 않았다.
- 기존 Google 로그인 대신 test-support JWT로 화면에 진입했다. Google 인증 경로 검증은 제외했다.
- 성능 재측정, 부분 취소, 가상계좌, 일반 사용자 환불 API는 범위 밖이다. 과거 성능 수치를 이번 수정본의 수치로 주장하지 않는다.
- `test-results/`는 Git 제외 로컬 자료이며 push로 백업되지 않는다. 키 값은 결과 문서에 기록하지 않는다.
