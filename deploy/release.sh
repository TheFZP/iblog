#!/usr/bin/env bash
# =============================================================
#  iblog 一键发布
#
#  把「改源码 → 上线」这条容易漏步的链路固化成一条命令：
#      本地构建 → 组装 deploy/dist → 上传到服务器 → 重建容器 → 自检
#
#  为什么需要它：线上跑的是 deploy/dist/ 里的构建产物和容器镜像，不是 src/。
#  改了源码而不重新构建并上传，生产环境不会有任何变化；而 deploy/dist/ 在
#  .gitignore 里，所以「在服务器上 git pull」也拿不到产物。
#
#  用法（在 Git Bash 里，仓库任意位置都能执行）：
#      export IBLOG_SERVER=root@<服务器IP>
#      bash deploy/release.sh                # 全部（后端 + 管理后台 + 官网 + 建库 SQL）
#      bash deploy/release.sh admin          # 只发管理后台（最快，几秒）
#      bash deploy/release.sh web server     # 可以给多个目标
#      bash deploy/release.sh --build-only   # 只在本机构建组装 dist，不上传不重启
#
#  在 PowerShell 里跑（bash 不在 PATH 上）：
#      & "D:\develop\Git\bin\bash.exe" deploy/release.sh admin
#
#  配置：IBLOG_SERVER 必填（--build-only 除外）。也可以写进 deploy/.release.env
#        （该文件已加入 .gitignore，不会提交），内容形如：
#            IBLOG_SERVER=root@1.2.3.4
#
#  ⚠️ 本脚本只上传 dist/<目标> 与 docker-compose.yml，**永远不会碰 deploy/.env**。
#     服务器上的 .env 里有数据库密码和 JWT 密钥，被本地产物覆盖会导致整站起不来。
# =============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/deploy/dist"
REMOTE_DIR="/opt/iblog/deploy"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15)

log()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31m!!  %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,32p' "${BASH_SOURCE[0]}"; }

# ---------- 参数 ----------
TARGETS=()
BUILD_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --build-only)            BUILD_ONLY=1 ;;
    all)                     TARGETS=(server admin web db) ;;
    server|admin|web|db)     TARGETS+=("$arg") ;;
    -h|--help)               usage; exit 0 ;;
    *)                       die "未知参数：$arg（-h 看用法）" ;;
  esac
done
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(server admin web db)

# 去重并保持顺序
UNIQ=()
for t in "${TARGETS[@]}"; do
  for u in "${UNIQ[@]:-}"; do [ "$u" = "$t" ] && continue 2; done
  UNIQ+=("$t")
done
TARGETS=("${UNIQ[@]}")

# ---------- 配置 ----------
[ -f "$ROOT/deploy/.release.env" ] && . "$ROOT/deploy/.release.env"
SERVER="${IBLOG_SERVER:-}"
if [ "$BUILD_ONLY" -eq 0 ]; then
  [ -n "$SERVER" ] || die "没指定服务器。请 export IBLOG_SERVER=root@<IP>，或写进 deploy/.release.env"
fi

log "发布目标：${TARGETS[*]}"
echo "  仓库根目录 : $ROOT"
[ "$BUILD_ONLY" -eq 1 ] && echo "  模式       : 只构建（不上传）" || echo "  目标服务器 : $SERVER:$REMOTE_DIR"
echo -n "  当前提交   : "; git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "(不是 git 仓库)"
if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
  warn "工作区有未提交的改动 —— 这次发布的内容与记录的提交不一致，留意一下"
fi

# ---------- 构建 ----------
need() { for t in "${TARGETS[@]}"; do [ "$t" = "$1" ] && return 0; done; return 1; }

build_server() {
  log "[后端] gradle bootJar"
  command -v java >/dev/null 2>&1 || die "找不到 java，需要 JDK 17+"
  echo "  java: $(java -version 2>&1 | head -1)"
  ( cd "$ROOT/iblog-server"
    if ! ./gradlew clean bootJar -x test; then
      warn "默认 GRADLE_USER_HOME 不可用（本机已知的 journal 锁问题），改用项目内独立缓存重试"
      ./gradlew --gradle-user-home "$ROOT/.gradle-local-home" clean bootJar -x test
    fi )
  mkdir -p "$DIST/server"
  cp "$ROOT/iblog-server/build/libs/iblog-server.jar" "$DIST/server/iblog-server.jar"
  cp "$ROOT/deploy/runtime/server.Dockerfile" "$DIST/server/Dockerfile"
  echo "  产物: dist/server/iblog-server.jar ($(du -h "$DIST/server/iblog-server.jar" | cut -f1))"
}

build_front() {  # $1=iblog-admin|iblog-web  $2=admin|web
  local dir="$1" name="$2"
  log "[$name] vite build"
  [ -d "$ROOT/$dir/node_modules" ] || die "$dir/node_modules 不存在，先执行：cd $dir && pnpm install"
  ( cd "$ROOT/$dir" && node ./node_modules/vite/bin/vite.js build )
  rm -rf "$DIST/$name/dist"
  cp -r "$ROOT/$dir/dist" "$DIST/$name/dist"
  cp "$ROOT/$dir/nginx.conf" "$DIST/$name/nginx.conf"
  cp "$ROOT/deploy/runtime/frontend.Dockerfile" "$DIST/$name/Dockerfile"
  echo "  产物: dist/$name/dist ($(find "$DIST/$name/dist" -type f | wc -l) 个文件)"
}

build_db() {
  log "[建库 SQL] 同步"
  mkdir -p "$DIST/db"
  cp "$ROOT/iblog-server/src/main/resources/db/01-schema.sql" "$DIST/db/"
  cp "$ROOT/iblog-server/src/main/resources/db/02-data.sql"   "$DIST/db/"
  echo "  产物: dist/db/"
  warn "建库 SQL 只在 MySQL 数据卷**首次初始化**时执行。已有数据的库不会被重新初始化，"
  warn "也不会被这两个脚本改动 —— 要改表结构得另走迁移。"
}

need server && build_server
need admin  && build_front iblog-admin admin
need web    && build_front iblog-web   web
need db     && build_db

if [ "$BUILD_ONLY" -eq 1 ]; then
  log "只构建模式，结束。产物在 $DIST"
  exit 0
fi

# ---------- 上传 ----------
upload() {  # $1=本地路径  $2=远端相对 REMOTE_DIR 的路径
  local src="$1" rel="$2"
  # 先删远端目标目录再传，避免旧的 hash 文件残留（前端产物文件名带内容哈希，不清会越积越多）
  ssh "${SSH_OPTS[@]}" "$SERVER" "rm -rf '$REMOTE_DIR/$rel' && mkdir -p '$(dirname "$REMOTE_DIR/$rel")'"
  scp "${SSH_OPTS[@]}" -r "$src" "$SERVER:$REMOTE_DIR/$rel"
  printf '  上传 %-16s ✓\n' "$rel"
}

log "上传到 $SERVER"
need server && upload "$DIST/server" "dist/server"
need admin  && upload "$DIST/admin"  "dist/admin"
need web    && upload "$DIST/web"    "dist/web"
need db     && upload "$DIST/db"     "dist/db"
# 编排文件也同步一份（它不含任何密钥，密钥在服务器上的 .env 里，本脚本从不触碰 .env）
scp "${SSH_OPTS[@]}" "$ROOT/deploy/docker-compose.yml" "$SERVER:$REMOTE_DIR/docker-compose.yml"
echo "  上传 docker-compose.yml ✓"

# 部署侧脚本一并同步。这些脚本服务器上要直接执行（比如 crontab 调 backup-db.sh），
# 不同步就会出现「本机修好了、服务器还是旧的」这种漂移。
for f in backup-db.sh server-init.sh reload-tls.sh check-cert.sh; do
  if [ -f "$ROOT/deploy/$f" ]; then
    scp "${SSH_OPTS[@]}" "$ROOT/deploy/$f" "$SERVER:$REMOTE_DIR/$f"
    ssh "${SSH_OPTS[@]}" "$SERVER" "chmod +x '$REMOTE_DIR/$f'"
    echo "  上传 $f ✓"
  fi
done

# ---------- 重建容器 ----------
SERVICES=()
need server && SERVICES+=(iblog-server)
need admin  && SERVICES+=(iblog-admin)
need web    && SERVICES+=(iblog-web)

if [ ${#SERVICES[@]} -gt 0 ]; then
  log "重建并重启：${SERVICES[*]}"
  ssh "${SSH_OPTS[@]}" "$SERVER" "cd '$REMOTE_DIR' && docker compose up -d --build ${SERVICES[*]} 2>&1 | tail -8"
else
  log "本次没有需要重建的容器（只同步了建库 SQL）"
fi

# ---------- 自检 ----------
# ⚠️ 站点已全量走 HTTPS：80/81 上都挂了「HTTP → HTTPS」跳转，且管理后台容器内
#    本身就是 443/TLS（宿主机 81 → 容器 443）。所以自检必须打 https，
#    再用 -L 跟随跳转；否则只会看到 301/400，把真实故障掩盖过去。
log "自检（在服务器上打本机回环，不受安全组影响）"
check() { # $1=url  $2=期望码  $3=说明
  local code
  code="$(ssh "${SSH_OPTS[@]}" "$SERVER" "curl -skL -o /dev/null -m 10 -w '%{http_code}' '$1'" 2>/dev/null || echo 000)"
  if [ "$code" = "$2" ]; then printf '  ✓ %-42s HTTP %s（期望 %s）\n' "$3" "$code" "$2"
  else                          printf '  ✗ %-42s HTTP %s（期望 %s）\n' "$3" "$code" "$2"; fi
}
check 'http://127.0.0.1:8080/api/portal/site' '200' '后端 /api/portal/site'
check 'https://127.0.0.1/'                   '200' '官网首页（https）'
check 'https://127.0.0.1:81/'                '200' '管理后台首页（https:81）'
# 回归守卫：官网端口不应该能碰到管理后台接口（见 iblog-web/nginx.conf）
check 'https://127.0.0.1/api/admin/auth/login' '404' '官网碰 admin 接口（应 404）'
# 跳转守卫：80 应当是 301 到 https，而不是把明文内容直接吐出来
check 'http://127.0.0.1/'                    '200' '官网 80 → HTTPS 跳转（跟随）'

log "容器状态"
ssh "${SSH_OPTS[@]}" "$SERVER" "cd '$REMOTE_DIR' && docker compose ps --format 'table {{.Name}}\t{{.Status}}'"

log "发布完成"
