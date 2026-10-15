# iblog 项目长期记忆

## 项目定位

个人博客，前后端分离，需要发布到阿里云服务器。**不做移动端适配**。

## 模块与端口

| 模块 | 技术 | 本地端口 | 容器端口 |
| --- | --- | --- | --- |
| iblog-server | Java 17 + Spring Boot 3.3.4 + **Gradle 8.10.2** + MyBatis-Plus + MySQL8 + Redis | 8080 | 8080 |
| iblog-admin | Vue3 + Vite + TS + Element Plus + Pinia + **pnpm 11** | 5173 | 80（宿主 81）|
| iblog-web | Vue3 + Vite + TS（SPA）+ marked | 5174 | 80（宿主 80）|
| deploy | Docker Compose + Nginx | — | — |

## 硬性约定

- **依赖源必须全部走公网镜像，禁止使用公司内网 Nexus**（`192.168.0.244:8081`）。
  `build.gradle` 里用 `repositories.clear()` 剥离机器级 init.gradle 注入的内网仓库，不要删掉这行
- 官网前台**固定用 Vue3 + Vite SPA**（用户明确选择，已知 SEO 弱，不要擅自改成 Nuxt）
- **两个前端都用 pnpm**（`packageManager: pnpm@11.5.0`）：`iblog-admin` 与 `iblog-web` 统一。
  pnpm 11 的配置写在各自的 `pnpm-workspace.yaml`（**不是** package.json 的 pnpm 字段），
  允许执行构建脚本的字段是 `allowBuilds`（map），漏配会让 `pnpm build` 因 ERR_PNPM_IGNORED_BUILDS 失败。
  iblog-admin 批准 `esbuild` + `vue-demi`；iblog-web 只需 `esbuild`（无 pinia/element-plus 故无 vue-demi）
- 后端统一响应体 `R<T>`：`{ code, msg, data }`，200 成功 / 401 未登录 / 400 业务参数 / 500 异常
- 鉴权方案：JWT 签名 + Redis 保存会话（key 前缀 `iblog:login:token:`），支持服务端注销
- 接口分域：`/api/admin/**` 需登录（login 接口除外），`/api/portal/**` 公开
- 数据库表前缀：`sys_` 给系统表，`blog_` 给业务表；所有业务表带 `deleted` 逻辑删除字段
- 站点配置在 `blog_site_config` 表以 kv 形式存储，读取带 Redis 缓存，写后必须清缓存
- 默认管理员 admin / admin123，由 `DataInitializer` 运行时 BCrypt 生成

## 数据库初始化（建表 SQL 只有一份）

- 表结构 + 种子数据固定在 `iblog-server/src/main/resources/db/`：
  `01-schema.sql`（`CREATE TABLE IF NOT EXISTS`）与 `02-data.sql`（`INSERT IGNORE` + 显式主键），
  **两个脚本都必须保持幂等**，重复执行不能清空或重复写数据
- 后端启动自举由 `DatabaseBootstrap` 负责（注册在 `IblogApplication.main` 的 listener，
  监听 `ApplicationEnvironmentPreparedEvent`，在 DataSource 创建之前执行）：
  库不存在则建库、表不存在则建表写种子数据；总开关是 `iblog.db.auto-init`
  （`application.yml` 里 dev 为 `true`，`application-prod.yml` 里为 `false`，生产只自检不改结构）
- 部署时 `docker-compose.yml` 把同一个目录挂进 `/docker-entrypoint-initdb.d`，
  **不要**再单独放一份 init SQL 到 deploy 下
- 启动期所有数据库类异常（Unknown database / Access denied / 连不上 / 超时）
  统一由 `DatabaseBootstrap.translate()` 翻译成中文提示块，不要再让原始堆栈直接抛给用户

## 构建布局（重要，勿随意改动）

- 仓库是**聚合 Gradle 工程**：根 `settings.gradle` 用 `include 'iblog-server'` 把后端纳入同一构建，
  这样在 IDEA 里打开仓库根目录就能被识别为 Gradle 项目
- 根目录和 `iblog-server/` **各有一份 Gradle Wrapper**，这是刻意保留的双入口：
  根目录跑 `./gradlew :iblog-server:bootRun`；`iblog-server` 目录跑 `./gradlew bootRun`
  （Docker 构建 context 是 iblog-server，因此必须保留它那一份 wrapper）
- 新增根级 Gradle 文件后，**不要删掉 `iblog-server/settings.gradle`**，否则 Docker 构建与
  子目录独立构建都会失效

## 本地环境备忘

- 后端构建工具是 **Gradle（Wrapper 8.10.2）**，不再用 Maven。命令：`./gradlew bootRun` / `./gradlew bootJar -x test`，产物 `build/libs/iblog-server.jar`
- 本机 Maven 已不需要；便携 Maven 仍在 `C:\Users\Administrator\.workbuddy\tools\apache-maven-3.9.9`（备用）
- 便携 Gradle：`C:\Users\Administrator\.workbuddy\tools\gradle-8.10.2\bin\gradle`
- 编译需设置 `JAVA_HOME=/c/Program Files/Zulu/zulu-21`
- **本机 `GRADLE_USER_HOME=D:\develop\gradle_user_home` 有坑**：journal 锁常被守护进程占用会报"拒绝访问"，且全局 `init.gradle` 会重定向到内网 Nexus。命令行验证时建议用独立目录 `--gradle-user-home` 覆盖
- 前端 npm 建议加 `--registry=https://registry.npmmirror.com`
- 本机 MySQL 8 为**原生安装**（非 Docker），监听 3306；root 密码**不是** `root`，CLI 直连会 1045，
  别再重复试探；能用的凭据在 `iblog-server/src/main/resources/application-dev.yml`（该文件含敏感信息）
- **Redis 已安装**（2026-10-15）：redis-windows 社区构建 **8.10.2**，位于
  `D:\develop\redis\Redis-8.10.2-Windows-x64-msys2-with-Service\`；已注册 Windows 服务 `Redis`
  （自启动，`RedisService.exe` 包装，数据目录 `D:\develop\redis\data`，监听 127.0.0.1:6379 无密码）。
  操作服务用 `RedisService.exe install/uninstall`，**不要用 `sc.exe`**（被本机安全策略拉黑）
- **Redis 是 iblog 的硬依赖**：站点配置缓存的读操作没有兜底（Redis 挂 → `/api/portal/site` 500），
  登录态也完全靠 Redis；启动阶段不报错只是因为连接懒加载
- 验证启动期初始化逻辑的省事办法：`gradle bootRun --args="--spring.main.web-application-type=none"`，
  跑完自动退出，不占 8080 端口。命令行验证记得加 `--gradle-user-home C:/Users/Administrator/.workbuddy/tools/gradle-home`

## 部署

- 一键：`cd deploy && cp .env.example .env && docker compose up -d --build`
- 必改：`MYSQL_ROOT_PASSWORD`、`IBLOG_JWT_SECRET`（≥32 字符）
- 详细步骤见 `deploy/DEPLOY.md`
- **目标服务器是 2 核 2G**，资源参数已按小内存调好，别随手调大：
  后端 `JAVA_OPTS=-Xms128m -Xmx384m -XX:+UseSerialGC`（`.env`）；
  MySQL `--innodb-buffer-pool-size=128M --performance-schema=OFF --max-connections=50`；
  Redis `--maxmemory 128mb --maxmemory-policy allkeys-lru`；
  构建期 Gradle 在 Dockerfile 里压到 `-Xmx512m` 且关并行
- 2C2G 上**必须**：加 2G Swap + 串行构建（`COMPOSE_PARALLEL_LIMIT=1 docker compose build`），
  否则 `--build` 阶段容易 OOM；再不行就本地构建镜像推 ACR，服务器只 pull
- **部署有两种构建模式，靠 `deploy/.env` 的 7 个变量切换，`docker-compose.yml` 不用改**：
  `SERVER_BUILD_CONTEXT` / `SERVER_DOCKERFILE` / `ADMIN_*` / `WEB_*` / `DB_INIT_DIR`
  - 模式 A（默认）：`../iblog-*` + `Dockerfile`，服务器上编译源码
  - 模式 B（2C2G 首选）：`./dist/*` + `Dockerfile`，服务器只做 COPY。
    本机跑 `bash deploy/build-local.sh` 生成 `deploy/dist/`（~45MB：jar、前端产物、db SQL、
    运行时 Dockerfile 模板来自 `deploy/runtime/`），上传整个 `deploy/` 目录即可
  - 本机没有 Docker，所以**不要**设计需要本地 `docker build` 的流程；产物文件路线才走得通
- **AL4 事实（2026-10-15 修正，别再照抄 CentOS 教程）**：服务器系统是 **Alibaba Cloud Linux 4 LTS**，
  它基于龙蜥 **Anolis OS 23**，`$releasever` = `alnx4`，官方文档明说**不再承载 CentOS 兼容替代**
  （这点和 AL3 不同）。所以 docker-ce 的 centos/rhel 源不能直接套用，**pin `$releasever=9` 是错的**
  （AL3 时代的老经验；AL4 上可能因 `container-selinux` 之类依赖装到一半失败）。
  AL4 仓库自带 **moby 28.x**（含 docker/dockerd，但**不含** compose 插件），正确路线：
  `dnf install -y moby` → 再从阿里云 docker-ce 镜像源取 compose 静态二进制放进
  `/usr/libexec/docker/cli-plugins/`（两个插件目录都放一份，不同打包默认路径不一致）
- **Docker 镜像加速器**：`docker.mirrors.ustc.edu.cn`（中科大，2024-06-06 起停服）和
  `hub-mirror.c.163.com`（网易公共加速，已停用）**都不能用**，`daemon.json` 里留着只会卡死拉取。
  阿里云走账号专属 `https://<ID>.mirror.aliyuncs.com`（控制台 → ACR → 镜像工具 → 镜像加速器）
- 服务器初始化脚本 `deploy/server-init.sh`：`sudo bash deploy/server-init.sh --mirror <加速地址>`，
  幂等，做四件事 —— 2G Swap（写 fstab）→ 装 moby（失败退 docker-ce rhel/9 源）→
  装 compose 插件 → 写 `daemon.json`（加速器 + 容器日志 `20m×3`，不给上限 40G 盘会被日志吃满）
