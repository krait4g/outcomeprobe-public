FROM gradle:9.6.1-jdk21 AS build

WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY cli cli
COPY LICENSE ./
COPY THIRD-PARTY-NOTICES THIRD-PARTY-LICENSES.json ./
RUN mkdir -p server examples/reference-consumer \
    && gradle :cli:shadowJar --no-daemon -x test -x check

FROM eclipse-temurin:21-jre AS java-runtime

FROM python:3.13.5-slim-bookworm

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --gid 10001 outcomeprobe \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin outcomeprobe \
    && mkdir -p /opt/outcomeprobe/realtime-track /evidence \
    && chown root:root /opt/outcomeprobe /opt/outcomeprobe/realtime-track \
    && chown outcomeprobe:outcomeprobe /evidence \
    && chmod 0555 /opt/outcomeprobe /opt/outcomeprobe/realtime-track \
    && chmod 0700 /evidence

WORKDIR /opt/outcomeprobe
COPY --from=build --chown=0:0 --chmod=0444 \
    /build/cli/build/libs/outcomeprobe-cli-0.1.0.jar \
    /opt/outcomeprobe/outcomeprobe-cli.jar
COPY --chown=0:0 --chmod=0555 docker/realtime-kit.sh /opt/outcomeprobe/realtime-kit.sh
COPY --chown=0:0 --chmod=0555 scripts/user-service-kit/create_user_service_kit_summary.py \
    /opt/outcomeprobe/create_user_service_kit_summary.py
COPY --chown=0:0 --chmod=0444 examples/realtime-track/provider-contract.yaml \
    examples/realtime-track/target-profile.compose.yaml \
    examples/realtime-track/scenario-single-event.yaml \
    examples/realtime-track/scenario-duplicate-3x.yaml \
    examples/realtime-track/scenario-out-of-order.yaml \
    /opt/outcomeprobe/realtime-track/

USER 10001:10001
ENTRYPOINT ["/opt/outcomeprobe/realtime-kit.sh"]
