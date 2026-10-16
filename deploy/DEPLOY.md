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

| 端口 | 用途 | 来源建议 |
| --- | --- | --- |
| 22 | SSH | 你的固定出口 IP；若 IP 不固定，用 `0.0.0.0/0` 但**务必关掉密码登录只留密钥** |
| 80 | 官网（现在只做 ACME 校验 + 301 跳 443） | `0.0.0.0/0`，**不能关**（见下） |
| 443 | 官网 HTTPS | `0.0.0.0/0` |
| 81 | 管理后台（HTTPS） | **只放你的 IP**（`x.x.x.x/32`） |

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

**首次登录账号：`admin` / `admin123`，登录后请立即修改密码。**

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
3. **22 端口现在可以放心放宽来源了。** 之前为了防爆破只能限单个 IP，而宽带 IP 是动态的，
   IP 一变就连不上；现在没有密码可爆破，安全组里 22 的授权对象改成 `0.0.0.0/0` 也不会有
   实质风险（仍然建议只在需要时放宽）。

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

## 六、本地开发直连线上库

个人博客只有一份数据，线上线下保持一致，这样本地调试看到的就是真实数据。

线上 MySQL 的 3306 只挂在 docker 内网（宿主机没有做端口映射，公网也取不到），
所以本地不能直连 IP，要走 SSH 隧道。

### 1. 起隧道

```bash
bash deploy/db-tunnel.sh -d      # 后台起：127.0.0.1:3307 -> iblog-mysql:3306
bash deploy/db-tunnel.sh --status
bash deploy/db-tunnel.sh --stop
```

不带 `-d` 就是前台运行，`Ctrl+C` 断开。脚本会自动去服务器上解析 mysql 容器的内网 IP
（compose 重建后 IP 会变，所以每次启动现查），并防止重复启动。

数据库密码按 `deploy/.env` → 环境变量 `MYSQL_ROOT_PASSWORD` → ssh 读服务器 `.env`
的顺序获取，**本地不需要存一份线上密码**。

### 2. 起后端

`application-dev.yml` 默认连 `127.0.0.1:3307`，但**密码不在仓库里**，
放在 `iblog-server/src/main/resources/application-local.yml`（已被 `.gitignore` 忽略）。

```bash
java -jar iblog-server/build/libs/iblog-server.jar --spring.profiles.active=dev,local
```

或者在 IDE 的 Active profiles 里填 `dev,local`。

想临时切回本地库，用环境变量覆盖即可，不用改文件：

```bash
IBLOG_DB_HOST=localhost IBLOG_DB_PORT=3306 IBLOG_DB_PASSWORD=123456 \
  java -jar iblog-server.jar --spring.profiles.active=dev,local
```

### 3. 前后端一起

```bash
bash deploy/db-tunnel.sh -d
# 后端（见上）
# 前端
cd iblog-web && node node_modules/vite/bin/vite.js    # http://localhost:5174
```

前端 dev server 已经把 `/api` 代理到 `localhost:8080`，不用额外配置。

### 注意

- **Redis 保持本地**。它只存登录态和站点配置缓存，连线上会让本地登录覆盖线上
  token，把浏览器里的线上登录态挤掉。
- 本地改数据就是**直接改线上库**，没有第二份兜底。要动结构或批量改数据前，
  先 `bash deploy/backup-db.sh`。
- 隧道断了后端不会立刻报错（连接池里还留着连接），要等下次取连接才会失败。
  重启隧道后连接池会自动恢复，不用重启后端。
