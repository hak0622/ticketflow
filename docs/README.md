# TicketFlow 문서 안내

현재 실행 환경은 로컬이다. GCP/AWS 구성과 기존 클라우드 성능 수치는 과거 검증 기록이다.

## 현재 구조와 실행

1. [아키텍처](architecture.md) — 데이터 책임, 잠금, 복구 Worker.
2. [예매·결제 흐름](flow.md), [API](api.md), [테스트 실행](testing.md).
3. [요청별 예매 복구](booking-attempt-recovery.md), [결제 Saga 구현 당시 결과와 한계](payment-saga-results.md).
4. [로컬 실행·성능·장애 실행기](../perf/README.md), [DB 마이그레이션](migrations/).
5. [Outbox 운영 재시도·알림](payment-compensation-alerting.md).

## 대표 검증 결과

| 범위 | 기록 |
|---|---|
| 예매 DB·Redis 장애 | [응답 유실·프로세스 종료·동시성](booking-failure-stage2-results.md) |
| 로컬 앱 3개와 부하 중 장애 | [3개 앱 자동 복구](booking-local-three-fault-results.md), [부하 중 종료](booking-load-fault-results.md) |
| 성능과 병목 | [동일 조건 버전 비교](booking-performance-optimized-results.md), [성공 경로 계측](booking-success-path-timing-results.md) |
| 결제 실프로세스 장애 | [SIGKILL·오래된 응답·스케줄러 경합](payment-saga-fault-results.md) |
| Worker 중복 재시도 | [회차 비교 수정과 검증](payment-retry-round-fix-results.md) |
| 커밋 응답 유실 | [결제 완료](payment-commit-fault-results.md), [취소·Outbox](payment-cancel-commit-fault-results.md) |
| 실제 Toss | [간편결제 회귀 수정·자동 확정](payment-easypay-fix-results.md), [취소·늦은 승인·추가 Worker 최종 검증](toss-live-cancel-final-results.md) |

각 문서는 실행 당시 코드·환경을 기록한다. 과거 성능 수치를 현재 결제 수정본의 성능으로 해석하거나, 단일 테스트 성공을 모든 운영 장애의 검증으로 확대하지 않는다. JDBC 커밋 예외 주입과 실제 네트워크 ACK 차단도 구분한다.

## 기록 보존

공개 문서는 현재 설계와 대표 결과를 선별했다. 과거 계획·중복 중간 기록은 로컬 `test-results/commit-preparation/`에 보존한다. 실패 원인을 숨기거나 기존 결과를 성공으로 덮어쓰지 않는다.

`build/`와 `test-results/`의 로그·XML·JAR·원장은 Git 제외 로컬 원본이다. Git push로 백업되지 않는다. 민감한 설정은 `.env`/`secrets/`에 두며 실제 키를 문서에 기록하지 않는다.

- [선점 유지와 Booking 생성 복구](booking-hold-recovery.md) — 5분 선점 기한, Worker 생성 재개, 상태 조회 화면.

- [선점 유지 복구 검증 결과](booking-hold-recovery-results.md) — 관련 회귀 164건, 실제 5분 만료, 앱 3대 장애 및 화면 검증.
