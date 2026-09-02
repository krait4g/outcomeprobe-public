FROM python:3.13.5-alpine3.22

RUN addgroup -g 10001 -S outcomeprobe \
    && adduser -u 10001 -S -D -H -G outcomeprobe outcomeprobe \
    && mkdir -p /opt/outcomeprobe \
    && chown root:root /opt/outcomeprobe \
    && chmod 0555 /opt/outcomeprobe

WORKDIR /opt/outcomeprobe
COPY --chown=0:0 --chmod=0555 realtime_track_target.py /opt/outcomeprobe/realtime_track_target.py

USER 10001:10001
EXPOSE 8181
HEALTHCHECK --interval=3s --timeout=2s --retries=30 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8181/health', timeout=1).read()"]
ENTRYPOINT ["python", "/opt/outcomeprobe/realtime_track_target.py"]
