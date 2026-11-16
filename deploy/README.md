# 容器云部署手册（一期）

目标环境：联通容器云平台，测试租户「智创工坊-揭榜」，K8s 集群 + 可视化界面（BMO 生产集群）。
依据：`docs/04-容器云部署方案.md`、《容器云网络说明 2026-11》、《容器云.txt》培训材料。

```
用户 ──HTTP 80 + 域名──> [全局负载均衡 130.36.8.237]
                              └─> preaudit-web (nginx :80)
                                     ├─ /      静态资源
                                     └─ /api/  ──> preaudit-backend (ClusterIP :8080)
                                                      └─> preaudit-ai (ClusterIP :8000)
                                     MySQL 省公司实例（不在容器内自建）
```

## 1 平台硬约束（照抄即可，勿自创）

| 约束 | 取值 | 来源 |
| --- | --- | --- |
| Pod 网段 | **132.151.74.0/24**（其余三个网段已紧张） | 网络说明 §2 |
| 网络配置 | 主网络「是」+ 网络类型 `macvlan` + 分配策略「随机分配」+ 配置域 **B域** | 网络说明截图 |
| 对外入口 | 必须走负载均衡（Pod IP 可变）；**优先全局负载均衡 130.36.8.237**，B域 132.151.70.240 已高负载 | 网络说明 §3 |
| 服务类型 | 选 **HTTP**（共用 80，**必须域名访问**，B/M 域均可访问）；不选 TCP（M域需另行打网） | 网络说明 §3 |
| 存储 | 先建「存储声明」（动态存储 `juice`，读写**多写**），再在应用里配挂载点 | 网络说明 §1 |
| 基础镜像 | `130.36.8.236:8443/library/uos-server-20-1070u1a:latest`；缺依赖用临时 yum 源（需把 `130.36.8.237 zcgf-test.com` 写入 hosts） | 培训材料 §3.7 |
| 数据库 | 省公司统一 MySQL 实例，不自建容器内库 | 培训材料 §3.1 |
| 迁移 | 测试租户赛后需迁移：镜像全部来自仓库、配置外置、应用无状态 | 培训材料 §1 |
| 打网 | OA → 业务流程系统3.0 → 权限类 → 互联网/跨域/VPN/网络准入 | 网络说明 §末 |

## 2 部署前置（未完成不要开工）

| # | 事项 | 检查点 |
| --- | --- | --- |
| 1 | 云资源申请已批 | 配额 8 核 / 16GiB / PVC 150GB / MySQL 50GB（含冗余，见 `docs/04` §8）；部署实际值远低于配额 |
| 2 | **域名已确认** | HTTP 强制域名，需平台分配或申请；同时确认 80 端口与其他项目共用的分隔方式 |
| 3 | 打网已放行 | M 域办公终端可访问该域名 |
| 4 | MySQL 实例可用 | 已执行 `backend/src/main/resources/db/schema.sql`；从集群内可连通 |
| 5 | 镜像仓库账号可用 | `docker login 130.36.8.236:8443 -u<用户名> -p<密码>` 成功 |
| 6 | 构建机能拉基础镜像 | `docker pull 130.36.8.236:8443/library/uos-server-20-1070u1a:latest` 成功（否则需在内网跳板机构建） |
| 7 | 内网 PyPI 代理或离线 wheel | 否则 AI 镜像 `pip3 install` 会失败；离线包放 `ai-service/wheels/` |
| 8 | 4A 账号（可选） | 一期用自建账号；接入 4A 需可达 `132.151.167.246:29000` |

## 3 构建与推送

```bash
# 一键完成：前端构建 → 后端打包 → 三镜像构建 → 推送
REGISTRY_PROJECT=<项目命名空间> TAG=v0.1.0 ./deploy/build.sh

# AI 依赖走内网源时
REGISTRY_PROJECT=<项目命名空间> TAG=v0.1.0 PIP_INDEX_URL=http://<内网pypi>/simple ./deploy/build.sh
```

产物：

| 镜像 | 基础 | 端口 | 内容 |
| --- | --- | --- | --- |
| `preaudit-web` | UOS + yum nginx | 80 | Vue 构建产物 + `/api` 反代 |
| `preaudit-backend` | UOS + JDK17 | 8080 | Spring Boot 可执行 jar |
| `preaudit-ai` | UOS + Python3 | 8000 | FastAPI + ezdxf/openpyxl（DWG 另需 ODA，见下） |

> DWG 支持需在构建 AI 镜像前，把 ODA File Converter 的 **Linux .rpm 安装包**放入 `deploy/oda/`，再放开 `ai.Dockerfile` 中四行注释（安装包 + Xvfb + `test -x` 校验 + `AI_ODA_CONVERTER_PATH`）。容器云无外网，**不能**在运行时下载；不装 ODA 时 DWG 返回明确失败提示，DXF 与清单核查不受影响。

> 首次构建若 `docker pull` 失败，说明构建机不在容器云网络内，需在内网跳板机重复本节步骤。
>
> 构建前可选自检（任一有 nginx 的机器）：
> ```bash
> nginx -t -c "$PWD/deploy/docker/nginx.conf" -p /tmp   # 仅校验语法，会因 root 路径不存在报 warning
> ```
> 或用镜像自检：`docker run --rm -v "$PWD/deploy/docker/nginx.conf:/etc/nginx/nginx.conf:ro" <web镜像> nginx -t`。

### 3.1 本地验证「容器内 DWG 行为」

macOS 上测不到只有 Linux 容器才走的分支（无 `DISPLAY` → `xvfb-run` 虚显），用开发机 Docker 复现：

```bash
# Docker Hub 不可达时用可达镜像源覆盖基础镜像
docker build --build-arg BASE_IMAGE=docker.m.daocloud.io/library/debian:bookworm-slim \
  -f deploy/docker/ai.dwg-local.Dockerfile -t preaudit-ai-dwgtest .

# 1) 虚显可用（应输出 name of display: :99）
docker run --rm preaudit-ai-dwgtest bash -c 'xvfb-run -a xdpyinfo | head -2'

# 2) 配上 ODA 后端到端（端口避开本地 8000）
docker run -d --name ai-dwgtest -p 18000:8000 \
  -e AI_ODA_CONVERTER_PATH=/opt/oda/ODAFileConverter preaudit-ai-dwgtest
curl -s localhost:18000/health                                  # oda_configured 应为 true
curl -s -F "file=@<任意>.dwg" localhost:18000/parse/dxf          # 应为 SUCCESS + 量算摘要

# 3) 对照：屏蔽 /opt/oda 后应为 oda_configured=false 且 DWG 返回明确提示
docker run -d --name ai-nooda -p 18001:8000 -v /tmp/empty:/opt/oda:ro preaudit-ai-dwgtest
```

镜像内 `/opt/oda/ODAFileConverter` 是 `deploy/docker/oda-stub.py` 测试替身（只验证调用链与虚显，不做真实转换）；拿到 ODA 官方 `.deb` 后把该 COPY 换成安装真包即可等价验证。

## 4 平台建资源（界面操作）

### 4.1 存储声明（先做）

| 名称 | 集群 | 动态存储 | 容量 | 读写权限 |
| --- | --- | --- | --- | --- |
| `preaudit-files` | BMO生产 | juice | 100 GB | 读写（多写） |
| `preaudit-ai-cache` | BMO生产 | juice | 50 GB | 读写（多写） |

合计 150 GB，与云资源申请配额一致（`docs/04` §8）；若平台按实际用量计费，也可先建小容量再逐步扩。

（YAML 参考 `k8s/10-pvc.yaml`）

### 4.2 配置与密钥

参考 `k8s/00-config.yaml` 建 ConfigMap `preaudit-config` 与 Secret `preaudit-secret`。
必填：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`TOKEN_SECRET`（32 位以上随机串）。
选填：`LLM_API_KEY`（留空则 AI 归因/成文降级为模板文案）、`API_DOC_ENABLED`（默认 false）。

### 4.3 三个应用

创建应用时**都选 132.151.74.0/24、B域、macvlan、随机分配**。

| 应用 | 镜像 | 容器端口 | 挂载 | 环境变量 | 探针 |
| --- | --- | --- | --- | --- | --- |
| `preaudit-web` | preaudit-web:v0.1.0 | 80 | 无 | 无 | `/healthz` |
| `preaudit-backend` | preaudit-backend:v0.1.0 | 8080 | `preaudit-files` → `/data/uploads` | ConfigMap + Secret | `/api/health`（就绪延迟 40s、存活 120s） |
| `preaudit-ai` | preaudit-ai:v0.1.0 | 8000 | `preaudit-ai-cache` → `/data/cache` | `AI_LLM_*`、`TZ`、`AI_ODA_CONVERTER_PATH` | `/health` |

资源建议（部署实际值，limits 合计 4.5 核 / 4.25 GiB；申请配额 8 核 / 16 GiB，余量留给扩至 2 副本与解析峰值）：

| 应用 | requests | limits |
| --- | --- | --- |
| preaudit-web | 100m / 128Mi | 500m / 256Mi |
| preaudit-backend | 500m / 1Gi | 2 / 2Gi |
| preaudit-ai | 500m / 1Gi | 2 / 2Gi |

### 4.4 对外服务（关键）

进入 `preaudit-web` 应用 → 服务 → **对外服务** → 添加：

| 字段 | 填法 |
| --- | --- |
| 负载均衡 | **全局负载均衡** |
| 服务类型 | **HTTP**（共用 80 端口） |
| 服务域名 | 平台分配/申请到的域名 |
| Uri 映射 | `/` → `preaudit-web:80` |
| 备注 | 后端与 AI 不建对外服务，内部 ClusterIP 通信 |

- 若改用 TCP（IP 访问，仅便于临时联调）：全局 LB 入口为 `130.36.8.30` / `130.36.8.45`，M 域访问需自行打网。
- 域名与 Uri 映射避免与其他项目冲突：本项目接口统一在 `/api` 前缀下，天然可分。

## 5 上线验收自测（逐条打勾）

1. 域名访问首页正常，刷新任意前端路由（如 `/projects`）不 404。
2. `admin/123456` 登录成功（如已改密用新密码），JWT 后续接口可用。
3. 上传清单 Excel（≤100MB）解析成功，可解析率正常显示。
4. 上传 DXF 图纸，`/parse/dxf` 返回量算结果（线路长度/设备数量）；未装 ezdxf 时提示明确且不影响清单核查。
4.1 DWG 验证（装了 ODA 才有）：`curl http://preaudit-ai:8000/health` 的 `oda_converter` 非空、`oda_configured=true`；再上传 DWG 应返回 `SUCCESS`；留空则为 `false` 且 DWG 提示"安装 ODA 或改用 DXF"。
5. 发起自动核查 → 进度轮询 → 疑点分级输出 → 报告初稿 → Word 导出 → `manager` 确认。
6. **重启 `preaudit-backend` Pod 后，已上传原件与清单仍可访问**（验证 PVC 生效）。
7. 上传 150MB 文件被 nginx 拦下（确认 `client_max_body_size` 生效，前后端限制一致）。
8. `kubectl logs` 或平台日志能看到应用日志（stdout 采集生效），无写盘告警。
9. 关闭 `API_DOC_ENABLED` 后 `/api/doc.html` 不可访问。
10. 迁移演练：删掉一个 Pod，副本自动重建并恢复服务（无状态验证）。

## 6 常见问题

| 现象 | 原因/处理 |
| --- | --- |
| Pod 启动即 CrashLoop，日志报连不上数据库 | 打网未放行或连接串错误；先 `nc -vz <mysql-host> 3306` |
| nginx 502 | 后端未就绪（就绪探针 40s 延迟）；或服务名不一致（须为 `preaudit-backend`） |
| 上传大文件 413 | nginx `client_max_body_size` 与后端 `max-request-size` 不一致 |
| AI 镜像 pip 安装失败 | 无内网 PyPI：用 `ai-service/wheels/` 离线包，或改内网源 |
| preaudit-ai 启动即退，日志报 `Form data requires "python-multipart"` | requirements.txt 缺 `python-multipart`（上传接口依赖），已补齐；离线 wheel 目录需同步增加该包 |
| 图纸解析总失败 | 未装 ezdxf（离线 wheel 是否包含）；DWG 需 ODA Converter 并设 `AI_ODA_CONVERTER_PATH` |
| DWG 报 `cannot connect to X server` | Linux 版 ODA 依赖 Qt/X11：镜像内装 `xorg-x11-server-Xvfb`（代码在无 `DISPLAY` 时自动 `xvfb-run -a` 包裹） |
| DWG 报 `ODA 转换未产出 DXF（exit N）` | 图纸版本高于转换器支持版本、文件加密、或 rpm 未真正装上（`test -x /usr/bin/ODAFileConverter` 校验） |
| 上传文件重启后丢失 | PVC 未挂载或 `STORAGE_ROOT` 未指向 `/data/uploads` |
| DWG 每次解析/预览都要等 5s 以上，日志无"缓存命中" | DXF 转换缓存写不进去：确认 `preaudit-ai-cache` 已挂到 `/data/cache`（未挂载时会回退 `/tmp`，Pod 重启即失效） |
| 迁移后域名访问异常 | 对外服务重新绑定域名；配置项核对（镜像内无环境差异） |

## 7 与本地开发的差异

| 维度 | 本地（local profile） | 容器环境（prod profile） |
| --- | --- | --- |
| 数据库 | H2 文件库，自动建表 + 演示数据 | 省公司 MySQL，手工执行 `db/schema.sql`（`spring.sql.init.mode=never`） |
| 文件存储 | `./data/uploads` | PVC 挂载 `/data/uploads` |
| AI 服务地址 | `http://localhost:8000` | `http://preaudit-ai:8000` |
| 接口文档 | 开启（Knife4j） | 默认关闭（`API_DOC_ENABLED=false`） |
| 日志 | 控制台 | stdout，由平台日志中心采集 |
| 跨域 | 放行 localhost | 同域反代，不需要跨域（`ALLOWED_ORIGINS` 留空） |
