# 部署到阿里云服务器

本文以 **ECS + Docker Compose** 为例，一次拉起 MySQL、Redis、后端、管理后台、官网五个容器。

---

## 一、服务器准备

### 1. 配置建议

| 项目 | 最低 | 推荐 |
| --- | --- | --- |
| CPU / 内存 | 2 核 2G | 2 核 4G |
| 系统盘 | 40G | 60G |
| 系统 | **Alibaba Cloud Linux 4 LTS**（推荐）/ Ubuntu 22.04 | 同左 |

> 2G 内存如果想同时跑 MySQL + Redis + Java，建议先给服务器加 2G Swap，或者把数据库换成阿里云 RDS。
> 注意：`docker compose up --build` 会在容器里编译 Gradle 和 pnpm，2G 机器构建期容易 OOM，**生产建议 4G 起步**，或本地构建好镜像再 push。

### 2. 安装 Docker

**先把 AL4 的坑说清楚**：Alibaba Cloud Linux 4 基于龙蜥 Anolis OS 23，官方定位**不再是 CentOS 兼容替代**
（这点和 AL3 不同），`$releasever` 是 `alnx4` 而不是 `9`，内核 6.6。照抄网上给 CentOS/RHEL 的 Docker 官方源，
轻则找不到包，重则因 `container-selinux` 之类依赖装到一半失败。

**AL4 自己的仓库里 Docker 相关包是全的，引擎和 Compose 都不用出仓库。** 下面是真机 `dnf list` 实测结果：

| 包 | AL4 仓库里的版本 | 说明 |
| --- | --- | --- |
| `moby` / `moby-engine` / `moby-client` | `3:29.5.1-2.alnx4` | 新引擎（dockerd + docker CLI） |
| `docker` / `docker-client` | `3:24.0.9-9.alnx4` | 旧引擎，有些镜像预装的就是这个 |
| `containerd` | `1.7.34-2.alnx4` | |
| `docker-compose-plugin` | `2.26.1-1.alnx4` | **`docker compose` 就是它，13MB** |

```bash
sudo dnf -y install moby                    # 引擎；已经装了 docker 的机器跳过
sudo dnf -y install docker-compose-plugin   # compose v2 插件
sudo systemctl enable --now docker
docker --version && docker compose version
```

> `docker` 和 `moby` 在仓库里并存、提供同一套命令，**二选一即可**，没必要都装。
>
> ⚠️ 本文件早前版本写过「AL4 仓库不带 compose，要从 docker-ce 的 RPM 里 `rpm2cpio` 抠静态二进制」——
> **那是错的**，AL4 仓库里就有 `docker-compose-plugin`，直接 `dnf install` 即可。
> 抠 RPM 那条路只在仓库里真的没有时才需要（`server-init.sh` 里留作兜底）。
>
> **上面两步，加上 2G Swap、镜像加速器、容器日志轮转，仓库里已封装好，一条命令搞定**：
> `sudo bash deploy/server-init.sh`

Ubuntu：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

**配镜像加速器**：本项目要拉 `mysql:8.0`、`redis:7-alpine`、`eclipse-temurin:17-jre`、`nginx:1.27-alpine`
四个镜像。**在阿里云 ECS 上实测 Docker Hub 直连是被重置的**：

```
Error response from daemon: Get "https://registry-1.docker.io/v2/":
  read tcp ...:443: read: connection reset by peer
```

> ⚠️ 不少老教程还在写 `docker.mirrors.ustc.edu.cn` 和 `hub-mirror.c.163.com`，
> **这两个都已停服**（中科大 2024-06-06 起关闭、网易公共加速早已停用），留着只会拖慢甚至卡死拉取。

所以 `registry-mirrors` 必须配。**先看服务器上是不是已经配好了**：

```bash
docker info | grep -A5 'Registry Mirrors'
```

已经有能用的源就别动。要新配的话，阿里云对**同账号 ECS** 提供专属加速地址，走内网、最稳 ——
控制台 → 容器镜像服务 ACR → 镜像工具 → **镜像加速器**，复制形如 `https://xxxx.mirror.aliyuncs.com` 的地址。

> `server-init.sh` 是**合并**写入而不是覆盖：它用 python3 读现有的 `daemon.json`，
> 把你原有的 `registry-mirrors` 原样保留，只追加日志轮转配置，并在动手前留备份。
> 第三方公共加速源（daocloud、1ms.run 等）能用但不是生产首选 —— 你的拉取请求会经过第三方。

```bash
# 手工配的话（注意别把已有的 registry-mirrors 覆盖掉）
sudo cp -a /etc/docker/daemon.json /etc/docker/daemon.json.bak
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": ["https://把这里换成你的专属加速地址"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
sudo systemctl restart docker
docker info | grep -A5 'Registry Mirrors'
```

> `log-opts` 是给小磁盘兜底的：不加的话容器日志会一直涨，40G 系统盘迟早被吃满。

### 3. 安全组放行

在 ECS 控制台 → 安全组 → 入方向，放行这四条：

| 端口 | 用途 | 当前线上实际配置（2026-10-17 起） |
| --- | --- | --- |
| 22 | SSH | `0.0.0.0/0`（密码登录已关闭，仅密钥，见「六、SSH 登录」） |
| 80 | 官网（现在只做 ACME 校验 + 301 跳 443） | `0.0.0.0/0`，**不能关**（见下） |
| 443 | 官网 HTTPS | `0.0.0.0/0` |
| 81 | 管理后台（HTTPS） | `0.0.0.0/0`（2026-10-17 由单 IP 改为全网，原因见下） |

> ⚠️ **为什么 22 / 81 改成了 `0.0.0.0/0`（2026-10-17 事故复盘）**
>
> 原先 22 和 81 都只放行 `14.145.59.108/32`（公司出口 IP）。搬到宿舍后宽带 IP 变成
> `183.12.135.215`，两条规则同时失效，表现为**管理后台打不开、SSH 也连不上**。
> 排查时容易误判成容器挂了，实际判别方法：
>
> - **连接超时（丢弃）** = 安全组没放行；**connection refused** = 服务/sshd 本身的问题
> - 容器侧佐证：`docker ps` 里 `iblog-admin` Up、`ss -lntp | grep ':81 '` 有 docker-proxy 监听、
>   服务器本机 `curl -sk https://127.0.0.1:81/dashboard` 返回 **200** —— 三者都正常就说明是网络层
>
> 动态 IP 下固定 `/32` 没法长期使用，因此放宽为 `0.0.0.0/0`。安全性由应用层兜底：
> 22 端口 `PasswordAuthentication no` + `PermitRootLogin prohibit-password`（无密码可爆破）；
> 81 端口有 JWT 认证、登录接口 `limit_req`，且 80 端口的 `/api/admin/**` 已 `return 404`。
>
> 想收窄回单 IP 的话：把这两条的**访问来源**改回 `x.x.x.x/32` 即可，多个 IP 可以建多条规则并存，
> 安全组是「取并集」而不是覆盖。新增规则只用「手动添加」，**不要动其它已有规则**。
>
> 更稳的替代方案（IP 频繁变化时推荐）：81 继续维持单 IP 或干脆不放行，本地走 SSH 隧道
> （和下面 8080 的做法完全一致）：
>
> ```bash
> ssh -N -L 8181:127.0.0.1:81 root@8.138.105.59   # 然后本机开 https://localhost:8181/dashboard
> ```
>
> 注意浏览器会提示证书不受信任（证书签给 IP 而非 `localhost`），点继续即可；隧道窗口不能关。

> ⚠️ **80 端口即使已经全站跳 https 了也不能关**：Let's Encrypt 的 IP 证书只有 6 天有效期，
> certbot 每 6 天都要靠 80 端口做一次 HTTP-01 校验。关了 80，续期就会失败，
> 最多 6 天后站点变成浏览器报警告状态。详见「三、4」。

**8080 不要放行。** 两个前端容器是通过 Docker 网络里的服务名 `iblog-server:8080` 反代后端的，
宿主机上的 8080 映射只是为了让你能直接开 Swagger —— 要看接口文档用 SSH 隧道就够：

```bash
ssh -L 8080:127.0.0.1:8080 root@<服务器IP>   # 然后本机开 http://localhost:8080/swagger-ui.html
```

> ⚠️ **添加规则请用「手动添加」或「快速添加」，不要走任何会替换整个规则列表的入口。**
> 本项目的部署过程中真的发生过一次：加 80/81 时把 SSH(22) 规则一起弄丢了，
> 结果 22 端口被静默丢弃、彻底进不去。判断方法 —— `22` 若返回 **连接超时（丢弃）** 就是
> 安全组没放行；若是 **connection refused** 才是 sshd 本身的问题。

> ℹ️ **80 端口为什么碰不到管理后台接口？** `iblog-web/nginx.conf` 只把 `/api/portal/`
> 反代给后端，其余 `/api/**` 一律 `return 404`。否则 `/api/admin/**`（含登录接口）会从
> 开放的 80 端口对全网暴露，那么把 81 限制到单个 IP 就白做了 —— 本仓库曾有这个问题，已修。
> 管理后台的 nginx 另外给登录接口加了 `limit_req`（每 IP 10 次/分钟，突发 10），防止被爆破。
> 这也是 2026-10-17 敢把 81 放开到 `0.0.0.0/0` 的前提：后台入口只有 81 一个，80 上打不到。

> 443 端口留给你配置 HTTPS 证书时使用。

### 4. 服务器上已经有别的应用时（端口 / 内存冲突）

**先别急着 `up`，先看这两件事**，否则一定失败或把现有服务搞慢：

```bash
# a) 80 / 81 / 8080 是不是已经被别人占了
ss -lntp | grep -E ':(80|81|8080)\b'

# b) 还有多少内存真的可用（看 available 那一列，别看 free）
free -h
```

**端口冲突**：iblog 默认要 **80（官网）、81（后台）、8080（后端）** 三个宿主机端口。
其中 80/81 是必须的；**8080 其实不是必须的** —— 两个前端容器是通过 Docker 网络里的服务名
`iblog-server:8080` 反代后端接口的，宿主机上的 8080 映射只是为了让你能直接开 Swagger 页面。
所以如果 8080 已被占用，最省事的做法是改 `deploy/.env`：

```ini
SERVER_PORT=18080     # 随便挑一个没被占用的
```

改完 `docker compose up -d` 重新拉起即可，官网和后台照常工作。

**内存冲突**：整套服务的运行期占用大致是

| 容器 | 内存 |
| --- | --- |
| MySQL 8（buffer pool 128M） | 400～500M |
| 后端 JVM（`-Xmx384m`） | 300～400M |
| Redis（`maxmemory 128mb`） | 30～50M |
| 两个 Nginx | ~20M |
| **合计** | **约 800M～1G** |

2G 的机器如果已经跑了别的 Java 应用（比如动辄 255M 的 Spring Boot），**两边加起来会顶到物理内存上限**，
只能靠 Swap 硬扛，MySQL 的 buffer pool 被换出去会让整站明显变慢。
这种时候要么把旧应用停掉，要么把它迁到别的机器，不建议硬挤。

> `server-init.sh` 只做加 Swap、装 Docker/Compose、写 `daemon.json`（**合并**，不覆盖你原有的
> `registry-mirrors`）这几件**纯新增**的事，不会去动已经在跑的应用。但 `daemon.json` 生效
> 需要重启 docker —— 脚本检测到已有运行中的容器时会跳过重启并提示你手工确认。

---

## 二、部署应用

### 1. 上传代码

**2 核 2G 走「预构建模式 B」**：本机先打好产物，服务器上只需要 `deploy/` 这一个目录，
**不需要** Gradle、pnpm、Node、JDK，整个目录只有 ~45MB：

```bash
# 本机执行（Windows 用 PowerShell 也一样，Win10+ 自带 ssh/scp）
bash deploy/build-local.sh        # 产出 deploy/dist/：jar + 两个前端静态产物 + 建库 SQL
ssh root@<服务器IP> "mkdir -p /opt/iblog"
scp -r ./deploy root@<服务器IP>:/opt/iblog/
```

> `deploy/dist/` 写在 `.gitignore` 里，所以 `git clone` 拿不到它 —— **预构建模式必须手工上传 `deploy/` 目录**。

若走源码构建（模式 A，需要 4G 内存或 2G+Swap），才需要把整个仓库传上去：

```bash
git clone <你的仓库地址> /opt/iblog
# 或
scp -r ./iblog root@<服务器IP>:/opt/
```

### 2. 修改配置

```bash
cd /opt/iblog/deploy
cp .env.example .env
vim .env
```

必改两项：

- `MYSQL_ROOT_PASSWORD`：数据库密码
- `IBLOG_JWT_SECRET`：JWT 密钥，**长度必须 ≥ 32 字符**，可用 `openssl rand -base64 48` 生成

可选（不写就用默认值）：

- `IBLOG_PAPER_ENABLED`：自动交易总开关，默认 `false`；打开后才允许自动选股与自动交易（手动买卖不受影响，见「二、7」）
- `IBLOG_COOKIE_DOMAIN` / `IBLOG_COOKIE_SECURE`：行情登录 Cookie 的域 / HTTPS 标记（见「二、6」）

**2 核 2G 建议整份直接生成**（顺手把构建模式切成 B，省掉「忘了注释掉模式 A」这个坑）：

```bash
cd /opt/iblog/deploy
cat > .env <<EOF
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 16)
IBLOG_JWT_SECRET=$(openssl rand -hex 32)
WEB_PORT=80
ADMIN_PORT=81
SERVER_PORT=8080
JAVA_OPTS=-Xms128m -Xmx384m -XX:+UseSerialGC
SERVER_BUILD_CONTEXT=./dist/server
SERVER_DOCKERFILE=Dockerfile
ADMIN_BUILD_CONTEXT=./dist/admin
ADMIN_DOCKERFILE=Dockerfile
WEB_BUILD_CONTEXT=./dist/web
WEB_DOCKERFILE=Dockerfile
DB_INIT_DIR=./dist/db
EOF
chmod 600 .env && cat .env        # 把生成的密码记下来
```

> `.env` 已在 `.gitignore` 里，不会被提交。`JAVA_OPTS` 用的是 SerialGC，比 G1 省内存。

### 3. 一键启动

```bash
cd /opt/iblog/deploy
sudo docker compose up -d --build
```

**模式 B 只做 `COPY`，几秒完成、几乎不吃内存**；模式 A 才会在容器里编译 Gradle + pnpm，
视网络情况约 5～15 分钟（且 2G 机器很容易 OOM）。

数据库**不需要手工初始化**：MySQL 容器第一次启动时会自动执行 `docker-entrypoint-initdb.d` 下的
`01-schema.sql`（建表）与 `02-data.sql`（种子数据）—— 模式 B 来自 `deploy/dist/db/`，
模式 A 来自 `iblog-server/src/main/resources/db/`，两者是同一份脚本，全项目只有一份建表 SQL。

> 生产环境后端的 `iblog.db.auto-init` 是 `false`，只做连接自检、不会去改库结构；
> 如果数据库是空的，后端会在启动日志里打印一段中文提示块而不是几百行堆栈。

> 依赖来源已固定为公网镜像：Gradle 发行包走阿里云 `mirrors.aliyun.com`，
> Maven 依赖走 `maven.aliyun.com/repository/public`，pnpm 走 `registry.npmmirror.com`。
> 在阿里云 ECS 上构建时这些源都是同地域就近访问，速度很快，不依赖任何公司内网服务。

查看状态与日志：

```bash
docker compose ps
docker compose logs -f iblog-server
```

### 4. 小内存服务器（2 核 2G）专项

2G 内存跑这套（MySQL + Redis + Java + 两个 Nginx）**运行期够用**（实测合计约 1G），真正的坑在
**构建阶段**：`--build` 要在容器里同时跑 Gradle 和 pnpm，很容易 OOM。

#### 首选：本地预构建（服务器完全不编译）

本机装好 JDK 17+ 和 pnpm，在仓库根目录执行：

```bash
bash deploy/build-local.sh
```

脚本会依次跑 `gradlew bootJar`、两个前端的 `pnpm build`，然后把 jar、静态产物、运行时 Dockerfile、
建库 SQL 组装到 `deploy/dist/`。接着把整个 `deploy` 目录传上去：

```bash
scp -r deploy root@<服务器IP>:/opt/iblog/
```

服务器上把 `deploy/.env` 的构建模式切到 B —— `.env.example` 里模式 B 的 7 行已写好，注释掉模式 A、放开模式 B 即可
（直接照抄「二、2」里那份整份生成的 `.env` 更省事，已经就是模式 B）：

```ini
SERVER_BUILD_CONTEXT=./dist/server
SERVER_DOCKERFILE=Dockerfile
ADMIN_BUILD_CONTEXT=./dist/admin
ADMIN_DOCKERFILE=Dockerfile
WEB_BUILD_CONTEXT=./dist/web
WEB_DOCKERFILE=Dockerfile
DB_INIT_DIR=./dist/db
```

然后照常启动。此时镜像构建只做 `COPY`，几秒完成、内存占用可忽略：

```bash
cd /opt/iblog/deploy
sudo docker compose up -d --build
```

> 这条路服务器上**不需要** Gradle、pnpm、Node、JDK，只需要 Docker；
> 也不用 Docker Desktop 或 ACR —— 本机只负责产出产物文件。

#### 备选：就在服务器上编译

不想来回传文件的话，给构建阶段兜底（Gradle + 两个 pnpm 并发）：

**① 先加 2G Swap（不加基本必崩）**

> 下面这段 `deploy/server-init.sh` 里已经做了（幂等，重跑无副作用）：
> `sudo bash deploy/server-init.sh`

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # 开机自动挂载
free -h                                                       # 确认 Swap 已生效
```

**② 串行构建，别让三个镜像同时编译**

```bash
cd /opt/iblog/deploy
sudo -E COMPOSE_PARALLEL_LIMIT=1 docker compose build   # 逐个构建
sudo docker compose up -d                               # 再统一启动
```

**③ 资源参数仓库里已按小内存配好，无需手动改**

| 项 | 值 | 位置 |
| --- | --- | --- |
| 后端 JVM 堆 | `-Xms128m -Xmx384m -XX:+UseSerialGC` | `deploy/.env` 的 `JAVA_OPTS` |
| MySQL | buffer pool 128M、关 performance_schema、连接数 50 | `docker-compose.yml` 的 mysql `command` |
| Redis | `--maxmemory 128mb --maxmemory-policy allkeys-lru` | `docker-compose.yml` 的 redis `command` |
| 构建期 Gradle | `-Xmx512m`、`-Dorg.gradle.parallel=false` | `iblog-server/Dockerfile` |

> 两种模式由 `.env` 里 `SERVER_BUILD_CONTEXT` / `ADMIN_BUILD_CONTEXT` / `WEB_BUILD_CONTEXT` /
> `DB_INIT_DIR` 等 7 个变量切换，`docker-compose.yml` 本身不用改。切换后记得加 `--build`
> （或先 `docker compose build`）让镜像重建。

### 5. 验证

```bash
curl http://localhost:8080/api/portal/site
```

返回 JSON 即后端正常。

浏览器访问：

- 官网：`http://<服务器IP>/`
- 管理后台：`http://<服务器IP>:81/`
- 接口文档：`http://<服务器IP>:8080/swagger-ui.html`

### 6. 行情登录门禁（Cookie / 一次性票据）

官网的「行情」只对**登录过管理后台**的浏览器开放：未登录时导航里不显示「行情」，
直接访问 `/stock` 会看到登录提示页；`/api/stock/**` 接口也会返回业务码 401
（HTTP 状态仍为 200，与后台接口的约定一致）。

后台登录接口在返回 JWT 的同时会下发一个 **HttpOnly Cookie**（默认名 `iblog_auth`），
官网靠它判断登录态，因此不需要在官网再登录一次。按线上两个站点的网址形态分三种情况：

| 网址形态 | 需要做什么 | 说明 |
| --- | --- | --- |
| 同一主机的不同端口（当前：官网 `https://<IP>/`、后台 `https://<IP>:81/`） | 不用配置 | 浏览器 Cookie 不区分端口，登录后台后刷新官网即可自动解锁 |
| 同一主域的不同子域（`www.example.com` + `admin.example.com`） | `.env` 设 `IBLOG_COOKIE_DOMAIN=example.com` 后重建后端 | HTTPS 下保持 `IBLOG_COOKIE_SECURE=true`；`Domain` 覆盖子域，Cookie 即可共享 |
| 完全不同的注册域名 | 配 `VITE_WEB_URL`，不用 Cookie 跨域 | 从后台顶部「行情」按钮进入：先取 120 秒、单次有效的 ticket，官网用票据换登录态，后台会话不受影响 |

> ⚠️ `Secure` Cookie 只在 HTTPS 页面写入。如果先用 http 登录后台、再切到 https，需要重新登录一次。
> 后台「退出登录」只注销**当前设备**的那一个令牌并清掉本机 Cookie：同一账号在手机、另一台电脑、
> 另一个浏览器上的会话都继续有效（每台设备一个独立令牌，存在 Redis `iblog:login:sessions:<userId>` 里）。
> 想让某账号的全部设备同时下线，删掉这个 Redis key 即可：`redis-cli DEL iblog:login:sessions:<userId>`。

跨域场景下，管理后台需要知道官网完整地址（相对路径 `/` 拼不出跨站链接）。构建后台时注入：

```bash
# 在仓库根目录；源码构建模式则在 pnpm build 前 export VITE_WEB_URL
VITE_WEB_URL=https://www.example.com bash deploy/build-local.sh
```

本地开发无需额外配置：dev 环境 Cookie 默认不要求 HTTPS，在 `localhost:5180` 登录后台后，
`localhost:5181` 的官网会直接识别（Cookie 按主机共享、不区分端口）。

自检：

```bash
# 未登录直接访问行情接口，应返回 401
curl -s "http://<服务器IP>/api/stock/quotes?codes=sh600000"
# 官网接口的登录态（浏览器登录后台后，带上 Cookie 请求）
curl -s http://<服务器IP>/api/portal/auth/state
```

**首次登录账号：`admin` / `admin123`，登录后请立即修改密码。**

### 7. 模拟盘（自动交易默认关闭）

官网 `/paper` 的模拟盘是**服务端记账**的 A 股模拟交易：不需要外部券商接口，行情来自现有的
腾讯 / 东财数据源，成交规则（T+1、按板块校验申报数量、涨跌停、费用、滑点）都在后端 `com.iblog.module.paper`。

默认 `IBLOG_PAPER_ENABLED=false`：手动模拟买卖可用，自动选股与自动交易不会跑。要打开自动交易：

```bash
# /opt/iblog/deploy/.env 追加
IBLOG_PAPER_ENABLED=true
# 可选：新建 / 重置账户的初始资金（默认 1000000）
IBLOG_PAPER_INITIAL_CASH=30000
# 然后重建后端容器
sudo docker compose up -d --build iblog-server
```

> 自动交易还有第二道开关：即使服务端打开了，也需要用户在 `/paper` 页面点「自动交易」按钮，
> 才会替该用户自动下单（默认关）。服务端总开关没开时，前端打开按钮会直接报错。
> 多账户：账户数量不限，每个账户独立持仓、资金和选股规则（`paper_strategy.params` 里
> 的 59 项参数（含换手率 / 流通市值 / 分时均价线 / 涨停记忆、首板 / 连板高度、近 N 日两年新高、缩量上涨、买入时间窗、次日开盘强制卖出，
> 以及「严格过滤（硬条件）」的 18 项一票否决条件：年内低点比 / 集合竞价换手率 / 早盘与全天实际换手率（除以自由流通股）/ 现价高于今开 / 现价高于均价 /
> 量比 / 5 日与 20 日均价与均量趋势 / 近 N 日无涨停 / 总市值下限 / 股东户数减少 / 二季报或预告或年报盈利 / 按资金剔除买不起的标的，
> 在页面「高级规则（选股打分）」里按账户保存），适合并行对比不同选股策略。
> 严格过滤默认全部关闭（值为 0 / false），不配置时行为与之前完全一致。
> 申报数量按板块校验：主板 / 创业板 100 股整数倍，科创板 200 股起、超出部分可 1 股递增，北交所 100 股起可零股。
> 规则模板：同 59 项参数可另存为多套命名模板（`paper_rule` 表），在任意账户上一键应用 / 切换。
> 换手率类的「自由流通股」取东财 F10 的 `FREELIQCI_SHARES`（不是流通股）；「集合竞价换手率」只统计 9:25 撮合量，
> 「早盘换手率」统计 9:25–9:40 累计量，两者独立，都按 0 默认关闭。
> 「重新选股」`POST /api/stock/paper/selections/refresh` 是同步接口，严格过滤全开一轮要 1~3 分钟：
> `iblog-web/nginx.conf` 给该路径单独配了 `proxy_read_timeout 300s`（其它行情接口仍是 20s），
> 前端 axios 对应 180s；改这两个值必须成对改，否则浏览器先收到 504，而后端其实还在跑并把候选写进了库。
> 东财资金流被限流（连续失败）后会熔断 60 秒，期间选股直接跳过资金流加分项，
> 不再每只票白等 5 次重试 —— 这是为了让一轮选股在限流时也能跑完，不拖成分钟级。

参数都在 `iblog.paper.*`（环境变量 `IBLOG_PAPER_*`，完整字段见 `IblogProperties.Paper`）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | false | 总开关 |
| `initial-cash` | 1000000 | 新建 / 重置账户的初始资金 |
| `tick-ms` | 15000 | 引擎撮合 / 自动交易间隔（毫秒）|
| `selection-refresh-ms` | 300000 | 自动选股最小间隔（毫秒）|
| `commission-rate` / `commission-min` | 0.00025 / 5 | 佣金费率与最低 5 元 |
| `stamp-tax-rate` | 0.0005 | 印花税（仅卖出）|
| `transfer-fee-rate` | 0.00001 | 过户费 |
| `slippage-bps` | 5 | 市价单滑点（万分之）|
| `max-position-pct` / `max-total-position-pct` | 0.2 / 0.8 | 单票 / 总仓上限 |
| `max-daily-loss-pct` | 0.03 | 日亏熔断（只减仓不加仓）|
| `max-orders-per-day` | 20 | 每账户每日委托上限 |
| `max-candidates` | 5 | 每日自动买入候选数 |
| `holiday-dates` | 空 | 额外休市日，逗号分隔 `2026-10-01,...` |
| `force-trading-hours` | false | 忽略交易时段限制（**仅本地调试用**）|

> 模拟盘数据在 MySQL 的 `paper_*` 表；已有库会在后端启动时自动执行 `db/05-migration-paper.sql` 补表，
> 再执行 `db/06-migration-paper-multi.sql` 把账户唯一键升级为「一用户多账户」，
> 最后执行 `db/07-migration-paper-rule.sql` 建规则模板表（均幂等，可重复启动）。
> 「重置账户」只清当前账户的持仓 / 委托 / 成交 / 净值，不影响其他账户。

### 8. 访问日志（默认开启，无需配置）

后台「访问日志」菜单记录官网 `/api/portal/**` 的每次调用——官网是 SPA，页面本身由
nginx 直接吐静态文件，但每次页面打开必然触发 `/api/portal/site` 等接口，所以在
API 层记录即等价于记录官网访问。数据在 `blog_visit_log` 表（`db/09-migration-visit-log.sql`
自动建表），每天凌晨 3:30 自动清理 30 天前的记录。后台/行情接口不记录（前者是博主
自己、后者是 15s 轮询，记了只有噪声）。

### 9. AI 分析 / 问财选股（需在后台填 API Key）

官网个股详情页的「AI 分析」抽屉需要一个 DeepSeek API Key，**只需要在后台配置一次，
不需要改环境变量、不需要重启容器**：

1. 后台左侧菜单「AI 配置」→ 填 `DeepSeek API Key`（`sk-...`）→ 保存；
2. 可选：改模型名（默认 `deepseek-v4-flash`）、关深度思考（更快更省）、开联网搜索；
3. 回到官网个股页点「AI 分析」，先发一句话验证能出字。

Key 存在 MySQL 的 `stock_setting` 表（`ai_api_key`，标记为 secret），**不会下发到浏览器**，
接口只回传打码值（`sk-1****cdef`）；页面把打码值原样提交回来时后端会跳过不覆盖。
浏览器只调本站的 `/api/stock/ai/chat`，Key 始终只出现在服务端到 DeepSeek 的那一跳。

> 为什么 AI Key 不放在 `deploy/.env` 环境变量里：环境变量会出现在 `docker inspect`
> 和进程环境里，任何能读容器的人都看得到；而且改一次要重启容器。放数据库可以在后台随时换。

对话历史存在 `stock_ai_chat_session` / `stock_ai_chat_message` 两张表（
`db/10-migration-ai-chat.sql`，后端启动时自动建表，幂等）。按「用户 + 股票」归档，
每个会话保留最近 200 条；面板右上角「清空」会真删该股票的会话与消息。

两个容易踩的点：

- **AI 对话是 SSE 长连接**，`iblog-web/nginx.conf` 里 `location = /api/stock/ai/chat`
  单独关掉了 `proxy_buffering` 并把超时放到 300s。如果把这条 location 删掉或合并回
  `/api/stock/`，对话会变成「憋一分钟后一次性出现」，或者直接 504。
- **问财选股依赖浏览器侧生成的 `v` Cookie**：官方接口在非问财域名、无 `v` 时一律
  nginx 403，而这个 `v` 只能由 `chameleon.js` 在真实浏览器里算出（服务端跑不出来，
  实测过纯 Node 沙箱、headless 都失败）。所以流程是「官网加载 `chameleon.js` 算 v →
  随请求发给后端 → 后端带齐反爬头转发」。`iblog-web/public/chameleon.js` 不要删，
  它是这个链路的前半段。一个 `v` 约 30 分钟有效且只能连发 6 次左右，后端识别到
  限流会回 429，前端据此换新 `v` 重试。

---

## 三、绑定域名 + HTTPS（可选）

> 上 HTTPS 有两条路，先看哪条适合你：
>
> - **有域名**：往下走（本节的 1～3）。国内服务器用域名对外提供网站服务**需要 ICP 备案**
>   （约 1～2 周），但证书是 90 天期、生态最成熟，官网和后台都能挂 443。
> - **不想备案、不买域名**：直接看 **「4. 不用域名也能上 HTTPS：IP 地址证书」**。
>   现在（2026 年起）可以让 `https://<服务器IP>/` 拿到公信证书、浏览器不报警告，
>   **代价是证书只有 6 天有效期**，必须靠可靠的自动续期撑着。

### 1. 域名解析

在阿里云 DNS 添加 A 记录指向 ECS 公网 IP：

| 记录 | 值 |
| --- | --- |
| `@` | 服务器 IP |
| `www` | 服务器 IP |
| `admin` | 服务器 IP |

### 2. 用宿主机 Nginx 反代（推荐做法）

在 80 端口被占用前，先改 `deploy/.env`：

```ini
WEB_PORT=8080
ADMIN_PORT=8081
```

然后宿主机安装 Nginx 并配置：

```nginx
server {
    listen 80;
    server_name www.example.com example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}

server {
    listen 80;
    server_name admin.example.com;

    # 后台再加一层访问控制更稳妥
    # auth_basic "Restricted";
    # auth_basic_user_file /etc/nginx/.htpasswd;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

### 3. 申请免费证书

阿里云「数字证书管理服务」可申请免费 DV 证书，下载 Nginx 版本后配置：

```nginx
server {
    listen 443 ssl;
    server_name www.example.com;

    ssl_certificate     /etc/nginx/cert/example.com.pem;
    ssl_certificate_key /etc/nginx/cert/example.com.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 4. 不用域名也能上 HTTPS：IP 地址证书（**已实施，2026-10-15**）

**`https://<服务器IP>/` 现在浏览器不会报警告**，而且不需要域名、不需要 ICP 备案。
Let's Encrypt 从 **2026-01-15** 起正式对纯 IP 地址签发公信证书
（[官方公告](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)；
[Certbot 支持说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html)）。

**现在线上的实际状态：**

| 项 | 值 |
| --- | --- |
| 地址 | 官网 **https://\<IP\>/**（80 会 301 跳过来）；管理后台 **https://\<IP\>:81/** |
| 证书 | `/etc/letsencrypt/live/iblog/`（`--cert-name iblog`，**故意不用 IP 当名字**，避免 IP 进公开仓库） |
| certbot | `/opt/certbot`（独立 venv，版本 5.8.0；AL4 仓库没有 certbot） |
| 校验方式 | `webroot`，目录 `/opt/iblog/deploy/acme`（挂进 `iblog-web` 容器） |
| 自动续期 | crontab 每天 **03:20 / 15:20** 各跑一次 `certbot renew` |
| 续期后重载 | `deploy/reload-tls.sh` 作为 `--deploy-hook`，`docker exec nginx -s reload` 两个容器 |
| 到期自检 | `deploy/check-cert.sh` 每天 03:40，剩余 < 48 小时会在日志里打醒目告警 |
| 日志 | `/var/log/iblog-certbot.log`（logrotate 每周轮转，保留 8 份） |

**几个刻意做的决定，改动前请先理解：**

1. **不开 HSTS。** 证书只有 6 天有效期，一旦续期失败，HSTS 会把浏览器钉死在 https 上，
   用户连「继续访问」都点不了，站点彻底不可达。等换成 90 天期的域名证书再考虑。
2. **管理后台改成 HTTPS-only**（容器内监听 443，宿主机仍是 81）。81 上 TLS 的全部意义
   就是让登录密码不再明文传输，所以没有保留 HTTP 入口 —— 老书签 `http://<IP>:81` 会失效。
3. **80 端口不能关、也不能整体跳转**：certbot 每 6 天续期都要靠 80 做 HTTP-01 校验。
   所以 nginx 里有一条 `location ^~ /.well-known/acme-challenge/` 的例外，
   它优先于 `return 301`，且文件不存在时直接 404（不再回落到 index.html —— 回落会返回
   200 + HTML，让 ACME 报「内容不对」，反而更难排查）。
4. **证书目录必须整目录挂进容器**（`/etc/letsencrypt:/etc/letsencrypt:ro`）：
   `live/` 下面全是软链指向 `../../archive/…`，只挂 `live/` 会断链导致 nginx 起不来。

**已做过的验证（不是靠推理）：**

- 两台容器分别在临时容器里跑过 `nginx -t` 才重建线上
- 强制真续期一次（`certbot renew --force-renewal`），**磁盘上的证书序列号与两个容器
  实际吐出的序列号完全一致** —— 证明「ACME 签发 → deploy-hook → nginx 重载」整条链路通
- 外网验证 `curl -w '%{ssl_verify_result}'` 全为 **0**（证书被信任，不是靠 `-k` 跳过）
- 外网验证 80 上的校验路径返回**精确内容且未被跳转**
- **2026-10-17 复验**：当前证书 `notAfter = Oct 22 01:14:20 2026 GMT`（剩余 4 天）时跑
  `certbot renew --dry-run`，返回 `all simulated renewals succeeded` —— 80 端口的
  HTTP-01 校验通道在生产配置下依然通畅，10/18 之后 cron 会自动续上。
  （本次只是演练，未签发真证书；磁盘证书仍是 10/15 那张。）

**日常排查：**

```bash
bash /opt/iblog/deploy/check-cert.sh     # 看剩余有效期（< 48h 会告警并返回非 0）
tail -50 /var/log/iblog-certbot.log      # 续期与自检的历史

# 万一续期失败，手工来一次（会真的重新签发）
/opt/certbot/bin/certbot renew --force-renewal --deploy-hook /opt/iblog/deploy/reload-tls.sh
```

> ⚠️ 已知限制（LE/Certbot 文档）：IP 证书只支持 `webroot`／`manual`／`standalone` 校验，
> **`nginx` 和 `apache` 插件不支持**；Certbot **不会**帮你把证书装进 Web 服务器，必须自己写
> nginx 配置和 `--deploy-hook`。`--standalone` 要临时占用 80（每次续期都得停官网容器），所以选 `webroot`。
>
> ⚠️ 续期依赖 crontab 里的 `certbot renew`。**这是整条链路上唯一的自动化依赖**：
> 它静默失败的话，最多 6 天后站点就会变成浏览器报警告状态。`check-cert.sh` 就是为这个准备的。

### 5. 走域名路线的话，备案有哪些坑

**资格**：阿里云要求用于备案的 ECS 是**包年包月且剩余时长 ≥ 3 个月**（按量付费拿不到备案服务号）。
本项目的 ECS 已确认是**包年**。

**域名**：必须在**工信部许可的国内注册商**注册。境外注册商注册的域名**不能直接备案**，
只能先转入国内注册商再办（且注册未满 60 天不能转移）。后缀还必须在备案白名单里 ——
`.com` / `.cn` / `.net` / `.org` / `.top` / `.xyz` / `.vip` / `.shop` / `.site` 等在白名单；
**`.me` / `.io` / `.ai` 等 ccTLD 不在**，买了也备不了案。

**⚠️ 两个会直接卡住本项目的点：**

1. **网站名称不能含「博客」。** 个人备案的网站名称禁用词包括：博客、论坛、在线、社区、交流、
   个人空间、爱好者、工作室、平台、热线、社团、网络、网站、网址、主页、资讯、作品展示 等，
   各省还有额外禁用词。同时要求 **≥3 个汉字**、不能是人物名称／地点名称／成语、
   不能用纯数字／英文／域名。所以「xxx 的博客」这类名字直接过不了。
2. **个人备案不支持「评论、登录等交互功能」**（同时还明确不支持论坛性质、经营性、搜索类网站）。
   本项目的 iblog **有前台评论提交 + 后台审核**，属于高风险项。备案期间应先把前台评论入口关掉
   （站点配置里本来就有 `comment_enabled` 开关），审核通过后再看情况恢复。

**其他负担**：备案期间域名**不能解析**（这 1～2 周仍只能用 IP 访问）；阿里云初审 1～2 天 +
管局审核约 1～2 周；ICP 通过后 **30 日内还要做公安备案**；备案按**身份证所在省**提交，
禁用词清单要看你那个省的。

**结论**：如果目的只是「浏览器不报不安全」，走上一节的 **IP 证书**成本低得多 ——
不用改网站名、不用关评论、不用等备案、不用花钱。域名路线的真正价值在于
「好记的地址 + 能分子域名（blog./admin. 各挂 443）+ 90 天证书」，要为此接受上述合规成本。
两条路不冲突：先上 IP 证书，以后备案通过拿到域名了，把 nginx 里的证书路径换掉即可。

---

## 四、日常运维

### 发布更新：在本机跑 `deploy/release.sh`

线上跑的是 `deploy/dist/` 里的构建产物和容器镜像，**不是 `src/`**。只改源码、只提交、
只推送，生产环境不会有任何变化。所以更新一律走这个脚本：

```bash
# 本机 Git Bash（bash 不在 PATH 上时用：
#   & "D:\develop\Git\bin\bash.exe" deploy/release.sh admin）
export IBLOG_SERVER=root@<服务器IP>       # 或写进 deploy/.release.env（已在 .gitignore）
bash deploy/release.sh                    # 全部：后端 + 管理后台 + 官网 + 建库 SQL
bash deploy/release.sh admin              # 只发管理后台（几秒）
bash deploy/release.sh web server         # 可给多个目标
bash deploy/release.sh --build-only       # 只在本机构建组装 dist，不上传
```

它会依次完成「构建 → 组装 `deploy/dist` → 上传 → 重建容器 → 自检」，
自检里带一条回归守卫：`http://<服务器>/api/admin/auth/login` 必须是 **404**
（即官网 80 端口碰不到管理后台接口，见「一、3」）。

> ⚠️ **不要用 `git pull` + `docker compose up -d --build` 更新线上。**
> `deploy/dist/` 在 `.gitignore` 里，服务器上既没有源码也没有构建产物，
> `git pull` 什么也拿不到 —— 这正是必须有 `release.sh` 的原因。
>
> ✅ `release.sh` **只上传 `dist/<目标>` 与 `docker-compose.yml`，永远不会碰服务器上的
> `deploy/.env`**（那里面有数据库密码和 JWT 密钥，被覆盖会导致整站起不来）。

### 服务器上的日常操作

```bash
cd /opt/iblog/deploy

# 查看运行状态
docker compose ps

# 查看某个服务日志
docker compose logs -f --tail=200 iblog-server

# 只重启后端
docker compose restart iblog-server

# 停止（保留数据卷）
docker compose down

# 停止并清空数据（慎用！）
docker compose down -v
```

### 数据备份

**已经自动配好了，不需要手工做。** 服务器上 `root` 的 crontab 有两条：

```cron
# 每天 03:00 全量备份
0 3 * * * /opt/iblog/deploy/backup-db.sh >> /var/log/iblog-backup.log 2>&1
# 每周日 04:30 把最新备份真的还原到临时库、逐表比对行数，然后删掉临时库
30 4 * * 0 /opt/iblog/deploy/backup-db.sh verify >> /var/log/iblog-backup.log 2>&1
```

脚本是 [`deploy/backup-db.sh`](backup-db.sh)，产出在 `/opt/backup/iblog/`（目录 700、文件 600）：

| 文件 | 说明 |
| --- | --- |
| `iblog_<日期>.sql` | 数据库全量（`--single-transaction`，不锁表） |
| `env_<日期>.bak` | `deploy/.env` —— 里面有 MySQL 密码和 JWT 密钥，**丢了就算有 sql 也还原不回去** |
| `docker-compose_<日期>.yml` | 编排文件，便于在全新机器上重建 |

保留 **14 天**，超期自动删除；`/var/log/iblog-backup.log` 由 logrotate 每周轮转、保留 8 份。

常用操作：

```bash
bash /opt/iblog/deploy/backup-db.sh          # 立刻备份一次
bash /opt/iblog/deploy/backup-db.sh verify   # 验证最新备份能否真正还原（强烈建议定期做）
bash /opt/iblog/deploy/backup-db.sh list     # 看现有备份
tail -50 /var/log/iblog-backup.log           # 看历史（备份失败会记在这里）
```

> ⚠️ **只备份不验证，等于不知道备份能不能用。** 所以 `verify` 会把 dump 真的导入一个临时库
> `iblog_verify`，逐表比对行数，再删库 —— 这才是「备份有效」的证据。
>
> ⚠️ **备份和数据库在同一台机器的同一块盘上**，磁盘损坏、误删或整台 ECS 出问题，数据和备份会一起丢。
> 这一步**尚未做**，属于当前已知的最大缺口。

#### 异地备份（尚未实施，以下是已验证的做法）

已勘察清楚的现状（2026-10-15）：ECS 在 **`cn-guangzhou`**（可用区 `cn-guangzhou-a`，实例
`i-7xv0nbx4kbazda5mew26`），**未挂载实例 RAM 角色**；服务器上 `ossutil v1.7.18` 已安装但未配置。

推荐做法是**实例 RAM 角色**：ossutil 通过 ECS 元数据服务自动换取小时级轮换的临时凭证，
服务器磁盘和 crontab 里都不会出现 AccessKey。

1. OSS 控制台建 bucket，地域选**华南1（广州）**，读写权限**私有**。
   同地域可以用内网 endpoint `oss-cn-guangzhou-internal.aliyuncs.com` —— 流量免费且更快。
2. RAM 控制台建权限策略 `iblog-oss-backup`（把 `<bucket名>` 换掉）：
   ```json
   {
     "Version": "1",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["oss:PutObject","oss:GetObject","oss:ListObjects","oss:ListParts","oss:AbortMultipartUpload"],
       "Resource": ["acs:oss:*:*:<bucket名>", "acs:oss:*:*:<bucket名>/*"]
     }]
   }
   ```
3. RAM 控制台建房角色，可信实体选「阿里云服务 → ECS 云服务器」，授权上面的策略。
4. ECS 控制台 → 实例 → 更多 → 实例设置 → **授予/收回 RAM 角色**。**不需要重启实例。**
5. 服务器上配置并加法：
   ```bash
   ossutil config --mode EcsRamRole --ecs-role-name iblog-oss-backup-role \
     -e oss-cn-guangzhou-internal.aliyuncs.com -c /root/.ossutilconfig
   ossutil -c /root/.ossutilconfig cp -r -f /opt/backup/iblog/ oss://<bucket名>/iblog-backup/   # 先手工验一次
   # 验证通过后加进 crontab：
   # 0 4 * * * ossutil -c /root/.ossutilconfig cp -r -f /opt/backup/iblog/ oss://<bucket名>/iblog-backup/ >> /var/log/iblog-backup.log 2>&1
   ```
   > `--mode` 的取值在 ossutil 1.7.18 的 help 里确认过是 `AK|StsToken|RamRoleArn|EcsRamRole`。

**更省事的替代方案**：如果手上还有另一台长期在线的服务器，直接
`scp -r /opt/backup/iblog/ root@<另一台>:/opt/iblog-backup/` 就行 —— 零凭证、零控制台操作，
今天就能做。缺点是同账号同地域的话容灾等级低一档，且需要在那台机器上留公钥。

#### 真出事时怎么恢复

```bash
cd /opt/iblog/deploy

# 1) 停掉后端，避免恢复过程中有人写入
docker compose stop iblog-server

# 2) 导入备份（注意 -i，且密码用容器自己的环境变量，宿主机上不需要知道密码）
docker exec -i iblog-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" iblog' < /opt/backup/iblog/iblog_2026-10-15.sql

# 3) 拉起后端
docker compose start iblog-server
docker compose logs --tail=20 iblog-server
```

> 上面这条导入命令是**覆盖式**的：备份里是 `DROP TABLE IF EXISTS` + `CREATE TABLE`，会重建表。
> 想先确认备份内容再动手，可以先按 `backup-db.sh verify` 的做法导进一个临时库看一眼。
>
> 如果连 `.env` 也没了，从 `env_<日期>.bak` 恢复回去（`cp` 后 `docker compose up -d` 重建容器），
> 否则数据库密码和 JWT 密钥对不上，整站起不来。

### SSH 登录：只允许密钥（2026-10-15 已改）

服务器的 SSH **密码认证已经关闭**，只能用公钥登录。配置在
`/etc/ssh/sshd_config.d/00-iblog-no-password.conf`：

```
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
GSSAPIAuthentication no
GSSAPIKeyExchange no
```

> 放 `sshd_config.d/` 而不是改主配置，是因为 `sshd_config` 第 15 行有
> `Include /etc/ssh/sshd_config.d/*.conf`，而 sshd 对多数指令取「**首个**获得的值」——
> drop-in 会先被读到，从而覆盖主配置里的 `PasswordAuthentication yes`。
> 这样也好回滚，且不会被系统升级覆盖。

生效值可以随时用 **`sshd -T`** 核对（这是唯一权威，别只看配置文件）：

```bash
sshd -T | grep -iE 'passwordauthentication|permitrootlogin|pubkeyauthentication'
# 期望：passwordauthentication no / permitrootlogin without-password
```

**几个必须知道的后果：**

1. **密码从此不能用于 SSH 了。** 在你自己电脑上 `ssh root@<IP>` 会直接用 `~/.ssh/id_ed25519`
   免密登录；如果换一台设备，必须先把私钥拷过去（或用 PuTTY 加载 `id_ed25519.ppk`）。
2. **万一私钥丢了**，SSH 进不去 —— 只能用阿里云控制台的
   **实例 → 远程连接（VNC）** 或 **云助手发送命令** 把
   `/etc/ssh/sshd_config.d/00-iblog-no-password.conf` 删掉后 `systemctl reload sshd`。
   （服务器上留了现成的回滚脚本 `/root/ssh-restore-password.sh`，在控制台里直接执行它即可。）
   也可以按下面「给新电脑加一把密钥」的办法，用控制台把新公钥贴进去，**不用**重新打开密码登录。
3. **22 端口现在可以放心放宽来源了。** 之前为了防爆破只能限单个 IP，而宽带 IP 是动态的，
   IP 一变就连不上；现在没有密码可爆破，安全组里 22 的授权对象改成 `0.0.0.0/0` 也不会有
   实质风险（仍然建议只在需要时放宽）。

#### 给新电脑加一把密钥（换设备 / 私钥丢失时）

**前提：22 端口的安全组已经放行并能到达服务器；贴公钥这一步走控制台，不依赖 SSH。**

**① 在本机生成密钥对**（PowerShell）：

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\id_ed25519" -C "你的机器名-iblog"
```

生成两个文件（Windows 上默认就在 `C:\Users\<你>\.ssh\`）：

| 文件 | 用途 | 处置 |
| --- | --- | --- |
| `id_ed25519` | **私钥** | 只留在本机，绝不外传/不进仓库，**务必备份** |
| `id_ed25519.pub` | 公钥 | 贴到服务器上 |

取出公钥内容：

```powershell
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub"
```

**② 把公钥写到服务器**（二选一，都不需要密码登录）：

- **控制台 VNC**：实例 → 远程连接 → VNC 登录，执行下面三行（把公钥整行粘进去）：

  ```bash
  mkdir -p ~/.ssh && chmod 700 ~/.ssh
  echo 'ssh-ed25519 AAAA...你的公钥... 机器名-iblog' >> ~/.ssh/authorized_keys
  chmod 600 ~/.ssh/authorized_keys
  ```

- **云助手发送命令**：实例 → 发送远程命令 → Shell，粘贴同样三行，免得手打长公钥。

> `>>` 是**追加**，不会覆盖已有密钥——公司那台电脑的旧密钥可以继续用。
> 权限必须是 `~/.ssh` = `700`、`authorized_keys` = `600`，`sshd` 对组/其他可读写是直接拒绝的。

**③ 本机验证**：

```powershell
ssh -o BatchMode=yes root@8.138.105.59 "hostname && docker ps --format '{{.Names}}\t{{.Status}}'"
```

看到主机名和容器列表就成功了。用 `sshd -T | grep passwordauthentication` 确认仍是 `no`。

> ⚠️ **常见坑：在服务器的 VNC 终端里执行 `ssh root@8.138.105.59` 会报
> `Permission denied (publickey)`。** 那是服务器在连它自己 —— 它上面只有公钥没有私钥，
> 必然失败。隧道和登录都要在**你自己的电脑**上执行。

### 证书续期自检（2026-10-17 已演练验证）

IP 证书只有 6 天有效期，靠 root 的 crontab 自动续期（`crond` 必须 active）：

```bash
crontab -l | grep -iE 'cert|acme'
# 20 3,15 * * *  /opt/certbot/bin/certbot renew --quiet --deploy-hook /opt/iblog/deploy/reload-tls.sh
# 40 3 * * *     /opt/iblog/deploy/check-cert.sh
```

**不确定续期是否还能跑时，做一次无害演练**（不签发真证书，只走 ACME 校验）：

```bash
/opt/certbot/bin/certbot renew --dry-run
# 期望：Congratulations, all simulated renewals succeeded
```

演练只验证「能签发」，**签发后还得让容器重载证书** —— 这由 `--deploy-hook
/opt/iblog/deploy/reload-tls.sh` 负责（先 `nginx -t` 校验再 `nginx -s reload`，
失败则不重载、保留旧证书继续服务）。查续期与重载记录：

```bash
tail -20 /var/log/iblog-certbot.log
```

> ℹ️ 日志里每条 `check-cert` 会重复打印两次：`reload-tls.sh` 的 `log()` 用 `tee -a` 写了一次，
> crontab 的 `>>` 重定向又写一次。只是冗余，不影响续期。

### 系统安全更新（2026-10-15 打过一次，之后建议定期做）

登录横幅会提示待打的安全更新。查一下有多少：

```bash
dnf updateinfo list --security | wc -l        # 行数 = 公告×包
dnf updateinfo list --security | awk '{print $2}' | sort | uniq -c   # 按严重级别
```

**打补丁必须把 dnf 放进分离进程**，不能挂在 SSH 会话上：升级 `openssh`/`docker`/`containerd`
时它们的 scriptlet 会重启服务，万一 SSH 断掉，SIGHUP 可能**打断事务、留下半升级状态**。

```bash
LOG=/var/log/iblog-upgrade.log
setsid nohup bash -c "dnf -y upgrade-minimal --security > $LOG 2>&1; echo UPGRADE_EXIT=\$? >> $LOG" \
  >/dev/null 2>&1 < /dev/null &
# 然后用 `grep UPGRADE_EXIT $LOG` 轮询到结束；期望 UPGRADE_EXIT=0 且日志里没有 error/failed
```

用 `upgrade-minimal --security`（而不是全量 `upgrade`）只取修复安全问题的**最小版本**，
避免顺带引入非安全变更。打完 `kernel` 之后**必须重启**才生效
（`needs-restarting -r` 在打之前说「不需要重启」指的是当前状态，别被误导）：

```bash
nohup sh -c 'sleep 5; systemctl reboot' >/dev/null 2>&1 &     # 分离式触发，避免卡住
```

#### 重启自愈已实测验证（2026-10-15，86 天来第一次重启）

| 依赖 | 机制 |
| --- | --- |
| 容器 | 五个全是 `restart: always`，docker 起来后自动拉起 |
| docker | `enabled`；containerd 是 `disabled` 但 docker 的 `Wants=containerd.service` 会拉起它 |
| swap | `/swapfile` 写在 `/etc/fstab`，自动挂载 |
| sshd / crond | 都 `enabled` |
| 各项加固 | `sshd_config.d/` 的 drop-in、journald 的 `journald.conf.d/` 都是文件，重启后仍在 |

**实测数据**：重启后 **30 秒**恢复 SSH，**42 秒**后端就绪，6 项 HTTP 自检全中，
`ipeng` 登录正常，备份 cron 与 SSH 加固均未丢失。

> ⚠️ 一个容易误判的点：`docker compose` 的 `depends_on: service_healthy` **只在 compose 启动时生效**，
> Docker 守护进程重启后**不保证启动顺序**。所以开机瞬间 `iblog-server` 可能先于 MySQL 就绪而启动失败，
> 靠 `restart: always` 反复重试直到成功。**这属于正常现象**，等一两分钟即可，不用手动干预。


---

## 五、常见问题

**Q：后端启动报 `Communications link failure`**
A：MySQL 还在初始化。`docker compose logs mysql` 看是否完成，或用 `docker compose ps` 确认 mysql 是 `healthy` 再重启后端：`docker compose restart iblog-server`。

**Q：管理后台能打开但接口 502**
A：`iblog-server` 没起来，先看 `docker compose logs iblog-server`；确认 `MYSQL_PASSWORD` 与 `MYSQL_ROOT_PASSWORD` 一致。

**Q：管理后台 / SSH 突然打不开，但官网正常（2026-10-17 实际踩过）**
A：**先怀疑安全组，别急着重建容器。** 换网络（公司↔宿舍）后宽带出口 IP 变了，
安全组里旧的 `x.x.x.x/32` 就失效了，而 80/443 是对全网开放所以官网没事。按三步定位：

```bash
# ① 本机：超时=安全组丢弃；refused=服务本身没起
Test-NetConnection 8.138.105.59 -Port 81     # PowerShell

# ② 服务器上确认服务侧是否正常（三者都正常就一定是网络层）
docker ps --filter name=iblog-admin --format '{{.Status}} {{.Ports}}'   # 期望 Up
ss -lntp | grep ':81 '                                                  # 期望 docker-proxy 监听
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:81/dashboard # 期望 200
```

③ 对不上就去 ECS 控制台把对应端口的**访问来源**改成本机当前出口 IP
（`curl ifconfig.me` 可查），或直接放 `0.0.0.0/0`。**改规则只用「手动添加」**，
多个 IP 建多条规则并存即可，别动已有规则。详见「一、3」。

> 临时绕过、不想改安全组：`ssh -N -L 8181:127.0.0.1:81 root@8.138.105.59`，
> 然后本机开 `https://localhost:8181/dashboard`。

**Q：在服务器上 `ssh root@8.138.105.59` 报 `Permission denied (publickey)`**
A：你是在服务器的 VNC 终端里连它自己——服务器上只有公钥 `authorized_keys`、没有私钥，
必然失败。SSH 登录和端口隧道都要在**你自己的电脑**上执行，见「四、SSH 登录」。

**Q：改了站点配置前台没变化**
A：站点配置有 2 小时 Redis 缓存，保存配置时会自动清除。如果手工改过数据库，执行 `docker exec -it iblog-redis redis-cli del iblog:site:config`。

**Q：想换端口**
A：改 `deploy/.env` 里的 `WEB_PORT` / `ADMIN_PORT` 后 `docker compose up -d`。

**Q：内存不够被 OOM Kill**
A：先按「二、4. 小内存服务器（2 核 2G）专项」加 Swap、串行构建。仍不行就调小 `JAVA_OPTS` 的 `-Xmx`
（如 320m），并把 MySQL 的 `--innodb-buffer-pool-size` 降到 64M。实在紧张就走 ACR 路线：
本地构建镜像、服务器只 pull 不 build。`dmesg | grep -i "killed process"` 可确认是否被 OOM Killer 干掉。

**Q：`docker compose up` 卡在拉镜像 / 报 `i/o timeout`、`no such host`**
A：Docker Hub 直连的问题。先 `docker info | grep -A3 'Registry Mirrors'` 看加速器到底有没有生效，
再确认 `daemon.json` 里没有留着已经停服的 `docker.mirrors.ustc.edu.cn` / `hub-mirror.c.163.com`。
按「一、2」配好阿里云账号的专属加速地址后 `sudo systemctl restart docker` 重试。
单个镜像也可以用 ACR 的「镜像同步」或 `docker pull <加速域名>/library/mysql:8.0` 手动拉。

**Q：报 `docker: 'compose' is not a docker command`**
A：引擎装上了但 Compose 插件没装（AL4 的 moby 包不含 compose）。按「一、2」的第 2 步补插件，
或直接 `sudo bash deploy/server-init.sh` 让它自动补。装完 `docker compose version` 应当有输出。

---

## 六、本地开发与数据同步

开发流程：**先在本地库改，确认没问题，再同步到线上。**

本地库跑在 `localhost:3306`（`application-dev.yml` 的默认配置），线上是 docker 里的
`iblog-mysql`。两边都是独立的库，靠 `deploy/sync-db.sh` 单向同步。

### 1. 日常开发

```bash
# 后端（默认就是本地库，监听 10730）
java -jar iblog-server/build/libs/iblog-server.jar --spring.profiles.active=dev

# 前端
cd iblog-web && node node_modules/vite/bin/vite.js    # http://localhost:5181
```

前端 dev server 已把 `/api` 代理到 `localhost:10730`，不用额外配置。

> 本地端口：后端 **10730**、管理后台 **5180**、官网 **5181**。都不是框架默认端口，
> 因为本机同时开多个项目时 8080/5173 很容易被抢。端口被占时 Vite 会直接报错退出
> （`strictPort: true`），不会悄悄换端口。这只是本地开发端口，线上容器内部仍是
> 8080（前端 nginx 按服务名 `iblog-server:8080` 反代），对外映射由 `deploy/.env` 决定。

**Redis 保持本地**：它只存登录态和站点配置缓存，连线上会让本地登录覆盖线上 token，
把浏览器里的线上登录态挤掉。

### 2. 同步：本地 -> 线上

```bash
bash deploy/sync-db.sh --dry-run    # 先看两边差异，不写任何东西
bash deploy/sync-db.sh              # 真正同步（会要求输入 yes 确认）
bash deploy/sync-db.sh --yes        # 跳过确认（给脚本用）
```

脚本按顺序做这几件事：

1. **先备份线上**，存到 `deploy/.sync-backup/`（保留最近 10 份）
2. 导出本地库，校验 dump 完整性
3. 用 SSH 管道导入线上（中转文件不落地）
4. 逐表比对行数，不一致就报错并给出回滚命令

备份和导出都会校验 `-- Dump completed` 标记 —— 中断的 dump 也会留下一个非空文件，
只看「文件存在」不够。

**回滚**：脚本会把回滚命令打在最后一行，形如：

```bash
ssh root@8.138.105.59 "docker exec -i iblog-mysql sh -c 'mysql -uroot -p\$MYSQL_ROOT_PASSWORD iblog'" \
  < deploy/.sync-backup/remote-before-sync-<时间戳>.sql
```

### 3. 反向：线上 -> 本地

换机器、或本地库搞乱了想重新拉一份：

```bash
bash deploy/sync-db.sh --pull
```

会用线上覆盖本地（**本地先自动备份**）。

**跑之前先确认本机环境**（2026-10-17 在 Windows 上踩过）：

- 本地 `mysql` / `mysqldump` **不在 PATH 里**（默认装在 `C:\Program Files\MySQL\MySQL Server 8.0\bin\`），
  脚本找不到会直接退出。用环境变量指过去即可。
- 脚本是 bash script，Windows 上要用 **Git Bash** 跑，`pwsh` 里没有 `bash`。

一条命令跑通（Git Bash）：

```bash
export IBLOG_MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
export IBLOG_MYSQLDUMP_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe"
bash deploy/sync-db.sh --pull
```

> 从 PowerShell 调用时，环境变量名一样，路径用 Windows 形式：
> `$env:IBLOG_MYSQL_BIN = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"`，
> 然后 `& "C:\Program Files\Git\bin\bash.exe" -lc "cd /d/develop/IdeaProjects/iblog && bash deploy/sync-db.sh --pull"`。

**`--pull` 之前务必先 `--dry-run` 看一眼**：它是整库覆盖，不区分表。曾经出现过本地只有 2 篇
种子文章、线上有 13 篇的情况 —— 如果本地那 2 篇里有一篇是你新写的，`--pull` 会直接冲掉它。
先比对行数，确认本地没有「线上没有的、你想留的东西」再覆盖。

### 4. 用 Navicat 之类的图形客户端连本地

本地库直接连 `localhost:3306`，root / 123456，不需要隧道。

**要连线上库**，先起隧道再连 `127.0.0.1:3307`：

```bash
bash deploy/db-tunnel.sh -d
bash deploy/db-tunnel.sh --status
bash deploy/db-tunnel.sh --stop
```

连的时候注意：填了 `127.0.0.1:3307` 就**不要再勾「使用 SSH 隧道」**。
隧道脚本本身已经是一层 SSH，客户端再加一层，会变成
「先 SSH 到 3307、在 MySQL 端口上发 SSH 握手」，报 `Socket error: disconnected (-1)`。
两个方案选一个：

| | 主机 / 端口 | SSH 隧道勾选 |
|---|---|---|
| **方案 A（推荐）** | `127.0.0.1` / `3307` | **不勾** |
| 方案 B | 容器内网 IP / `3306` | 勾上，填 `8.138.105.59:22` |

方案 B 的容器 IP 会随 `docker compose up -d --build` 变化（现查：
`ssh root@8.138.105.59 "docker inspect iblog-mysql --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'"`），
所以推荐方案 A。

**绝对不要把线上 3306 映射到公网**。线上 MySQL 的 root 是 `root@%`，
只要网络能通就能连，安全性完全靠「3306 没有暴露」。日常查数据建议建个只读账号：

```sql
CREATE USER 'iblog_ro'@'%' IDENTIFIED BY '<强密码>';
GRANT SELECT ON iblog.* TO 'iblog_ro'@'%';
FLUSH PRIVILEGES;
```

### 注意

- `deploy/sync-db.sh` 是**单向覆盖**，线上那几行改动会被丢掉。往线上写之前脚本一定先备份。
- `iblog-server/src/main/resources/application-local.yml` 已默认注释掉。它只在需要临时
  直连线上库时放开（配合 `--spring.profiles.active=dev,local`），日常别用。
- 隧道断了后端不会立刻报错（连接池里还留着连接），要等下次取连接才会失败。
  重启隧道后连接池会自动恢复，不用重启后端。
- **美食栏目录数据时，`poi_id` 优先于经纬度**：`iblog-web/src/utils/amap.ts` 的
  `buildAmapUrl()` 先看 `poiid`，没有再退回 `position`，两者都没有才用「店名+地址」做关键词搜索。
  所以**只填 `poi_id` 就能精确跳转**，不必再抠经纬度。POI ID 从高德分享链接的
  `/place/<POI_ID>` 里取；链接换成 `/ssr/place/<POI_ID>` 也一样。
- `blog_food.rating` 是 `TINYINT` 1-5 的整数星级，存不下高德的 4.7 / 4.6 这类小数评分，
  按四舍五入取整（4.7→5、4.6→5、4.4→4）。原始小分可以写进 `remark` 备注。

