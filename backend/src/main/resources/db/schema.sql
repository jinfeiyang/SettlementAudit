-- ============================================================
-- 通信工程造价结算 AI 预审分析工具 —— 一期库表
-- 规则外置可配置：定额/取费规则落库（带版本与生效时间），支撑台账反哺闭环
-- 结论可追溯：疑点记录来源（规则ID/清单行号/图元ID/AI归因/复核状态）
-- 说明：MySQL 8.0 生产库；本地免装库的 H2 版本见 schema-h2.sql（内容保持一致）
-- ============================================================

CREATE DATABASE IF NOT EXISTS cost_preaudit DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cost_preaudit;

-- 1. 用户与角色（RBAC：造价人员 / 项目经理，4A 统一认证接入预留）
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL COMMENT '登录名/4A账号',
  password VARCHAR(200) COMMENT '密码摘要（SHA-256 加盐；接入 4A 后置空）',
  real_name VARCHAR(64) COMMENT '姓名',
  role_code VARCHAR(32) NOT NULL COMMENT 'AUDITOR 造价人员 / MANAGER 项目经理',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) COMMENT '用户表';

-- 2. 项目与资料
CREATE TABLE IF NOT EXISTS proj_project (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_no VARCHAR(64) NOT NULL COMMENT '项目编号',
  project_name VARCHAR(255) NOT NULL COMMENT '项目名称',
  eng_type VARCHAR(32) COMMENT '工程类型：LINE 线路 / DEVICE 设备安装（页面暂不展示）',
  contract_no VARCHAR(64) COMMENT '合同编号',
  order_no VARCHAR(128) COMMENT '订单编号',
  builder_unit VARCHAR(255) COMMENT '施工单位',
  supervisor_unit VARCHAR(255) COMMENT '监理单位',
  construction_fee DECIMAL(18,2) COMMENT '施工费（元，不含税）',
  city VARCHAR(64) COMMENT '地市',
  owner_user_id BIGINT COMMENT '造价人员',
  settle_amount DECIMAL(18,2) COMMENT '结算送审金额',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IMPORTED/CHECKING/CHECKED/REPORTED/ARCHIVED',
  remark VARCHAR(512) COMMENT '备注',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project_no (project_no)
) COMMENT '预审项目';

CREATE TABLE IF NOT EXISTS proj_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  file_type VARCHAR(32) NOT NULL COMMENT 'DWG/DXF/BOQ_EXCEL/RULE_EXCEL/SETTLE_DOC/OTHER',
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(512) NOT NULL COMMENT '存储路径（容器云 PVC / MinIO）',
  file_size BIGINT,
  parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSING/SUCCESS/FAILED',
  parse_rate DECIMAL(6,2) COMMENT '清单可解析率（%）',
  parse_message VARCHAR(1024) COMMENT '解析提示/失败原因',
  parse_result LONGTEXT COMMENT '解析结果（JSON：图元量算/清单统计）',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) COMMENT '项目资料文件';

-- 3. 工程量清单项（导入原文 + 标准化结果，行号用于回溯）
CREATE TABLE IF NOT EXISTS boq_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  source_file_id BIGINT COMMENT '来源文件',
  row_no INT COMMENT '清单行号（回溯定位用）',
  item_type VARCHAR(32) COMMENT 'QUOTA 工程量 / MATERIAL 乙供材料 / SUPPLY_MATERIAL 甲供材料 / SUPPLY_EQUIPMENT 甲供设备',
  source_sheet VARCHAR(64) COMMENT '来源工作表（表三甲/表四甲/表八）',
  item_code VARCHAR(64) COMMENT '原始编号',
  item_name VARCHAR(255) COMMENT '原始名称',
  feature_desc TEXT COMMENT '项目特征',
  unit VARCHAR(32),
  quantity DECIMAL(18,6),
  unit_price DECIMAL(18,4),
  total_price DECIMAL(18,2),
  std_item_code VARCHAR(64) COMMENT '标准化后编号（清单标准化产出）',
  std_item_name VARCHAR(255) COMMENT '标准化后名称',
  eng_type VARCHAR(32) COMMENT '标准化识别工程类型 LINE/DEVICE',
  match_quota_code VARCHAR(64) COMMENT '推荐定额子目编号（B1）',
  match_quota_name VARCHAR(255) COMMENT '推荐定额名称',
  match_score DECIMAL(6,4) COMMENT '匹配置信度 0~1',
  match_level TINYINT COMMENT '匹配级别 1精确 2语义召回 3未命中',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id),
  KEY idx_project_row (project_id, row_no)
) COMMENT '工程量清单项';

-- 4. 规则库（外置可配置，带版本与生效时间）
CREATE TABLE IF NOT EXISTS rule_quota (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  quota_code VARCHAR(64) NOT NULL COMMENT '定额子目编号',
  quota_name VARCHAR(255) NOT NULL COMMENT '定额名称',
  unit VARCHAR(32),
  base_price DECIMAL(18,4) COMMENT '基价',
  labor_price DECIMAL(18,4) COMMENT '人工费（取费基数用）',
  feature_keys TEXT COMMENT '特征关键词（JSON 数组，语义召回用）',
  eng_type VARCHAR(32) COMMENT '适用工程类型',
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  effective_date DATE,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1生效 0停用',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code_version (quota_code, version)
) COMMENT '定额子目规则';

CREATE TABLE IF NOT EXISTS rule_fee (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  fee_code VARCHAR(64) NOT NULL COMMENT '费用项编码（人工费/材料费/规费/税金…）',
  fee_name VARCHAR(128) NOT NULL,
  base_formula VARCHAR(512) COMMENT '取费基数公式（DSL，如 人工费*1.0）',
  rate DECIMAL(10,6) COMMENT '费率',
  eng_category VARCHAR(64) COMMENT '适用工程类别',
  eng_type VARCHAR(32) COMMENT '适用工程类型',
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  effective_date DATE,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code_version (fee_code, eng_category, version)
) COMMENT '取费规则';

-- 5. 疑点（三级分级 + 来源追溯 + AI 归因 + 人工复核）
CREATE TABLE IF NOT EXISTS issue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  boq_item_id BIGINT COMMENT '关联清单项',
  issue_type VARCHAR(64) NOT NULL COMMENT 'QUOTA_MISMATCH/FEE_ERROR/QTY_ERROR/DUP_CHARGE/DRAWING_MISMATCH/OTHER',
  level TINYINT NOT NULL COMMENT '3高=确定偏差 2中=疑似需复核 1低=提示性',
  rule_id BIGINT COMMENT '命中规则ID（审计链）',
  rule_code VARCHAR(64) COMMENT '命中规则标识（如 B1-EXACT / B2-FEE-RATE）',
  ref_type VARCHAR(32) COMMENT 'BOQ_ROW 清单行号 / ENTITY_ID 图元ID',
  ref_value VARCHAR(255) COMMENT '行号或图元ID（回溯定位）',
  title VARCHAR(255) NOT NULL,
  detail TEXT COMMENT '确定性内核判定明细',
  ai_note TEXT COMMENT 'LLM 归因解释（仅建议，不写入金额/结论）',
  ai_model VARCHAR(64) COMMENT '生成 ai_note 的模型标识',
  review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
  reviewer_id BIGINT,
  review_time DATETIME,
  review_comment VARCHAR(512),
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project_level (project_id, level),
  KEY idx_boq_item (boq_item_id)
) COMMENT '疑点清单';

-- 6. 预审报告（模板装配 + AI 成文，版本管理）
CREATE TABLE IF NOT EXISTS report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  content LONGTEXT COMMENT '结构化内容（JSON：章节/表格/文字，Word 导出源）',
  ai_model VARCHAR(64) COMMENT '成文所用模型（空=模板成文）',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/EXPORTED',
  version INT NOT NULL DEFAULT 1,
  confirm_user_id BIGINT COMMENT '项目经理确认人',
  confirm_time DATETIME,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) COMMENT '预审报告';

-- 7. 异步任务（核查/解析进度，G2）
CREATE TABLE IF NOT EXISTS async_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT COMMENT '所属项目',
  task_type VARCHAR(32) NOT NULL COMMENT 'CHECK 核查 / PARSE 解析',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  progress INT NOT NULL DEFAULT 0 COMMENT '0-100',
  total INT NOT NULL DEFAULT 0,
  message VARCHAR(1024),
  result_json LONGTEXT COMMENT '任务结果摘要（命中率/疑点数等）',
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) COMMENT '异步任务';

-- 8. 审计日志（G3 安全基线）
CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  username VARCHAR(64),
  action VARCHAR(32) COMMENT 'HTTP 方法',
  target VARCHAR(512) COMMENT '请求路径',
  detail VARCHAR(1024) COMMENT '参数摘要',
  ip VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (username),
  KEY idx_time (create_time)
) COMMENT '操作审计日志';

-- ------------------------------------------------------------
-- 已存在的库升级用（新建库无需执行，建表语句已包含这些列）：
-- ALTER TABLE proj_project ADD COLUMN contract_no VARCHAR(64) COMMENT '合同编号' AFTER eng_type;
-- ALTER TABLE proj_project ADD COLUMN order_no VARCHAR(128) COMMENT '订单编号' AFTER contract_no;
-- ALTER TABLE proj_project ADD COLUMN builder_unit VARCHAR(255) COMMENT '施工单位' AFTER order_no;
-- ALTER TABLE proj_project ADD COLUMN supervisor_unit VARCHAR(255) COMMENT '监理单位' AFTER builder_unit;
-- ALTER TABLE proj_project ADD COLUMN construction_fee DECIMAL(18,2) COMMENT '施工费（元，不含税）' AFTER supervisor_unit;
-- 项目编号不唯一（同一项目编号下可有多张订单）：
-- ALTER TABLE proj_project DROP INDEX uk_project_no, ADD INDEX idx_project_no (project_no);
-- ALTER TABLE boq_item ADD COLUMN item_type VARCHAR(32) AFTER row_no, ADD COLUMN source_sheet VARCHAR(64) AFTER item_type;
