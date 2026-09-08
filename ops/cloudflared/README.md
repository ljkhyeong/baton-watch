# cloudflared 보안 패치 빌드

[공식 2026.8.3 릴리스](https://github.com/cloudflare/cloudflared/releases/tag/2026.8.3)의
소스 커밋은 `fe70e951a3c52d92abf9f6c4248e32937b2f42fc`다. 소스 압축 파일의 SHA-256은
Dockerfile에 고정한다. 애플리케이션 소스는 수정하지 않고 다음 빌드 입력만 갱신한다.

| 입력 | 사용 버전 |
| --- | --- |
| Go | 1.26.6 |
| golang.org/x/crypto | 0.55.0 |
| google.golang.org/grpc | 1.83.1 |

`go.mod`·`go.sum`은 함께 갱신한 전이 의존성과 체크섬도 고정한다. 빌드는
`-mod=readonly`로 실행하며 CLI·터널 RPC 테스트를 먼저 통과해야 한다.
CGO를 끈 실행 파일·CA 인증서·라이선스 원문을 최소 이미지에 복사한다. 원본 LICENSE와
비루트 사용자 `65532:65532`, 자동 업데이트 중지는 유지한다.

빌드와 선택한 테스트에서 사용하지 않는 `facebookgo/ensure`·`freeport`·`stack`·`subset`,
`x/arch`는 모듈 파일에서 제거했다. 이 구성을 전체 소스 기준으로 `go mod tidy`하면
선택하지 않은 테스트 의존성이 다시 추가될 수 있다.

GitHub가 별도 라이선스로 분류한 Go 특허 허여문은 원본 BSD-3-Clause 본문과 함께
확인했다. `x/net`·`x/sync`·`x/sys`·`x/term`·`protobuf` 다섯 모듈만
Dependency Review 메타데이터 예외로 지정한다. 액션의 예외는 버전을 무시하므로
[라이선스 복사 도구](copy-licenses.sh)가 실제 선택된 모듈 버전과
[LICENSE·PATENTS 체크섬](licenses.sha256)을 대조한다. 다르면 빌드가 실패하며,
확인한 원문은 최종 이미지의 `/usr/share/licenses/cloudflared/modules`에 보존한다.
BSD 본문과 추가 특허 허여문은 각각 해당 모듈의 `LICENSE`·`PATENTS`에서 확인한다.

공식 이미지가 필요한 수정 버전을 포함하면 해당 이미지를 같은 공급망 검사로 검증한 뒤
이 별도 빌드와 의존성 파일을 제거할 수 있다. 패키지 버전 변경 시에는 이 파일 두 개,
Dockerfile의 Go 이미지·소스 체크섬·표시 버전과 공급망 검사 결과를 함께 확인한다.
