package com.zjlt.preaudit.module.system.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.security.PasswordUtil;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import com.zjlt.preaudit.module.rule.mapper.RuleFeeMapper;
import com.zjlt.preaudit.module.rule.mapper.RuleQuotaMapper;
import com.zjlt.preaudit.module.system.entity.SysUser;
import com.zjlt.preaudit.module.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 初始数据：演示账号 + 首批规则（通信线路 / 设备安装两类）。
 * 正式定额与费率由 A3 规则库导入任务灌入，此处仅保证开箱可演练主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "123456";

    private final SysUserMapper sysUserMapper;
    private final RuleQuotaMapper ruleQuotaMapper;
    private final RuleFeeMapper ruleFeeMapper;

    @Override
    public void run(ApplicationArguments args) {
        initUsers();
        initQuotaRules();
        initFeeRules();
    }

    private void initUsers() {
        upsertUser("admin", "造价员-演示", Dict.ROLE_AUDITOR);
        upsertUser("manager", "项目经理-演示", Dict.ROLE_MANAGER);
    }

    private void upsertUser(String username, String realName, String roleCode) {
        SysUser exist = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username).last("LIMIT 1"));
        if (exist != null) {
            // 兼容由 data.sql 初始化、未设置密码摘要的历史数据
            if (exist.getPassword() == null || exist.getPassword().isBlank()) {
                exist.setPassword(PasswordUtil.hash(username, DEFAULT_PASSWORD));
                sysUserMapper.updateById(exist);
                log.info("补齐演示账号密码 {} / {}", username, DEFAULT_PASSWORD);
            }
            return;
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setRoleCode(roleCode);
        user.setStatus(1);
        user.setPassword(PasswordUtil.hash(username, DEFAULT_PASSWORD));
        sysUserMapper.insert(user);
        log.info("初始化演示账号 {} / {}", username, DEFAULT_PASSWORD);
    }

    private void initQuotaRules() {
        Long count = ruleQuotaMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        List<RuleQuota> quotas = List.of(
                quota("TXL1-001", "敷设室外光缆（架空）", "百米", "320.0000", "120.0000",
                        "[\"光缆\",\"架空\",\"敷设\"]", Dict.ENG_LINE),
                quota("TXL1-002", "敷设管道光缆", "百米", "410.0000", "150.0000",
                        "[\"光缆\",\"管道\",\"敷设\"]", Dict.ENG_LINE),
                quota("TXL1-003", "敷设埋式光缆", "百米", "380.0000", "140.0000",
                        "[\"光缆\",\"直埋\",\"敷设\"]", Dict.ENG_LINE),
                quota("TXL1-004", "光缆接头盒安装", "个", "85.0000", "40.0000",
                        "[\"光缆\",\"接头盒\",\"安装\"]", Dict.ENG_LINE),
                quota("TXL1-005", "立电杆（8m 以内）", "根", "260.0000", "160.0000",
                        "[\"电杆\",\"立杆\",\"架空\"]", Dict.ENG_LINE),
                quota("TXL1-006", "管道沟土方开挖", "m3", "65.0000", "55.0000",
                        "[\"土方\",\"开挖\",\"管道\"]", Dict.ENG_LINE),
                quota("TXL1-007", "敷设管道电缆", "百米", "520.0000", "180.0000",
                        "[\"电缆\",\"管道\",\"敷设\"]", Dict.ENG_LINE),
                quota("TXL1-008", "光缆熔接成端", "芯", "12.0000", "10.0000",
                        "[\"光缆\",\"成端\",\"熔接\"]", Dict.ENG_LINE),
                quota("TYS2-011", "安装室内通信设备（机架式）", "台", "150.0000", "90.0000",
                        "[\"设备\",\"安装\",\"机架\"]", Dict.ENG_DEVICE),
                quota("TYS2-012", "安装走线架", "米", "45.0000", "30.0000",
                        "[\"走线架\",\"安装\"]", Dict.ENG_DEVICE),
                quota("TYS2-013", "布放设备电源线", "百米", "210.0000", "110.0000",
                        "[\"电源线\",\"布放\",\"设备\"]", Dict.ENG_DEVICE),
                quota("TYS2-014", "安装 ODF 配线架", "架", "180.0000", "100.0000",
                        "[\"ODF\",\"配线架\",\"安装\"]", Dict.ENG_DEVICE),
                quota("TYS2-015", "设备加电测试", "台", "60.0000", "50.0000",
                        "[\"设备\",\"加电\",\"测试\"]", Dict.ENG_DEVICE)
        );
        quotas.forEach(ruleQuotaMapper::insert);
        log.info("初始化演示定额子目 {} 条", quotas.size());
    }

    private void initFeeRules() {
        Long count = ruleFeeMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        List<RuleFee> fees = List.of(
                fee("F01", "措施项目费", "人工费*1.0", "0.050000", "通信线路", Dict.ENG_LINE, 1),
                fee("F02", "规费", "人工费*1.0", "0.026000", "通信线路", Dict.ENG_LINE, 2),
                fee("F03", "税金", "直接费*1.0", "0.090000", "通信线路", Dict.ENG_LINE, 3),
                fee("F01", "措施项目费", "人工费*1.0", "0.045000", "设备安装", Dict.ENG_DEVICE, 1),
                fee("F02", "规费", "人工费*1.0", "0.025000", "设备安装", Dict.ENG_DEVICE, 2),
                fee("F03", "税金", "直接费*1.0", "0.090000", "设备安装", Dict.ENG_DEVICE, 3)
        );
        fees.forEach(ruleFeeMapper::insert);
        log.info("初始化演示取费规则 {} 条", fees.size());
    }

    private RuleQuota quota(String code, String name, String unit, String basePrice, String laborPrice,
                            String keys, String engType) {
        RuleQuota q = new RuleQuota();
        q.setQuotaCode(code);
        q.setQuotaName(name);
        q.setUnit(unit);
        q.setBasePrice(new BigDecimal(basePrice));
        q.setLaborPrice(new BigDecimal(laborPrice));
        q.setFeatureKeys(keys);
        q.setEngType(engType);
        q.setVersion("v1");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setStatus(1);
        return q;
    }

    private RuleFee fee(String code, String name, String formula, String rate, String category,
                        String engType, int sort) {
        RuleFee f = new RuleFee();
        f.setFeeCode(code + "-" + sort);
        f.setFeeName(name);
        f.setBaseFormula(formula);
        f.setRate(new BigDecimal(rate));
        f.setEngCategory(category);
        f.setEngType(engType);
        f.setVersion("v1");
        f.setEffectiveDate(LocalDate.of(2026, 1, 1));
        f.setStatus(1);
        return f;
    }
}
