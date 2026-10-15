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

**先把 AL4 的坑说清楚**：Alibaba Cloud Linux 4 基于龙蜥 Anolis OS 23，定位上**不再是 CentOS 兼容替代**
（这点和 AL3 不同），`$releasever` 是 `alnx4` 而不是 `9`。直接照抄网上给 CentOS/RHEL 的 Docker 官方源，
轻则找不到包，重则因 `container-selinux` 之类依赖装到一半失败。

AL4 仓库里已经内置 **moby（Docker 开源引擎）28.x**，用它最省事，也不会踩 cgroup v2 / nftables 的兼容坑：

```bash
# 1) 引擎（AL4 自带，包含 docker / dockerd）
sudo dnf -y install moby
sudo systemctl enable --now docker
docker --version            # 期望 Docker version 28.x
```

moby 包不带 `docker compose`，需要单独补 Compose 插件。
官方插件是纯静态 Go 二进制，从阿里云 docker-ce 镜像源里取出来即可（不用解依赖，最稳）：

```bash
# 2) Docker Compose v2 插件
sudo dnf -y install cpio curl
cd /tmp
sudo curl -fsSLO https://mirrors.aliyun.com/docker-ce/linux/rhel/9/x86_64/stable/Packages/docker-compose-plugin-2.33.0-1.el9.x86_64.rpm
rpm2cpio docker-compose-plugin-*.rpm | cpio -idm --quiet
sudo mkdir -p /usr/libexec/docker/cli-plugins /usr/lib/docker/cli-plugins
sudo install -m 0755 usr/libexec/docker/cli-plugins/docker-compose /usr/libexec/docker/cli-plugins/docker-compose
sudo install -m 0755 usr/libexec/docker/cli-plugins/docker-compose /usr/lib/docker/cli-plugins/docker-compose
docker compose version
```

> 两个插件目录都放一份，是因为不同发行版打包的 docker CLI 默认查找路径不一致，多放一份没有副作用。
> 想换更新版本，去 `mirrors.aliyun.com/docker-ce/linux/rhel/9/x86_64/stable/Packages/` 里挑一个。
>
> **以上两步，加上加 Swap、配镜像加速器、配容器日志轮转，仓库里已封装好，一条命令搞定**：
> `sudo bash deploy/server-init.sh --mirror https://<你的ID>.mirror.aliyuncs.com`

Ubuntu：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

**配镜像加速器（阿里云 ECS 必做）**：Docker Hub 国内直连基本拉不动，本项目要拉
`mysql:8.0`、`redis:7-alpine`、`eclipse-temurin:17-jre`、`nginx:1.27-alpine` 四个镜像。

> ⚠️ 不少老教程还在写 `docker.mirrors.ustc.edu.cn` 和 `hub-mirror.c.163.com`，
> **这两个都已停服**（中科大 2024-06-06 起关闭、网易公共加速早已停用）。
> `daemon.json` 里留着它们只会拖慢甚至卡死拉取，务必删掉。

阿里云对**同账号 ECS** 提供专属加速地址，走内网、最稳：
控制台 → 容器镜像服务 ACR → 镜像工具 → **镜像加速器**，复制形如 `https://xxxx.mirror.aliyuncs.com` 的地址：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": ["https://把这里换成你的专属加速地址"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
sudo systemctl restart docker
docker info | grep -A3 'Registry Mirrors'
```

> `log-opts` 是给小磁盘兜底的：不加的话容器日志会一直涨，40G 系统盘迟早被吃满。

### 3. 安全组放行

在 ECS 控制台 → 安全组 → 入方向，放行：

| 端口 | 用途 |
| --- | --- |
| 22 | SSH（建议限制来源 IP） |
| 80 | 官网 |
| 81 | 管理后台（建议限制来源 IP，或用 Nginx 加 Basic Auth） |
| 8080 | 后端接口（可选，正式环境建议只在内网暴露） |

> 443 端口留给你配置 HTTPS 证书时使用。

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

---

## 四、日常运维

```bash
cd /opt/iblog/deploy

# 查看运行状态
docker compose ps

# 查看某个服务日志
docker compose logs -f --tail=200 iblog-server

# 更新代码后重新部署（只重建变化的部分）
git pull
docker compose up -d --build

# 只重启后端
docker compose restart iblog-server

# 停止（保留数据卷）
docker compose down

# 停止并清空数据（慎用！）
docker compose down -v
```

### 数据备份

```bash
# 备份数据库
docker exec iblog-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" iblog > iblog_$(date +%F).sql

# 恢复
docker exec -i iblog-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" iblog < iblog_2026-01-01.sql
```

建议用 `crontab -e` 加一条每日备份：

```cron
0 3 * * * cd /opt/iblog/deploy && docker exec iblog-mysql mysqldump -uroot -p'你的密码' iblog > /opt/backup/iblog_$(date +\%F).sql
```

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
