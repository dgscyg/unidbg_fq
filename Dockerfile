# Build stage
FROM maven:3.9.12-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml /build/pom.xml
COPY src /build/src

RUN mvn -DskipTests package

# Runtime stage
FROM gcr.io/distroless/java25-debian13:nonroot

WORKDIR /app

ENV TZ=Asia/Shanghai
ENV SERVER_PORT=9999
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/

COPY --from=build /build/target/fqnovel.jar /app/fqnovel.jar

EXPOSE 9999

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/fqnovel.jar"]