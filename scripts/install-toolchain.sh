#!/usr/bin/env bash
# 安装后端便携工具链（JDK 17 + Maven 3.9）到 ~/tools，不写系统目录、不需要 sudo。
#
# 适用场景：机器上没有 JDK / Maven，且 brew 不可用（或被权限挡住）。
# 已存在则跳过下载。装完 npm run dev:backend 会自动找到它们（见 scripts/dev-backend.sh）。
#
#   bash scripts/install-toolchain.sh
set -euo pipefail

TOOLS="$HOME/tools"
JDK_VER="17.0.21+9"
JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/mac/OpenJDK17U-jdk_x64_mac_hotspot_17.0.21_9.tar.gz"
MVN_VER="3.9.9"
MVN_URL="https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz"

mkdir -p "$TOOLS"
cd "$TOOLS"

if [ -d "jdk-${JDK_VER}/Contents/Home" ]; then
  echo "✓ JDK ${JDK_VER} 已存在：$TOOLS/jdk-${JDK_VER}/Contents/Home"
else
  echo "↓ 下载 JDK ${JDK_VER}（清华 Adoptium 镜像，约 180MB）..."
  curl -L --fail --max-time 1800 -o jdk.tar.gz "$JDK_URL"
  tar xzf jdk.tar.gz
  rm -f jdk.tar.gz
  echo "✓ JDK 已解压：$TOOLS/jdk-${JDK_VER}/Contents/Home"
fi

if [ -d "apache-maven-${MVN_VER}" ]; then
  echo "✓ Maven ${MVN_VER} 已存在：$TOOLS/apache-maven-${MVN_VER}"
else
  echo "↓ 下载 Maven ${MVN_VER}（Apache 归档，约 9MB）..."
  curl -L --fail --max-time 600 -o maven.tar.gz "$MVN_URL"
  tar xzf maven.tar.gz
  rm -f maven.tar.gz
  echo "✓ Maven 已解压：$TOOLS/apache-maven-${MVN_VER}"
fi

echo
echo "验证："
"$TOOLS/jdk-${JDK_VER}/Contents/Home/bin/java" -version 2>&1 | head -2
"$TOOLS/apache-maven-${MVN_VER}/bin/mvn" -v 2>&1 | head -1
echo
echo "下一步：npm run dev:backend"
