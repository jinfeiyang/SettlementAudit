# 通信工程造价结算 AI 预审分析工具

造价人员与 AI 协同的结算预审工具：**双轨并行** = 确定性核算内核（CAD 矢量解析、清单标准化、定额/取费核查、疑点分级）+ 大模型（语义对齐、疑点归因、报告成文；不改金额、不下结论）。

## 仓库结构

```
.
├── backend/        Java 17 + Spring Boot 3.x 业务服务（模块化单体，二期拆服务）
├── ai-service/     Python + FastAPI 独立 AI/解析服务（CAD 解析、清单解析、LLM 网关）
├── frontend/       Vue 3 + TypeScript + Element Plus + ECharts PC 端
└── docs/           需求、技术选型、功能点、开发计划、容器云部署方案
```

## 一期已实现（M1 主链路）

| 需求点 | 实现内容 | 代码位置 |
| --- | --- | --- |
| A2 清单导入标准化 | Excel 表头自适应解析、行号保留、单位/工程类型归一、可解析率统计、模板下载 | `backend/.../module/boq`、`ai-service/app/services/boq_parser.py` |
| A1 图纸解析 | DXF 直解（ezdxf）→ 图层/块/标注提取 + 线缆长度、设备数量量算；DWG 经 ODA 转换 | `ai-service/app/services/dxf_parser.py` |
| A3 规则库 | 定额子目 / 取费规则 CRUD、Excel 导入与模板下载、版本与生效时间 | `backend/.../module/rule`、前端「规则库」 |
| A4/A5 项目资料 | 项目 CRUD、资料上传、异步解析与状态跟踪、原件下载 | `backend/.../module/project`、前端「项目管理」「资料与解析」 |
| B1 定额三级核查 | 一级精确匹配 → 二级特征语义召回（关键词 + 名称相似度，取前三）→ 三级换算系数校验 | `backend/.../module/check/QuotaMatchService` |
| B2 取费核查 | 取费基数（人工费/直接费）、费率与工程类别匹配、应计金额与送审核算偏差 | `backend/.../module/check/FeeCheckService` |
| D1 疑点清单 | 三级分级（高/中/低）、类型分类（定额/取费/工程量合价）、Excel 导出 | `backend/.../module/issue` |
| D2 回溯定位 | 疑点 → 清单行号，前端跨页定位并高亮 | `IssueService.locate`、`BoqPanel` |
| D3 疑点复核 | 分级/类型/状态筛选、逐条与批量确认/驳回、复核意见 | `backend/.../module/issue`、前端「疑点复核」 |
| C3/D4/D5 报告 | 模板装配 + AI 成文（不可用降级模板文案）、内容编辑、版本管理、项目经理确认、Word 导出 | `backend/.../module/report` |
| F1 流程引擎 | DRAFT→IMPORTED→CHECKING→CHECKED→REPORTED→ARCHIVED 状态校验 | `Dict` + `ProjectService.transit` |
| G2 异步调度 | 核查/解析任务独立线程池 + `async_task` 进度与结果摘要 | `common/config/AsyncConfig`、`CheckRunner` |
| G3 安全基线 | 登录令牌（HMAC）、RBAC（造价人员/项目经理）、写操作审计日志 | `common/security`、`module/system` |

**本期不做（顺延二期）**：B3 图纸-清单自动核对、B4 重复计费识别、C2 归因全量、D2 图元坐标回溯、E 模块。

## 环境要求（本地开发）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 后端编译运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+（建议 20 LTS） | 前端 |
| Python | 3.11 | AI/解析服务（`pip install -r ai-service/requirements.txt`） |
| MySQL | 8.0 | **可选**：local profile 默认使用 H2 文件库，免安装即可跑通 |
| Redis | 6+ | 一期未强依赖 |

> 部署环境为联通容器云平台（K8s，基础镜像 `library/uos-server-20-1070u1a`），详见 [docs/04-容器云部署方案.md](docs/04-容器云部署方案.md)。

## 启动方式

```bash
# 首次安装
npm install                     # 根依赖（concurrently）
cd frontend && npm install      # 前端依赖
python3 -m pip install -r ../ai-service/requirements.txt

# 一键启动：后端(8080, H2 本地库) + AI服务(8000) + 前端(5173)
npm run dev
```

单独启动：

```bash
npm run dev:backend        # 后端，local profile（H2 文件库，自动建表与演示数据）
npm run dev:backend:mysql  # 后端，连接本地 MySQL（需先执行 db/schema.sql）
npm run dev:ai             # AI/解析服务（8000）
npm run dev:web            # 前端（5173，代理 /api -> 8080、/ai -> 8000）
```

访问 `http://localhost:5173`，演示账号：

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `admin` | `123456` | 造价人员 | 项目/资料/清单/规则库/核查/疑点复核/报告生成编辑 |
| `manager` | `123456` | 项目经理 | 以上全部 + 报告确认 |

### MySQL 方式（生产/测试）

```bash
mysql -uroot -p < backend/src/main/resources/db/schema.sql
mysql -uroot -p < backend/src/main/resources/db/data.sql   # 可选演示数据
DB_URL='jdbc:mysql://localhost:3306/cost_preaudit?...' DB_USERNAME=root DB_PASSWORD=xxx npm run dev:backend:mysql
```

## 一期主链路演示（≤10 分钟）

1. `admin` 登录 → 项目管理 → 新建预审项目（工程类型选「通信线路」，填写结算送审金额）。
2. 进入「预审作业」→ 资料与解析 → 下载清单模板，按模板准备清单后上传（自动解析，显示可解析率）。
3. 点「发起自动核查」→ 轮询进度 → 完成后自动跳转疑点复核。
4. 疑点复核：按级别筛选，查看确定性判定明细与 AI 归因建议，逐条确认/驳回；「定位」跳转清单行并高亮。
5. 「预审报告」→ 生成报告初稿（含项目概况、核查结果汇总、疑点明细、处理建议）→ 编辑 → 导出 Word。
6. 退出后用 `manager` 登录 → 报告管理 → 确认报告。

### 指标口径（一期验收对齐）

- **清单可解析率** = 成功解析行 / 清单候选数据行，展示在资料列表与解析提示中（目标 ≥ 95%）。
- **定额前三命中率** = 精确匹配行 + 语义召回达阈值行 / 清单行，展示在项目概览与工作台（目标 ≥ 85%）。
- **疑点分级**：高=确定性偏差（定额未匹配、工程量/合价异常、取费偏差>15%）；中=疑似需复核（语义召回、单位换算、取费偏差>5%）；低=提示性。

## AI/解析服务说明

- 未配置 LLM（`AI_LLM_BASE_URL` / `AI_LLM_API_KEY`）时：`/llm/chat` 返回 503，后端自动降级为模板文案，主链路不受影响。
- 未安装 `ezdxf` 或图纸异常（加密/Xref）时：图纸解析返回明确失败原因，清单核查不受影响。
- DWG 需先由 ODA File Converter 转 DXF：安装后设 `AI_ODA_CONVERTER_PATH`（macOS 一般 `/Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter`，Linux `/usr/bin/ODAFileConverter`）；留空时自动探测这些路径与 PATH，`/health` 的 `oda_converter` 返回实际命中路径；一期图元坐标回溯延后至二期，仅做清单行号回溯。

## 接口

后端启动后访问 `http://localhost:8080/api/doc.html`（Knife4j）查看全部接口：

- `/auth/login`、`/auth/me`
- `/projects`、`/projects/{id}/overview`、`/projects/{id}/files`、`/files/{id}/parse`
- `/files/{id}/drawing?format=dxf|svg`（图纸预览：dxf 供前端交互渲染，svg 为服务端快照降级）
- `/projects/{id}/boq`、`/projects/{id}/boq/row/{rowNo}`
- `/rules/quota`、`/rules/fee`（含 `/import` 与 `/template`）
- `/projects/{id}/check`、`/tasks/{id}`
- `/issues`、`/issues/{id}/review`、`/issues/{id}/locate`、`/issues/export`
- `/projects/{id}/report`、`/reports/{id}`、`/reports/{id}/confirm`、`/reports/{id}/export`
- `/dashboard/stats`

## 开发日报（钉钉表单）

```bash
npm run daily        # 打印提示词，粘贴给 AI：读当天 git 改动 → 写 scripts/daily_notes.txt
npm run daily:build  # 只排版：scripts/daily_notes.txt → scripts/daily_report.txt（纯文本，可直接粘钉钉）
```

- `scripts/daily_notes.txt` 按 `[代码]` / `[其他]` 分段，每条以 `- ` 开头；`#`、`//` 开头为注释。
- `daily:build` 只做排版，不读 git、不总结；默认拒绝生成「notes 非当天修改」的日报，防止把昨天的内容当今天发。
- 补写历史日报：`DAY=2026-11-10 npm run daily:build`；确认要用现有 notes 重排：`npm run daily:build -- --force`。

## 部署（容器云）

一期部署到联通容器云「智创工坊-揭榜」租户，网络与平台约束（74 网段、全局负载均衡、HTTP 强制域名等）已在方案中固化：

- 部署手册（平台参数对照 / 验收自测 / 故障排查）：[deploy/README.md](deploy/README.md)
- 镜像定义：[deploy/docker/](deploy/docker)　构建脚本：`REGISTRY_PROJECT=<项目> TAG=v0.1.0 ./deploy/build.sh`
- K8s 清单参考：[deploy/k8s/](deploy/k8s)（存储声明 / ConfigMap+Secret / 三个 Deployment+Service）
- 生产 profile：`backend/src/main/resources/application-prod.yml`（日志 stdout、文档开关、PVC 路径、优雅停机）

本地开发用 `local` profile（H2 免装库），容器环境用 `prod` profile（MySQL + PVC + `preaudit-ai:8000`）。

## 文档

- [需求说明书](需求说明书-通信工程造价结算AI预审分析工具建设.md)
- [技术选型与架构分析](docs/01-技术选型与架构分析.md)
- [功能点拆分与工作量评估](docs/02-功能点拆分与工作量评估.md)
- [开发计划（三期里程碑）](docs/03-开发计划-三期里程碑.md)
- [容器云部署方案](docs/04-容器云部署方案.md)
