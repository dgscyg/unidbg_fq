ARG MAVEN_IMAGE=docker.io/library/maven:3.9.12-eclipse-temurin-25
ARG RUNTIME_IMAGE=docker.io/eclipse-temurin:25-jre

# Build stage
FROM ${MAVEN_IMAGE} AS build

WORKDIR /build

ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public

COPY pom.xml /build/pom.xml
COPY src /build/src

RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' \
        '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' \
        '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>mainland-mirror</id>' \
        '      <name>Mainland Maven Mirror</name>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '      <mirrorOf>*</mirrorOf>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /tmp/settings.xml; \
      mvn -s /tmp/settings.xml -DskipTests package; \
    else \
      mvn -DskipTests package; \
    fi

# Runtime stage
FROM ${RUNTIME_IMAGE}

WORKDIR /app

ENV TZ=Asia/Shanghai \
    SERVER_PORT=9999 \
    LOG_DIR=/app/logs \
    SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/

COPY --from=build /build/target/fqnovel.jar /app/fqnovel.jar

RUN mkdir -p /app/logs /app/config \
    && chown -R 65532:65532 /app

USER 65532:65532

EXPOSE 9999

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/fqnovel.jar"]
