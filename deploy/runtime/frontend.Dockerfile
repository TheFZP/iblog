# =============================================================
#  前端（管理后台 / 官网）「预构建」运行时镜像
#  不含 Node 构建环节，只把本地打好的 dist 拷进 Nginx。
#  由 deploy/build-local.sh 复制到 deploy/dist/{admin,web}/Dockerfile 使用。
# =============================================================
FROM nginx:1.27-alpine

COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
