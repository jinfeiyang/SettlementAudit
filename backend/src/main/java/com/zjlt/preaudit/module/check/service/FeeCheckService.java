package com.zjlt.preaudit.module.check.service;

import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.check.dto.CheckDtos;
import com.zjlt.preaudit.module.project.entity.Project;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B2 取费核查：取费基数、费率与工程类别匹配，输出应计费用与送审金额偏差
 */
@Service
public class FeeCheckService {

    private static final BigDecimal FALLBACK_LABOR_RATIO = new BigDecimal("0.35");

    public CheckDtos.FeeResult check(Project project, List<BoqItem> items,
                                     List<RuleQuota> quotas, List<RuleFee> fees) {
        Map<String, RuleQuota> quotaByCode = new HashMap<>();
        quotas.forEach(q -> quotaByCode.putIfAbsent(q.getQuotaCode(), q));

        BigDecimal direct = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        for (BoqItem item : items) {
            direct = direct.add(amount(item));
            RuleQuota quota = item.getMatchQuotaCode() == null ? null : quotaByCode.get(item.getMatchQuotaCode());
            if (quota != null && quota.getLaborPrice() != null && item.getQuantity() != null) {
                BigDecimal factor = TextUtil.unitFactor(item.getUnit(), quota.getUnit());
                if (factor == null) {
                    factor = BigDecimal.ONE;
                }
                labor = labor.add(quota.getLaborPrice().multiply(item.getQuantity()).multiply(factor));
            }
        }
        if (labor.compareTo(BigDecimal.ZERO) == 0 && direct.compareTo(BigDecimal.ZERO) > 0) {
            labor = direct.multiply(FALLBACK_LABOR_RATIO);
        }
        direct = scale(direct);
        labor = scale(labor);

        List<CheckDtos.FeeLine> lines = new ArrayList<>();
        BigDecimal feeTotal = BigDecimal.ZERO;
        for (RuleFee fee : fees) {
            if (fee.getRate() == null) {
                continue;
            }
            boolean onLabor = fee.getBaseFormula() != null && fee.getBaseFormula().contains("人工费");
            BigDecimal base = onLabor ? labor : direct;
            BigDecimal amount = scale(base.multiply(fee.getRate()));
            feeTotal = feeTotal.add(amount);
            lines.add(new CheckDtos.FeeLine(fee.getFeeCode(), fee.getFeeName(), fee.getBaseFormula(),
                    fee.getRate(), base, amount));
        }
        feeTotal = scale(feeTotal);
        BigDecimal expected = scale(direct.add(feeTotal));
        BigDecimal settle = project.getSettleAmount();
        BigDecimal deviation = null;
        if (settle != null && expected.compareTo(BigDecimal.ZERO) > 0) {
            deviation = settle.subtract(expected)
                    .divide(expected, 6, RoundingMode.HALF_UP);
        }
        return new CheckDtos.FeeResult(direct, labor, feeTotal, expected, settle, deviation, lines);
    }

    private BigDecimal amount(BoqItem item) {
        if (item.getTotalPrice() != null) {
            return item.getTotalPrice();
        }
        if (item.getQuantity() != null && item.getUnitPrice() != null) {
            return item.getQuantity().multiply(item.getUnitPrice());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
