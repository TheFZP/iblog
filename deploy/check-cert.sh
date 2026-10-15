#!/usr/bin/env bash
# =============================================================
#  证书到期自检
#
#  为什么要它：IP 证书只有 6 天有效期，续期一旦静默失败，**一周内**站点就会
#  变成浏览器报警告的状态 —— 而那看起来不像"我改坏了什么"，很容易想不到。
#  这个脚本每天跑一次，把剩余有效期写进日志，快到期时输出醒目告警。
#
#  用法：
#      bash deploy/check-cert.sh          # 打印状态；剩余 < 48 小时时退出码非 0
# =============================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

CERT=/etc/letsencrypt/live/iblog/fullchain.pem
LOG=/var/log/iblog-certbot.log
WARN_HOURS=48

log() { printf '[%s] [check-cert] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

[ -f "$CERT" ] || { log "!! 证书文件不存在：$CERT"; exit 1; }

END="$(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2)"
END_TS="$(date -d "$END" +%s)"
NOW_TS="$(date +%s)"
LEFT_H=$(( (END_TS - NOW_TS) / 3600 ))

log "证书到期: $END（剩余 ${LEFT_H} 小时）"

if [ "$LEFT_H" -lt 0 ]; then
  log "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  log "!! 证书已过期！浏览器会报警告。请执行："
  log "!!   /opt/certbot/bin/certbot renew --force-renewal \\"
  log "!!     --deploy-hook /opt/iblog/deploy/reload-tls.sh"
  log "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit 2
elif [ "$LEFT_H" -lt "$WARN_HOURS" ]; then
  log "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  log "!! 证书剩余不足 ${WARN_HOURS} 小时 —— 说明自动续期可能失败了。"
  log "!! 排查：tail -50 $LOG ；手工续期："
  log "!!   /opt/certbot/bin/certbot renew --force-renewal \\"
  log "!!     --deploy-hook /opt/iblog/deploy/reload-tls.sh"
  log "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit 1
fi

exit 0
