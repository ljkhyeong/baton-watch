ARG OCI_SOURCE="https://github.com/ljkhyeong/baton-watch"
ARG OCI_VERSION="0.1.0-SNAPSHOT"
ARG OCI_REVISION="unknown"

FROM eclipse-temurin:24-jdk-alpine-3.22@sha256:8fdbcb6bc6b846640cea7058e6eeb56c311fae4efaa506a213789134065c6b90 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :bootstrap:bootJar

FROM postgres:18.6-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2 AS database-operations
ARG OCI_SOURCE
ARG OCI_VERSION
ARG OCI_REVISION
LABEL org.opencontainers.image.title="BATON WATCH 데이터베이스 운영 작업" \
      org.opencontainers.image.description="BATON WATCH PostgreSQL 운영 작업 이미지" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN apk add --no-cache \
        "libcrypto3=3.5.8-r0" \
        "libssl3=3.5.8-r0" \
        "su-exec=0.3-r0" \
    && rm /usr/local/bin/gosu
COPY --chmod=0444 LICENSE /usr/share/licenses/baton-watch/LICENSE
RUN chmod 0555 /usr/share/licenses /usr/share/licenses/baton-watch
COPY --chmod=0555 ops/staging-database-operation.sh /opt/watch/staging-database-operation.sh
COPY --chmod=0555 ops/run-as-database-user.sh /opt/watch/run-as-database-user.sh
ENTRYPOINT ["/opt/watch/run-as-database-user.sh", "70", "70", "/opt/watch/staging-database-operation.sh", "configure-runtime-role"]

FROM flyway/flyway:12.11.0-alpine@sha256:6bf3a713f52c4d803a88501f8409dda2191e9ccba1454358a6de2c4cc65f71b0 AS flyway-source
RUN find /flyway/drivers -mindepth 1 -maxdepth 1 ! -name 'postgresql-*.jar' -exec rm -rf {} + \
    && find /flyway/lib/flyway -maxdepth 1 -type f -name 'flyway-database-*.jar' ! -name 'flyway-database-postgresql-*.jar' -delete \
    && rm -f \
        /flyway/lib/flyway/flyway-firebird-*.jar \
        /flyway/lib/flyway/flyway-gcp-bigquery-*.jar \
        /flyway/lib/flyway/flyway-gcp-spanner-*.jar \
        /flyway/lib/flyway/flyway-mysql-*.jar \
        /flyway/lib/flyway/flyway-singlestore-*.jar \
        /flyway/lib/flyway/flyway-sqlserver-*.jar \
    && rm -rf /flyway/lib/aad /flyway/lib/netty

FROM eclipse-temurin:24-jre-alpine-3.22@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f AS migrations
ARG OCI_SOURCE
ARG OCI_VERSION
ARG OCI_REVISION
ENV PATH="/flyway:${PATH}"
WORKDIR /flyway
LABEL org.opencontainers.image.title="BATON WATCH 마이그레이션" \
      org.opencontainers.image.description="BATON WATCH Flyway 마이그레이션 이미지" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN apk add --no-cache \
        "bash=5.3.9-r1" \
        "libcrypto3=3.5.8-r0" \
        "libexpat=2.8.3-r0" \
        "libssl3=3.5.8-r0" \
        "openssl=3.5.8-r0" \
        "p11-kit=0.26.2-r0" \
        "p11-kit-trust=0.26.2-r0" \
        "sqlite-libs=3.53.4-r0" \
        "su-exec=0.3-r0"
COPY --from=flyway-source /flyway /flyway
COPY --chmod=0444 LICENSE /usr/share/licenses/baton-watch/LICENSE
COPY --chmod=0555 adapter-out-persistence/src/main/resources/db/migration /flyway/sql
COPY --chmod=0555 ops/flyway /flyway/callbacks
RUN chmod 0555 /usr/share/licenses /usr/share/licenses/baton-watch \
    && find /flyway/sql /flyway/callbacks -type f -exec chmod 0444 {} +
COPY --chmod=0555 ops/staging-database-operation.sh /opt/watch/staging-database-operation.sh
COPY --chmod=0555 ops/run-as-database-user.sh /opt/watch/run-as-database-user.sh
ENTRYPOINT ["/opt/watch/run-as-database-user.sh", "65532", "65532", "/opt/watch/staging-database-operation.sh", "migrate"]

FROM eclipse-temurin:24-jre-alpine-3.22@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f AS runtime
ARG OCI_SOURCE
ARG OCI_VERSION
ARG OCI_REVISION
LABEL org.opencontainers.image.title="BATON WATCH" \
      org.opencontainers.image.description="BATON WATCH 애플리케이션 런타임 이미지" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN command -v wget >/dev/null \
    && apk add --no-cache \
        "libcrypto3=3.5.8-r0" \
        "libssl3=3.5.8-r0" \
        "su-exec=0.3-r0"
COPY --chmod=0444 LICENSE /usr/share/licenses/baton-watch/LICENSE
RUN chmod 0555 /usr/share/licenses /usr/share/licenses/baton-watch \
    && addgroup -S -g 10001 baton \
    && adduser -S -D -H -u 10001 -G baton baton
WORKDIR /app
COPY --from=build /workspace/bootstrap/build/libs/baton-watch.jar ./baton-watch.jar
COPY --chmod=0555 ops/run-as-watch-user.sh /opt/watch/run-as-watch-user.sh
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/baton-watch.jar"]
