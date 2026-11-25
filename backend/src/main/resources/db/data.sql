-- 初始化种子数据（演示用，正式定额/取费数据由 A3 规则库导入任务灌入）
USE cost_preaudit;

-- 默认用户：admin/系统管理员 + manager/项目经理（骨架占位，接入 4A 后替换）
-- 管理员按项目经理权限开通，才能使用用户管理等管理功能
INSERT INTO sys_user (username, real_name, role_code)
SELECT 'admin', '系统管理员', 'MANAGER' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
INSERT INTO sys_user (username, real_name, role_code)
SELECT 'manager', '项目经理', 'MANAGER' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'manager');

-- 示例定额（通信线路类，正式口径以 A3 导入为准）
INSERT INTO rule_quota (quota_code, quota_name, unit, base_price, feature_keys, eng_type, version, effective_date)
SELECT 'TXL1-001', '敷设室外光缆（架空）', '百米', 320.0000, '["光缆","架空","敷设"]', 'LINE', 'v1', '2026-01-01' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rule_quota WHERE quota_code = 'TXL1-001' AND version = 'v1');
INSERT INTO rule_quota (quota_code, quota_name, unit, base_price, feature_keys, eng_type, version, effective_date)
SELECT 'TXL1-002', '敷设管道光缆', '百米', 410.0000, '["光缆","管道","敷设"]', 'LINE', 'v1', '2026-01-01' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rule_quota WHERE quota_code = 'TXL1-002' AND version = 'v1');
INSERT INTO rule_quota (quota_code, quota_name, unit, base_price, feature_keys, eng_type, version, effective_date)
SELECT 'TYS2-011', '安装室内通信设备（机架式）', '台', 150.0000, '["设备","安装","机架"]', 'DEVICE', 'v1', '2026-01-01' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rule_quota WHERE quota_code = 'TYS2-011' AND version = 'v1');

-- 示例取费规则（费率与公式以现行费用定额为准，此处为骨架占位）
INSERT INTO rule_fee (fee_code, fee_name, base_formula, rate, eng_category, eng_type, version, effective_date)
SELECT 'F01', '措施项目费', '人工费*1.0', 0.050000, '通信线路', 'LINE', 'v1', '2026-01-01' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rule_fee WHERE fee_code = 'F01' AND eng_category = '通信线路' AND version = 'v1');
INSERT INTO rule_fee (fee_code, fee_name, base_formula, rate, eng_category, eng_type, version, effective_date)
SELECT 'F02', '规费', '人工费*1.0', 0.026000, '通信线路', 'LINE', 'v1', '2026-01-01' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rule_fee WHERE fee_code = 'F02' AND eng_category = '通信线路' AND version = 'v1');
