# ---------- 构建阶段 ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# 先拷构建脚本，提前把依赖拉下来，利用 Docker 层缓存
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
# 覆盖 gradle.properties 里的 -Xmx1g：小内存服务器（2G）编译期容易 OOM，这里压到 512m 并关掉并行
RUN ./gradlew --no-daemon \
        -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8" \
        -Dorg.gradle.parallel=false \
        clean bootJar -x test

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseSerialGC"

COPY --from=build /build/build/libs/iblog-server.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=prod -jar /app/app.jar"]
