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
- **`iblog-admin` 用 pnpm**（`packageManager: pnpm@11.5.0`），`iblog-web` 仍用 npm。
  pnpm 11 的配置写在 `iblog-admin/pnpm-workspace.yaml`（**不是** package.json 的 pnpm 字段），
  允许执行构建脚本的字段是 `allowBuilds`（map），漏配会让 `pnpm build` 因 ERR_PNPM_IGNORED_BUILDS 失败
- 后端统一响应体 `R<T>`：`{ code, msg, data }`，200 成功 / 401 未登录 / 400 业务参数 / 500 异常
- 鉴权方案：JWT 签名 + Redis 保存会话（key 前缀 `iblog:login:token:`），支持服务端注销
- 接口分域：`/api/admin/**` 需登录（login 接口除外），`/api/portal/**` 公开
- 数据库表前缀：`sys_` 给系统表，`blog_` 给业务表；所有业务表带 `deleted` 逻辑删除字段
- 站点配置在 `blog_site_config` 表以 kv 形式存储，读取带 Redis 缓存，写后必须清缓存
- 默认管理员 admin / admin123，由 `DataInitializer` 运行时 BCrypt 生成

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

## 部署

- 一键：`cd deploy && cp .env.example .env && docker compose up -d --build`
- 必改：`MYSQL_ROOT_PASSWORD`、`IBLOG_JWT_SECRET`（≥32 字符）
- 详细步骤见 `deploy/DEPLOY.md`
