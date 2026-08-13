# 고정 호스트 TLS 테스트 픽스처

`pinned-host-server.p12.base64`는 텍스트로 감싼 테스트 전용 PKCS12 키
저장소입니다. 이 파일에는 DNS 주체 대체 이름이 `watch.invalid` 하나뿐인 자체
서명 RSA 인증서가 들어 있습니다. 주체 CN은 의도적으로 다른
`certificate-fixture.invalid`로 지정해, 성공 사례가 DNS SAN 대신 CN 대체
처리에 의존하지 않도록 합니다. 이 키는 공개 테스트 자료이므로 자동화 테스트
외부에서 절대 사용해서는 안 됩니다. Gradle은 이 디렉터리를 운영 JAR이 아닌
테스트 클래스패스에만 포함합니다.

JDK 21 `keytool`로 다시 생성한 뒤, 생성된 PKCS12 파일을 일반 텍스트 형태로
Base64 인코딩합니다.

~~~bash
keytool -genkeypair \
  -alias pinned-host-server \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -storetype PKCS12 \
  -keystore pinned-host-server.p12 \
  -storepass baton-watch-test \
  -keypass baton-watch-test \
  -dname "CN=certificate-fixture.invalid,OU=BATON WATCH Test,O=Personal,C=KR" \
  -ext SAN=dns:watch.invalid \
  -ext EKU=serverAuth \
  -ext KU=digitalSignature,keyEncipherment \
  -startdate "2020/01/01 00:00:00" \
  -validity 36500
base64 < pinned-host-server.p12 > pinned-host-server.p12.base64
~~~
