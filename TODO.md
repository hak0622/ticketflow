# TODO

## 현재 상태
- 콘서트 티켓 예매 백엔드 구현 완료
- Lecture → Concert 도메인 마이그레이션 완료
- Booking / Payment / Outbox / 만료 처리 구현 완료
- Redis 대기열 + Lua + Pessimistic Lock 적용
- Step3 테스트 통과
- CLAUDE.md 및 docs 정리 완료

## 완료된 작업
- [x] E1 동시성 실험 (Redis / DB 전략 비교)
- [x] E3 멱등성 실험 (SETNX / DB unique key)
- [x] E4 보상 트랜잭션 실험 (Outbox 패턴)
- [x] 실험 코드 experiments/ 패키지로 분리
- [x] Outbox 기반 보상 처리 구현
- [x] BookingExpiryScheduler 구현

## 현재 진행 작업
- [ ] React + Vite 프론트 1차 MVP 설계

## 다음 작업 (우선순위 순)
- [ ] 프론트 프로젝트 생성 (Vite + React)
- [ ] 공연 목록 / 상세 / 대기열 / 결제 / 내 예매 화면 구현
- [ ] axios API 연동
- [ ] 최소 상태 관리 구조 확정

## 2차 작업 (확장)
- [ ] Kafka 도입 (Outbox → Kafka → Consumer)
- [ ] 이벤트 기반 보상 처리로 전환
- [ ] 좌석맵 UI 구현
- [ ] 로그인 UI 개선

## 보류 / 참고
- experiments/ 패키지는 참고용 유지 (프로덕션 코드와 분리)