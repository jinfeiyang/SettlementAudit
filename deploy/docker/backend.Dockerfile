# 业务后端镜像：UOS 基础镜像 + JDK17 + Spring Boot 可执行 jar
# 构建前提：mvn -DskipTests package 产出 backend/target/preaudit-backend-0.1.0.jar
# 构建上下文：仓库根目录
#   docker build -f deploy/docker/backend.Dockerfile -t <registry>/<项目>/preaudit-backend:v0.1.0 .
FROM 130.36.8.236:8443/library/uos-server-20-1070u1a:latest

RUN printf '[myuos-BaseOS]\nname=UOS Server BaseOS\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/BaseOS\nenabled=1\ngpgcheck=0\n\n[myuos-AppStream]\nname=UOS Server AppStream\nbaseurl=http://zcgf-nginx.zcgf-test.com/myuos/AppStream\nenabled=1\ngpgcheck=0\n' > /etc/yum.repos.d/myuos.repo \
 && (grep -q 'zcgf-test.com' /etc/hosts || echo "130.36.8.237 zcgf-test.com" >> /etc/hosts) \
 && yum -y install java-17-openjdk-headless fontconfig tzdata \
 && yum clean all && rm -rf /var/cache/yum

# 若基础源无 java-17-openjdk：注释上一段，改为自带 JDK（解压到 /opt/jdk17）
#   COPY deploy/jdk/jdk-17_linux-x64.tar.gz /tmp/jdk.tar.gz
#   RUN mkdir -p /opt/jdk17 && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk17 --strip-components=1 && rm -f /tmp/jdk.tar.gz
#   ENV JAVA_HOME=/opt/jdk17 PATH=/opt/jdk17/bin:$PATH

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxMetaspaceSize=256m -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

WORKDIR /app
COPY backend/target/preaudit-backend-0.1.0.jar /app/app.jar

# 上传原件挂载点：容器内路径需与 STORAGE_ROOT / PVC 挂载点一致
RUN mkdir -p /data/uploads
VOLUME ["/data/uploads"]

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
