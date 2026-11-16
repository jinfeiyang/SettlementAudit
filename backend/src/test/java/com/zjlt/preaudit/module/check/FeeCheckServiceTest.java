package com.zjlt.preaudit.module.check;

import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.check.service.FeeCheckService;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 取费核查口径回归：直接费、人工费基数、费率与偏差计算
 */
class FeeCheckServiceTest {

    private final FeeCheckService service = new FeeCheckService();

    @Test
    @DisplayName("取费核算：直接费 + 措施费 + 规费 + 税金，并按送审金额计算偏差")
    void checkFeeDeviation() {
        Project project = new Project();
        project.setSettleAmount(new BigDecimal("6738.05"));

        BoqItem item = new BoqItem();
        item.setItemName("敷设室外光缆（架空）");
        item.setUnit("百米");
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("320"));
        item.setTotalPrice(new BigDecimal("3200"));
        item.setMatchQuotaCode("TXL1-001");

        RuleQuota quota = new RuleQuota();
        quota.setQuotaCode("TXL1-001");
        quota.setUnit("百米");
        quota.setLaborPrice(new BigDecimal("120"));

        CheckDtos.FeeResult result = service.check(project, List.of(item), List.of(quota), fees());

        assertEquals(new BigDecimal("3200.00"), result.directCost());
        assertEquals(new BigDecimal("1200.00"), result.laborCost());
        // 措施费 1200*5% = 60，规费 1200*2.6% = 31.2，税金 3200*9% = 288
        assertEquals(new BigDecimal("379.20"), result.feeTotal());
        assertEquals(new BigDecimal("3579.20"), result.expectedAmount());
        assertNotNull(result.deviation());
        assertTrue(result.deviation().doubleValue() > 0.8, "送审金额显著高于应计金额");
    }

    @Test
    @DisplayName("未填写送审金额时不产生偏差（避免误报）")
    void noSettleAmount() {
        Project project = new Project();
        BoqItem item = new BoqItem();
        item.setTotalPrice(new BigDecimal("1000"));

        CheckDtos.FeeResult result = service.check(project, List.of(item), List.of(), fees());
        assertEquals(new BigDecimal("1000.00"), result.directCost());
        assertEquals(null, result.deviation());
    }

    private List<RuleFee> fees() {
        return List.of(
                fee("F01-1", "措施项目费", "人工费*1.0", "0.050000"),
                fee("F02-2", "规费", "人工费*1.0", "0.026000"),
                fee("F03-3", "税金", "直接费*1.0", "0.090000"));
    }

    private RuleFee fee(String code, String name, String formula, String rate) {
        RuleFee fee = new RuleFee();
        fee.setFeeCode(code);
        fee.setFeeName(name);
        fee.setBaseFormula(formula);
        fee.setRate(new BigDecimal(rate));
        fee.setStatus(1);
        return fee;
    }
}
