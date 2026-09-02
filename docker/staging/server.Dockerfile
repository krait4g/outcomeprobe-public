FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55

ARG OCI_CREATED
ARG OCI_REVISION
ARG OCI_SOURCE
ARG OCI_VERSION=0.1.0

LABEL org.opencontainers.image.created="${OCI_CREATED}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.title="OutcomeProbe Server Core" \
      org.opencontainers.image.description="OutcomeProbe 0.1.0 core-only staging server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.outcomeprobe.release.flavor="core-only" \
      io.outcomeprobe.pack.support="false"

RUN groupadd --gid 10001 outcomeprobe \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin outcomeprobe \
    && mkdir -p /app /licenses \
    && chown root:root /app /licenses \
    && chmod 0555 /app /licenses

WORKDIR /app
COPY --chown=0:0 --chmod=0444 outcomeprobe-server-core-0.1.0.jar /app/outcomeprobe-server.jar
COPY --chown=0:0 --chmod=0444 LICENSE /licenses/LICENSE
COPY --chown=0:0 --chmod=0444 THIRD-PARTY-NOTICES /licenses/THIRD-PARTY-NOTICES
COPY --chown=0:0 --chmod=0444 THIRD-PARTY-LICENSES.json /licenses/THIRD-PARTY-LICENSES.json

USER 10001:10001
ENV SERVER_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=24 \
    CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT}; printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; read -r status <&3; [[ "$status" == *" 200 "* ]]'

ENTRYPOINT ["java", "-jar", "/app/outcomeprobe-server.jar"]
