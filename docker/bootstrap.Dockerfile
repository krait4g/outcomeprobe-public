FROM gradle:9.6.1-jdk21 AS build

WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY cli cli
COPY LICENSE ./
COPY THIRD-PARTY-NOTICES THIRD-PARTY-LICENSES.json ./
RUN mkdir -p server examples/reference-consumer \
    && gradle :cli:shadowJar --no-daemon -x test -x check

FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="OutcomeProbe Quick Start Bootstrap" \
      org.opencontainers.image.description="Imports the canonical core-only golden path with the staged CLI" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.outcomeprobe.release.flavor="core-only" \
      io.outcomeprobe.pack.support="false"

RUN groupadd --gid 10001 outcomeprobe \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin outcomeprobe \
    && mkdir -p /opt/outcomeprobe/golden-path/payment \
    && chown root:root /opt/outcomeprobe /opt/outcomeprobe/golden-path \
        /opt/outcomeprobe/golden-path/payment \
    && chmod 0555 /opt/outcomeprobe /opt/outcomeprobe/golden-path \
        /opt/outcomeprobe/golden-path/payment

WORKDIR /opt/outcomeprobe
COPY --from=build --chown=0:0 --chmod=0444 \
    /build/cli/build/libs/outcomeprobe-cli-0.1.0.jar \
    /opt/outcomeprobe/outcomeprobe-cli.jar
COPY --chown=0:0 --chmod=0555 docker/bootstrap.sh /opt/outcomeprobe/bootstrap.sh
COPY --chown=0:0 --chmod=0444 examples/golden-path/payment/provider-contract.yaml \
    /opt/outcomeprobe/golden-path/payment/provider-contract.yaml
COPY --chown=0:0 --chmod=0444 examples/golden-path/payment/target-profile-vulnerable.yaml \
    /opt/outcomeprobe/golden-path/payment/target-profile-vulnerable.yaml
COPY --chown=0:0 --chmod=0444 examples/golden-path/payment/target-profile-resilient.yaml \
    /opt/outcomeprobe/golden-path/payment/target-profile-resilient.yaml
COPY --chown=0:0 --chmod=0444 examples/golden-path/payment/scenario.yaml \
    /opt/outcomeprobe/golden-path/payment/scenario.yaml
COPY --chown=0:0 --chmod=0444 examples/golden-path/payment/scenario-callback-duplicate.yaml \
    /opt/outcomeprobe/golden-path/payment/scenario-callback-duplicate.yaml

USER 10001:10001
ENTRYPOINT ["/opt/outcomeprobe/bootstrap.sh"]
