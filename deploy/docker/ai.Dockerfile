# AI/解析服务镜像：UOS 基础镜像 + Python3 + ezdxf/openpyxl
# 构建上下文：仓库根目录
#   docker build -f deploy/docker/ai.Dockerfile -t <registry>/<项目>/preaudit-ai:v0.1.0 \
#     --build-arg PIP_INDEX_URL=http://<内网pypi>/simple .
FROM 130.36.8.236:8443/library/uos-server-20-1070u1a:latest

# 内网 PyPI 代理；无代理时把 wheel 包放入 ai-service/wheels/ 走离线安装
ARG PIP_INDEX_URL=""

RUN printf '[myuos-BaseOS]\nname=UOS Server BaseOS\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/BaseOS\nenabled=1\ngpgcheck=0\n\n[myuos-AppStream]\nname=UOS Server AppStream\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/AppStream\nenabled=1\ngpgcheck=0\n' > /etc/yum.repos.d/myuos.repo \
 && (grep -q 'zcgf-test.com' /etc/hosts || echo "130.36.8.237 zcgf-test.com" >> /etc/hosts) \
 && yum -y install python3 python3-pip gcc gcc-c++ libffi-devel fontconfig tzdata \
 && yum clean all && rm -rf /var/cache/yum

ENV TZ=Asia/Shanghai LANG=C.UTF-8 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

WORKDIR /app
COPY ai-service/requirements.txt /app/requirements.txt
COPY ai-service/wheels/ /tmp/wheels/

# 优先离线 wheel；否则走内网源；两者都没有则用默认源（需外网，容器云通常不可用）
# 判断依据必须是「有无 .whl 文件」：wheels/ 目录里带 README.md，用 ls -A 判空会误走离线分支导致安装失败
RUN if ls /tmp/wheels/*.whl >/dev/null 2>&1; then \
        pip3 install --no-index --find-links=/tmp/wheels -r /app/requirements.txt ; \
    elif [ -n "$PIP_INDEX_URL" ]; then \
        pip3 install -i "$PIP_INDEX_URL" -r /app/requirements.txt ; \
    else \
        pip3 install -r /app/requirements.txt ; \
    fi \
 && rm -rf /tmp/wheels

COPY ai-service/app /app/app

# DWG 支持（可选）：把 ODA File Converter 的 Linux 安装包（.rpm）放入 deploy/oda/ 后取消下面注释。
# Linux 版 ODAFileConverter 依赖 Qt/X11，容器内无 DISPLAY，需 Xvfb 虚显（代码在无 DISPLAY 时会自动用 xvfb-run 包裹）。
# xorg-x11-xauth 不可省：xvfb-run 依赖 xauth，缺失会直接报 "xauth command not found"。
# 容器云无外网，安装包必须随构建上下文带入，不能在运行时下载。
# COPY deploy/oda/ /opt/oda/
# RUN yum -y install xorg-x11-server-Xvfb xorg-x11-xauth /opt/oda/*.rpm \
#  && rm -rf /opt/oda \
#  && test -x /usr/bin/ODAFileConverter
# ENV AI_ODA_CONVERTER_PATH=/usr/bin/ODAFileConverter

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
