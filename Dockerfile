FROM gradle:8.14.3-jdk21 AS development
USER root
ENV GRADLE_USER_HOME=/root/.gradle
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew
CMD ["./gradlew", "test", "--no-daemon"]

FROM development AS build
RUN ./gradlew test installDist --no-daemon

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 audioapp \
    && useradd --uid 10001 --gid audioapp --no-create-home --shell /usr/sbin/nologin audioapp \
    && mkdir -p /data \
    && chown audioapp:audioapp /data
WORKDIR /app
COPY --from=build /workspace/build/install/audio-storage-api/ /app/
ENV DATA_DIR=/data PORT=8080
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/audio-storage-api"]
