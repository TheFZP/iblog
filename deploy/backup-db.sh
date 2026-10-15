#!/usr/bin/env bash
# =============================================================
#  iblog 数据库备份
#
#  由 root 的 crontab 每天调用；也可以手工执行。
#
#  用法：
#      bash deploy/backup-db.sh              # 备份（cron 用的就是这个）
#      bash deploy/backup-db.sh verify       # 把最新备份真的还原到一个临时库，
#                                            # 逐表比对行数，然后删掉临时库。
#                                            # 只备份不验证 = 不知道备份能不能用。
#      bash deploy/backup-db.sh list         # 列出已有备份
#
#  备份内容（都在 /opt/backup/iblog/，权限 700/600）：
#      iblog_<日期>.sql            数据库全量
#      env_<日期>.bak              deploy/.env —— 里面有 MySQL 密码和 JWT 密钥，
#                                  丢了就算有 sql 也还原不回去，必须一起备份
#      docker-compose_<日期>.yml   编排文件，便于在全新机器上重建
#
#  ⚠️ 备份留在同一台机器的同一块盘上，**磁盘损坏或误删会一起丢**。
#     真要防灾，得把 /opt/backup/iblog/ 同步到 OSS 或另一台机器（见 DEPLOY.md「数据备份」）。
# =============================================================
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="/opt/backup/iblog"
CONTAINER="iblog-mysql"
DB="iblog"
VERIFY_DB="iblog_verify"
KEEP_DAYS=14

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die()  { printf '[%s] !! %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; exit 1; }

# 从 stdin 读 SQL，输出 TSV。密码由容器自己的环境变量提供，
# 所以宿主机上不会出现明文密码（docker exec 的命令行里也没有）。
run_sql() {  # $1 = 数据库名，可空
  local db="${1:-}"
  docker exec -i "$CONTAINER" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -B ${db}" 2>/dev/null
}

ensure_db_up() {
  [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ] \
    || die "容器 $CONTAINER 没在运行，无法备份"
}

latest_dump() { ls -1t "$BACKUP_DIR"/iblog_*.sql 2>/dev/null | head -1; }

# ---------- 备份 ----------
do_backup() {
  ensure_db_up
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"

  local stamp sql tmp
  stamp="$(date +%F)"
  sql="$BACKUP_DIR/iblog_${stamp}.sql"
  tmp="$sql.part"

  log "开始备份库 $DB -> $sql"
  # --single-transaction：不锁表，且拿到一致性快照（本库全是 InnoDB）
  #
  # 注意 `2>&1 >"$tmp"` 的顺序：先把 stderr 绑到当前 stdout（被下面的 $( ) 捕获），
  # 再把 stdout 重定向到文件。这样能拿到退出码和错误信息，同时不污染 dump 文件。
  local err rc
  err="$(docker exec "$CONTAINER" sh -c \
    "mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 \
     --single-transaction --routines --triggers --events $DB" 2>&1 >"$tmp")" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -f "$tmp"
    die "mysqldump 退出码 $rc，备份失败：$err"
  fi
  # mysql 客户端对「-p<密码>」有一条固有告警，已知且无害，过滤掉免得 cron 每天报噪音
  if [ -n "$err" ]; then
    printf '%s\n' "$err" | grep -v 'Using a password on the command line' >&2 || true
  fi

  # ---- 完整性校验 ----
  # mysqldump 成功时会在结尾写一行 "-- Dump completed"，这是最直接的成品标记；
  # 只看「文件存在」是不够的，中断的 dump 也会留下一个非空文件。
  if ! grep -q -- '-- Dump completed' "$tmp"; then
    rm -f "$tmp"
    die "备份文件缺少 mysqldump 的完成标记（-- Dump completed），判定失败，已删除"
  fi
  if ! grep -q 'CREATE TABLE' "$tmp"; then
    rm -f "$tmp"
    die "备份里没有 CREATE TABLE，判定异常，已删除"
  fi

  mv "$tmp" "$sql"
  chmod 600 "$sql"
  log "备份完成：$sql（$(du -h "$sql" | cut -f1)，$(grep -c 'INSERT INTO' "$sql" || true) 条 INSERT）"

  # .env 与编排文件（丢了 sql 也还原不回去）
  cp -a "$DEPLOY_DIR/.env" "$BACKUP_DIR/env_${stamp}.bak"
  chmod 600 "$BACKUP_DIR/env_${stamp}.bak"
  cp -a "$DEPLOY_DIR/docker-compose.yml" "$BACKUP_DIR/docker-compose_${stamp}.yml"
  log "已同时备份 .env 与 docker-compose.yml"

  # 轮转
  log "清理 ${KEEP_DAYS} 天前的备份"
  find "$BACKUP_DIR" -type f -name 'iblog_*.sql'        -mtime "+$KEEP_DAYS" -print -delete
  find "$BACKUP_DIR" -type f -name 'env_*.bak'          -mtime "+$KEEP_DAYS" -print -delete
  find "$BACKUP_DIR" -type f -name 'docker-compose_*.yml' -mtime "+$KEEP_DAYS" -print -delete

  log "当前备份目录："
  ls -lh "$BACKUP_DIR" | sed 's/^/    /'
  log "备份流程结束"
}

# ---------- 验证（真的还原一次） ----------
do_verify() {
  ensure_db_up
  local sql; sql="$(latest_dump)"
  [ -n "$sql" ] || die "在 $BACKUP_DIR 里找不到任何 iblog_*.sql"

  log "拿最新备份做还原验证：$sql（$(du -h "$sql" | cut -f1)）"
  log "建临时库 $VERIFY_DB"
  echo "DROP DATABASE IF EXISTS \`$VERIFY_DB\`; CREATE DATABASE \`$VERIFY_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" | run_sql

  log "导入备份到 $VERIFY_DB"
  local err rc
  err="$(docker exec -i "$CONTAINER" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $VERIFY_DB" 2>&1 < "$sql")" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "DROP DATABASE IF EXISTS \`$VERIFY_DB\`;" | run_sql || true
    die "导入失败（退出码 $rc）—— 这份备份不可用！$err"
  fi
  if [ -n "$err" ]; then
    printf '%s\n' "$err" | grep -v 'Using a password on the command line' >&2 || true
  fi

  log "逐表比对行数（$DB  vs  $VERIFY_DB）"
  local tables t a b fail=0
  tables="$(echo "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME" | run_sql)"
  [ -n "$tables" ] || die "读不到源库的表清单"

  while read -r t; do
    [ -n "$t" ] || continue
    a="$(echo "SELECT COUNT(*) FROM \`$DB\`.\`$t\`"        | run_sql)"
    b="$(echo "SELECT COUNT(*) FROM \`$VERIFY_DB\`.\`$t\`" | run_sql)"
    if [ "$a" = "$b" ]; then printf '    ✓ %-20s %s 行\n' "$t" "$a"
    else                     printf '    ✗ %-20s 源库 %s 行 / 备份 %s 行\n' "$t" "$a" "$b"; fail=1; fi
  done <<< "$tables"

  log "删除临时库 $VERIFY_DB"
  echo "DROP DATABASE IF EXISTS \`$VERIFY_DB\`;" | run_sql

  [ "$fail" -eq 0 ] || die "行数不一致，这份备份不能当作有效备份"
  log "还原验证通过：这份备份可以真正用来恢复"
}

case "${1:-backup}" in
  backup) do_backup ;;
  verify) do_verify ;;
  list)   ls -lh "$BACKUP_DIR" 2>/dev/null || echo "（还没有任何备份）" ;;
  -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}" ;;
  *) die "未知参数：$1（可用：backup | verify | list）" ;;
esac
