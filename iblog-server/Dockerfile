# ---------- 构建阶段 ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# 先拷构建脚本，提前把依赖拉下来，利用 Docker 层缓存
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

COPY --from=build /build/build/libs/iblog-server.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=prod -jar /app/app.jar"]
