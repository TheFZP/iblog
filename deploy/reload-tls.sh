#!/usr/bin/env bash
# =============================================================
#  certbot 续期成功后由 --deploy-hook 调用：让容器里的 nginx 重新加载新证书
#
#  为什么必须有它：Certbot **不负责**把 IP 证书装进 Web 服务器（nginx/apache 插件
#  不支持 IP 证书），证书只存在磁盘上；不重载 nginx，它就一直在用旧证书，
#  6 天后浏览器照样报警告。
#
#  用法（由 crontab 里的 certbot renew --deploy-hook 自动调用，一般不用手工跑）：
#      bash deploy/reload-tls.sh
# =============================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

LOG=/var/log/iblog-certbot.log
log() { printf '[%s] [reload-tls] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

CERT=/etc/letsencrypt/live/iblog/fullchain.pem
if [ -f "$CERT" ]; then
  log "当前证书到期时间: $(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2)"
else
  log "!! 找不到 $CERT"
fi

RC=0
for c in iblog-web iblog-admin; do
  if ! docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -q true; then
    log "!! 容器 $c 没在运行，跳过"
    RC=1
    continue
  fi
  # 先校验配置再重载：万一配置有问题，重载会让 nginx 挂掉，不如维持旧的继续服务
  if docker exec "$c" nginx -t >/dev/null 2>&1; then
    if docker exec "$c" nginx -s reload >/dev/null 2>&1; then
      log "已重载 $c 的 nginx，新证书生效"
    else
      log "!! 重载 $c 失败"
      RC=1
    fi
  else
    log "!! $c 的 nginx 配置校验不通过，跳过重载（证书已更新，修好后手工重载）"
    RC=1
  fi
done

exit "$RC"
