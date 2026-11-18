# 관리자 수량 불변·생성 권한·테스트 API 격리

2026-11-18, 기준 HEAD `0d02ced` 이후 변경. 로컬용 최소 변경이며 앱 재배포·커밋·푸시는 하지 않았다.

## 적용한 정책과 코드

- 공연 수량은 생성 시 결정하고 이후에는 변경하지 않는다. `ConcertService.adminUpdate()`는 필수값 검증·DB 공연 조회 후 수량이 다르면 HTTP 409와 `생성된 공연의 총 티켓 수량은 변경할 수 없습니다.`로 거절한다. 정상 형식의 요청에 대한 정책이며 잘못된 필수값은 기존 400이다.
- 거절은 엔티티 변경·Redis 조회/쓰기·캐시 갱신 등록 전에 수행한다. 예매 기록 유무에 따른 예외와 Redis 재고 재계산/초기화 분기를 없앴다. 같은 수량의 정보 수정과 커밋 후 캐시 갱신은 유지한다. 신규 생성의 재고 초기화는 유지한다.
- 두 생성 POST `/api/concerts`와 `/api/admin/concerts`는 ADMIN만 허용한다. 비로그인 401·일반 사용자 403이며 공개 GET은 유지한다. 기존 요청 형식을 유지하고 제목·일시·양수 수량의 기본 검증을 공유한다.
- 일반 `local`의 테스트 지원 API는 `${TEST_SUPPORT_ENABLED:false}`로 기본 비활성화다. 테스트 JWT 발급·CSV·setup/reset과 자동 CSV 실행기가 기본 등록되지 않는다. 기존 격리 perf Compose의 명시적 true는 유지한다. 명시적으로 활성화한 환경은 인증 없는 테스트 도구 환경이므로 관리자 권한 보호가 완결된 환경으로 취급하지 않는다.
- 관리자 화면은 수정 수량을 읽기 전용으로 표시하고 기존 값을 payload에 포함한다. 서버 message/error를 안내하고 자동 재전송하지 않는다. 마감·삭제 오류는 목록 오류와 구분하며 기존 목록을 유지하고 재시도할 수 있다. 삭제 안내는 복구 기록 보존 정책에 맞췄다.

구현 근거: [ConcertService](../src/main/java/studying/blog/service/ConcertService.java), [보안 설정](../src/main/java/studying/blog/config/WebOAuthSecurityConfig.java), [관리 화면](../frontend/src/pages/AdminConcertPage.jsx), [로컬 설정](../src/main/resources/application-local.yml).

## 실제 검증 범위

| 검증 | 환경과 결과 |
|---|---|
| 신규 관리자·테스트 API·설정 검사 | 21건 통과, 실패·오류·스킵 0 |
| 기존 기능을 포함한 선택 백엔드 회귀 | 105건 통과, 실패·오류·스킵 0 |
| 프론트 회귀 | 신규 관리자 8 + 기존 대기열/결제 51 = 59건 통과 |
| 수정 프론트 파일 Lint | 통과. 기존 관리자 화면의 effect 내 동기 상태 변경 오류도 해소 |
| 프론트 production build | 통과 |
| 전체 프론트 Lint | 실패. 변경하지 않은 HeroCarousel·ConcertListPage·HomePage의 기존 오류 6건 |

105건은 신규 21 + 대기열/예매 복구/예매·결제 API 75 + 기존 API 권한 6 + 기존 관리자 삭제 3이다. 최초 21건은 반복 실행한 것이며 서로 다른 126개 사례라고 합산하지 않는다. 기존 Queue 8건은 H2+Redis, 신규 설정 단위 검사 1건은 YAML 속성 해석이고 나머지는 MySQL 8.4·Redis 7 Testcontainers 기반이다.

- 실제 JWT·MockMvc 보안/Controller/예외 처리를 사용했다. TCP 서버나 브라우저 E2E 검증은 아니다.
- OPEN/SOLD_OUT/CLOSED의 수량 증가·감소 거절에서 제목·가격·포스터도 함께 변경하도록 요청하고 DB/Redis 전체 스냅샷 무변경과 재고 초기화 미호출을 검사했다. 예매 기록 없는 직접 서비스 호출과 실제 HELD 예매도 검사했다.
- 경합 검증은 실제 reserve Lua 차감 직후 게이트를 두고 Booking 커밋 전에 관리자의 변경 요청을 거절한다. 게이트 해제 후 Booking 1건·BOOKED·잔여 99/전체 100을 확인했다. 운영 코드에 게이트나 새 락을 추가하지 않았다.
- 비활성 Spring 컨텍스트에서 테스트 지원 Controller·서비스·Runner가 없고 다섯 API가 404이며 예매/선점/재고가 보존됨을 확인했다. 명시적 opt-in 컨텍스트에서는 JWT 발급·CSV·setup/reset 호환성을 확인했다. 데이터 초기화는 폐기 가능한 테스트 DB/Redis에만 실행했다.
- `LocalTestSupportDefaultTest`는 local YAML의 기본 false·명시적 true 해석을 확인하는 단위 시험이다. 실제 local 시연 앱을 기동하거나 재설정한 시험은 아니다.
- 전체 회귀에서 SQL 실패·Redis timeout은 기존 장애 주입 시험에도 포함된다. 다중 Spring 컨텍스트 종료 시 공유 테스트 스키마가 이미 정리돼 발생한 Hibernate schema-drop 로그가 있으나 JUnit 실패/오류는 0이다. 이 로그를 별도 장애 복구 성공으로 세지 않는다.

## 최초 실패와 기록

프론트 신규 시험 최초 실행은 로그인 mock이 매 렌더 새 user 객체를 반환해 effect를 반복하면서 5건 실패했다. 실제 store처럼 안정된 객체를 반환하도록 테스트 장치를 고쳤고 기대 호출 횟수를 완화하지 않았다. 수정 후 59건 통과했다. 앞선 파일 생성 명령의 경로 오류로 기존 51건만 실행한 로그도 별도 보존하며 신규 검증으로 세지 않는다.

실행 명령·원본 로그·XML·소스 해시/사본·DB/Redis 상태·프론트 결과는 Git 제외 `test-results/admin-concert-safety/20261118-103755/`에 보존한다. opt-in CSV와 토큰이 포함된 원본은 공개 문서에 복사하지 않는다. 기존 대기열 회귀 증거는 이번 실행의 queue-regression 디렉터리로 구분하고 이전 출력 경로 표식은 복원했다.

신규 시험은 다음으로 재현한다. Java 17·Docker Desktop 및 오프라인 의존성 캐시가 필요하다.

```bash
./gradlew test --offline --tests studying.blog.fault.AdminConcertSafetyIntegrationTest --tests studying.blog.fault.AdminTestSupportIsolationIntegrationTest --tests studying.blog.fault.AdminTestSupportOptInIntegrationTest --tests studying.blog.config.LocalTestSupportDefaultTest
cd frontend
npm test
npx eslint src/pages/AdminConcertPage.jsx src/pages/AdminConcertPage.test.jsx
npm run build
```

## 유지한 한계

- 예매 요청 기록이 있는 공연의 삭제 차단은 기존대로 유지한다. 삭제와 첫 예매의 경합, 생성 중 DB/Redis 실패의 새 복구 보장은 추가하지 않았다.
- 제목·가격·상태 등 다른 관리자 수정 정책, OAuth 쿠키·토큰 구분·로그아웃 보안은 이번 개선 범위가 아니다.
- 로컬 테스트 지원 기능의 명시적 활성화 가능성은 남긴다. 이 환경을 외부에 공개하지 않으며 개발·시연 데이터에서 setup/reset을 실행하지 않는다.
- 예매·결제·재고 Lua, Worker 주기·잠금·스키마·풀·timeout은 변경하지 않았다.
- k6·실제 PG·실제 시연 앱 기동·재배포·커밋·푸시는 실행하지 않았다.

## 변경 파일과 독립 리뷰

- 운영·화면: ConcertService.java, WebOAuthSecurityConfig.java, ConcertApiController.java, application-local.yml, AdminConcertPage.jsx.
- 테스트: AdminConcertSafetyIntegrationTest.java, AdminTestSupportIsolationIntegrationTest.java, AdminTestSupportOptInIntegrationTest.java, LocalTestSupportDefaultTest.java, AdminConcertPage.test.jsx.
- 문서: api.md, architecture.md, testing.md, admin-concert-safety-results.md, perf/README.md.

별도 읽기 전용 리뷰가 구현과 최종 XML·소스 해시·문서를 대조했고 추가 P1/P2나 정책 모순을 발견하지 않았다. 백엔드 105건·프론트 59건과 검증 소스 10개의 현재 파일·사본·SHA-256 일치를 확인했다. 리뷰어는 파일 수정이나 테스트 실행을 하지 않았다.
