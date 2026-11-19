# Vesper backend (Ktor). Build context = repo root. Used by Render (render.yaml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY server-build server-build
COPY shared shared
COPY server server
RUN sed -i "s/\r$//" gradlew && chmod +x gradlew && ./gradlew --no-daemon -q -p server-build :server:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
RUN useradd -r -u 10001 vesper
USER vesper
ENV PORT=8080
# Render Free has ~512 MB RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
CMD ["/app/bin/server"]
