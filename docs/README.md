# TicketFlow 문서 안내

현재 실행 환경은 로컬이다. 지정 좌석 없이 공연별 티켓 수량을 관리하며, GCP/AWS 구성과 클라우드 성능 수치는 과거 검증 기록이다. 최신 소스가 실행 중인 시연 앱에 배포됐다는 의미는 아니므로 실행 JAR·설정을 별도로 확인한다.

## 현재 구조를 읽는 순서

1. [아키텍처](architecture.md) — 데이터 책임, 상태의 의미, 잠금·트랜잭션·Worker.
2. [사용자와 서버 처리 흐름](flow.md) — 대기열 → 티켓 확보 → 예매 생성 → PG 결제 → 확정/보상.
3. [예매 생성 복구 상세](booking-hold-recovery.md) — 5분 복구 기한, 재시도·반환·배포 조건과 한계.
4. [API](api.md) — 실제 요청·응답과 인증. 상태 조회는 조회만 수행한다.
5. [테스트 환경과 실행](testing.md), [로컬 실행·성능·장애 실행기](../perf/README.md).
6. [DB 마이그레이션](migrations/), [Outbox 실패 알림·관리자 재시도](payment-compensation-alerting.md).

상세 트랜잭션·상태 정의는 아키텍처, 5분 복구 정책은 예매 생성 복구 상세가 기준이다. 개별 결과 문서는 실행 당시의 증거이며 현재 설계 전체를 대신하지 않는다.

## 대표 검증 결과

| 범위 | 기록과 해석 |
|---|---|
| 최신 예매 생성 복구 | [164건 선택 회귀·실제 5분 만료·앱 3대 장애·화면](booking-hold-recovery-results.md). 상속 테스트를 포함한 실행 건수이며 전체 테스트 통과가 아님 |
| 결제 실프로세스 장애 | [승인/취소 직후 SIGKILL·늦은 응답·만료/보상 경합 12회](payment-saga-fault-results.md). 로컬 모의 HTTP PG 사용 |
| Worker 중복 재시도 | [동일 회차 비교 수정·지연 검증](payment-retry-round-fix-results.md). Worker 공정성 보장은 아님 |
| 커밋 응답 유실 | [결제 완료](payment-commit-fault-results.md), [취소·Outbox](payment-cancel-commit-fault-results.md). 실제 JDBC commit 성공 후 예외 주입, 네트워크 ACK 차단과 구분 |
| 실제 Toss 테스트 환경 | [간편결제 승인 수정](payment-easypay-fix-results.md), [취소·늦은 승인·추가 Worker](toss-live-cancel-final-results.md). 실제 PG 장애나 일반 환불 API 검증은 아님 |
| 성능·계측 | [버전 비교](booking-performance-optimized-results.md), [성공 경로 계측](booking-success-path-timing-results.md). 최신 5분 선점 유지 버전의 성능 수치가 아님 |

각 결과의 실행 ID·환경·해시·판정·한계는 연결한 문서에서 확인한다. 최신 예매 복구 변경 후 전체 k6 재측정은 하지 않았다.

## 과거 설계와 검증 기록

- [이전 예매 반환 정책](booking-attempt-recovery.md), [예매 2단계 장애](booking-failure-stage2-results.md).
- [이전 앱 3대 자동 반환 복구](booking-local-three-fault-results.md), [부하 중 종료](booking-load-fault-results.md).
- [결제 Saga 최초 구현 당시 결과](payment-saga-results.md). 당시 미검증 항목과 이후 검증 결과를 구분한다.

과거 수치·실패·이미지는 보존한다. 과거 JMeter/GCP 결과와 로컬 k6 결과를 같은 조건으로 직접 비교하지 않는다.

## 원본 보존과 공개 범위

`build/`와 `test-results/`의 로그·XML·JAR·PG 원장·CSV는 Git 제외 로컬 원본이다. 공개 저장소를 clone해도 내려받을 수 없으며 결과 문서의 경로는 원본 위치를 설명하는 것이다. 과거 중간 계획은 로컬 `test-results/commit-preparation/`에도 보존돼 있다.

재현에는 소스·실행 명령과 독립 테스트 환경이 필요하다. 기존 개발·시연 DB/Redis에 초기화 테스트를 연결하지 않는다. `.env`/`secrets/`의 실제 키·토큰·원본 요청 전문은 공개 문서에 기록하지 않는다.

## 이번 문서 점검 범위

2026-11-12에 현재 소스·설정·스크립트 인자를 정적으로 대조했다. 실제 테스트 재실행·k6·실제 PG 호출·시연 앱 교체는 하지 않았다. 로컬 링크와 코드 블록을 검사했고 Mermaid는 렌더러가 없어 텍스트 구조만 점검했다. 외부 URL 접속과 현재 가동 앱의 JAR 일치 여부는 이번에 확인하지 않았다.
