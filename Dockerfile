FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :bootstrap:bootJar

FROM postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15 AS database-operations
COPY --chmod=0555 ops/staging-database-operation.sh /opt/watch/staging-database-operation.sh
USER 70:70
ENTRYPOINT ["/opt/watch/staging-database-operation.sh", "configure-runtime-role"]

FROM flyway/flyway:12.4.0-alpine@sha256:b43c3d9b7227687682a9124451ac3dbc9b0003eca65290ad1dcda760345bc680 AS migrations
COPY --chmod=0555 adapter-out-persistence/src/main/resources/db/migration /flyway/sql
COPY --chmod=0555 ops/flyway /flyway/callbacks
RUN find /flyway/sql /flyway/callbacks -type f -exec chmod 0444 {} +
COPY --chmod=0555 ops/staging-database-operation.sh /opt/watch/staging-database-operation.sh
USER 65532:65532
ENTRYPOINT ["/opt/watch/staging-database-operation.sh", "migrate"]

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
RUN command -v wget >/dev/null
RUN addgroup -S baton && adduser -S baton -G baton
WORKDIR /app
COPY --from=build /workspace/bootstrap/build/libs/baton-watch.jar ./baton-watch.jar
USER baton
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/baton-watch.jar"]
