-- ============================================================
-- 本地免装库（H2 MySQL 兼容模式）建表脚本，字段与 schema.sql 一致
-- 仅用于 local profile 开发自测；生产/测试环境仍以 schema.sql(MySQL) 为准
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password VARCHAR(200),
  real_name VARCHAR(64),
  role_code VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS proj_project (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_no VARCHAR(64) NOT NULL,
  project_name VARCHAR(255) NOT NULL,
  eng_type VARCHAR(32),
  contract_no VARCHAR(64),
  order_no VARCHAR(128),
  builder_unit VARCHAR(255),
  supervisor_unit VARCHAR(255),
  construction_fee DECIMAL(18,2),
  city VARCHAR(64),
  owner_user_id BIGINT,
  settle_amount DECIMAL(18,2),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  remark VARCHAR(512),
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS proj_file (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  file_size BIGINT,
  parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  parse_rate DECIMAL(6,2),
  parse_message VARCHAR(1024),
  parse_result CLOB,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_project ON proj_file(project_id);

CREATE TABLE IF NOT EXISTS boq_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  source_file_id BIGINT,
  row_no INT,
  item_type VARCHAR(32),
  source_sheet VARCHAR(64),
  item_code VARCHAR(64),
  item_name VARCHAR(255),
  feature_desc CLOB,
  unit VARCHAR(32),
  quantity DECIMAL(18,4),
  unit_price DECIMAL(18,4),
  total_price DECIMAL(18,2),
  std_item_code VARCHAR(64),
  std_item_name VARCHAR(255),
  eng_type VARCHAR(32),
  match_quota_code VARCHAR(64),
  match_quota_name VARCHAR(255),
  match_score DECIMAL(6,4),
  match_level TINYINT,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_boq_project ON boq_item(project_id, row_no);

CREATE TABLE IF NOT EXISTS rule_quota (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  quota_code VARCHAR(64) NOT NULL,
  quota_name VARCHAR(255) NOT NULL,
  unit VARCHAR(32),
  base_price DECIMAL(18,4),
  labor_price DECIMAL(18,4),
  feature_keys CLOB,
  eng_type VARCHAR(32),
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  effective_date DATE,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_quota_code_version UNIQUE (quota_code, version)
);

CREATE TABLE IF NOT EXISTS rule_fee (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  fee_code VARCHAR(64) NOT NULL,
  fee_name VARCHAR(128) NOT NULL,
  base_formula VARCHAR(512),
  rate DECIMAL(12,6),
  eng_category VARCHAR(64),
  eng_type VARCHAR(32),
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  effective_date DATE,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_fee_code_version UNIQUE (fee_code, eng_category, version)
);

CREATE TABLE IF NOT EXISTS issue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  boq_item_id BIGINT,
  issue_type VARCHAR(64) NOT NULL,
  level TINYINT NOT NULL,
  rule_id BIGINT,
  rule_code VARCHAR(64),
  ref_type VARCHAR(32),
  ref_value VARCHAR(255),
  title VARCHAR(255) NOT NULL,
  detail CLOB,
  ai_note CLOB,
  ai_model VARCHAR(64),
  review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reviewer_id BIGINT,
  review_time DATETIME,
  review_comment VARCHAR(512),
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_issue_project ON issue(project_id, level);

CREATE TABLE IF NOT EXISTS report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  content CLOB,
  ai_model VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 1,
  confirm_user_id BIGINT,
  confirm_time DATETIME,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_project ON report(project_id);

CREATE TABLE IF NOT EXISTS async_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT,
  task_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  progress INT NOT NULL DEFAULT 0,
  total INT NOT NULL DEFAULT 0,
  message VARCHAR(1024),
  result_json CLOB,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_project ON async_task(project_id);

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  username VARCHAR(64),
  action VARCHAR(32),
  target VARCHAR(512),
  detail VARCHAR(1024),
  ip VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 增量升级：本地 H2 文件库已存在时，CREATE TABLE IF NOT EXISTS 不会补列，这里补一次
ALTER TABLE proj_project ADD COLUMN IF NOT EXISTS contract_no VARCHAR(64);
ALTER TABLE proj_project ADD COLUMN IF NOT EXISTS order_no VARCHAR(128);
ALTER TABLE proj_project ADD COLUMN IF NOT EXISTS builder_unit VARCHAR(255);
ALTER TABLE proj_project ADD COLUMN IF NOT EXISTS supervisor_unit VARCHAR(255);
ALTER TABLE proj_project ADD COLUMN IF NOT EXISTS construction_fee DECIMAL(18,2);

-- 项目编号不唯一：同一项目编号下可有多张订单，去掉历史库上的唯一约束。
-- 注意必须先删约束（H2 给约束建的是 uk_project_no_INDEX_* 这类索引，直接 DROP INDEX 删不到）
ALTER TABLE proj_project DROP CONSTRAINT IF EXISTS uk_project_no;
DROP INDEX IF EXISTS uk_project_no_INDEX_F;
CREATE INDEX IF NOT EXISTS idx_project_no ON proj_project (project_no);

-- 清单行类别与来源表：多表合并导入后用于区分工程量 / 乙供材料 / 甲供物资
ALTER TABLE boq_item ADD COLUMN IF NOT EXISTS item_type VARCHAR(32);
ALTER TABLE boq_item ADD COLUMN IF NOT EXISTS source_sheet VARCHAR(64);
