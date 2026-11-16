#!/usr/bin/env bash
# 构建并推送三个镜像到容器云镜像仓库
#
# 用法：
#   REGISTRY_PROJECT=zjlt TAG=v0.1.0 ./deploy/build.sh
#   REGISTRY_PROJECT=zjlt TAG=v0.1.0 PIP_INDEX_URL=http://pypi.xxx/simple ./deploy/build.sh
#
# 前置条件：
#   1. 本地 Docker Desktop 已启动，且能访问 130.36.8.236:8443（内网/VPN）
#   2. docker login 130.36.8.236:8443 -u<容器云用户名> -p<密码>
#   3. 构建机可解析 zcgf-nginx.zcgf-test.com（脚本用 --add-host 注入 130.36.8.237）
set -euo pipefail

REGISTRY="${REGISTRY:-130.36.8.236:8443}"
REGISTRY_PROJECT="${REGISTRY_PROJECT:?请设置 REGISTRY_PROJECT（镜像仓库中的项目命名空间）}"
TAG="${TAG:-v0.1.0}"
PIP_INDEX_URL="${PIP_INDEX_URL:-}"
YUM_HOST="zcgf-nginx.zcgf-test.com:130.36.8.237"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

log "1/5 构建前端产物"
(cd frontend && npm ci --silent && npm run build)

log "2/5 打包后端 jar"
(cd backend && mvn -q -DskipTests package)

log "3/5 校验基础镜像仓库登录状态"
if ! docker info >/dev/null 2>&1; then
  echo "Docker 未运行，请先启动 Docker Desktop" >&2
  exit 1
fi

build_image() {
  local name="$1" dockerfile="$2"; shift 2
  local image="${REGISTRY}/${REGISTRY_PROJECT}/${name}:${TAG}"
  log "构建 ${image}"
  docker build \
    --add-host "$YUM_HOST" \
    -f "$dockerfile" \
    -t "$image" \
    "$@" \
    .
  echo "$image"
}

WEB_IMAGE=$(build_image preaudit-web deploy/docker/web.Dockerfile)
BACKEND_IMAGE=$(build_image preaudit-backend deploy/docker/backend.Dockerfile)
AI_IMAGE=$(build_image preaudit-ai deploy/docker/ai.Dockerfile --build-arg "PIP_INDEX_URL=${PIP_INDEX_URL}")

log "4/5 推送镜像"
for image in "$WEB_IMAGE" "$BACKEND_IMAGE" "$AI_IMAGE"; do
  docker push "$image"
done

log "5/5 完成"
cat <<EOF
已推送：
  $WEB_IMAGE
  $BACKEND_IMAGE
  $AI_IMAGE

平台侧后续步骤见 deploy/README.md：
  1) 创建两个存储声明（preaudit-files 50Gi / preaudit-ai-cache 20Gi）
  2) 创建三个应用（网络配置选 132.151.74.0/24）
  3) 为 preaudit-web 配置「对外服务」：全局负载均衡 + HTTP + 域名 + Uri 映射 /
EOF
