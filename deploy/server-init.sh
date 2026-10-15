#!/usr/bin/env bash
# =============================================================
#  iblog 阿里云 ECS 初始化脚本
#  目标环境：Alibaba Cloud Linux 4 LTS（Anolis OS 23 系）/ 2 核 2G
#
#  做四件事：
#    1. 加 2G Swap      —— 2G 内存同时跑 MySQL + Redis + JVM 的保命项
#    2. 装 Docker 引擎   —— 优先用 AL4 自带的 moby，失败再退到 Docker CE（rhel/9 源）
#    3. 装 Compose v2 插件 —— 从阿里云 docker-ce 镜像源里取出静态二进制（AL4 仓库不含）
#    4. 配镜像加速器 + 容器日志轮转 —— 不配日志上限，40G 系统盘会被容器日志吃满
#
#  用法（在服务器上，需要 root）：
#     sudo bash deploy/server-init.sh --mirror https://<你的ID>.mirror.aliyuncs.com
#     sudo bash deploy/server-init.sh            # 暂不配加速器，稍后重跑加 --mirror
#
#  幂等：重复执行不会破坏已装好的部分。
# =============================================================
set -euo pipefail

SWAP_FILE=/swapfile
SWAP_SIZE_MB=2048
COMPOSE_VERSION=2.33.0
DOCKER_CE_BASE=https://mirrors.aliyun.com/docker-ce/linux/rhel/9/x86_64/stable
MIRROR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --mirror)   MIRROR="${2:-}"; shift 2 ;;
    --mirror=*) MIRROR="${1#*=}"; shift ;;
    -h|--help)  sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（用 -h 看用法）" >&2; exit 1 ;;
  esac
done

log()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31m!!  %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 执行：sudo bash $0"
command -v dnf >/dev/null 2>&1 || die "本脚本面向 Alibaba Cloud Linux / RHEL 系（需要 dnf）"

if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  log "系统：${PRETTY_NAME:-unknown}    内核：$(uname -r)"
fi

# ---------- 1. Swap ----------
log "[1/4] 配置 ${SWAP_SIZE_MB}MB Swap"
if swapon --show 2>/dev/null | awk 'NR>1{print $1}' | grep -qx "$SWAP_FILE"; then
  log "Swap 已启用，跳过"
elif [ -f "$SWAP_FILE" ]; then
  swapon "$SWAP_FILE"
  log "复用已有 swapfile"
else
  if ! fallocate -l "${SWAP_SIZE_MB}M" "$SWAP_FILE" 2>/dev/null; then
    warn "fallocate 不可用，改用 dd（慢一些）"
    dd if=/dev/zero of="$SWAP_FILE" bs=1M count="$SWAP_SIZE_MB" status=none
  fi
  chmod 600 "$SWAP_FILE"
  mkswap "$SWAP_FILE" >/dev/null
  swapon "$SWAP_FILE"
  log "Swap 已创建并启用"
fi
grep -qE "^${SWAP_FILE}[[:space:]]" /etc/fstab || echo "$SWAP_FILE none swap sw 0 0" >> /etc/fstab
free -h

# ---------- 2. Docker 引擎 ----------
log "[2/4] 安装 Docker 引擎"
if command -v docker >/dev/null 2>&1; then
  log "已安装，跳过：$(docker --version)"
elif dnf -y install moby; then
  log "已安装 AL4 自带的 moby"
else
  warn "仓库里没有 moby，改用 Docker CE（rhel/9 源）"
  cat > /etc/yum.repos.d/docker-ce.repo <<EOF
[docker-ce-stable]
name=Docker CE Stable - \$basearch
baseurl=${DOCKER_CE_BASE}/\$basearch
enabled=1
gpgcheck=1
gpgkey=https://mirrors.aliyun.com/docker-ce/linux/rhel/gpg
EOF
  dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin
fi
systemctl enable --now docker
docker --version

# ---------- 3. Compose v2 插件 ----------
log "[3/4] 安装 Docker Compose v2 插件"
if docker compose version >/dev/null 2>&1; then
  log "已可用：$(docker compose version --short 2>/dev/null || echo ok)"
else
  dnf -y install cpio curl
  tmp="$(mktemp -d)"
  rpm_name="docker-compose-plugin-${COMPOSE_VERSION}-1.el9.x86_64.rpm"
  log "从阿里云镜像源取 ${rpm_name}"
  curl -fsSL -o "$tmp/$rpm_name" "${DOCKER_CE_BASE}/Packages/${rpm_name}" \
    || die "下载 compose 插件失败；可到 ${DOCKER_CE_BASE}/Packages/ 挑一个更新版本，改脚本里的 COMPOSE_VERSION 再跑"
  ( cd "$tmp" && rpm2cpio "$rpm_name" | cpio -idm --quiet )
  bin="$(find "$tmp" -type f -name docker-compose -print -quit)"
  [ -n "$bin" ] || die "RPM 里没找到 docker-compose 二进制"
  # 不同发行版打包的 docker CLI，默认插件目录不一致，两个位置都放一份（无副作用）
  mkdir -p /usr/libexec/docker/cli-plugins /usr/lib/docker/cli-plugins
  install -m 0755 "$bin" /usr/libexec/docker/cli-plugins/docker-compose
  install -m 0755 "$bin" /usr/lib/docker/cli-plugins/docker-compose
  rm -rf "$tmp"
  docker compose version || die "compose 插件装完仍不可用，把这里上面的输出发我"
fi

# ---------- 4. 镜像加速器 + 日志轮转 ----------
log "[4/4] 配置 /etc/docker/daemon.json"
mkdir -p /etc/docker
if [ -f /etc/docker/daemon.json ]; then
  cp -a /etc/docker/daemon.json "/etc/docker/daemon.json.bak.$(date +%s)"
  warn "原有 daemon.json 已备份为 /etc/docker/daemon.json.bak.<时间戳>"
fi
if [ -n "$MIRROR" ]; then
  cat > /etc/docker/daemon.json <<EOF
{
  "registry-mirrors": ["${MIRROR}"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
  log "已写入加速器：${MIRROR}"
else
  cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
  warn "未指定 --mirror，本次只配了日志轮转。"
  warn "Docker Hub 国内直连基本拉不动，请到：阿里云控制台 → 容器镜像服务 ACR → 镜像工具 → 镜像加速器"
  warn "取到 https://<你的ID>.mirror.aliyuncs.com 后重跑本脚本并加上 --mirror <该地址>"
fi
systemctl restart docker
sleep 1
docker info 2>/dev/null | grep -A3 'Registry Mirrors' || true

# ---------- 自检 ----------
log "自检"
echo "docker  : $(docker --version)"
echo "compose : $(docker compose version --short 2>/dev/null || echo '不可用')"
echo "内存    :"
free -h
echo "系统盘  :"
df -h / | tail -1

log "服务器准备完成。下一步见 deploy/DEPLOY.md「二、部署应用」"
