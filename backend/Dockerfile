# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# Static analysis configs: checkstyle:check is bound to the validate phase by
# pom.xml, so the `mvn package` below runs it before compiling — copy both
# configs (spotbugs:check would join at verify) or the build fails to resolve
# them from /app.
COPY checkstyle.xml spotbugs-exclude.xml ./

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
# Spring Boot repackaged fat JAR (plain library JAR is saved as *.jar.original).
COPY --from=build /app/target/bikes-*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
