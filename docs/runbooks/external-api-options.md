# 무료 외부 API 연동 검토

검토일: 2026-09-12. WATCH 코드와 CAL·RELAY·GO의 기존 기능을 확인했다.
공개 주소는 `b4ton.com`, 서비스별 주소는 `<서비스명>.b4ton.com`을 기준으로 한다.

## 적용 판단

| 대상 | 연동 방법 | 판단과 현재 상태 |
| --- | --- | --- |
| WATCH 운영 알림 | Alertmanager → Telegram Bot API·Slack Incoming Webhook | **연결 설정 추가.** 채널을 선택하고 경보 묶기·재시도·복구 통지를 기본 기능으로 처리한다. 별도 Java 전송기는 필요 없다. 실제 채널 연결은 미설정 |
| WATCH 메트릭 보관·조회 | Prometheus → Grafana Cloud Free | [기존 설정](grafana-cloud-free.md) 사용. 자체 외부 전송 코드를 추가할 필요 없다. Free 계정·인증 정보는 미설정 |
| CAL 일정 구독 | Google·Apple·Outlook의 `.ics` 구독 | 기존 구독 URL과 등록 안내 사용. 별도 OAuth·일정별 생성·수정 API를 추가할 필요 없다. 갱신 시점은 캘린더 앱에 따라 달라 즉시 동기화를 보장하지 않는다 |
| 공휴일 데이터 | 한국천문연구원 특일 정보 API | 휴일 표를 직접 관리할 때 유용하다. 일정 원본을 관리하는 BATON에 적용할 후보이며, CAL·WATCH에 중복 수집기를 넣지 않는다. 활용 신청과 인증키 필요 |
| BATON 업무 알림 | RELAY의 Discord 웹훅 | RELAY가 이미 전송·재시도·결과 처리를 담당한다. WATCH에서 같은 발송 기능을 다시 구현하지 않는다 |
| WATCH 자료 URL 점검 | 외부 가동 상태 점검 API | 현재 구현 유지. 내부 주소 차단, DNS 검증 후 IP 고정, 리다이렉트별 재검증과 본문 미수집 조건을 그대로 대체하기 어렵다 |
| GO 단축 링크 | 외부 단축 URL API | 현재 구현 유지. 멱등 생성·폐기·유효 기간·서명 키 관리가 서비스 계약에 포함돼 있어 단순 주소 변환만 교체해도 코드가 크게 줄지 않는다 |
| 공개 HTTPS 연결 | 기존 Cloudflare Tunnel 연동 | [기존 운영 설정](../../compose.staging-tunnel.yml) 사용 가능. 도메인 연결 설정은 별도이며 이번 변경에 서버 설치·DNS 변경은 없다 |

Telegram 기본 메시지는 무료이며 유료 대량 발송은 사용하지 않는다.
Slack도 Free 요금제로 연결할 수 있으며, 최대 10개인 앱 설치 한도에 여유가 필요하다.
Grafana Cloud는 Free 요금제의 10,000개 활성 시계열·14일 보관 한도 안에서 사용한다.
공휴일 API는 무료지만 승인된 호출량을 지켜야 한다. 연도별로 보관하고 주기적으로 갱신하면
일정 조회 때마다 API를 호출할 필요가 없다.
근거: [Telegram 요금·제한](https://core.telegram.org/bots/faq#broadcasting-to-users),
[Slack Free 한도](https://slack.com/help/articles/115002422943-Usage-limits-for-free-workspaces),
[Grafana 요금](https://grafana.com/pricing/),
[한국천문연구원 특일 정보](https://www.data.go.kr/data/15012690/openapi.do).

CAL의 `docs/external-api-options.md`, RELAY와 GO의 `README.md`를 함께 확인했다.
인접 저장소의 파일은 변경하지 않았다. 이번 적용 범위는 아래 WATCH 운영 알림 설정이다.

## 채널 선택과 공통 설정

연결 흐름은 `Prometheus 경보 → Alertmanager → Telegram 또는 Slack`이다.
사용할 채널의 설정과 [공통 알림 문구](../../ops/alertmanager/watch-notification.tmpl)를 적용한다.
검증한 Alertmanager 버전은 `v0.31.1`이다.

| 채널 | 설정 파일 | 필요한 연결 정보 |
| --- | --- | --- |
| Telegram | [watch-telegram.yml](../../ops/alertmanager/watch-telegram.yml) | 봇 토큰·채팅 ID |
| Slack | [watch-slack.yml](../../ops/alertmanager/watch-slack.yml) | 채널용 Incoming Webhook 주소 |

공통 문구 파일은 `/etc/alertmanager/watch-notification.tmpl`에 제공한다.
이전 Telegram 설정을 사용 중이면 YAML과 템플릿 경로를 함께 갱신한다.
연결 정보는 저장소에 기록하지 않고 Alertmanager 실행 사용자만 읽을 수 있게 제공한다.
두 채널에 함께 보내려면 같은 WATCH 수신자에 `telegram_configs`와 `slack_configs`를 함께 둔다.

1. WATCH 전용 Alertmanager에서는 YAML 전체를 사용할 수 있다. 공용 인스턴스에서는
   기존 설정을 유지하고 WATCH 하위 `route`, 선택한 수신자, 템플릿 경로만 병합한다.
   예시의 기본 수신자 `ignore`는 WATCH 이외의 경보를 버리므로 공용 설정 전체를 덮어쓰지 않는다.
2. 기존 Prometheus에 [WATCH 경보 규칙](monitoring-alerts.md)과 Alertmanager 내부 주소를 연결한다.
   `alerting.alertmanagers[].static_configs[].targets`가 실제 Alertmanager를 가리켜야 한다.
   Grafana Cloud로 `remote_write`만 설정해도 이 연결이 생기는 것은 아니다.
3. 실제 연결 후 장애 발생·복구 알림을 각각 확인한다. 현재 저장소 검증은 설정과 문구만 검사한다.

수신 대상은 `job=baton-watch` 또는 `baton-watch-ingress`이고 `Watch`로 시작하는 경보다.
같은 경보 이름을 묶고 첫 알림은 30초 대기, 변경 알림은 5분 간격, 미복구 알림은 4시간 간격으로 보낸다.
실제 감지 시간에는 각 규칙의 유지 시간과 수집·평가 간격도 더해진다.
메시지에는 경보 이름·고정 요약·발생 및 복구 건수와 `watch.b4ton.com`의 상태 조회 주소만 넣는다.
규칙의 `summary`에는 고정 설명만 유지하고 URL·호스트·리소스 참조·예외 메시지를 추가하지 않는다.
도메인 표기는 연결 완료를 의미하지 않는다.

Alertmanager와 WATCH가 같은 홈서버에 있으면 서버 전체 중단 중에는 자체 알림을 보낼 수 없다.
이 경우 별도 위치의 감시가 필요하며 이번 연결 설정만으로 감지한다고 보지 않는다.

## Telegram 연결 정보

| 항목 | 준비 방법 | Alertmanager에서 읽을 경로 |
| --- | --- | --- |
| 봇 토큰 | Telegram의 공식 BotFather에서 운영 알림 전용 봇 생성 | `/run/secrets/watch-telegram-bot-token` |
| 채팅 ID | 봇과 대화하거나 그룹에 초대한 뒤, [공식 getUpdates](https://core.telegram.org/bots/api#getupdates) 응답의 `message.chat.id` 확인 | `/run/secrets/watch-telegram-chat-id` |

두 파일에는 각각 토큰 문자열과 정수 채팅 ID만 넣는다. 전송 전용 봇은 공개 수신 웹훅이 필요 없다.
설정 형식은 [공식 Telegram 수신자 설정](https://prometheus.io/docs/alerting/latest/configuration/#telegram_config)을 따른다.

Telegram의 기본 제한은 단일 채팅 초당 약 1개, 그룹 분당 20개다. 여러 경보가 동시에 발생하면
429 응답으로 전송이 늦어질 수 있다. 알림 묶기는 전송량을 줄이지만 전역 속도 제한을 대신하지 않는다.
이 구성은 유료 대량 발송을 활성화하지 않는다.

## Slack 연결 정보

1. [Slack 앱 관리](https://api.slack.com/apps)에서 알림용 앱을 만들거나 기존 앱을 선택한다.
2. `Incoming Webhooks`를 켜고 `Add New Webhook to Workspace`에서 받을 채널을 선택한다.
3. 발급된 웹훅 주소만 `/run/secrets/watch-slack-webhook-url` 파일에 넣는다.
   주소 자체가 비밀값이므로 문서·명령 인자·로그에 복사하지 않는다.

채널은 웹훅에 지정돼 있으므로 채널 ID나 봇 토큰은 필요 없다. 받을 채널을 바꾸려면
해당 채널용 웹훅을 발급한다. Slack 설정은 Telegram 자격 증명을 요구하지 않는다.
본문·알림 미리보기에는 공통 한글 문구를 쓰며, 제목 링크는 공개 상태 조회 주소로 고정한다.
워크스페이스 정책에 따라 앱 설치 승인이 필요할 수 있다.
절차와 설정은 [Slack Incoming Webhooks](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/)와
[Alertmanager Slack 수신자 설정](https://prometheus.io/docs/alerting/latest/configuration/#slack_config)을 따른다.

## 검증

```bash
python3 ops/tests/alertmanager-config-test.py
```

고정된 공식 Alertmanager 이미지의 `amtool`로 두 채널의 설정 파싱, WATCH 수신 경로, 다른 서비스 제외,
공통 장애·복구 문구와 내부 주소·자료 참조 미노출을 확인한다. 가짜 자격 증명을 사용하며
검사 컨테이너의 네트워크를 차단한다. 이미지가 없으면 최초 실행 때 내려받는다.
실제 Telegram·Slack 인증과 메시지 수신, Prometheus 연결은 이 검사에 포함되지 않는다.
