#!/bin/bash
# =============================================================
#  本地开发连线上库：建一条 SSH 隧道
#
#  线上 MySQL 只挂在 docker 内网（3306 没有映射到宿主机），
#  所以不能直连 IP，必须走 SSH 隧道：
#      本机 127.0.0.1:3307  ->  服务器 iblog-mysql:3306
#
#  用法：
#      bash deploy/db-tunnel.sh            # 前台运行，Ctrl+C 断开
#      bash deploy/db-tunnel.sh -d         # 后台运行
#      bash deploy/db-tunnel.sh --stop     # 关掉后台隧道
#      bash deploy/db-tunnel.sh --status   # 看隧道状态
#
#  配套：iblog-server/src/main/resources/application-dev.yml 默认连 127.0.0.1:3307
# =============================================================
set -euo pipefail

SERVER="${IBLOG_SERVER:-root@8.138.105.59}"
LOCAL_PORT="${IBLOG_TUNNEL_PORT:-3307}"
REMOTE_PORT="3306"
PIDFILE="/tmp/iblog-db-tunnel.pid"

# 取数据库密码，按顺序尝试：
#   1) deploy/.env（本地若有副本；release.sh 不会拉这个文件过来）
#   2) 环境变量 MYSQL_ROOT_PASSWORD
#   3) 直接用 ssh 去服务器上读 /opt/iblog/deploy/.env
# 这样本地不需要存一份线上密码。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

DB_PASS="${MYSQL_ROOT_PASSWORD:-}"
if [ -z "$DB_PASS" ] && [ -f "$ENV_FILE" ]; then
  DB_PASS="$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' "$ENV_FILE" | tr -d '\r' | head -1)"
fi
if [ -z "$DB_PASS" ]; then
  # 从服务器读，不回显
  DB_PASS="$(ssh -o BatchMode=yes "$SERVER" \
    "sed -n 's/^MYSQL_ROOT_PASSWORD=//p' /opt/iblog/deploy/.env 2>/dev/null | tr -d '\r' | head -1" \
    2>/dev/null || true)"
fi

if [ -z "$DB_PASS" ]; then
  echo "错误：拿不到数据库密码。" >&2
  echo "  试过：deploy/.env、环境变量 MYSQL_ROOT_PASSWORD、服务器上的 /opt/iblog/deploy/.env" >&2
  echo "  可以显式传入：MYSQL_ROOT_PASSWORD='xxx' bash deploy/db-tunnel.sh" >&2
  exit 1
fi

tunnel_alive() {
  [ -f "$PIDFILE" ] || return 1
  local pid
  pid="$(cat "$PIDFILE" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

# 解析 mysql 容器的内网 IP。
# 线上 MySQL 没有映射宿主机端口，只能转发到容器的内网 IP；
# 而容器 IP 在 compose 重建后可能变，所以每次启动都现查一次。
resolve_remote_host() {
  local ip
  ip="$(ssh -o BatchMode=yes "$SERVER" \
    "docker inspect iblog-mysql --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>/dev/null" \
    2>/dev/null | tr -d '\r' | head -1)"
  if [ -z "$ip" ]; then
    echo "错误：拿不到 iblog-mysql 容器 IP（ssh 或容器名不对？）" >&2
    return 1
  fi
  printf '%s' "$ip"
}

case "${1:-}" in
  --stop)
    if tunnel_alive; then
      kill "$(cat "$PIDFILE")" && rm -f "$PIDFILE"
      echo "隧道已关闭。"
    else
      rm -f "$PIDFILE"
      echo "没有在跑的隧道。"
    fi
    exit 0
    ;;
  --status)
    if tunnel_alive; then
      echo "隧道运行中：PID $(cat "$PIDFILE")，127.0.0.1:$LOCAL_PORT -> $SERVER:<mysql容器>:$REMOTE_PORT"
    else
      echo "隧道未运行。"
      exit 1
    fi
    exit 0
    ;;
esac

if tunnel_alive; then
  echo "隧道已在运行（PID $(cat "$PIDFILE")），无需重复启动。"
  exit 0
fi

# 端口占用检查：避免和本地 MySQL(3306) 之类冲突
if command -v ss >/dev/null 2>&1 && ss -tln 2>/dev/null | grep -q ":$LOCAL_PORT "; then
  echo "错误：本机 $LOCAL_PORT 已被占用，换个端口：IBLOG_TUNNEL_PORT=3308 bash deploy/db-tunnel.sh" >&2
  exit 1
fi

REMOTE_HOST="$(resolve_remote_host)"

SSH_ARGS=(
  -N
  -o ExitOnForwardFailure=yes
  -o ServerAliveInterval=30
  -o ServerAliveCountMax=3
  -L "$LOCAL_PORT:$REMOTE_HOST:$REMOTE_PORT"
  "$SERVER"
)

if [ "${1:-}" = "-d" ]; then
  # 后台：把 ssh 的 stderr 收进日志，方便排查
  nohup ssh "${SSH_ARGS[@]}" >/tmp/iblog-db-tunnel.log 2>&1 &
  echo $! > "$PIDFILE"
  sleep 2
  if tunnel_alive; then
    echo "隧道已在后台运行：PID $(cat "$PIDFILE")"
    echo "  127.0.0.1:$LOCAL_PORT -> $SERVER:$REMOTE_HOST:$REMOTE_PORT"
    echo "  日志：/tmp/iblog-db-tunnel.log"
    echo "  关闭：bash deploy/db-tunnel.sh --stop"
  else
    echo "隧道启动失败，日志：" >&2
    cat /tmp/iblog-db-tunnel.log >&2 || true
    rm -f "$PIDFILE"
    exit 1
  fi
else
  echo "建立隧道：127.0.0.1:$LOCAL_PORT -> $SERVER:$REMOTE_HOST:$REMOTE_PORT"
  echo "（Ctrl+C 断开；本地后端 application-dev.yml 已默认连 3307）"
  echo
  echo "连上后可以用这个验证："
  echo "  mysql -h 127.0.0.1 -P $LOCAL_PORT -uroot -p'<密码>' iblog -e 'select count(*) from blog_article'"
  echo
  exec ssh "${SSH_ARGS[@]}"
fi
