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
- **AL4 事实（2026-10-15 在真机上核实过，别再照抄 CentOS 教程）**：系统是
  **Alibaba Cloud Linux 4.0.3（OpenAnolis Edition）**，内核 `6.6.102-5.3.4.alnx4`，
  `$releasever` = `alnx4`。它基于龙蜥 **Anolis OS 23**，官方定位**不再承载 CentOS 兼容替代**
  （这点和 AL3 不同）。所以 docker-ce 的 centos/rhel 源不能套用，**pin `$releasever=9` 是错的**
  （AL3 时代的老经验，AL4 上可能因 `container-selinux` 之类依赖装到一半失败）。
- **AL4 仓库里 Docker 包是全的（真机 `dnf list` 实测）**：`moby`/`moby-engine`/`moby-client`
  `3:29.5.1-2.alnx4`、`docker`/`docker-client` `3:24.0.9-9.alnx4`、`containerd 1.7.34-2.alnx4`、
  **`docker-compose-plugin 2.26.1-1.alnx4`（13MB）**。所以正确动作就两条：
  `dnf -y install moby`（已有 docker 则跳过）+ `dnf -y install docker-compose-plugin`。
  `docker` 与 `moby` 并存、提供同一套命令，二选一即可。
  ⚠️ 之前记录里写「AL4 仓库不带 compose、要从 docker-ce 的 RPM 里 `rpm2cpio` 抠静态二进制」
  **是错的**，那条路只留作兜底。别再从公网找 compose 二进制。
- **Docker Hub 直连在阿里云 ECS 上是被重置的**（实测 `read: connection reset by peer`），
  `registry-mirrors` 必须配。`docker.mirrors.ustc.edu.cn`（中科大，2024-06-06 起停服）和
  `hub-mirror.c.163.com`（网易公共加速，已停用）都不能用。
- **教训：改 `/etc/docker/daemon.json` 必须「合并」，不能「覆盖」。** 生产机上往往已经配了
  能用的 `registry-mirrors`（这台就有 daocloud / 1ms.run / xuanyuan.me 三个，实测能拉通），
  直接整体覆盖会把人家能用的镜像源弄丢。`server-init.sh` 现已改用 python3 做真正的 JSON 合并，
  并且只在**没有运行中容器**时才自动重启 docker，否则只警告。
- 服务器初始化脚本 `deploy/server-init.sh`：`sudo bash deploy/server-init.sh [--mirror <地址>]`，
  幂等，做四件事 —— 2G Swap（写 fstab）→ 装 Docker 引擎（已有则跳过）→ 装 compose 插件
  （优先走 AL4 仓库，失败才退 rpm2cpio）→ **合并**写 `daemon.json`（保留原 registry-mirrors +
  容器日志 `20m×3`，不给上限 40G 盘会被日志吃满）。
- **目标服务器 `8.138.105.59`（hostname `iZkbazda5mew26Z`）不是空机器**，详见
  `.workbuddy/memory/2026-10-15.md`：它上面跑着一个与本项目无关的 `json-parse`（占着 8080），
  2G 内存下两者共存会顶到上限 —— 部署前必须先决定端口和内存怎么分。
  该项目**已于 2026-10-15 部署上线**（`/opt/iblog/deploy`，模式 B 预构建）。

## 管理员账号（2026-10-15 已从 admin 改为 ipeng）

- 登录用户名 **`ipeng`**，密码由用户指定、**不记录在仓库与记忆里**。
  改密码只能走数据库（前后端都没有改密码功能，用户明确说不做）。
- ⚠️ **绝对不能删 `sys_user` 里 `username='admin'` 那一行。**
  `DataInitializer.run()` 的判断是 `if (userService.getByUsername("admin") != null) return;`——
  **只认 `admin` 这个字符串**。所以现在库里保留了一行 id=2、`status=0`、
  nickname「已停用（占位）」、密码为随机不可猜哈希的**废弃占位行**，专门用来让这个判断提前 return。
  一旦删掉（或再次改名），**下次后端重启就会自动重建 `admin` / `admin123`**，
  而且是在日志里悄悄重建，极难排查。
  根因修复应改 `DataInitializer`：要么「存在任意管理员就跳过」，要么把默认口令改成从
  环境变量读（如 `IBLOG_ADMIN_PASSWORD`）。用户目前选择了不改代码。
- 服务器上没有 BCrypt 生成工具（python3 无 `bcrypt` 模块、无 `htpasswd`；
  **`openssl passwd -6` 是 SHA-512 crypt，不能用**）。已装 `httpd-tools`，
  用法：`htpasswd -bnB -C 10 '' '<password>' | tr -d ':\n'`。
- **BCrypt 版本前缀实测**：库里原本是 `$2a$`，htpasswd 生成的是 `$2y$`，
  **Spring 的 `BCryptPasswordEncoder` 接受 2y**（改完真实登录成功验证过）。
  形状正则：`^\$2[aby]\$[0-9]{2}\$[./0-9A-Za-z]{53}$`，长度 60。
- 改库前的全表备份留在服务器 `/root/sys_user-backup-*.sql`。
- 改完密码记得踢掉 Redis 旧会话：`docker exec iblog-redis redis-cli DEL iblog:login:token:<userId>`
  （key 前缀见 `CacheKeys.LOGIN_TOKEN = "iblog:login:token:"`）。
- 登录页曾写死 `username: 'admin'` 作为表单初值，已改成空串 —— 否则改名后打开登录页
  会预填一个已停用的账号名，直接输密码必然报「用户名或密码错误」。
- 另有 3 处写死的 `'admin'` **保留未改**，它们与登录无关：
  `layout/index.vue` 的昵称兜底 `userStore.nickname || 'admin'`；
  `views/article/edit.vue` 的 `author: 'admin'` 默认值 —— 而库里两篇文章的
  `author` 字段确实都是 `admin`，改前端默认值反而会造成新旧文章不一致。

## 网络与安全的硬教训（都踩过）

- **安全组：加规则只用「手动添加 / 快速添加」，绝不要走会替换整个列表的入口。**
  真发生过一次：加 80/81 时把 SSH(22) 一起弄没了，于是彻底连不上。
- **判断端口不通是安全组还是服务的问题**：
  **超时（被静默丢弃）= 安全组没放行**；**connection refused = 服务本身没监听**。
  再配合在服务器上排除本机因素：`ss -lntp`（是否 0.0.0.0 监听）、
  `iptables -t nat -S DOCKER`（DNAT 规则在不在）、`firewalld`/`nftables` 是否 inactive、
  `iptables -S INPUT` 策略。全都正常 → 只可能是云安全组。
- **官网 nginx 只放行 `/api/portal/`，其余 `/api/**` 一律 `return 404`。**
  `iblog-web` 前端本来就只调 `/api/portal/**`；若把整个 `/api/` 反代出去，
  `/api/admin/**`（含登录接口）就会从公网 80 端口暴露，管理后台的 IP 白名单形同虚设
  （本仓库曾有这个问题，2026-10-15 修）。**不要回落到 index.html** ——
  否则爆破者拿到 200 的 HTML，不易察觉也不利于排查。
- **管理后台登录接口有 `limit_req` 防爆破**（每 IP 10 次/分钟，突发 10，超限返回 JSON 429）。
  `limit_req_zone` / `limit_req_status` 属于 **http 上下文**，写在 `conf.d/*.conf` 顶部可以，
  但不能挪进 `server{}`，否则 nginx 起不来。

## 工具链的坑（Windows 侧）

- **PowerShell 5.1 会把 `-d '{"k":"v"}'` 里的双引号吃掉**，curl 发出去变成非法 JSON，
  后端回 `{"code":500,"msg":"系统繁忙，请稍后重试"}`，害我误判成应用 bug。
  → **给接口发 JSON body 一律写进文件 + `--data-binary @file`**，或者把测试脚本写成 `.sh`
  丢到服务器上用 bash 跑。**别在 PowerShell 命令行里拼 JSON。**
- **PowerShell 会把传给 `ssh` 的内层引号吃掉**（`grep -E ":(80|81)"` 到远端变成
  `unexpected token '('`）。→ 要跑的东西写成 `.sh`，`scp` 上去再 `ssh "bash /tmp/x.sh"`。
- **`Get-Content pubkey | ssh ... "cat >> authorized_keys"` 装公钥会失败**（Windows OpenSSH
  在 stdin 被管道占用时，密码提示读不到键盘输入）。→ 先交互式 ssh 进去，再用 `<<'KEY'` here-doc。
- **`nginx -t` 校验不能裸跑**：临时容器不在 compose 网络里会报
  `host not found in upstream "iblog-server"`，那是环境问题不是配置错。
  → `docker run --rm --network iblog_iblog-net -v <conf>:/etc/nginx/conf.d/default.conf:ro
  nginx:1.27-alpine nginx -t`。另外 `set -e` 管不到管道里非末位的命令，校验失败也不会中断。
