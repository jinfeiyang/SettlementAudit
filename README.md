# 通信工程造价结算 AI 预审分析工具

造价人员与 AI 协同的结算预审工具：**双轨并行** = 确定性核算内核（CAD 矢量解析、清单标准化、定额/取费核查、重复计费识别）+ 大模型（图纸-清单语义对齐、疑点归因解释、报告成文；不改金额、不下结论）。

## 仓库结构

```
.
├── backend/        Java 17 + Spring Boot 3.x 业务服务（模块化单体，二期拆服务）
├── ai-service/     Python + FastAPI 独立 AI/解析服务（CAD 解析、清单标准化、LLM 网关、语义对齐）
├── frontend/       Vue 3 + TypeScript + Element Plus + ECharts PC 端
└── docs/           需求、技术选型、功能点、开发计划、容器云部署方案
```

## 环境要求（本地开发）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 已安装（Temurin 17.0.21，JAVA_HOME 已配置） |
| Maven | 3.8+ | 已安装（3.9.9，已加入 PATH） |
| Node.js | 18+（建议 20 LTS） | 已安装 |
| Python | 3.11（本机为 3.14，若二进制依赖安装失败请装 3.11） | 已安装 |
| MySQL | 8.0 | 本地开发库；生产由省公司统一提供 |
| Redis | 6+ | 本地开发可后置（骨架默认可连不上启动） |

> 部署环境为联通容器云平台（K8s，基础镜像 `library/uos-server-20-1070u1a`），详见 [docs/04-容器云部署方案.md](docs/04-容器云部署方案.md)。

## 启动方式

> 推荐：在项目根目录用一条命令拉起三端（需先安装根依赖 `npm install`，会装入 concurrently）。
> 骨架默认可无 MySQL/Redis 启动（懒连接），无需先建库。

```bash
# 根目录一键启动：后端(8080) + AI服务(8000) + 前端(5173)
npm install      # 仅首次，安装 concurrently
npm run dev
```

如需单独启动某端，也可分别执行：

```bash
# 数据库初始化（本地 MySQL，可选）
mysql -uroot -p < backend/src/main/resources/db/schema.sql
mysql -uroot -p < backend/src/main/resources/db/data.sql

# 后端（8080）
npm run dev:backend          # = cd backend && mvn spring-boot:run

# AI/解析服务（8000，需先 pip install -r ai-service/requirements.txt）
npm run dev:ai               # = cd ai-service && python -m uvicorn app.main:app --reload --port 8000

# 前端（5173，代理 /api -> 8080，/ai -> 8000）
npm run dev:web              # = cd frontend && npm run dev
```

> 注意：前端 `vite` 在本机“中文路径 + 深层目录”下会触发 Windows esbuild 目录遍历
> “Access is denied”缺陷（生产 Linux K8s 不受影响）。本地开发如遇此问题，
> 将 `frontend` 复制到 ASCII 短路径（如 `C:\preaudit\frontend`）再 `npm run dev:web` 即可。

## 一期主链路（MVP）

清单导入 → 清单标准化 → 定额套用核查（三级匹配）→ 取费核查 → 疑点清单（三级+行号回溯）→ 报告初稿 → 项目经理复核确认

详细计划见 [docs/03-开发计划-三期里程碑.md](docs/03-开发计划-三期里程碑.md)。