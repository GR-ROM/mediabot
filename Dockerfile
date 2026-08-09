# Built in two stages so the image carries a runtime and not a toolchain: the build stage needs a
# JDK and the whole Gradle cache, and none of that has any business being on a server.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src
# The wrapper and the build files first, on their own: this layer only changes when a dependency
# does, so an edit to a source file does not re-download the world.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar --quiet && \
    cp build/libs/*.jar /app.jar

FROM eclipse-temurin:25-jre

# The two binaries the bot shells out to. ffmpeg comes from the distribution; yt-dlp does not,
# because the packaged one is always months behind and a stale yt-dlp is indistinguishable from
# a site that has stopped working.
# Latest rather than pinned, deliberately. A pinned yt-dlp is reproducible right up until YouTube
# changes something, and then it is simply broken — "The page needs to be reloaded" and friends.
# Rebuild to update it.
#
# nodejs is here for the same reason: YouTube hands out an "n challenge" that has to be executed,
# and with no JS engine the format list comes back empty, which reads like a video that does not
# exist rather than a missing dependency.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg nodejs ca-certificates curl \
    && curl -fsSL -o /usr/local/bin/yt-dlp \
       "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux" \
    && chmod +x /usr/local/bin/yt-dlp \
    && apt-get purge -y curl && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app.jar /app/app.jar

# Everything that has to outlive the container. Kept apart because they age differently: work is
# scratch, public is what links point at, cache is the queue that lets a restart say what was lost.
RUN mkdir -p /app/work /app/public /app/cache
VOLUME ["/app/public", "/app/cache"]

ENV MEDIABOT_YTDLP=/usr/local/bin/yt-dlp \
    MEDIABOT_FFMPEG=/usr/bin/ffmpeg \
    MEDIABOT_JS_RUNTIME=node:/usr/bin/node \
    MEDIABOT_PORT=8099
# A cap rather than a default: the host has under 2 GB and ffmpeg needs room beside the JVM, so
# letting the heap grow to a quarter of the machine is how both end up being killed.
ENV JAVA_TOOL_OPTIONS="-Xmx384m -XX:MaxMetaspaceSize=192m --enable-native-access=ALL-UNNAMED"

EXPOSE 8099
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
