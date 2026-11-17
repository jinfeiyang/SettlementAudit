package com.zjlt.preaudit.module.check.service;

import com.zjlt.preaudit.common.enums.Dict;
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
 * B2 取费核查：取费基数、费率与工程类别匹配，输出应计费用与送审金额偏差。
 *
 * <p>取费基数（直接费）取值顺序：
 * <ol>
 *   <li>清单自带合价 / 单价×数量；</li>
 *   <li>清单无价但已套定额（清单带定额编号或匹配到子目）时，用「数量 × 定额基价」估算，
 *       否则表三甲这类只有定额编号+工程量的清单直接费会被算成 0，取费偏差必然离谱。</li>
 * </ol>
 * 可核算金额的覆盖面太低（约一半以上的行既无价也无定额基价）时不判定偏差，
 * 只提示补充价格信息，避免拿一个算不准的数去下结论。
 */
@Service
public class FeeCheckService {

    private static final BigDecimal FALLBACK_LABOR_RATIO = new BigDecimal("0.35");

    /** 可核算行占比低于该值（%）时不判偏差，只给提示性疑点 */
    private static final BigDecimal MIN_COVERAGE = new BigDecimal("50");

    public CheckDtos.FeeResult check(Project project, List<BoqItem> items,
                                     List<RuleQuota> quotas, List<RuleFee> fees) {
        Map<String, RuleQuota> quotaByCode = new HashMap<>();
        quotas.forEach(q -> {
            if (q.getQuotaCode() != null) {
                quotaByCode.putIfAbsent(TextUtil.normalize(q.getQuotaCode()), q);
            }
        });

        BigDecimal direct = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        // 覆盖面只统计定额子目行：取费基数主要来自定额子目，甲供物资的领用金额不能替它兜底
        int quotaRows = 0;
        int coveredRows = 0;
        for (BoqItem item : items) {
            boolean quotaRow = item.getItemType() == null || Dict.BOQ_QUOTA.equals(item.getItemType());
            BigDecimal amount = amount(item);
            boolean covered = false;
            if (amount != null) {
                direct = direct.add(amount);
                covered = true;
            } else {
                BigDecimal est = estimateByQuota(item, quotaByCode);
                if (est != null) {
                    direct = direct.add(est);
                    estimated = estimated.add(est);
                    covered = true;
                }
            }
            if (quotaRow) {
                quotaRows++;
                if (covered) {
                    coveredRows++;
                }
            }
            RuleQuota quota = item.getMatchQuotaCode() == null ? null
                    : quotaByCode.get(TextUtil.normalize(item.getMatchQuotaCode()));
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
        estimated = scale(estimated);

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
        BigDecimal coverage = quotaRows == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(coveredRows * 100.0 / quotaRows).setScale(2, RoundingMode.HALF_UP);
        BigDecimal settle = project.getSettleAmount();
        BigDecimal deviation = null;
        if (settle != null && expected.compareTo(BigDecimal.ZERO) > 0 && coverage.compareTo(MIN_COVERAGE) >= 0) {
            deviation = settle.subtract(expected)
                    .divide(expected, 6, RoundingMode.HALF_UP);
        }
        return new CheckDtos.FeeResult(direct, labor, feeTotal, expected, settle, deviation,
                estimated, coverage, lines);
    }

    /** 清单行的合价（优先）或数量×单价；都没有返回 null，表示该行没价 */
    private BigDecimal amount(BoqItem item) {
        if (item.getTotalPrice() != null) {
            return item.getTotalPrice();
        }
        if (item.getQuantity() != null && item.getUnitPrice() != null) {
            return item.getQuantity().multiply(item.getUnitPrice());
        }
        return null;
    }

    /** 无价行按定额基价估算：数量按单位换算到定额单位后乘基价 */
    private BigDecimal estimateByQuota(BoqItem item, Map<String, RuleQuota> quotaByCode) {
        if (item.getQuantity() == null) {
            return null;
        }
        String code = item.getMatchQuotaCode() != null ? item.getMatchQuotaCode() : item.getStdItemCode();
        if (code == null) {
            return null;
        }
        RuleQuota quota = quotaByCode.get(TextUtil.normalize(code));
        if (quota == null || quota.getBasePrice() == null) {
            return null;
        }
        BigDecimal factor = TextUtil.unitFactor(item.getUnit(), quota.getUnit());
        if (factor == null) {
            factor = BigDecimal.ONE;
        }
        return quota.getBasePrice().multiply(item.getQuantity()).multiply(factor);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
