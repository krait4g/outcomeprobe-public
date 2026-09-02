# 소스부터 이미지 내부에서 빌드하는 multi-stage 빌드입니다.
# 사용자 환경에 Java/Gradle이 없어도 docker compose up --build만으로 실행됩니다.
# 보안 강화: production 배포 시 base 이미지를 digest(@sha256:...)로 고정하세요.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY LICENSE ./
COPY THIRD-PARTY-NOTICES THIRD-PARTY-LICENSES.json ./
COPY server server
COPY cli cli
COPY examples examples
# 이미지 빌드에서는 테스트·품질 게이트를 건너뛴다 (CI가 담당).
RUN gradle :server:bootJar --no-daemon -x test -x check

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system outcomeprobe && useradd --system --gid outcomeprobe outcomeprobe

WORKDIR /app
COPY --from=build /build/server/build/libs/outcomeprobe-server.jar /app/outcomeprobe-server.jar
RUN chown -R outcomeprobe:outcomeprobe /app

USER outcomeprobe
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --retries=30 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/outcomeprobe-server.jar"]
