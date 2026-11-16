package com.zjlt.preaudit.module.boq.parser;

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
import java.util.regex.Pattern;

/**
 * 工程量清单 Excel 解析（A2）：多工作表自动识别 + 表头自适应 + 行号保留 + 可解析率统计。
 *
 * 真实决算/结算文件通常一个文件多张表（表一汇总 / 表二取费 / 表三甲工程量 …），
 * 清单在「表三甲」等非首个工作表，且前几行是标题、项目名称、建设单位等抬头，
 * 表头下方还有「技工/普工」子表头与 Ⅰ Ⅱ Ⅲ 列标行，故需按列命中数打分选表并过滤列标行。
 */
@Component
public class BoqExcelParser {

    private static final List<String> HEADER_KEYWORDS = List.of(
            "定额编号", "项目编码", "清单编码", "项目名称", "子目名称", "项目特征",
            "计量单位", "工程量", "数量", "单价", "合价", "金额");

    private static final List<String> SKIP_NAME = List.of("合计", "小计", "总计", "总价", "以下空白", "说明");

    /** 仅含符号/罗马数字/纯数字的行（列标行、页脚行） */
    private static final Pattern NOT_MEANINGFUL = Pattern.compile("^[^\\p{IsHan}A-Za-z]+$");

    public ParseOutcome parse(InputStream in, String filename) {
        try (Workbook wb = ExcelUtil.open(in, filename)) {
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
            String sheetName = ExcelUtil.sheetName(wb, bestSheet);
            return parseSheet(bestRows, bestHeader, sheetName);
        } catch (IOException e) {
            return new ParseOutcome(List.of(), 0, 0, "Excel 读取失败：" + e.getMessage(), "", false);
        }
    }

    private ParseOutcome parseSheet(List<List<String>> rows, int headerRow, String sheetName) {
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
            parsed.add(new Row(rowNo, code, name, ExcelUtil.cell(row, idxFeature),
                    ExcelUtil.cell(row, idxUnit), qty, price, total));
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

    public record Row(Integer rowNo, String itemCode, String itemName, String featureDesc,
                      String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal totalPrice) {
    }

    public record ParseOutcome(List<Row> rows, int candidateRows, double parseRate, String message,
                               String sheetName, boolean hasPrice) {

        public String summary() {
            return String.format("%s，可解析率 %.2f%%", message, parseRate);
        }
    }
}
