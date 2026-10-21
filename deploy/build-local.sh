#!/usr/bin/env bash
# =============================================================
#  iblog 本地预构建脚本
#
#  作用：在本机把后端 jar 和两个前端的静态产物打好，连同运行时 Dockerfile、
#        建库 SQL 一起组装成 deploy/dist/，供低配服务器（2 核 2G）直接组装镜像。
#
#  为什么需要它：服务器上跑 Gradle + pnpm 编译很容易 OOM。本机编译好之后，
#  服务器只做 `COPY`，构建耗时从 5~15 分钟降到几秒，内存占用可以忽略。
#
#  用法（在仓库根目录，Git Bash 里执行）：
#      bash deploy/build-local.sh
# =============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/deploy/dist"

# ---------- JDK 探测（本机 JAVA_HOME 常没设，这里兜底找一下） ----------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for candidate in \
    "/c/Program Files/Zulu/zulu-21" \
    "/c/Program Files/Java"/jdk-2* \
    "/c/Program Files/Eclipse Adoptium"/jdk-* \
    "/c/Program Files/Microsoft"/jdk-*; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  echo "!! 没找到 JDK，请先设置 JAVA_HOME（本项目用 JDK 17 或更高，本机实测 Zulu 21 可用）" >&2
  exit 1
fi
echo "JAVA_HOME = $JAVA_HOME"

command -v pnpm >/dev/null 2>&1 || { echo "!! 没找到 pnpm，先执行 corepack enable pnpm 或 npm i -g pnpm" >&2; exit 1; }

# ---------- 1. 后端 ----------
echo
echo "==> [1/4] 编译后端 jar"
# 本机 GRADLE_USER_HOME(D:\develop\gradle_user_home) 常因 journal 锁被其它 Gradle 守护进程
# 占用而报「拒绝访问」，这里首次失败就自动换成项目内的独立缓存目录重试。
if ! (cd "$ROOT/iblog-server" && ./gradlew clean bootJar -x test); then
  echo
  echo "!! 默认 GRADLE_USER_HOME 用不了（本机已知问题：journal 锁被其它 Gradle 守护进程占用）"
  echo "   自动改用项目内独立缓存重试：$ROOT/.gradle-local-home（首次需重新下载依赖，稍慢）"
  (cd "$ROOT/iblog-server" && ./gradlew --gradle-user-home "$ROOT/.gradle-local-home" clean bootJar -x test)
fi

# ---------- 2. 管理后台 ----------
echo
echo "==> [2/4] 构建管理后台（iblog-admin）"
# 官网与后台网址不同时，后台顶部「行情」按钮要拼出官网完整地址；在构建时注入。
# 用法：VITE_WEB_URL=https://www.example.com bash deploy/build-local.sh
# 不设置则沿用 iblog-admin/.env.production 里的值。
if [ -n "${VITE_WEB_URL:-}" ]; then
  echo "    VITE_WEB_URL = $VITE_WEB_URL"
  (cd "$ROOT/iblog-admin" && pnpm install --frozen-lockfile && VITE_WEB_URL="$VITE_WEB_URL" pnpm build)
else
  echo "    VITE_WEB_URL 未设置，使用 iblog-admin/.env.production 里的值"
  (cd "$ROOT/iblog-admin" && pnpm install --frozen-lockfile && pnpm build)
fi

# ---------- 3. 官网前台 ----------
echo
echo "==> [3/4] 构建官网前台（iblog-web）"
(cd "$ROOT/iblog-web" && pnpm install --frozen-lockfile && pnpm build)

# ---------- 4. 组装 dist ----------
echo
echo "==> [4/4] 组装 deploy/dist"
rm -rf "$OUT"
mkdir -p "$OUT/server" "$OUT/admin" "$OUT/web" "$OUT/db"

# 后端：jar + 运行时 Dockerfile
cp "$ROOT/iblog-server/build/libs/iblog-server.jar" "$OUT/server/iblog-server.jar"
cp "$ROOT/deploy/runtime/server.Dockerfile" "$OUT/server/Dockerfile"

# 管理后台
cp -r "$ROOT/iblog-admin/dist" "$OUT/admin/dist"
cp "$ROOT/iblog-admin/nginx.conf" "$OUT/admin/nginx.conf"
cp "$ROOT/deploy/runtime/frontend.Dockerfile" "$OUT/admin/Dockerfile"

# 官网前台
cp -r "$ROOT/iblog-web/dist" "$OUT/web/dist"
cp "$ROOT/iblog-web/nginx.conf" "$OUT/web/nginx.conf"
cp "$ROOT/deploy/runtime/frontend.Dockerfile" "$OUT/web/Dockerfile"

# 建库脚本（MySQL 容器首次启动会执行）
cp "$ROOT/iblog-server/src/main/resources/db/01-schema.sql" "$OUT/db/01-schema.sql"
cp "$ROOT/iblog-server/src/main/resources/db/02-data.sql" "$OUT/db/02-data.sql"

echo
echo "构建完成，产物："
du -sh "$OUT"/* 2>/dev/null || ls -R "$OUT"
echo
echo "下一步："
echo "  1. 把 deploy/ 整个目录上传到服务器（含 dist/）："
echo "       scp -r deploy root@<服务器IP>:/opt/iblog/"
echo "  2. 服务器上按 DEPLOY.md「二、4. 本地预构建部署」切到预构建模式启动"
