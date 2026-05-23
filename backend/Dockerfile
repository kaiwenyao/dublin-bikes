# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
