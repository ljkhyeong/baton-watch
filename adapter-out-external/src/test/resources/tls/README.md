# Pinned-host TLS test fixture

`pinned-host-server.p12.base64` is a text-wrapped, test-only PKCS12 key store.
It contains a self-signed RSA certificate whose only DNS subject alternative
name is `watch.invalid`. Its deliberately different subject CN,
`certificate-fixture.invalid`, prevents the success case from relying on CN
fallback instead of the DNS SAN. The key is public test material and must never
be used outside automated tests. Gradle packages this directory only on the
test classpath, not in the production JAR.

Regenerate it with JDK 21 `keytool`, then Base64-encode the resulting PKCS12
file as plain text:

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
