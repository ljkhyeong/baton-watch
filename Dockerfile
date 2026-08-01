FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :bootstrap:bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S baton && adduser -S baton -G baton
WORKDIR /app
COPY --from=build /workspace/bootstrap/build/libs/baton-watch.jar ./baton-watch.jar
USER baton
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/baton-watch.jar"]

