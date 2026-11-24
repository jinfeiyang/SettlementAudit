package com.zjlt.preaudit.module.boq.parser;

import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.common.util.ExcelUtil;
import com.zjlt.preaudit.common.util.TextUtil;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工程量清单 Excel 解析（A2）。
 *
 * <p>两条解析路径，自动识别：
 * <ol>
 *   <li><b>联通结算模板</b>（表一~表八）：一个文件里清单分散在多张工作表——
 *       表三甲是定额子目工程量，表八是甲供物资领用明细，两张表必须合并导入并按类别打标，
 *       否则只挑一张表就丢数据。表四甲（乙供材料）属于辅材目录，审计口径不纳入清单，
 *       故不导入。</li>
 *   <li><b>通用清单</b>：单表导入，按表头命中数挑出最像工程量清单的工作表。</li>
 * </ol>
 *
 * <p>行号统一重排为 1..N（多表合并后序号会重复，不能再用原表序号做回溯定位），
 * 原始来源表名记录在 sourceSheet，便于回到 Excel 核对。
 */
@Component
public class BoqExcelParser {

    private static final List<String> HEADER_KEYWORDS = List.of(
            "定额编号", "项目编码", "清单编码", "项目名称", "子目名称", "项目特征",
            "计量单位", "工程量", "数量", "单价", "合价", "金额");

    private static final List<String> SKIP_NAME = List.of("合计", "小计", "总计", "总价", "以下空白", "说明");

    /** 仅含符号/罗马数字/纯数字的行（列标行、页脚行） */
    private static final Pattern NOT_MEANINGFUL = Pattern.compile("^[^\\p{IsHan}A-Za-z]+$");

    /** 结算模板特征工作表 */
    private static final List<String> TEMPLATE_SHEETS = List.of("表三甲", "表四甲", "表八");

    /** 折后人工单价（元/工日）：技工 114×18%、普工 61×18%，与决算表二初审口径一致。
     *  表三甲只有工日、无金额列，导入时按此单价折算单价/合价，使工程量行可计入清单合计。 */
    private static final BigDecimal SKILL_DAY_RATE = new BigDecimal("20.52");
    private static final BigDecimal NORMAL_DAY_RATE = new BigDecimal("10.98");

    public ParseOutcome parse(InputStream in, String filename) {
        try (Workbook wb = ExcelUtil.open(in, filename)) {
            if (isSettlementTemplate(wb)) {
                ParseOutcome outcome = parseSettlementTemplate(wb);
                if (!outcome.rows().isEmpty()) {
                    return outcome;
                }
            }
            return parseSingleSheet(wb);
        } catch (IOException e) {
            return new ParseOutcome(List.of(), 0, 0, "Excel 读取失败：" + e.getMessage(), "", false);
        }
    }

    // ------------------------------------------------------------------
    // 联通结算模板（表一~表八）
    // ------------------------------------------------------------------

    private boolean isSettlementTemplate(Workbook wb) {
        int hit = 0;
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = ExcelUtil.sheetName(wb, i);
            if (TEMPLATE_SHEETS.stream().anyMatch(name::contains)) {
                hit++;
            }
        }
        return hit >= 2;
    }

    private ParseOutcome parseSettlementTemplate(Workbook wb) {
        List<Row> rows = new ArrayList<>();
        List<FixedSegment> fixedSegments = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        int candidates = 0;
        int[] seq = {0};

        int quotaSheet = findSheet(wb, "表三甲");
        if (quotaSheet >= 0) {
            List<List<String>> data = ExcelUtil.readRows(wb, quotaSheet);
            int header = ExcelUtil.findHeaderRow(data, HEADER_KEYWORDS, 12);
            if (header >= 0) {
                int before = rows.size();
                candidates += parseQuotaSheet(data, header, ExcelUtil.sheetName(wb, quotaSheet), rows, seq);
                parts.add("表三甲 工程量 " + (rows.size() - before) + " 条");
            }
        }

        // 表七：按芯数聚合出固定单价施工费（计价依据），同时保留逐段明细供 B4 重复计费识别
        java.util.Map<String, BigDecimal[]> fixedByCore = java.util.Map.of();
        int fixedSheet = findSheet(wb, "表七");
        if (fixedSheet >= 0) {
            fixedByCore = parseFixedPriceSheet(ExcelUtil.readRows(wb, fixedSheet), wb, fixedSegments);
        }

        int supplySheet = findSheet(wb, "表八");
        if (supplySheet >= 0) {
            List<List<String>> data = ExcelUtil.readRows(wb, supplySheet);
            int before = rows.size();
            candidates += parseSupplySheet(data, ExcelUtil.sheetName(wb, supplySheet), rows, seq, fixedByCore);
            if (rows.size() > before) {
                parts.add("表八 甲供物资 " + (rows.size() - before) + " 条");
            }
        }

        // 表五甲：工程建设其他费（安全生产费），汇总为 1 条
        int otherSheet = findSheet(wb, "表五甲");
        if (otherSheet < 0) {
            otherSheet = findSheet(wb, "表五");
        }
        if (otherSheet >= 0) {
            List<List<String>> data = ExcelUtil.readRows(wb, otherSheet);
            int before = rows.size();
            candidates += parseOtherFeeSheet(data, ExcelUtil.sheetName(wb, otherSheet), rows, seq);
            if (rows.size() > before) {
                parts.add("表五 其他费 " + (rows.size() - before) + " 条");
            }
        }

        if (rows.isEmpty()) {
            return new ParseOutcome(List.of(), 0, 0, "识别为联通结算模板，但表三甲/表八 均未解析出有效数据行", "", false);
        }
        BigDecimal rate = candidates == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(rows.size() * 100.0 / candidates).setScale(2, RoundingMode.HALF_UP);
        boolean hasPrice = rows.stream().anyMatch(r -> r.unitPrice() != null || r.totalPrice() != null);
        String message = String.format("联通结算模板：%s，合计 %d 条（行号按导入顺序重排）",
                String.join("、", parts), rows.size());
        // 表二 J_A_PRJ_FEE：定额法建安工程费（除税），用于补齐定额法完整造价
        BigDecimal quotaBuildTotal = readQuotaBuildTotal(wb);
        return new ParseOutcome(rows, candidates, rate.doubleValue(), message, "表三甲等", hasPrice,
                quotaBuildTotal, fixedSegments);
    }

    /** 表三甲：定额子目工程量（定额编号 / 项目名称 / 单位 / 数量 / 合计工日） */
    private int parseQuotaSheet(List<List<String>> rows, int headerRow, String sheetName,
                                List<Row> out, int[] seq) {
        List<String> header = rows.get(headerRow);
        int idxCode = ExcelUtil.findColumn(header, "定额编号", "项目编码", "编号");
        int idxName = ExcelUtil.findColumn(header, "项目名称", "名称");
        int idxUnit = ExcelUtil.findColumn(header, "单位");
        int idxQty = ExcelUtil.findColumn(header, "数量", "工程量");
        int idxTotalGroup = ExcelUtil.findColumn(header, "合计值");
        int idxSkill = idxTotalGroup >= 0 ? idxTotalGroup : ExcelUtil.findColumn(header, "技工");
        int idxNormal = idxTotalGroup >= 0 ? idxTotalGroup + 1 : ExcelUtil.findColumn(header, "普工");

        int candidates = 0;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String name = ExcelUtil.cell(row, idxName);
            if (name == null || isNoise(name)) {
                continue;
            }
            BigDecimal qty = TextUtil.firstNumber(ExcelUtil.cell(row, idxQty));
            if (qty == null) {
                continue;
            }
            candidates++;
            String code = ExcelUtil.cell(row, idxCode);
            String skillRaw = ExcelUtil.cell(row, idxSkill);
            String normalRaw = ExcelUtil.cell(row, idxNormal);
            String feature = workDayFeature(skillRaw, normalRaw);
            // 表三甲无金额列：按合计工日×折后单价折算合价，单价=合价÷工程量。
            // 这样工程量行也有单价/合价，可计入清单合计与后续取费核算。
            BigDecimal skillDays = TextUtil.firstNumber(skillRaw);
            BigDecimal normalDays = TextUtil.firstNumber(normalRaw);
            BigDecimal total = null;
            if (skillDays != null || normalDays != null) {
                total = (skillDays == null ? BigDecimal.ZERO : skillDays.multiply(SKILL_DAY_RATE))
                        .add(normalDays == null ? BigDecimal.ZERO : normalDays.multiply(NORMAL_DAY_RATE))
                        .setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal unitPrice = null;
            if (total != null && qty != null && qty.compareTo(BigDecimal.ZERO) != 0) {
                unitPrice = total.divide(qty, 2, RoundingMode.HALF_UP);
            }
            // 序号列只用于人工核对，导入行号统一重排，避免多表合并后重号
            out.add(new Row(++seq[0], Dict.BOQ_QUOTA, sheetName, code, name, feature,
                    ExcelUtil.cell(row, idxUnit), qty, unitPrice, total));
        }
        return candidates;
    }

    private String workDayFeature(String skill, String normal) {
        BigDecimal s = TextUtil.firstNumber(skill);
        BigDecimal n = TextUtil.firstNumber(normal);
        if (s == null && n == null) {
            return null;
        }
        return "合计工日 技工 " + plain(s) + " / 普工 " + plain(n);
    }

    /**
     * 表七：光缆施工基准价（逐段列示），仅作为计价依据，不生成清单行。
     * 按芯数聚合出 [长度合计, 施工费合计, 段数]，供表八甲供光缆行套用。
     *
     * <p>不同模板差异较大：有的模板「送审工费合计/其他费/熔接测试」是引用外部工作簿的公式，
     * POI 求值失败会返回公式文本；有的模板这几列就是数值。因此优先取工费合计列的数值，
     * 取不到才用工费公式的输入列自行重算：
     * 工费 = 敷设工费单价×折扣系数×长度 + 其他费×长度 + 熔接测试单价×熔接芯数。
     */
    private java.util.Map<String, BigDecimal[]> parseFixedPriceSheet(List<List<String>> rows, Workbook wb,
                                                                     List<FixedSegment> segments) {
        int header = -1;
        for (int i = 0; i < Math.min(12, rows.size()); i++) {
            String joined = String.join(" ", rows.get(i));
            if (joined.contains("送审光缆芯数") || (joined.contains("敷设") && joined.contains("工费"))) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return java.util.Map.of();
        }
        java.util.Map<Integer, BigDecimal> fuseRateByCore = readFuseRate(wb);
        java.util.Map<String, BigDecimal[]> agg = new java.util.LinkedHashMap<>();
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            // 列位固定（表七表头：序号/长度范围/芯数范围/段落/芯数/工费/折扣/其他费/长度/熔接芯数/测试单价/工费合计）
            BigDecimal core = TextUtil.firstNumber(ExcelUtil.cell(row, 4));
            BigDecimal fee = TextUtil.firstNumber(ExcelUtil.cell(row, 5));
            BigDecimal discount = TextUtil.firstNumber(ExcelUtil.cell(row, 6));
            BigDecimal len = TextUtil.firstNumber(ExcelUtil.cell(row, 8));
            BigDecimal fuseCores = TextUtil.firstNumber(ExcelUtil.cell(row, 9));
            if (core == null || len == null || len.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            // 「合计金额」行没有芯数/长度，跳过；优先用表内已算好的工费合计（数值模板）
            BigDecimal amount = plainAmount(ExcelUtil.cell(row, 11));
            BigDecimal resolvedFuseRate = plainAmount(ExcelUtil.cell(row, 10));
            if (resolvedFuseRate == null) {
                resolvedFuseRate = fuseRateByCore.get(core.intValue());
            }
            if (amount == null) {
                if (fee == null) {
                    continue;
                }
                if (discount == null) {
                    discount = new BigDecimal("0.18");
                }
                BigDecimal otherFee = plainAmount(ExcelUtil.cell(row, 7));
                if (otherFee == null) {
                    otherFee = defaultOtherFee(core);
                }
                if (resolvedFuseRate == null) {
                    resolvedFuseRate = fuseCores == null || fuseCores.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO : estimateFuseRate(core);
                }
                amount = fee.multiply(discount).multiply(len)
                        .add(otherFee.multiply(len))
                        .add(resolvedFuseRate.multiply(fuseCores == null ? BigDecimal.ZERO : fuseCores));
            }
            amount = amount.setScale(2, RoundingMode.HALF_UP);
            if (segments != null) {
                segments.add(new FixedSegment(i + 1, ExcelUtil.cell(row, 3), core, len,
                        fee, discount, ExcelUtil.cell(row, 7), fuseCores, resolvedFuseRate, amount));
            }
            String key = core.stripTrailingZeros().toPlainString();
            BigDecimal[] bucket = agg.computeIfAbsent(key, k -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            bucket[0] = bucket[0].add(len);
            bucket[1] = bucket[1].add(amount);
            bucket[2] = bucket[2].add(BigDecimal.ONE);
        }
        return agg;
    }

    /** 只取纯数值单元格：公式文本（含 = 或字母）不会被误当金额 */
    private BigDecimal plainAmount(String raw) {
        if (raw == null || raw.contains("=") || raw.matches(".*[a-zA-Z\\u4e00-\\u9fa5].*")) {
            return null;
        }
        return TextUtil.firstNumber(raw);
    }

    /** 从表二读取 J_A_PRJ_FEE（定额法建安工程费·除税）作为补齐基准 */
    private BigDecimal readQuotaBuildTotal(Workbook wb) {
        int sheet = findSheet(wb, "表二");
        if (sheet < 0) {
            return null;
        }
        for (List<String> row : ExcelUtil.readRows(wb, sheet)) {
            String code = row.size() > 0 ? ExcelUtil.cell(row, 0) : null;
            if (code != null && "J_A_PRJ_FEE".equalsIgnoreCase(code.trim())) {
                return row.size() > 4 ? TextUtil.firstNumber(ExcelUtil.cell(row, 4)) : null;
            }
        }
        return null;
    }

    /** 从「数据」sheet 的光缆施工费基准造价表读取 芯数 -> 熔接测试单价 */
    private java.util.Map<Integer, BigDecimal> readFuseRate(Workbook wb) {
        java.util.Map<Integer, BigDecimal> map = new java.util.HashMap<>();
        int sheet = findSheet(wb, "数据");
        if (sheet < 0) {
            return map;
        }
        for (List<String> row : ExcelUtil.readRows(wb, sheet)) {
            String name = ExcelUtil.cell(row, 1);
            if (name == null || !name.contains("敷设光缆")) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*芯").matcher(name);
            if (!m.find()) {
                continue;
            }
            BigDecimal rate = TextUtil.firstNumber(ExcelUtil.cell(row, 14)); // 熔纤费列
            if (rate != null) {
                map.put(Integer.parseInt(m.group(1)), rate);
            }
        }
        return map;
    }

    /** 其他费（元/米）按芯数档位：48 芯 1.1、96 芯 1.2（与数据 sheet 基准表一致） */
    private BigDecimal defaultOtherFee(BigDecimal core) {
        int c = core.intValue();
        if (c <= 12) {
            return new BigDecimal("0.9");
        }
        if (c <= 24) {
            return new BigDecimal("1.0");
        }
        if (c <= 48) {
            return new BigDecimal("1.1");
        }
        if (c <= 96) {
            return new BigDecimal("1.2");
        }
        if (c <= 144) {
            return new BigDecimal("1.3");
        }
        return new BigDecimal("1.4");
    }

    private BigDecimal estimateFuseRate(BigDecimal core) {
        int c = core.intValue();
        if (c <= 12) {
            return new BigDecimal("33");
        }
        if (c <= 24) {
            return new BigDecimal("27");
        }
        if (c <= 48) {
            return new BigDecimal("22");
        }
        if (c <= 96) {
            return new BigDecimal("18");
        }
        if (c <= 144) {
            return new BigDecimal("15");
        }
        return new BigDecimal("13");
    }

    /**
     * 表五甲：工程建设其他费，按「总计」行的除税价汇总为 1 条（本决算为安全生产费）。
     */
    private int parseOtherFeeSheet(List<List<String>> rows, String sheetName, List<Row> out, int[] seq) {
        int header = -1;
        for (int i = 0; i < Math.min(12, rows.size()); i++) {
            String joined = String.join("", rows.get(i)).replace(" ", "");
            if (joined.contains("费用名称") && joined.contains("金额")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return 0;
        }
        // 累加明细费用行的除税价（第 4 列），跳过「总计」行——总计常是公式，求值失败会返回公式文本
        BigDecimal total = BigDecimal.ZERO;
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String name = ExcelUtil.cell(row, 1);
            if (name == null || isNoise(name) || normalize(name).contains("总计")) {
                continue;
            }
            BigDecimal amount = TextUtil.firstNumber(ExcelUtil.cell(row, 4));
            if (amount != null) {
                total = total.add(amount);
            }
        }
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        out.add(new Row(++seq[0], Dict.BOQ_OTHER, sheetName, "OTHER-FEE", "工程建设其他费",
                "表五汇总（安全生产费等）", "元", BigDecimal.ONE, total, total));
        return 1;
    }

    /**
     * 表八：甲供工程物资领用明细（报审：单价/领出量/实用量/剩余量）。
     * 分「一 甲供材料费」「二 甲供设备费」两段，按段标记给行打类别。
     */
    private int parseSupplySheet(List<List<String>> rows, String sheetName, List<Row> out, int[] seq,
                                 java.util.Map<String, BigDecimal[]> fixedByCore) {
        int header = findSupplyHeader(rows);
        if (header < 0) {
            return 0;
        }
        int candidates = 0;
        String itemType = Dict.BOQ_SUPPLY_MATERIAL;
        // 已套用表七固定单价法施工费的芯数：同芯数多行（光缆/防鼠光缆）只计一次
        java.util.Set<Integer> fixedUsedCores = new java.util.HashSet<>();
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String col0 = normalize(ExcelUtil.cell(row, 0));
            String col1 = normalize(ExcelUtil.cell(row, 1));

            if (col1.contains("甲供材料费") && !col1.contains("合计")) {
                itemType = Dict.BOQ_SUPPLY_MATERIAL;
                continue;
            }
            if (col1.contains("甲供设备费") && !col1.contains("合计")) {
                itemType = Dict.BOQ_SUPPLY_EQUIPMENT;
                continue;
            }
            if (col1.contains("合计") || col1.contains("签章") || col0.contains("签章")) {
                continue;
            }
            String name = ExcelUtil.cell(row, 1);
            if (name == null || isNoise(name)) {
                continue;
            }
            // 数量取表八实用量；实用量单元格是公式读不到值时，用「领出量 − 剩余量」算出来
            BigDecimal issued = TextUtil.firstNumber(ExcelUtil.cell(row, 5));
            BigDecimal remain = TextUtil.firstNumber(ExcelUtil.cell(row, 9));
            BigDecimal used = TextUtil.firstNumber(ExcelUtil.cell(row, 7));
            if (used == null && issued != null && remain != null) {
                used = issued.subtract(remain);
            }
            BigDecimal qty = used != null ? used : issued;
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            candidates++;
            BigDecimal price = TextUtil.firstNumber(ExcelUtil.cell(row, 4));
            BigDecimal amount = TextUtil.firstNumber(ExcelUtil.cell(row, 8));
            String spec = ExcelUtil.cell(row, 2);
            String feature = supplyFeature(spec, ExcelUtil.cell(row, 5), ExcelUtil.cell(row, 9));

            // 固定单价法只适用于表八「甲供材料」段的光缆本体：表七本就是光缆敷设基准价。
            // 名称必须以「光缆」结尾，「光缆分线箱」「光缆交接箱」这类设备行不套用，
            // 否则同一笔施工费会被重复计入；表七按芯数汇总、不区分型号，
            // 同芯数只认第一行（如「光缆」与「防鼠光缆」同为 24 芯，只计一次）。
            boolean opticalCable = Dict.BOQ_SUPPLY_MATERIAL.equals(itemType) && name.endsWith("光缆");
            if (opticalCable && !fixedByCore.isEmpty()) {
                Integer core = extractCore(spec, name);
                if (core != null) {
                    java.util.Map.Entry<String, BigDecimal[]> hit = null;
                    for (java.util.Map.Entry<String, BigDecimal[]> e : fixedByCore.entrySet()) {
                        if (Math.abs(new BigDecimal(e.getKey()).intValue() - core) <= 1) {
                            hit = e;
                            break;
                        }
                    }
                    // 同一芯数的表七汇总只能落到一行上，防止重复计费
                    if (hit != null && !fixedUsedCores.contains(new BigDecimal(hit.getKey()).intValue())) {
                        fixedUsedCores.add(new BigDecimal(hit.getKey()).intValue());
                        BigDecimal[] v = hit.getValue();
                        // 数量保留表八实用量（领出 − 剩余）：表七长度合计是敷设长度，
                        // 和领用量同口径、不扣剩余，不能直接当结算数量
                        amount = v[1].setScale(2, RoundingMode.HALF_UP);
                        price = qty.compareTo(BigDecimal.ZERO) > 0
                                ? amount.divide(qty, 2, RoundingMode.HALF_UP) : amount;
                        feature = "固定单价法施工费（" + eKey(core) + "芯，" + v[2].stripTrailingZeros().toPlainString()
                                + "段汇总）" + (feature == null ? "" : "；" + feature);
                    }
                }
            }
            out.add(new Row(++seq[0], itemType, sheetName, null, name, feature,
                    ExcelUtil.cell(row, 3), qty, price, amount));
        }
        return candidates;
    }

    /** 从光缆型号/名称提取芯数，如 GYTS-48B1 -> 48 */
    private Integer extractCore(String spec, String name) {
        String s = (spec == null ? "" : spec) + " " + (name == null ? "" : name);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[芯B]").matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String eKey(int core) {
        return new BigDecimal(core).stripTrailingZeros().toPlainString();
    }

    /** 表八表头：第 0 列是「序号」且同一行有「工程物资名称」 */
    private int findSupplyHeader(List<List<String>> rows) {
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            List<String> row = rows.get(i);
            if (row.size() > 1 && "序号".equals(normalize(ExcelUtil.cell(row, 0)))
                    && normalize(ExcelUtil.cell(row, 1)).contains("工程物资名称")) {
                return i;
            }
        }
        return -1;
    }

    /** 甲供物资特征：规格 + 领出/剩余量（剩余不为 0 是审计关注点） */
    private String supplyFeature(String spec, String issued, String remain) {
        StringBuilder sb = new StringBuilder();
        if (spec != null) {
            sb.append(spec);
        }
        BigDecimal issuedNum = TextUtil.firstNumber(issued);
        BigDecimal remainNum = TextUtil.firstNumber(remain);
        if (issuedNum != null || remainNum != null) {
            if (sb.length() > 0) {
                sb.append('；');
            }
            sb.append("领出 ").append(plain(issuedNum)).append("，剩余 ").append(plain(remainNum));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // 通用清单（单表）
    // ------------------------------------------------------------------

    private ParseOutcome parseSingleSheet(Workbook wb) {
        int sheetCount = wb.getNumberOfSheets();
        int bestSheet = -1;
        int bestHeader = -1;
        int bestScore = 0;
        List<List<String>> bestRows = null;

        for (int i = 0; i < sheetCount; i++) {
            List<List<String>> rows = ExcelUtil.readRows(wb, i);
            if (rows.isEmpty()) {
                continue;
            }
            int headerRow = ExcelUtil.findHeaderRow(rows, HEADER_KEYWORDS, 12);
            if (headerRow < 0) {
                continue;
            }
            int score = scoreSheet(rows, headerRow);
            if (score > bestScore) {
                bestScore = score;
                bestSheet = i;
                bestHeader = headerRow;
                bestRows = rows;
            }
        }
        if (bestRows == null || bestScore < 3) {
            return new ParseOutcome(List.of(), 0, 0,
                    "未在任何工作表识别到工程量清单表头（需包含：定额/项目编码、项目名称、单位、数量/工程量）；"
                            + "已扫描 " + sheetCount + " 个工作表", "", false);
        }
        return parseGenericSheet(bestRows, bestHeader, ExcelUtil.sheetName(wb, bestSheet));
    }

    private ParseOutcome parseGenericSheet(List<List<String>> rows, int headerRow, String sheetName) {
        List<String> header = rows.get(headerRow);
        int idxNo = ExcelUtil.findColumn(header, "序号", "行号");
        int idxCode = ExcelUtil.findColumn(header, "定额编号", "项目编码", "清单编码", "子目编码", "编号");
        int idxName = ExcelUtil.findColumn(header, "项目名称", "分项名称", "清单名称", "子目名称", "名称");
        int idxFeature = ExcelUtil.findColumn(header, "项目特征", "特征描述", "工作内容");
        int idxUnit = ExcelUtil.findColumn(header, "计量单位", "单位");
        int idxQty = ExcelUtil.findColumn(header, "工程量", "工程数量", "数量");
        int idxPrice = ExcelUtil.findColumn(header, "综合单价", "单价");
        int idxTotal = ExcelUtil.findColumn(header, "合价", "金额", "合计", "总价");

        if (idxName < 0 && idxCode < 0) {
            return new ParseOutcome(List.of(), 0, 0, "工作表「" + sheetName + "」表头缺少「项目名称/定额编号」列", sheetName, false);
        }
        boolean hasPrice = idxPrice >= 0 || idxTotal >= 0;

        List<Row> parsed = new ArrayList<>();
        int candidates = 0;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String name = ExcelUtil.cell(row, idxName);
            String code = ExcelUtil.cell(row, idxCode);
            if (name == null && code == null) {
                continue;
            }
            // 列标行（Ⅰ Ⅱ Ⅲ）、纯符号行、合计/小计行（合计常错位在编码列）
            if (isNoise(name) || isNoise(code)) {
                continue;
            }
            candidates++;
            if (name == null) {
                continue;
            }
            BigDecimal qty = TextUtil.firstNumber(ExcelUtil.cell(row, idxQty));
            BigDecimal price = TextUtil.firstNumber(ExcelUtil.cell(row, idxPrice));
            BigDecimal total = TextUtil.firstNumber(ExcelUtil.cell(row, idxTotal));
            if (total == null && qty != null && price != null) {
                total = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            }
            Integer rowNo = parseRowNo(ExcelUtil.cell(row, idxNo), i + 1);
            parsed.add(new Row(rowNo, Dict.BOQ_QUOTA, sheetName, code, name,
                    ExcelUtil.cell(row, idxFeature), ExcelUtil.cell(row, idxUnit), qty, price, total));
        }
        BigDecimal rate = candidates == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(parsed.size() * 100.0 / candidates).setScale(2, RoundingMode.HALF_UP);
        String message = String.format("工作表「%s」表头第 %d 行，数据行 %d，成功解析 %d 行%s",
                sheetName, headerRow + 1, candidates, parsed.size(),
                hasPrice ? "" : "（该表无单价/合价列，按无价清单处理：仅核查工程量与定额套用）");
        return new ParseOutcome(parsed, candidates, rate.doubleValue(), message, sheetName, hasPrice);
    }

    /** 工作表打分：列命中数 + 有效数量行加成，用于在多工作表中挑出真正的工程量清单 */
    private int scoreSheet(List<List<String>> rows, int headerRow) {
        List<String> header = rows.get(headerRow);
        int score = 0;
        int idxCode = ExcelUtil.findColumn(header, "定额编号", "项目编码", "清单编码", "子目编码", "编号");
        int idxName = ExcelUtil.findColumn(header, "项目名称", "分项名称", "清单名称", "子目名称", "名称");
        int idxUnit = ExcelUtil.findColumn(header, "计量单位", "单位");
        int idxQty = ExcelUtil.findColumn(header, "工程量", "工程数量", "数量");
        int idxPrice = ExcelUtil.findColumn(header, "综合单价", "单价");
        int idxTotal = ExcelUtil.findColumn(header, "合价", "金额", "合计", "总价");
        if (idxCode >= 0) {
            score++;
        }
        if (idxName >= 0) {
            score++;
        }
        if (idxUnit >= 0) {
            score++;
        }
        if (idxQty >= 0) {
            score++;
        }
        if (idxPrice >= 0 || idxTotal >= 0) {
            score++;
        }
        // 数量列大量为 0 的表（工日表）说明不是工程量清单，降低优先级
        if (idxQty >= 0) {
            int nonZero = 0;
            int checked = 0;
            for (int i = headerRow + 1; i < rows.size() && checked < 200; i++) {
                String cell = ExcelUtil.cell(rows.get(i), idxQty);
                if (cell == null) {
                    continue;
                }
                checked++;
                BigDecimal qty = TextUtil.firstNumber(cell);
                if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                    nonZero++;
                }
            }
            if (nonZero > 0) {
                score += 2;
            }
        }
        return score;
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    private int findSheet(Workbook wb, String hint) {
        String target = hint.toLowerCase(Locale.ROOT).replace(" ", "");
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = ExcelUtil.sheetName(wb, i).toLowerCase(Locale.ROOT).replace(" ", "");
            if (name.contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s", "");
    }

    private String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** 非清单数据行：纯符号/罗马数字列标行、合计小计行 */
    private boolean isNoise(String text) {
        if (text == null) {
            return false;
        }
        String t = text.replaceAll("\\s", "");
        if (NOT_MEANINGFUL.matcher(t).matches()) {
            return true;
        }
        return t.length() <= 6 && SKIP_NAME.stream().anyMatch(t::contains);
    }

    private Integer parseRowNo(String raw, int fallback) {
        BigDecimal n = TextUtil.firstNumber(raw);
        if (n == null) {
            return fallback;
        }
        return n.intValue();
    }

    public record Row(Integer rowNo, String itemType, String sourceSheet, String itemCode, String itemName,
                      String featureDesc, String unit, BigDecimal quantity, BigDecimal unitPrice,
                      BigDecimal totalPrice) {
    }

    /** 表七逐段明细：段落名 + 计价要素，供 B4 重复计费识别与前端列表展示 */
    public record FixedSegment(Integer rowNo, String segmentName, BigDecimal coreCount, BigDecimal length,
                               BigDecimal feeRate, BigDecimal discount, String otherFeeRaw,
                               BigDecimal fuseCores, BigDecimal fuseRate, BigDecimal amount) {
    }

    public record ParseOutcome(List<Row> rows, int candidateRows, double parseRate, String message,
                               String sheetName, boolean hasPrice, BigDecimal quotaBuildTotal,
                               List<FixedSegment> fixedSegments) {

        /** 兼容旧 7 参构造 */
        public ParseOutcome(List<Row> rows, int candidateRows, double parseRate, String message,
                            String sheetName, boolean hasPrice, BigDecimal quotaBuildTotal) {
            this(rows, candidateRows, parseRate, message, sheetName, hasPrice, quotaBuildTotal, List.of());
        }

        /** 兼容旧 6 参构造 */
        public ParseOutcome(List<Row> rows, int candidateRows, double parseRate, String message,
                            String sheetName, boolean hasPrice) {
            this(rows, candidateRows, parseRate, message, sheetName, hasPrice, null, List.of());
        }

        public String summary() {
            return String.format("%s，可解析率 %.2f%%", message, parseRate);
        }
    }
}
