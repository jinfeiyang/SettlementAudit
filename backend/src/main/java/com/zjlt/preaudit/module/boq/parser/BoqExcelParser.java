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

        int supplySheet = findSheet(wb, "表八");
        if (supplySheet >= 0) {
            List<List<String>> data = ExcelUtil.readRows(wb, supplySheet);
            int before = rows.size();
            candidates += parseSupplySheet(data, ExcelUtil.sheetName(wb, supplySheet), rows, seq);
            if (rows.size() > before) {
                parts.add("表八 甲供物资 " + (rows.size() - before) + " 条");
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
        return new ParseOutcome(rows, candidates, rate.doubleValue(), message, "表三甲等", hasPrice);
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
            String feature = workDayFeature(ExcelUtil.cell(row, idxSkill), ExcelUtil.cell(row, idxNormal));
            // 序号列只用于人工核对，导入行号统一重排，避免多表合并后重号
            out.add(new Row(++seq[0], Dict.BOQ_QUOTA, sheetName, code, name, feature,
                    ExcelUtil.cell(row, idxUnit), qty, null, null));
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
     * 表八：甲供工程物资领用明细（报审：单价/领出量/实用量/剩余量）。
     * 分「一 甲供材料费」「二 甲供设备费」两段，按段标记给行打类别。
     */
    private int parseSupplySheet(List<List<String>> rows, String sheetName, List<Row> out, int[] seq) {
        int header = findSupplyHeader(rows);
        if (header < 0) {
            return 0;
        }
        int candidates = 0;
        String itemType = Dict.BOQ_SUPPLY_MATERIAL;
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
            BigDecimal qty = TextUtil.firstNumber(ExcelUtil.cell(row, 7));  // 实用量
            if (qty == null) {
                qty = TextUtil.firstNumber(ExcelUtil.cell(row, 5));         // 退回领出量
            }
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            candidates++;
            BigDecimal price = TextUtil.firstNumber(ExcelUtil.cell(row, 4));
            BigDecimal amount = TextUtil.firstNumber(ExcelUtil.cell(row, 8));
            String spec = ExcelUtil.cell(row, 2);
            out.add(new Row(++seq[0], itemType, sheetName, null, name,
                    supplyFeature(spec, ExcelUtil.cell(row, 5), ExcelUtil.cell(row, 9)),
                    ExcelUtil.cell(row, 3), qty, price, amount));
        }
        return candidates;
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

    public record ParseOutcome(List<Row> rows, int candidateRows, double parseRate, String message,
                               String sheetName, boolean hasPrice) {

        public String summary() {
            return String.format("%s，可解析率 %.2f%%", message, parseRate);
        }
    }
}
