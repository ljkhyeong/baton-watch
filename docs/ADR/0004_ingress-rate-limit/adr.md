# ADR-0004: NGINX 기반 공개 요청 속도 제한

상태: 채택됨

날짜: 2026-08-31

## 결정

스테이징 터널 오버레이에 공식 NGINX OSS 이미지를 버전·다이제스트로 고정해 추가한다.
기본 `limit_req` 모듈로 상태 조회와 모니터 API 요청량을 독립적으로 제한한다.
한 서버의 고정된 이름을 키로 사용하여 IP 헤더 위조나 토큰·리소스 식별자 저장 없이
단일 BATON 연동의 전체 요청량을 제한한다. 사용자별 공정 배분이나 여러 프록시 간
공유 제한은 제공하지 않는다.

`cloudflared`는 `watch-ingress`에만, WATCH는 기존 `watch-edge`와 `watch-db`에만
연결한다. NGINX는 `watch-ingress`와 `watch-edge` 사이를 연결하며 DB 네트워크와
관리 포트에는 접근하지 않는다. 터널의 원격 인그레스는 `http://watch-gateway:8080`을
가리켜야 한다. 호스트 포트는 추가하지 않는다.

NGINX는 인증·본문 검증을 구현하지 않는다. 요청·응답 버퍼링과 재시도를 끄고 WATCH에
전달한다. 제한 초과는 인증 전에 고정된 429 문제 응답으로 거부한다. 정상 전달의
오류 계약은 WATCH가 소유하며 NGINX가 반환하는 다른 오류를 애플리케이션 오류로
재포장하지 않는다.

접근 로그는 상태 코드·처리 시간·제한 결과만 기록하고 요청 경로를 포함할 수 있는
오류 로그는 비활성화한다. 비루트 실행, 읽기 전용 파일시스템, 임시 공간·CPU·메모리·
프로세스 상한을 적용한다. Docker DNS를 다시 조회해 WATCH 재생성 시 주소 변경을
따르며 대상 URL 검사기의 공개 주소 정책과 혼동하지 않는다. 여기서 연결하는
내부 WATCH는 운영자가 지정한 고정 인바운드 백엔드다.

## 절충과 운영 조건

추가 구독이나 유료 API는 필요 없지만 NGINX 컨테이너의 CPU·메모리가 필요하다.
초기값은 초당 상태 10건·기타 API 5건이며 공개 배포 전 승인해야 한다.
프록시 하나의 예산이므로 복제하면 전체 허용량이 늘고, 한 호출자의 폭주가 같은
경로의 다른 호출자를 제한할 수 있다. 분산 제한이 필요해지면 별도로 결정한다.

프록시 설정은 배포 커밋의 읽기 전용 파일로 마운트한다. 롤백 시 이전 이미지뿐
아니라 같은 리비전의 설정·네트워크·원격 인그레스를 함께 검토한다. 실제 배포,
공개 HTTPS 429 관찰, 지원 용량과 이그레스 승인은 저장소 검사와 별개다.

CI는 Compose의 게이트웨이 이미지 참조를 읽어 기존 Trivy 이미지 검사와 CycloneDX
SBOM 생성에 포함한다. 수정 가능한 `HIGH`·`CRITICAL` 취약점이 있으면 차단한다.
공식 이미지의 원래 라이선스를 WATCH의 Apache-2.0으로 변경하지 않는다.

프록시 생존 확인은 유지하면서 실제 요청 경로는 Blackbox Exporter 표준 HTTP
모듈과 선택적 Prometheus 경보 템플릿으로 따로 확인한다. 임의 목적지를 받는 Exporter는
비공개 수집 경로와 제한된 이그레스가 승인된 뒤에만 적용한다. WATCH Java에 별도
점검기·감시 스레드·외부 메트릭 전송을 추가하지 않는다.

구현 근거는 NGINX 공식 [요청 제한](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html),
[백엔드 DNS 갱신](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#resolve),
[요청 버퍼링](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_request_buffering) 문서다.
