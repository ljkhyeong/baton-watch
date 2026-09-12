# GitHub 개발 알림을 Slack에 연결

GitHub 공식 앱으로 WATCH의 검증 결과와 PR·리뷰를 받는다.
현재는 구독 명령을 준비한 상태이며 앱 설치·계정 연결·채널 구독·실제 수신은 실행하지 않았다.
운영 장애 알림은 기존 [Alertmanager 연결](external-api-options.md)을 사용한다.

## 비용과 준비

GitHub의 기본 알림 기능을 사용하며 별도 웹훅·발송 프로그램·유료 자동화는 필요 없다.
Slack Free는 [앱 10개 한도](https://slack.com/help/articles/115002422943-Usage-limits-for-free-workspaces)에 여유가 있어야 한다.
기존 GitHub 앱이 있으면 재사용한다. 같은 앱을 여러 채널에서 사용할 수 있다.
Copilot 작업 실행은 이 연결에 포함하지 않는다.

1. [공식 GitHub 앱](https://slack.github.com/)을 워크스페이스에 추가하고 GitHub 계정을 연결한다.
   앱 설치 권한과 `ljkhyeong/baton-watch` 접근 권한이 필요하다.
2. 받을 채널에 `/invite @github`로 앱을 초대한다.
3. 아래 명령으로 기존 구독을 확인한다. 기존 채널에서는 필요한 항목만 변경한다.

```text
/github subscribe list features
```

워크플로를 처음 구독할 때 GitHub 앱의 추가 권한 요청이 표시될 수 있다.
계정 연결은 앱 화면에서 진행하며, 토큰이나 기존 Alertmanager 웹훅 주소를 입력하지 않는다.
[공식 설치 절차](https://docs.github.com/en/integrations/how-tos/slack/integrate-github-with-slack)를 따른다.

## WATCH 구독

받을 Slack 채널에서 다음 명령을 각각 실행한다.

```text
/github subscribe ljkhyeong/baton-watch pulls reviews
/github subscribe ljkhyeong/baton-watch workflows:{name:"검증","IANA 주소 레지스트리 드리프트" event:"pull_request","push","schedule","workflow_dispatch" branch:"main"}
```

| 알림 | 범위 |
| --- | --- |
| PR·리뷰 | PR 생성·병합·검토 준비 전환과 리뷰. Dependabot이 만든 PR도 포함 |
| 검증 | [검증 작업](../../.github/workflows/verify.yml)의 `main` 대상 PR, `main` 푸시·수동 실행 |
| IANA 검사 | [주소 레지스트리 검사](../../.github/workflows/iana-registry-drift.yml)의 정기 실행과 `main` 수동 실행 |

워크플로 이름은 파일의 `name`과 일치해야 한다. 이름을 바꾸면 구독 필터도 갱신한다.
PR의 `branch` 필터는 병합 대상 브랜치를 뜻한다. 작업 브랜치의 단순 푸시는 기존 검증 실행 대상이 아니다.
워크플로 시작 알림과 완료 결과가 같은 스레드에 표시되며, 이 구독이 실패만 알리는 것은 아니다.
실패 여부와 원인은 해당 실행 결과에서 확인한다.

새 채널에서 기본 구독으로 추가된 항목이 불필요하면 해당 항목만 해제한다. 예:

```text
/github unsubscribe ljkhyeong/baton-watch issues commits releases deployments
```

명령과 필터는 [공식 알림 설정](https://docs.github.com/en/integrations/how-tos/slack/customize-notifications)을 따른다.

## 확인과 중지

`/github subscribe list features`로 저장소·작업 이름·이벤트·브랜치 필터를 확인한다.
다음 실제 PR이나 검증 실행에서 시작·완료 알림과 원본 링크를 확인한다.
알림 확인을 위해 기존 검사나 배포를 임의로 다시 실행할 필요는 없다.

이 개발 알림만 중지할 때는 아래 명령을 사용한다. 같은 채널의 다른 GitHub 구독은 유지한다.

```text
/github unsubscribe ljkhyeong/baton-watch workflows pulls reviews
```
