# 本地验证「容器内 DWG 行为」用的镜像（**非生产镜像**，生产见 ai.Dockerfile：UOS + rpm）
#
# 用途：在开发机复现 Linux 容器里的两件事，这两件在 macOS 上测不到
#   1) 无 DISPLAY 环境时，dxf_parser 是否自动用 xvfb-run 包裹 ODA 调用
#   2) ODA 缺失 / 存在 两种情况下 /health 与 DWG 解析的返回是否符合预期
#
# 构建（仓库根目录）：
#   docker build -f deploy/docker/ai.dwg-local.Dockerfile -t preaudit-ai-dwgtest .
# 说明：镜像内 /opt/oda/ODAFileConverter 是测试替身（oda-stub.py），不做真实转换；
#       拿到 ODA 官方 .deb 后，把下面 COPY 换成 apt/yum 安装真包即可等价验证。
ARG BASE_IMAGE=debian:bookworm-slim
FROM ${BASE_IMAGE}

# 默认用阿里云 PyPI（开发机直连 pypi.org 可能超时）；内网构建请覆盖
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

ENV PYTHONUNBUFFERED=1 LANG=C.UTF-8 TZ=Asia/Shanghai

# xvfb：Linux 版 ODA 依赖 Qt/X11，容器无 DISPLAY 时需虚显
# xauth：xvfb-run 必需（缺它 xvfb-run 直接报 "xauth command not found"）
# x11-utils：提供 xdpyinfo，用于自检虚显是否真的可用
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip xvfb xauth x11-utils ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ai-service/requirements.txt /app/requirements.txt
RUN pip3 install --no-cache-dir --break-system-packages -i "$PIP_INDEX_URL" -r /app/requirements.txt

COPY ai-service/app /app/app
COPY deploy/docker/oda-stub.py /opt/oda/ODAFileConverter
RUN chmod +x /opt/oda/ODAFileConverter

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
