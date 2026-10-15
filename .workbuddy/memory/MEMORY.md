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

## 发布与备份（日常运维）

- **发布只能在本机跑 `deploy/release.sh`，绝不能在服务器上 `git pull`**：线上跑的是
  `deploy/dist/` 里的构建产物和容器镜像，而 `deploy/dist/` 在 `.gitignore` 里 ——
  服务器上既没有源码也没有产物，`git pull` 什么都拿不到。
  本项目真实发生过：改了登录页文案、提交并推送，线上依旧。
  ```bash
  # 本机（bash 不在 PATH 上，用 Git Bash 的绝对路径）
  & "D:\develop\Git\bin\bash.exe" deploy/release.sh [server|admin|web|db]
  ```
  需要 `IBLOG_SERVER=root@<IP>`，或写进 `deploy/.release.env`（已 gitignore）。
  本机构建工具齐备：node v24 / pnpm 11.5 / `JAVA_HOME=Zulu 21` / `gradlew` 可执行，
  Git Bash 在 **`D:\develop\Git\bin\bash.exe`（5.2.26）**（`Program Files` 下没有，别找错）。
  脚本只上传 `dist/<目标>`、`docker-compose.yml`、`backup-db.sh`、`server-init.sh`，
  **从不触碰服务器上的 `deploy/.env`**，自检里带一条回归守卫（80 端口碰
  `/api/admin/auth/login` 必须 404）。
- **备份已自动化**：root crontab 每天 03:00 跑 `backup-db.sh`，每周日 04:30 跑
  `backup-db.sh verify` —— 后者会把最新 dump **真的导入临时库 `iblog_verify`**，
  逐表比对行数，再删库。只备份不验证等于不知道备份能不能用。
  产出在 `/opt/backup/iblog/`（目录 700 / 文件 600，保留 14 天）：
  `iblog_<日期>.sql` + `env_<日期>.bak`(deploy/.env) + `docker-compose_<日期>.yml`。
  日志 `/var/log/iblog-backup.log`。
- ⚠️ **AL4 用 `logrotate.timer`（systemd timer）调度轮转，不是 `/etc/cron.daily/logrotate`**
  —— 后者在这台机器上不存在。别据此判断「轮转配置没生效」。
- ⚠️ 备份与数据库**在同一台机器的同一块盘**上，磁盘损坏、误删或整台 ECS 出问题会一起丢。
  **这一步用户 2026-10-15 主动决定「先不做，再想想」**，别擅自推进。已勘察清楚、随时可续：
  - ECS 在 **`cn-guangzhou`**（可用区 `cn-guangzhou-a`，实例 `i-7xv0nbx4kbazda5mew26`）；
    同地域可用**内网 endpoint** `oss-cn-guangzhou-internal.aliyuncs.com`（流量免费更快）
  - **未挂载实例 RAM 角色**（元数据 `/latest/meta-data/ram/security-credentials/` 返回 404）。
    ⚠️ 判断这个要用 HTTP 状态码，不能只看输出非空 —— 我当时就是把 404 的 HTML 当成了角色名
  - **`ossutil v1.7.18` 已装在 `/usr/local/bin/ossutil`**，未配置（1.7.19 的下载地址已 404，
    1.7.18 可用）。它的 `--mode` 实测支持 `AK|StsToken|RamRoleArn|EcsRamRole`，
    配 `--ecs-role-name` 即可走实例角色、**不落任何长期密钥**
  - 用户已选定用**实例 RAM 角色**方案（而非 AccessKey），只差去控制台建 bucket + 建角色 + 授予实例
  - 完整的可执行步骤已写进 `deploy/DEPLOY.md`「异地备份（尚未实施）」一节，续做时照抄即可
  - 替代方案：用户 known_hosts 里还有另外两台阿里云服务器（`101.200.235.184`、`8.134.55.236`），
    直接 `scp -r` 过去即可，零凭证零控制台操作；缺点是容灾等级低一档
- 恢复：`docker exec -i iblog-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" iblog' < <dump>`。
  密码由容器自己的环境变量提供，**宿主机上不需要也不应该知道明文密码**。
- mysqldump 会往 stderr 打一条「Using a password on the command line」告警，无害但会污染
  cron 日志。既要抓退出码又要让 stdout 干净地进文件的写法是 **`2>&1 >"$tmp"`（顺序不能反）**。

## SSH 访问（2026-10-15 起只允许密钥）

- **服务器 SSH 密码认证已关闭，只能用公钥登录。** 配置在
  `/etc/ssh/sshd_config.d/00-iblog-no-password.conf`：
  `PasswordAuthentication no` / `KbdInteractiveAuthentication no` /
  `PermitRootLogin prohibit-password` / `GSSAPIAuthentication no` / `GSSAPIKeyExchange no`。
  实测服务器对外声明的认证方式收窄为**仅 `publickey`**。
- **为什么放 drop-in 而不是改主配置**：`/etc/ssh/sshd_config` 第 15 行是
  `Include /etc/ssh/sshd_config.d/*.conf`，而 sshd 对多数指令取「**首个**获得的值」，
  所以 drop-in 先被读到 → 覆盖主配置第 133 行的 `PasswordAuthentication yes`。
  （这台机器的 `sshd_config.d/` 里原本只有 `40-redhat-crypto-policies.conf` 和
  `50-redhat.conf`，**都没有**设 PasswordAuthentication，所以没有云镜像覆盖的坑。）
- **核对生效值只认 `sshd -T`**，别只看配置文件：
  `sshd -T | grep -iE 'passwordauthentication|permitrootlogin|pubkeyauthentication'`。
  密码被拒时的报错也会列出剩余可用方式，看到只有 `(publickey)` 就说明彻底关干净了。
- **本机已装私钥，`ssh root@<IP>` 直接免密。换设备必须先把 `~/.ssh/id_ed25519` 拷过去**
  （PuTTY 可用 `id_ed25519.ppk`）。
- **万一私钥丢了**：SSH 进不去，只能用阿里云控制台的「远程连接（VNC）」或「云助手发送命令」，
  执行服务器上留着的 `/root/ssh-restore-password.sh`（它会删掉 drop-in 并重载 sshd）。
- 22 端口现在**可以放宽来源**了：之前为防爆破只能限单个 IP，而宽带 IP 是动态的，IP 一变就连不上；
  现在没有密码可爆破，`0.0.0.0/0` 也无实质风险。

### 可复用的安全改动模式（改 SSH 这类「错了就失联」的配置时）

1. **先把自动回滚挂上，再动手**（顺序绝对不能反）。这台机器没有 `at`，用 systemd 的：
   `systemd-run --on-active=20min --unit=<名字> <回滚脚本>`
   回滚脚本要能自己 `sshd -t` 校验后 reload/restart，并写一条日志便于事后确认是否触发过。
2. 改完先 `sshd -t` 校验语法，**不通过就撤销、不 reload**。
3. 用 `systemctl reload`（不断开现有连接），不要 `restart`。
4. **必须用「新连接」验证**：一条测密钥仍可登录，另一条 `-o PubkeyAuthentication=no
   -o PreferredAuthentications=password` 测密码确实被拒。
5. **两项都过了再取消定时器**，并确认回滚日志没被写入（= 从未触发）。

## HTTPS / 证书（2026-10-15 勘察完，用户选择「先不做」）

- **`https://<服务器IP>/` 现在是可行的、且不需要域名和 ICP 备案**：Let's Encrypt 自
  **2026-01-15** 起对纯 IP 地址签发公信证书（`--preferred-profile shortlived --ip-address <IP>`，
  Certbot ≥5.4；`--ip-address` 是 5.3 引入、5.4 才支持 webroot）。
- **硬约束：IP 证书有效期只有 6 天**（160 小时），是 LE 的强制要求，不能选长的。
  → 新增一个故障模式：续期静默失败后**一周内**站点就变浏览器警告状态。评估时务必把这个讲清楚。
- 这台机器已勘察的前提：**LE 可达（`acme-v02.api.letsencrypt.org` HTTP 200 / 2.5s）**、
  pip 23.3.1 可用、**AL4 仓库里没有 certbot**（要 venv + pip 装）、443 空闲、
  80 由 iblog-web 占用且对外全开（HTTP-01 走 80）、**容器无 bind mount**（要新增 webroot 挂载）。
- **IP 证书没有主机名 → 无法用 SNI 在同一端口分站点**。所以只能「官网 443 + 后台 :81 带 TLS」，
  不能都挂 443。
- Certbot 的限制：IP 证书只支持 `webroot`/`manual`/`standalone`（**nginx/apache 插件不支持**），
  且**不负责安装**，必须自己写 nginx 配置 + `--deploy-hook` 重载。
  `--standalone` 要临时占 80（每次续期都得停官网容器），所以选 `webroot`。
- 另一条路是域名 + 90 天证书（容错大得多），但国内服务器用域名对外提供网站服务**要 ICP 备案**。
  纯 IP 访问目前不需要备案（现有 http://8.138.105.59/ 就是实证）。
- 完整方案已写进 `deploy/DEPLOY.md`「三、4. 不用域名也能上 HTTPS：IP 地址证书」。
- **用户明确说「先不做」**，别擅自推进。
  → **2026-10-15 后来改主意了，IP 证书方案已完整实施上线**，见下。

### HTTPS（IP 证书）已实施上线（2026-10-15）

- **线上地址**：官网 `https://<IP>/`（80 端口 301 跳过来）、管理后台 `https://<IP>:81/`。
  外网实测 `curl -w '%{ssl_verify_result}'` 全为 **0**（证书被信任，不是 `-k` 跳过）。
- **证书**：`/etc/letsencrypt/live/iblog/`。用 `--cert-name iblog` 而非 IP 当名字 ——
  **故意避免服务器 IP 出现在公开仓库的 nginx 配置里**。
- **certbot**：装在独立 venv `/opt/certbot`（版本 5.8.0）。AL4 仓库没有 certbot，必须 pip 装。
  `--ip-address` 是 5.3 引入、**webroot 支持 IP 需要 5.4+**；`certonly --help` 默认只显示参数子集，
  要看 `--help all` 才 grep 得到（我第一次就因此误判成"不支持"）。
- **校验方式**：`webroot`，目录 `/opt/iblog/deploy/acme`，挂进 `iblog-web`
  （`./acme/.well-known/acme-challenge:/usr/share/nginx/html/.well-known/acme-challenge:ro`）。
  IP 证书**只支持 webroot/manual/standalone**，nginx/apache 插件不支持；`--standalone`
  每次续期都要停官网容器，所以选 webroot。
- **证书目录必须整目录挂**（`/etc/letsencrypt:/etc/letsencrypt:ro`）：`live/` 下全是软链
  指向 `../../archive/…`，只挂 `live/` 会断链导致 nginx 起不来。
- **自动续期**：crontab 每天 03:20 / 15:20 跑 `certbot renew --quiet
  --deploy-hook /opt/iblog/deploy/reload-tls.sh`；`reload-tls.sh` 会 `docker exec nginx -s reload`
  两个容器（Certbot 不负责安装 IP 证书，不重载 nginx 就一直在用旧证书）。
  `check-cert.sh` 每天 03:40 自检，剩余 <48h 在日志里打醒目告警。日志 `/var/log/iblog-certbot.log`
  （logrotate 每周，保留 8 份）。
- **三个刻意决定，改动前须理解**：
  1. **不开 HSTS** —— 6 天期证书一旦续期失败，HSTS 会把浏览器钉死在 https 上，
     用户连「继续访问」都点不了，站点彻底不可达。
  2. **管理后台改成 HTTPS-only**（容器内监听 443，宿主机仍 81）—— 81 上 TLS 的意义就是
     让登录密码不再明文；老书签 `http://<IP>:81` 因此失效。
  3. **80 端口不能关也不能整体跳转** —— 续期靠 80 做 HTTP-01。nginx 里有一条
     `location ^~ /.well-known/acme-challenge/` 例外，优先于 `return 301`，且文件不存在直接 404
     （不再回落到 index.html —— 回落会返回 200+HTML，让 ACME 报「内容不对」，更难排查）。
- **实施时的安全顺序（值得复用）**：443 还没在安全组放行前，**不要**先做 80→443 跳转，
  否则会出现「80 跳到一个被墙的 443」导致站点外网不可达。分两阶段：先 80 照常服务 +
  新增 443，验证外网 443 通（`ssl_verify_result=0`）后再把 80 改成跳转。
- **验证方式**：强制真续期一次（`certbot renew --force-renewal`），**磁盘证书序列号与两个容器
  实际吐出的序列号一致** → 证明「ACME 签发 → deploy-hook → nginx 重载」整条链路通。
  这比只跑 `--dry-run` 有力得多。
- 新脚本 `deploy/reload-tls.sh`、`deploy/check-cert.sh` 已加入 `release.sh` 的同步清单。

### 域名 + 备案（2026-10-15 查清，用户也选择先不做）

- **资格**：阿里云要求备案实例**包年包月且剩余 ≥ 3 个月**（按量付费拿不到备案服务号）。
  这台 ECS **用户已确认是包年** → 资格没问题。
- **域名**：必须在工信部许可的**国内注册商**注册。境外注册的域名不能直接备案，只能先转入国内。
  后缀必须在白名单：`.com`/`.cn`/`.net`/`.org`/`.top`/`.xyz`/`.vip`/`.shop`/`.site` 等在；
  **`.me`/`.io`/`.ai` 等 ccTLD 不在**，买了也备不了案（用户曾看中 `fzpeng.me`，此路不通）。
- **⚠️ 两个会卡住本项目的点（关键，别再重新调研）**：
  1. **个人备案的网站名称不能含「博客」**。禁用词：博客、论坛、在线、社区、交流、个人空间、
     爱好者、工作室、平台、热线、社团、网络、网站、网址、主页、资讯、作品展示 等；各省另有补充。
     且要求 ≥3 个汉字、不能是人物名/地名/成语、不能用纯数字/英文/域名。
  2. **个人备案不支持「评论、登录等交互功能」**（也不支持论坛性质/经营性/搜索类网站）。
     iblog **有前台评论提交 + 后台审核**，属高风险项 —— 备案期间要关掉前台评论入口
     （站点配置里有 `comment_enabled`）。
- 其他负担：备案期间域名**不能解析**（1~2 周只能用 IP）；阿里云初审 1~2 天 + 管局 1~2 周；
  ICP 通过后 **30 日内还要做公安备案**；按**身份证所在省**提交。
- **教训：域名价格要看「续费价」而不是首年价。** 阿里云「组合购专享」页上的
  `.com ¥76 起` 其实比新用户首年促销（¥30~50）还贵；`.xyz`/`.online`/`.me` 首年 ¥1
  但续费 ¥120~135/年，3 年总成本（¥241~271）**高于 `.cn`（≈¥150）和 `.com`（≈¥200）**。
  卡片上的「官网折扣价」就是续费价。另外 `.xin` 是"¥0 首年但强制 2 年起购"。
- 阿里云「域名 + 万小智 AI 建站」这类**套餐对项目无用**：本项目已有自建 Docker 部署，
  AI 建站是另一套 SaaS，不能托管 iblog；且其"中国内地节点"同样需要备案。
- 结论：**只为 HTTPS 的话 IP 证书划算得多**；域名路线的价值在好记的地址 + 子域名 + 90 天证书。

## 系统安全更新（2026-10-15 打过一次）

- 登录横幅报「50 Security notice(s) / 19 Important」，实际 `dnf updateinfo list --security`
  是 **156 行**（行数 = 公告×包，与「公告数」不是一回事，别混淆）。
  当时事务是 **98 个包升级 + 3 个新装、203MB**，内含 `kernel`、`containerd`(Important)、
  `docker`、`openssh`、`curl`/`gnutls`。打完 `待打安全更新行数 = 0`。
- **打补丁必须把 dnf 放进分离进程**：`setsid nohup bash -c "dnf -y upgrade-minimal --security
  > $LOG 2>&1; echo UPGRADE_EXIT=\$? >> $LOG" &`，然后轮询日志。
  原因：升级 openssh/docker/containerd 时 scriptlet 会重启服务，若 dnf 挂在 SSH 会话上，
  SIGHUP 可能**打断事务留下半升级状态**。
- 用 `upgrade-minimal --security` 而非全量 `upgrade`，只取修复安全问题的最小版本。
  打 `kernel` 后必须重启才生效 —— **`needs-restarting -r` 说「不需要重启」指的是当前状态，
  别被误导**（那次它确实说不需要，但打完内核就必须重启）。
- 那次内核从 `6.6.102-5.3.4` → **`-7`**（不是横幅里的 -8；`upgrade-minimal` 只取最小修复版本）。
- **重启自愈已实测（86 天来第一次重启）**：30 秒恢复 SSH、42 秒后端就绪、6 项 HTTP 自检全中、
  五容器全自动回来、swap/journald 限流/SSH 加固 drop-in 全部保留。
  依赖链：容器 `restart: always` + docker `enabled` + containerd 由 docker 的 `Wants` 拉起 +
  swap 在 `/etc/fstab` + sshd/crond `enabled`。
- ⚠️ **`depends_on: service_healthy` 只在 compose 启动时生效，Docker 守护进程重启后不保证顺序。**
  开机瞬间 `iblog-server` 可能先于 MySQL 就绪而失败，靠 `restart: always` 重试自愈 —— 正常现象。
- 打完后可 `docker image prune -f` 清清重建留下的悬空镜像（那次显示已无悬空镜像）。

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
