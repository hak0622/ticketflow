# 테스트 실행과 결과 읽기

2026-11-12 문서 점검 기준. [문서 안내](README.md) · [실행기·자원 조건](../perf/README.md).

## 환경 격리와 준비

- Java 17, Docker Desktop, Python 3.9+, Gradle wrapper 의존성과 필요한 Docker 이미지가 필요하다. offline 명령은 캐시가 준비된 경우에 사용한다.
- 일반 test 프로파일은 H2/create-drop이다. 일부 기존 Scheduler 테스트는 실제 Redis가 필요하므로 전용 인스턴스와 프로젝트가 지원하는 Redis 설정을 사용한다. 개발·시연 Redis를 연결하지 않는다.
- IntegrationTestSupport는 실제 MySQL 8.4·Redis 7 Testcontainers, RedisTestSupport는 Redis Testcontainers·H2를 사용한다.
- flushDb·fixture 초기화가 있으므로 테스트 DB·Redis는 폐기 가능한 대상이어야 한다. perf 실행기는 실행별 프로젝트·전용 볼륨을 만들고 종료 시 해당 프로젝트를 down -v로 삭제한다. 기존 Compose 프로젝트에 명령을 복사해 적용하지 않는다.
- 실제 Toss 실행기는 별도 테스트 키·사용자 테스트 결제·취소를 필요로 한다. 자동 일반 회귀와 구분하고 재현 방법과 한계를 먼저 읽는다.

## 개발 실행과 테스트 실행의 차이

일반 개발은 `application-local.yml`, Docker 앱은 `application-docker.yml`, 격리 장애 실행기는 `perf`의 전용 Compose가 기준이다. 풀 크기·포트·테스트 API 설정이 다르므로 같은 환경으로 취급하지 않는다.

- 개발 기본 프로파일은 local이다. DB_URL/DB_USERNAME/DB_PASSWORD, JWT_SECRET_KEY, Google OAuth 클라이언트 ID·secret, TOSS_SECRET_KEY를 실행 프로세스에 전달한다. 이 프로젝트의 `./gradlew bootRun`은 루트 `.env`를 읽어 환경변수를 전달한다. 직접 `java -jar`로 실행할 때는 이 Gradle 처리가 없으므로 별도로 주입한다. 값은 공개 문서·로그에 붙이지 않는다.
- local의 Redis는 현재 localhost:6380이다. 루트 Compose는 MySQL 호스트 3307, Redis 호스트 6380을 매핑하므로 DB_URL도 대상 포트를 맞춘다. Docker 앱은 서비스 이름 mysql:3306·redis:6379로 접속한다.
- 준비한 개발 DB·Redis에서 `./gradlew bootRun`으로 백엔드를 시작하고, frontend 폴더에서 의존성을 준비한 뒤 `npm run dev`로 실행한다. Vite의 기본 /api 프록시는 localhost:8080이며 허용 frontend origin·OAuth 리다이렉트도 해당 실행 주소와 맞아야 한다.
- local은 Mock 결제와 테스트 지원 API를 활성화한다. 공통 설정의 Mock 기본값 false, prod의 강제 거부 정책과 구분한다. local을 공개 운영 환경으로 안내하지 않는다.
- 루트 Compose의 MySQL/Redis 볼륨은 개발 데이터다. 아래 격리 검증과 혼용하거나 `down -v`로 임의 삭제하지 않는다. 이 문서 정리에서는 어느 앱도 재시작하지 않았다.

## 현재 검증 경로

| 클래스/자료 | 목적 |
|---|---|
| BookingHoldRecoveryIntegrationTest | 유효 선점으로 Booking 생성 재시도·중복·만료·손상 기록 |
| BookingHoldLeaseClockIntegrationTest | 실제 5분 경과 후 자동 Worker 반환. 스케줄러 ON, 반드시 별도 실행 |
| BookingAttemptMySqlIntegrationTest / BookingFailureStageTwoIntegrationTest | 요청 잠금·매진·멱등 반환·DB/Redis 장애와 응답 유실 |
| BookingPaymentApiIntegrationTest | 상태 조회의 인증·소유권·조회 전용 및 API 회귀 |
| PaymentSagaIntegrationTest / PaymentRetryRoundIntegrationTest | HTTP PG 재조회·취소·결과 적용 및 동일 회차 실패 중복 갱신 보호 |
| PaymentCommitFaultIntegrationTest / PaymentCancelCommitFaultIntegrationTest | 실제 DB commit 후 JDBC 예외 주입과 회귀. 정확한 클래스·범위는 각 결과 문서 참조 |
| PaymentStageOneIntegrationTest / PaymentMockDisabledIntegrationTest / PaymentFlowIntegrationTest | 소유권·기존 결과·Mock 정책·API 회귀 |
| BookingExpirySchedulerTest / PaymentCompensationSchedulerTest | 만료·Outbox·재처리·결제 보호 |
| QueueServiceIntegrationTest | 대기열 및 테스트에 남긴 옛 Redis 메서드 계약 |
| EnrollServiceTest / EnrollConcurrencyTest | 구 이름이 남아 있는 예매 테스트 |

PaymentMockPolicyTest·PaymentServiceFailRateTest는 Mock 정책 검증이다. Mock 또는 단위 테스트 성공을 외부 PG 장애 검증으로 해석하지 않는다. 실제 존재하는 클래스명은 아래 결과 문서·소스에서 확인한다.

## 실행 안내

Docker와 의존성이 준비된 독립 환경에서 실행한다. 아래 예시는 선택 테스트이며 전체 회귀 명령을 대신하지 않는다.

```bash
./gradlew compileJava --offline
./gradlew test --offline --tests studying.blog.fault.BookingHoldRecoveryIntegrationTest
./gradlew test --offline --tests studying.blog.fault.PaymentSagaIntegrationTest
./gradlew test --offline --tests studying.blog.service.QueueServiceIntegrationTest
```

실제 5분 테스트는 활성 스케줄러가 다른 fixture를 처리할 수 있으므로 **다른 클래스와 합치지 않고 별도 Gradle test 실행/JVM으로 실행**한다. 5분 이상 필요하며 완료 시간 제한과 다르다.

```bash
./gradlew test --offline --tests studying.blog.fault.BookingHoldLeaseClockIntegrationTest
```

현재 선점 유지 정책의 앱 3대 실프로세스 검증:

```bash
python3 perf/booking_hold_fault.py
```

실행기는 독립 MySQL·Redis·테스트 전용 Probe JAR를 사용하고 해당 환경만 삭제한다. 기존 `local_three_fault.py` 등은 이전 반환 정책의 기대값을 포함하므로 최신 정책의 실행기로 대신 소개하지 않는다. 일반 bootJar에는 장애 Probe를 포함하지 않는다.

전체 test와 ciTest의 선택 범위도 다르다. ciTest는 experiment/integration 태그를 제외한다. 여기서는 실행 방법을 소스와 정적으로 대조했으며 문서 정리를 위해 테스트·초기화·부하를 실행하지 않았다.

## 검증 범위와 근거

| 검증 | 환경·주입 방식 | 기록과 한계 |
|---|---|---|
| 최신 예매 복구 | MySQL·Redis, 앱 3대 SIGKILL, 실제 5분 경과, 브라우저 | [결과](booking-hold-recovery-results.md): 164건 선택 회귀(상속 포함), 5분 1건, 프로세스 4건. 전체 저장소 테스트 아님 |
| 결제 승인/취소 종료·경합 | 로컬 HTTP 모의 PG·MySQL·Redis·앱 3대 | [12회](payment-saga-fault-results.md). 취소 의도는 fixture, 일반 환불 API 아님 |
| Worker 공유 재시도 | 동일 후보 선택과 실패 도착 순서 제어 | [회차 비교](payment-retry-round-fix-results.md). 정상 Worker 선택 공정성 보장 아님 |
| 결제 완료/취소 커밋 응답 유실 | JDBC commit 성공 후 앱에 예외 전달 | [완료](payment-commit-fault-results.md)·[취소](payment-cancel-commit-fault-results.md). 실제 네트워크 ACK 차단 아님 |
| 실제 Toss 테스트 환경 | 사용자 카카오페이 테스트 승인·전액 취소·조회 | [간편결제 수정](payment-easypay-fix-results.md)·[최종 취소 검증](toss-live-cancel-final-results.md). 실제 PG 장애 주입 아님 |
| 성능·계측 | 실행 당시 버전별 로컬 k6 | [버전 비교](booking-performance-optimized-results.md)·[단계별 계측](booking-success-path-timing-results.md). 최신 선점 유지 버전 미측정 |

각 결과 문서의 날짜·실행 ID·원본 위치·가능한 해시와 판정을 따른다. 서버 로그만이 아니라 DB·Redis·PG 효과 횟수로 정합성을 판단한 범위를 확인한다. 모든 장애를 검증했다고 확대하지 않는다.

## 과거 기록과 원본

PaymentPgBoundaryCharacterizationTest는 수정 전 위험 동작을 기대하는 @Disabled 기록이다. 과거 PASS를 현재 안전성 통과로 표현하지 않는다. `experiments/`는 읽기 전용 전략 비교 자료다.

[최초 Saga 결과](payment-saga-results.md)와 [이전 예매 반환 정책](booking-attempt-recovery.md)의 미검증·실패는 작성 당시 사실로 보존한다. 이후 검증은 문서 안내에서 연결한다.

`build/`, `test-results/`는 Git 제외이며 clone으로 복원되지 않는다. 원본 XML·JAR·PG 원장 경로는 로컬 증거 위치다. 특정 historical-results 명령은 원본 JAR가 있어야 실행 가능하다. 공개 저장소에는 비밀키·토큰·원본 원장을 추가하지 않는다. Redis 유실, 장기 장애, 실제 PG 네트워크 장애, 완료 거래 정기 대사 등 남은 한계는 아키텍처와 개별 결과를 따른다.
