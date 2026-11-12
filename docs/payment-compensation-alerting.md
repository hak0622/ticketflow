# 결제 보상 실패 Slack 알림

> 이 문서는 작성·실행 당시의 계획 또는 검증 기록입니다. 현재 흐름은 [문서 안내](README.md), PG 구현 및 한계는 [결제 결과](payment-saga-results.md)를 확인하세요. 과거 결과는 현재 전체 기능의 검증을 뜻하지 않습니다.

결제 보상 Outbox가 3회 재시도 후 `FAILED`가 되면 다음 흐름으로 운영자에게 알립니다.

```
FAILED Outbox → outbox_failed_current → Prometheus 규칙 → Alertmanager → Slack
```

누적 실패 횟수가 아니라 DB에 현재 남아 있는 `FAILED` 건수를 감시합니다. 따라서 앱이 재시작돼도 실패 건을 놓치지 않으며, 운영자가 수동 재시도해 `PENDING`으로 바꾸면 알림이 자동으로 해제됩니다.

## 최초 설정

1. Slack에서 Incoming Webhook을 만들고 URL을 발급합니다.
2. 예시 파일을 복사하고 실제 URL 한 줄만 저장합니다.

```bash
mkdir -p secrets
cp secrets/slack_webhook_url.example secrets/slack_webhook_url
```

`secrets/slack_webhook_url`은 Git에서 제외됩니다. 이 파일을 열어 예시 URL을 실제 Webhook URL로 교체합니다.

3. 알림 기능을 포함해 로컬 스택을 실행합니다.

```bash
docker compose --profile alerting up -d
```

Prometheus는 `http://localhost:9090/alerts`, Alertmanager는 `http://localhost:9093`에서 상태를 확인할 수 있습니다.

## 알림 수신 후 조치

1. `GET /api/admin/payment-compensations?status=FAILED`로 실패 원인과 Outbox ID를 확인합니다.
2. 원인을 해결합니다.
3. `POST /api/admin/payment-compensations/{outboxId}/retry`로 다시 처리합니다.

실패 건이 없어지면 Slack에 `RESOLVED` 알림이 전송됩니다.

## Lua 부분 쓰기 보류 알림 추가

기존 Outbox FAILED 알림과 별개로 `BookingInventoryIncomplete`는 Redis의 선점/반환 PENDING 진행 기록을, `BookingInventoryObservationUnavailable`은 관측 불능을 알립니다. [판단 기준과 운영 절차](redis-lua-partial-write.md#불완전-진행-기록의-관리자-알림)를 확인하세요. Outbox 재등록과 앱 재시작만으로 불완전 기록이 해소되지는 않습니다.
