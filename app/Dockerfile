# ---------- 1. Сборка jar и минимальной JRE ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# jlink: собираем урезанную JRE только с нужными Spring Boot модулями (~50 МБ вместо ~190 МБ)
RUN jlink \
      --add-modules java.base,java.logging,java.management,java.naming,java.desktop,java.instrument,java.security.jgss,java.sql,java.net.http,java.xml,jdk.unsupported,jdk.crypto.ec,jdk.management \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# ---------- 2. Итоговый образ ----------
FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /jre /opt/jre
COPY --from=build /src/target/sentiment-api.jar /app/app.jar
ENV PATH="/opt/jre/bin:${PATH}" \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -XX:TieredStopAtLevel=1 -Xss256k -XX:ReservedCodeCacheSize=32m"
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
