# 使用 Distroless Java 25
FROM gcr.io/distroless/java25-debian13:nonroot

WORKDIR /app

# 设置时区
ENV TZ=Asia/Shanghai

# 复制 jar 文件
COPY target/fqnovel.jar /app/fqnovel.jar

# 暴露端口
ENV SERVER_PORT=9999
EXPOSE 9999

# 启动应用
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/fqnovel.jar"]
