# syntax=docker/dockerfile:1
# Dos imágenes desde el mismo código: «app» (la aplicación) y «mock» (la Rama Judicial simulada).
#   docker build --target app  -t rama-queue-app  .
#   docker build --target mock -t rama-queue-mock .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY app/pom.xml app/pom.xml
COPY mock/pom.xml mock/pom.xml
# Un mvnw con fin de línea CRLF (checkout en Windows) no se puede ejecutar en Linux.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY app app
COPY mock mock
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine AS app
RUN addgroup -S rama && adduser -S rama -G rama
USER rama
WORKDIR /opt/rama
COPY --from=build /src/app/target/rama-queue-app-*.jar app.jar
EXPOSE 8087
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD wget -q -O /dev/null http://localhost:8087/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS mock
RUN addgroup -S rama && adduser -S rama -G rama
USER rama
WORKDIR /opt/rama
COPY --from=build /src/mock/target/rama-queue-mock-*.jar mock.jar
EXPOSE 8089
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
  CMD wget -q -O /dev/null http://localhost:8089/__admin/stats || exit 1
ENTRYPOINT ["java", "-jar", "mock.jar"]
