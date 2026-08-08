FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :bootstrap:bootJar

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
RUN command -v wget >/dev/null
RUN addgroup -S baton && adduser -S baton -G baton
WORKDIR /app
COPY --from=build /workspace/bootstrap/build/libs/baton-watch.jar ./baton-watch.jar
USER baton
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/baton-watch.jar"]
