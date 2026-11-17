# syntax=docker/dockerfile:1
# Build context is the repo root so the composite build (build-logic, version catalog) resolves.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    cd services/query && ./gradlew :query-app:bootJar --no-daemon

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /src/services/query/query-app/build/libs/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
