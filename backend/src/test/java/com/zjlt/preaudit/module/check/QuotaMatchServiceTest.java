package com.zjlt.preaudit.module.check;

import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.check.service.QuotaMatchService;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 定额三级匹配的核心口径回归（不依赖 Spring 上下文）
 */
class QuotaMatchServiceTest {

    private final QuotaMatchService service = new QuotaMatchService(0.55);

    @Test
    @DisplayName("一级：定额编号完全一致 → 精确匹配")
    void exactMatchByCode() {
        BoqItem item = item("TXL1-001", "敷设室外光缆（架空）", "米", "500");
        CheckDtos.MatchResult result = service.match(item, quotas());

        assertEquals(1, result.level());
        assertEquals("TXL1-001", result.best().quotaCode());
        assertEquals(BigDecimal.ONE, result.bestScore());
        assertTrue(result.unitConvertible(), "米→百米 应能解析换算关系");
    }

    @Test
    @DisplayName("二级：名称近似 + 特征命中 → 语义召回前三名")
    void semanticMatch() {
        BoqItem item = item(null, "架空光缆敷设施工", "百米", "10");
        CheckDtos.MatchResult result = service.match(item, quotas());

        assertEquals(2, result.level());
        assertEquals("TXL1-001", result.best().quotaCode());
        assertTrue(result.candidates().size() <= 3, "候选不超过三名");
        assertTrue(result.bestScore().doubleValue() >= 0.55);
    }

    @Test
    @DisplayName("三级：无相关内容 → 未命中；单位不可换算时给出提示")
    void noMatch() {
        BoqItem item = item(null, "安装一体化开关电源柜", "套", "2");
        CheckDtos.MatchResult result = service.match(item, quotas());

        assertEquals(3, result.level());
        assertFalse(result.unitConvertible());
    }

    private BoqItem item(String code, String name, String unit, String quantity) {
        BoqItem item = new BoqItem();
        item.setItemCode(code);
        item.setItemName(name);
        item.setUnit(unit);
        item.setQuantity(new BigDecimal(quantity));
        item.setEngType("LINE");
        return item;
    }

    private List<RuleQuota> quotas() {
        return List.of(
                quota("TXL1-001", "敷设室外光缆（架空）", "百米", "[\"光缆\",\"架空\",\"敷设\"]"),
                quota("TXL1-002", "敷设管道光缆", "百米", "[\"光缆\",\"管道\",\"敷设\"]"),
                quota("TXL1-003", "敷设埋式光缆", "百米", "[\"光缆\",\"直埋\",\"敷设\"]"),
                quota("TYS2-011", "安装室内通信设备（机架式）", "台", "[\"设备\",\"安装\",\"机架\"]"));
    }

    private RuleQuota quota(String code, String name, String unit, String keys) {
        RuleQuota quota = new RuleQuota();
        quota.setQuotaCode(code);
        quota.setQuotaName(name);
        quota.setUnit(unit);
        quota.setBasePrice(new BigDecimal("320"));
        quota.setFeatureKeys(keys);
        quota.setEngType("LINE");
        quota.setStatus(1);
        return quota;
    }
}
