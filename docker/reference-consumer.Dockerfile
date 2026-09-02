# 소스부터 이미지 내부에서 빌드하는 multi-stage 빌드입니다.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY server server
COPY cli cli
COPY examples examples
RUN gradle :examples:reference-consumer:bootJar --no-daemon -x test -x check

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system consumer && useradd --system --gid consumer consumer

WORKDIR /app
COPY --from=build /build/examples/reference-consumer/build/libs/reference-consumer.jar /app/reference-consumer.jar
RUN chown -R consumer:consumer /app

USER consumer
EXPOSE 8091 8092

HEALTHCHECK --interval=5s --timeout=3s --retries=30 \
    CMD curl -fsS http://localhost:${SERVER_PORT:-8091}/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/reference-consumer.jar"]
