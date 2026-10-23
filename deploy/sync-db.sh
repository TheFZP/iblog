#!/usr/bin/env bash
# =============================================================
#  iblog 数据库同步：本地 -> 线上
#
#  开发流程：先在本地库改，确认没问题，再执行本脚本推到线上。
#
#  用法：
#      bash deploy/sync-db.sh                 # 同步到线上（会先备份线上、要确认）
#      bash deploy/sync-db.sh --dry-run       # 只看两边差异，不写任何东西
#      bash deploy/sync-db.sh --pull          # 反向：线上 -> 本地（覆盖本地）
#      bash deploy/sync-db.sh --pull --dry-run  # 只读比对，不拉（--dry-run 两个方向都生效）
#      bash deploy/sync-db.sh --yes           # 跳过确认（给脚本/CI 用）
#
#      --dry-run 是全局修饰符，放在 --pull 前面或后面都一样，作用于当前方向。
#
#  前置条件：
#      - 线上要通，且 tunnel 或 ssh 可用（脚本自己开一次性隧道，不用先跑 db-tunnel.sh）
#      - 本地 MySQL 在 localhost:3306
#
#  安全性：
#      - 推之前一定先备份线上（存到 deploy/.sync-backup/，保留最近 10 份）
#      - 用完整性标记校验 dump（-- Dump completed），中断的 dump 不会被导入
#      - 推完逐表比对行数，不一致就报警
#      - 默认要人工确认；线上库没被碰过时不会留下半截数据
#
#  ⚠️ 这是「本地覆盖线上」，线上那几行改动会被丢掉。往线上写之前
#     脚本会先备份，出问题可以用备份还原（见文末提示）。
# =============================================================
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
BACKUP_DIR="$REPO_DIR/deploy/.sync-backup"
KEEP_BACKUPS=10

SERVER="${IBLOG_SERVER:-root@8.138.105.59}"
LOCAL_HOST="${IBLOG_LOCAL_HOST:-127.0.0.1}"
LOCAL_PORT="${IBLOG_LOCAL_PORT:-3306}"
LOCAL_USER="${IBLOG_LOCAL_USER:-root}"
LOCAL_PASS="${IBLOG_LOCAL_PASS:-123456}"
REMOTE_CONTAINER="${IBLOG_MYSQL_CONTAINER:-iblog-mysql}"
DB="iblog"

# 本地 mysql 客户端位置（Windows/Git Bash 下不在 PATH 里）
LOCAL_MYSQL="${IBLOG_MYSQL_BIN:-$(command -v mysql 2>/dev/null || true)}"
LOCAL_DUMP="${IBLOG_MYSQLDUMP_BIN:-$(command -v mysqldump 2>/dev/null || true)}"
if [ -z "$LOCAL_MYSQL" ] || [ -z "$LOCAL_DUMP" ]; then
  for d in "/c/Program Files/MySQL/MySQL Server 8.0/bin" \
           "/c/Program Files/MySQL/MySQL Server 8.4/bin" \
           "/usr/bin" "/usr/local/bin"; do
    if [ -z "$LOCAL_MYSQL" ] && [ -x "$d/mysql.exe" ];  then LOCAL_MYSQL="$d/mysql.exe"; fi
    if [ -z "$LOCAL_DUMP" ]  && [ -x "$d/mysqldump.exe" ]; then LOCAL_DUMP="$d/mysqldump.exe"; fi
    if [ -z "$LOCAL_MYSQL" ] && [ -x "$d/mysql" ];      then LOCAL_MYSQL="$d/mysql"; fi
    if [ -z "$LOCAL_DUMP" ]  && [ -x "$d/mysqldump" ];  then LOCAL_DUMP="$d/mysqldump"; fi
  done
fi
[ -n "$LOCAL_MYSQL" ] || { echo "找不到 mysql 客户端，用 IBLOG_MYSQL_BIN 指定路径" >&2; exit 1; }
[ -n "$LOCAL_DUMP" ]  || { echo "找不到 mysqldump，用 IBLOG_MYSQLDUMP_BIN 指定路径" >&2; exit 1; }

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

# 线上 mysql 一律通过 docker exec 走容器自己的环境变量取密码，
# 宿主机和命令行里都不会出现明文密码。
remote_sql() {  # $1 = 数据库名（可空），SQL 从 stdin 进
  local db="${1:-}"
  ssh -o BatchMode=yes "$SERVER" \
    "docker exec -i $REMOTE_CONTAINER sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -B $db'" 2>/dev/null | strip_cr
}

# 本地 mysql 客户端输出是 CRLF（Windows 版），取回来的每一行末尾都带 \r。
# 不剥掉的话，拼进反引号里会变成 `blog_article\r` —— 表名不合法，
# 查询直接报错，而 stderr 又被丢了，于是计数全部静默为空。
# 所有从 mysql 取回的标量/标识符都必须过一遍这个函数。
strip_cr() { tr -d '\r'; }

# 本地 mysql，包一层免得每处都写一长串
local_sql() {
  "$LOCAL_MYSQL" -h "$LOCAL_HOST" -P "$LOCAL_PORT" -u"$LOCAL_USER" -p"$LOCAL_PASS" \
    --connect-timeout=8 -N -B "$@" 2>/dev/null | strip_cr
}

ensure_remote_up() {
  local running
  running="$(ssh -o BatchMode=yes "$SERVER" \
    "docker inspect -f '{{.State.Running}}' $REMOTE_CONTAINER" 2>/dev/null || true)"
  [ "$running" = "true" ] || die "线上容器 $REMOTE_CONTAINER 没在运行（ssh 通吗？）"
}

ensure_local_up() {
  local n
  n="$(local_sql -e "SELECT 1" || true)"
  [ "$n" = "1" ] || die "连不上本地库 $LOCAL_HOST:$LOCAL_PORT（MySQL 起了吗？密码对吗？）"
}

# 逐表行数比对。$1=源描述 $2=目标描述；打印差异，返回 1 表示有不一致
compare_counts() {
  local src_label="$1" dst_label="$2" src_fn="$3" dst_fn="$4"
  local tables t a b bad=0
  tables="$(echo "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME" | $src_fn)"
  [ -n "$tables" ] || die "读不到表清单"
  printf '     %-22s %10s %10s\n' "表" "$src_label" "$dst_label"
  while read -r t; do
    [ -n "$t" ] || continue
    a="$(echo "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" | $src_fn)"
    b="$(echo "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" | $dst_fn)"
    # 两个空字符串相等，会伪装成「一致」—— 必须显式拦掉。
    # （踩过：mysql 输出带 \r 导致计数全为空，比对却报了 7 个 ✓）
    if [ -z "$a" ] || [ -z "$b" ]; then
      printf '  ✗  %-22s 取值失败（源=[%s] 目标=[%s]）\n' "$t" "$a" "$b"; bad=1; continue
    fi
    if [ "$a" = "$b" ]; then
      printf '  ✓  %-22s %10s %10s\n' "$t" "$a" "$b"
    else
      printf '  ✗  %-22s %10s %10s\n' "$t" "$a" "$b"; bad=1
    fi
  done <<< "$tables"
  return "$bad"
}

# 校验 dump 是完整的。只看文件非空是不够的 —— 中断的 dump 也会留下非空文件。
verify_dump() {
  local f="$1"
  [ -s "$f" ] || die "dump 文件是空的：$f"
  grep -q -- '-- Dump completed' "$f" || die "dump 缺少完成标记（-- Dump completed），判定为中断，已中止：$f"
  grep -q 'CREATE TABLE' "$f" || die "dump 里没有 CREATE TABLE，判定异常：$f"
}

# 从 stdin 收一份 dump 存到本地，返回路径
save_dump() {
  local f="$1" err rc
  mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR" 2>/dev/null || true
  err="$(cat > "$f.tmp")" 2>/dev/null || true
  mv "$f.tmp" "$f"
  chmod 600 "$f" 2>/dev/null || true
}

# 备份目录必须先建出来：重定向 "> $file" 的失败发生在命令执行之前，
# 目录不存在时 shell 会直接报 No such file or directory，
# 而重定向失败时的报错和 mysqldump 的报错混在一起很难判断。
ensure_backup_dir() {
  if [ ! -d "$BACKUP_DIR" ]; then
    mkdir -p "$BACKUP_DIR" || die "建不了备份目录 $BACKUP_DIR"
    log "已创建备份目录 $BACKUP_DIR"
  fi
  chmod 700 "$BACKUP_DIR" 2>/dev/null || true
}

rotate_backups() {
  local files
  files="$(ls -1t "$BACKUP_DIR"/*.sql 2>/dev/null || true)"
  local n
  n="$(printf '%s\n' "$files" | grep -c . || true)"
  if [ "$n" -gt "$KEEP_BACKUPS" ]; then
    printf '%s\n' "$files" | tail -n +$((KEEP_BACKUPS + 1)) | while read -r f; do
      [ -n "$f" ] && rm -f "$f" && log "清理旧备份 $(basename "$f")"
    done
  fi
}

do_dry_run() {
  local dir="${1:-push}"
  ensure_local_up
  ensure_remote_up
  log "只读比对（不写任何东西）"
  echo
  echo "  本地 $LOCAL_HOST:$LOCAL_PORT  vs  线上 $SERVER:$REMOTE_CONTAINER"
  # 按方向选源：pull 时必须从线上取表清单。
  # 以前不分方向一律用 local_sql 当源，本地没有的表（paper_*、stock_*）
  # 根本不出现在清单里，于是「比对通过」掩盖了整张表的缺失。
  if [ "$dir" = "pull" ]; then
    compare_counts "线上" "本地" "remote_sql" "local_sql" || true
  else
    compare_counts "本地" "线上" "local_sql" "remote_sql" || true
  fi
  echo
  if [ "$dir" = "pull" ]; then
    echo "  上面这两个数字就是同步的方向和影响面。"
    echo "  确认无误后执行：bash deploy/sync-db.sh --pull   （会用线上覆盖本地）"
  else
    echo "  上面这两个数字就是同步的方向和影响面。"
    echo "  确认无误后执行：bash deploy/sync-db.sh"
  fi
}

do_push() {
  local assume_yes="$1"
  ensure_local_up
  ensure_remote_up

  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  local backup="$BACKUP_DIR/remote-before-sync-$stamp.sql"
  ensure_backup_dir

  log "① 备份线上库 -> $(basename "$backup")"
  ssh -o BatchMode=yes "$SERVER" \
    "docker exec $REMOTE_CONTAINER sh -c 'mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \
       --default-character-set=utf8mb4 --single-transaction --routines --triggers --databases $DB'" \
    > "$backup" 2>/dev/null || die "线上备份失败，已中止（没有动线上任何数据）"
  verify_dump "$backup"
  log "   备份通过校验（$(du -h "$backup" | cut -f1)）"
  rotate_backups

  # 先导出本地，确认 dump 合格再碰线上
  local dump="$BACKUP_DIR/local-$stamp.sql"
  log "② 导出本地库"
  "$LOCAL_DUMP" -h "$LOCAL_HOST" -P "$LOCAL_PORT" -u"$LOCAL_USER" -p"$LOCAL_PASS" \
    --default-character-set=utf8mb4 --single-transaction --routines --triggers \
    --databases "$DB" > "$dump" 2>/dev/null || die "本地导出失败"
  verify_dump "$dump"
  log "   导出通过校验（$(du -h "$dump" | cut -f1)）"

  if [ "$assume_yes" != "yes" ]; then
    echo
    log "将要用【本地】覆盖【线上】的库 $DB"
    echo "    本地 -> $LOCAL_HOST:$LOCAL_PORT"
    echo "    线上 -> $SERVER:$REMOTE_CONTAINER"
    echo "    线上已备份：$backup"
    echo
    printf '确认同步？输入 yes 继续：'
    local ans; read -r ans
    [ "$ans" = "yes" ] || { log "已取消，线上未改动"; rm -f "$dump"; exit 0; }
  fi

  log "③ 导入线上（走 SSH 管道，中间不落地）"
  # sed 去掉 dump 里的 DROP/CREATE DATABASE —— 容器里已经有 iblog 库，
  # 而且 drop 整个库没有意义（我们只同步数据）。
  local err rc
  err="$(ssh -o BatchMode=yes "$SERVER" \
    "docker exec -i $REMOTE_CONTAINER sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 $DB'" \
    2>&1 < "$dump")" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    printf '%s\n' "$err" | grep -v 'Using a password on the command line' >&2 || true
    die "导入线上失败（退出码 $rc）。线上可能处于中间状态，用 $backup 还原"
  fi
  printf '%s\n' "$err" | grep -v 'Using a password on the command line' >&2 || true

  log "④ 校验行数（本地 vs 线上）"
  if compare_counts "本地" "线上" "local_sql" "remote_sql"; then
    log "同步完成，两边完全一致"
  else
    die "行数不一致，请检查！线上可以用 $backup 还原"
  fi
  log "回滚命令（万一）：ssh $SERVER \"docker exec -i $REMOTE_CONTAINER sh -c 'mysql -uroot -p\\\$MYSQL_ROOT_PASSWORD $DB'\" < $backup"
}

do_pull() {
  local assume_yes="${1:-no}"
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  local backup="$BACKUP_DIR/local-before-pull-$stamp.sql"
  ensure_backup_dir
  ensure_remote_up
  ensure_local_up

  log "① 备份本地库 -> $(basename "$backup")"
  "$LOCAL_DUMP" -h "$LOCAL_HOST" -P "$LOCAL_PORT" -u"$LOCAL_USER" -p"$LOCAL_PASS" \
    --default-character-set=utf8mb4 --single-transaction --routines --triggers \
    --databases "$DB" > "$backup" 2>/dev/null || die "本地备份失败"
  verify_dump "$backup"
  log "   已备份（$(du -h "$backup" | cut -f1)）"
  rotate_backups

  log "② 从线上导出并导入本地（会 DROP 本地库重建）"
  local dump="$BACKUP_DIR/remote-$stamp.sql"
  ssh -o BatchMode=yes "$SERVER" \
    "docker exec $REMOTE_CONTAINER sh -c 'mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \
       --default-character-set=utf8mb4 --single-transaction --routines --triggers --databases $DB'" \
    > "$dump" 2>/dev/null || die "线上导出失败"
  verify_dump "$dump"

  # ⚠️ 这一步之后就不可逆了（本地库被删重建），所以在它之前确认。
  if [ "$assume_yes" != "yes" ]; then
    echo
    log "将要用【线上】覆盖【本地】的库 $DB"
    echo "    线上 -> $SERVER:$REMOTE_CONTAINER"
    echo "    本地 -> $LOCAL_HOST:$LOCAL_PORT"
    echo "    本地已备份：$backup"
    echo
    printf '确认拉取？输入 yes 继续：'
    local ans; read -r ans
    [ "$ans" = "yes" ] || { log "已取消，本地未改动"; rm -f "$dump"; exit 0; }
  fi

  "$LOCAL_MYSQL" -h "$LOCAL_HOST" -P "$LOCAL_PORT" -u"$LOCAL_USER" -p"$LOCAL_PASS" \
    --default-character-set=utf8mb4 \
    -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" \
    2>/dev/null || die "重建本地库失败"

  local err rc
  err="$("$LOCAL_MYSQL" -h "$LOCAL_HOST" -P "$LOCAL_PORT" -u"$LOCAL_USER" -p"$LOCAL_PASS" \
    --default-character-set=utf8mb4 "$DB" < "$dump" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    printf '%s\n' "$err" | grep -v 'Using a password on the command line' >&2 || true
    die "导入本地失败。本地已备份在 $backup"
  fi

  log "③ 校验行数（线上 vs 本地）"
  if compare_counts "线上" "本地" "remote_sql" "local_sql"; then
    log "拉取完成，本地已与线上一致"
  else
    die "行数不一致，请检查！本地可以用 $backup 还原"
  fi
}

# 参数解析：把「方向」和「修饰符」分开。
# 以前写成 `case "${1:-}" in --pull) do_pull ;; ...`，只看第一个参数，
# 于是 `--pull --dry-run` 里 --dry-run 被静默忽略、直接执行了会 DROP 本地库的拉取
# （2026-10-18 真的踩到过）。现在先扫全部参数收集标志，再决定跑哪条路径。
MODIFIER_DRY_RUN=0
MODIFIER_YES=0
DIRECTION=""

for arg in "$@"; do
  case "$arg" in
    --dry-run|-n) MODIFIER_DRY_RUN=1 ;;
    --yes|-y)     MODIFIER_YES=1 ;;
    --pull)       DIRECTION="pull" ;;
    -h|--help)    sed -n '2,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)            die "未知参数：$arg（可用：--dry-run | --pull | --yes）" ;;
  esac
done

if [ "$MODIFIER_DRY_RUN" -eq 1 ]; then
  do_dry_run "${DIRECTION:-push}"
elif [ "$DIRECTION" = "pull" ]; then
  if [ "$MODIFIER_YES" -eq 1 ]; then
    do_pull yes
  else
    do_pull no
  fi
elif [ "$MODIFIER_YES" -eq 1 ]; then
  do_push yes
else
  do_push no
fi
