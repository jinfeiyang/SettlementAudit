# 前端镜像：UOS 基础镜像 + yum nginx + 已构建的 Vue 产物
# 构建前提：先在宿主机执行 frontend 构建（容器内不依赖 node 镜像）
#   cd frontend && npm ci && npm run build
# 构建上下文：仓库根目录
#   docker build -f deploy/docker/web.Dockerfile -t <registry>/<项目>/preaudit-web:v0.1.0 .
FROM 130.36.8.236:8443/library/uos-server-20-1070u1a:latest

# 基础镜像精简：配临时 yum 源（临时域名无内网解析，需 hosts 映射）
# 若构建机无法写入 /etc/hosts，改用：docker build --add-host zcgf-nginx.zcgf-test.com:130.36.8.237 -
RUN printf '[myuos-BaseOS]\nname=UOS Server BaseOS\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/BaseOS\nenabled=1\ngpgcheck=0\n\n[myuos-AppStream]\nname=UOS Server AppStream\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/AppStream\nenabled=1\ngpgcheck=0\n' > /etc/yum.repos.d/myuos.repo \
 && (grep -q 'zcgf-test.com' /etc/hosts || echo "130.36.8.237 zcgf-test.com" >> /etc/hosts) \
 && yum -y install nginx \
 && yum clean all && rm -rf /var/cache/yum

# 若 UOS 源内无 nginx 包：改为 COPY 预编译 nginx（tar 包解压到 /usr/local/nginx），
# 或把 nginx 与静态资源一起放进后端镜像以外的独立镜像；配置文件仍用 deploy/docker/nginx.conf
RUN mkdir -p /usr/share/nginx/html /run /var/log/nginx

COPY frontend/dist/                /usr/share/nginx/html/
COPY deploy/docker/nginx.conf      /etc/nginx/nginx.conf

EXPOSE 80
STOPSIGNAL SIGQUIT
CMD ["nginx", "-g", "daemon off;"]
