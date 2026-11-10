# 실제 Toss 취소 검증 마지막 두 항목 완료

2026-11-10. **합의한 두 항목 모두 성공했다.** 이 결과로 이번 실제 PG 취소 검증을 종료한다. 추가 기능·장애 시나리오·성능 테스트는 수행하지 않았다.

원본: `test-results/toss-live-cancel/20261110-171542/`. 앞선 `20261110-170301` 실행의 도구 실패와 원본은 그대로 보존했다. 그 결과를 성공으로 덮어쓰지 않는다.

## 두 항목과 근거

| 항목 | 결과 | 관측 근거 |
|---|---|---|
| 오래된 승인 결과 처리 완료 후에도 취소 유지 | 성공 | PG DONE 반환을 보류한 뒤 취소·Outbox 반환을 먼저 완료. 이후 승인 게이트를 해제하고 원래 `PaymentSaga.confirm()`이 CANCELED로 반환한 이벤트·JSON을 관측했다. 직후 스냅샷은 반환 직후와 동일했다. |
| 추가 Worker 실행 후에도 중복 취소·반환 없음 | 성공 | 중복 주문 3회 모두 CANCELED. 그 이후 scheduling 스레드의 결제 Worker 완료 4회·실제 Outbox 후보 조회 2회를 관측. 최종 PG 취소 내역 1개, Outbox 1개, Redis RETURNED 효과 1회를 확인했다. |

결과는 단순히 일정 시간을 기다렸다는 추정이 아니다. `gates/events.ndjson`의 `original_confirm_completed`, `automatic_scan_completed`, `automatic_outbox_query_completed` 완료 이벤트를 사용했다. 원래 confirm의 반환은 결과 적용·응답 조회 트랜잭션 이후이며 브라우저가 HTTP 응답을 수신했다는 의미는 아니다.

## 실제 실행 및 최종 상태

사용자가 새 공연 3의 실제 Toss 테스트 결제창에서 카카오페이 1원을 결제했다. 테스트 Probe는 실제 응답을 대체하거나 PG 목적지를 바꾸지 않았다. 승인 결과 적용을 보류한 사이 Booking → Payment 잠금으로 CANCEL_REQUIRED 의도만 fixture로 준비했다. 기존 자동 Worker가 실제 전액 취소, 기존 Outbox 스케줄러가 티켓 반환을 처리했다. 서비스/Worker 수동 호출과 DB 최종 상태 강제 변경은 없다.

| 대상 | 최종 값 |
|---|---|
| Toss | CANCELED / 간편결제·카카오페이 / totalAmount=1 / balanceAmount=0 |
| Toss 취소 내역 | 1개, cancelAmount=1, cancelStatus=DONE |
| Payment 3 | CANCELED / NONE / nextRetryAt=NULL |
| Booking 3 | CANCELLED |
| booking_attempt | RELEASED, Booking 3 연결 유지 |
| 해당 Payment의 Outbox | 1개, PUBLISHED, retryCount=0 |
| Redis | 재고 3, 요청 RELEASED/RETURNED, 활성 선점 없음 |
| 앱의 PG HTTP 호출 | 승인 1회·취소 1회 관측 |
| Redis 실제 반환 | RETURNED 1회 관측 |

앱 HTTP 호출 수와 PG 내부 효과 수를 혼동하지 않는다. 효과는 PG 최종 조회의 취소 내역 1개와 Redis Lua RETURNED 이벤트로 확인했다. PG 내부 원장을 직접 열람한 것은 아니다.

이전 결제 1·2의 상태·버전·Booking 연결 및 상태를 `protected-before.tsv`와 `protected-after.tsv`로 비교해 동일함을 확인했다. 브라우저 내 예매에는 기존 확정 1건과 취소 2건이 표시됐다.

## 화면의 확인 필요 표시

이번에는 승인 결과가 17:18:02.565부터 17:18:26.745까지 약 24.18초간 의도적으로 보류됐다. 프론트 `frontend/src/shared/api/axios.js`의 요청 제한은 10초다. 따라서 브라우저에서는 먼저 연결 오류/확인 필요 안내가 나왔지만 원래 서버 메서드는 뒤늦게 CANCELED로 정상 완료했다. 내 예매로 이동해 최종 취소 상태를 확인했다.

원래 메서드 처리 완료를 브라우저 수신 완료라고 주장하지 않는다. 프론트 timeout 및 화면 갱신 로직은 이번 범위에서 변경하지 않았다.

## 수정 범위와 복원

- `src/test/.../TossLiveCancelProbe.java`: 원래 confirm 완료와 Worker 완료·Outbox 조회 이벤트 관측 추가. 일반 bootJar에 포함하지 않는다.
- `perf/toss_live_cancel.py`: 원래 confirm 완료 후 상태 비교, 추가 스캔 완료 판정, 이전 결제 보존 검증, 실패 정리 시 보류 요청 완료 대기 추가. 앞서 수정한 MySQL `--raw` 유지.
- 운영 코드·스키마·잠금·스케줄 주기·timeout·풀·Lua는 변경하지 않았다. `summary.json`의 production_source_unchanged=true.
- `testClasses bootJar --offline` 성공. 이번 수정은 테스트 지원 코드만이며 이전 156건 회귀를 이번 단계에서 다시 실행한 것은 아니다.
- 종료 후 같은 일반 JAR로 복원했다. 런타임 profile=docker, JAR에 TossLiveCancelProbe 없음, API HTTP 200을 `normal-restoration-verified.json`으로 검증했다.
- 화면 시연 환경은 유지한다. 테스트용 Probe는 실행 앱에서 제외했으며 테스트 소스와 증거는 보존한다.

## 증거와 범위

- 실행 코드·Probe·일반/테스트 JAR·해시·소스 해시·빌드 로그·실행 명령 보존.
- `gates/original-confirm-result.json`: 원래 confirm의 CANCELED 응답.
- `returned-before-old-approval.json`, `after-original-confirm-completed.json`, `final.json`: 오래된 응답·중복 요청·추가 스캔 전후 상태 동일.
- `duplicate-0.json`~`duplicate-2.json`: CANCELED 응답 재사용.
- `pg-final.json`: 실제 Toss 최종 조회.
- `summary.json`: SUCCESS, extra_scans=4, extra_outbox_queries=2, manual_worker_invocations=0.
- `normal-restoration-verified.json`: 일반 앱 복원 확인.

단일 로컬 앱과 실제 MySQL·Redis·Toss 테스트 환경에서 수행한 1회 검증이다. 일반 사용자 환불 API, 실제 네트워크 ACK 차단, PG 전체 장애, 다중 앱 경합 재검증, 다른 지갑 제공자, 성능 측정을 포함하지 않는다. 취소 의도는 fixture이며 공개 API 생성 경로를 검증한 것이 아니다. 해당 한계를 유지하고 이번 작업은 완료한다.

`test-results/`는 Git 제외 로컬 자료다. 테스트 키 값은 문서에 기록하지 않았다.
