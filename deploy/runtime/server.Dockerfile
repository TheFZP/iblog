# =============================================================
#  后端「预构建」运行时镜像
#  不含 JDK 编译环节，只把本地打好的 jar 拷进来，镜像构建耗时 < 5 秒、几乎不占内存。
#  由 deploy/build-local.sh 复制到 deploy/dist/server/Dockerfile 使用。
# =============================================================
FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseSerialGC"

COPY iblog-server.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=prod -jar /app/app.jar"]
