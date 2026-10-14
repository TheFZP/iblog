# 部署到阿里云服务器

本文以 **ECS + Docker Compose** 为例，一次拉起 MySQL、Redis、后端、管理后台、官网五个容器。

---

## 一、服务器准备

### 1. 配置建议

| 项目 | 最低 | 推荐 |
| --- | --- | --- |
| CPU / 内存 | 2 核 2G | 2 核 4G |
| 系统盘 | 40G | 60G |
| 系统 | Alibaba Cloud Linux 3 / Ubuntu 22.04 | 同左 |

> 2G 内存如果想同时跑 MySQL + Redis + Java，建议先给服务器加 2G Swap，或者把数据库换成阿里云 RDS。

### 2. 安装 Docker

```bash
# Alibaba Cloud Linux / CentOS
sudo yum install -y docker
sudo systemctl enable --now docker
sudo systemctl enable --now docker

# 安装 compose 插件
sudo yum install -y docker-compose-plugin
docker compose version
```

```bash
# Ubuntu
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

阿里云服务器如果拉取 Docker Hub 镜像慢，可以配置镜像加速器：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://docker.mirrors.ustc.edu.cn", "https://hub-mirror.c.163.com"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
sudo systemctl restart docker
```

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

```bash
# 方式一：git clone
git clone <你的仓库地址> /opt/iblog
cd /opt/iblog

# 方式二：本地打包上传
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
- `IBLOG_JWT_SECRET`：JWT 密钥，**长度必须 ≥ 32 字符**，可用下面命令生成

```bash
openssl rand -base64 48
```

### 3. 一键启动

```bash
cd /opt/iblog/deploy
docker compose up -d --build
```

首次构建会编译 Gradle 依赖和前端依赖（管理后台用 pnpm、官网用 npm），视网络情况约 5～15 分钟。

> 依赖来源已固定为公网镜像：Gradle 发行包走阿里云 `mirrors.aliyun.com`，
> Maven 依赖走 `maven.aliyun.com/repository/public`，pnpm / npm 走 `registry.npmmirror.com`。
> 在阿里云 ECS 上构建时这些源都是同地域就近访问，速度很快，不依赖任何公司内网服务。

查看状态与日志：

```bash
docker compose ps
docker compose logs -f iblog-server
```

### 4. 验证

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
A：调小 `JAVA_OPTS` 的 `-Xmx`，并在 MySQL 的 `command` 里加 `--innodb-buffer-pool-size=128M`；2G 内存机器务必加 Swap。
