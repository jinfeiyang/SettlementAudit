离线依赖包目录（容器构建无内网 PyPI 时使用）

构建前把 wheel 包放入本目录：
  python3 -m pip download -r ../requirements.txt -d . --only-binary=:all: --platform manylinux2014_x86_64 --python-version 311

若有内网 PyPI 代理，本目录可留空，改用：
  docker build --build-arg PIP_INDEX_URL=http://<内网pypi>/simple ...
