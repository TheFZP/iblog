# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app

# 安装 pnpm，版本与 package.json 的 packageManager 字段保持一致
ARG PNPM_VERSION=11.5.0
RUN npm install -g pnpm@${PNPM_VERSION} --registry=https://registry.npmmirror.com

# 先装依赖，利用 Docker 层缓存（.npmrc 里已固定公网镜像源）
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
