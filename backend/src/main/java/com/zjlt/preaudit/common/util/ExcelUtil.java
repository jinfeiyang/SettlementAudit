package com.zjlt.preaudit.common.util;

import com.zjlt.preaudit.common.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Excel 读写工具（清单/规则导入导出、疑点导出共用）
 */
public final class ExcelUtil {

    private static final DataFormatter FORMATTER = new DataFormatter(Locale.CHINA);

    private ExcelUtil() {
    }

    public static Workbook open(InputStream in, String filename) {
        try {
            if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xls")) {
                return new HSSFWorkbook(in);
            }
            return new XSSFWorkbook(in);
        } catch (IOException e) {
            throw new BizException("Excel 解析失败：" + e.getMessage());
        }
    }

    /** 读取首个工作表为二维字符串表（保留原始行号顺序，空行保留为空列表） */
    public static List<List<String>> readRows(InputStream in, String filename) {
        try (Workbook wb = open(in, filename)) {
            return readRows(wb, 0);
        } catch (IOException e) {
            throw new BizException("Excel 读取失败：" + e.getMessage());
        }
    }

    /**
     * 读取指定工作表为二维字符串表（真实决算文件清单常在「表三甲」等非首个工作表）。
     *
     * 必须带公式求值器：联通结算模板里合计、工日这类单元格是公式（E6*F6），
     * 不取值只格式化会拿到公式文本本身，后续按数字解析就会把 "E6*F6" 读成 6。
     */
    public static List<List<String>> readRows(Workbook wb, int sheetIndex) {
        Sheet sheet = wb.getSheetAt(sheetIndex);
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        evaluator.setIgnoreMissingWorkbooks(true);
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        int lastCol = 0;
        for (Row row : sheet) {
            lastCol = Math.max(lastCol, row.getLastCellNum());
        }
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < lastCol; c++) {
                Cell cell = row.getCell(c);
                String value;
                try {
                    value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                } catch (RuntimeException e) {
                    // 个别公式算不出来（引用外链/未定义名称）时退回原值，避免整张表读不出来
                    value = cell == null ? "" : formatter.formatCellValue(cell);
                }
                cells.add(value == null ? "" : value.trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    /** 工作表名称（越界返回空串） */
    public static String sheetName(Workbook wb, int sheetIndex) {
        if (sheetIndex < 0 || sheetIndex >= wb.getNumberOfSheets()) {
            return "";
        }
        return wb.getSheetName(sheetIndex);
    }

    /** 在前 limit 行内查找表头行下标，命中关键词数最多且不少于 2 个者 */
    public static int findHeaderRow(List<List<String>> rows, List<String> keywords, int limit) {
        int bestRow = -1;
        int bestHit = 1;
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            int hit = 0;
            for (String cell : rows.get(i)) {
                if (containsAny(cell, keywords)) {
                    hit++;
                }
            }
            if (hit > bestHit) {
                bestHit = hit;
                bestRow = i;
            }
        }
        return bestRow;
    }

    /** 按关键词定位列下标，未找到返回 -1 */
    public static int findColumn(List<String> header, String... keywords) {
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            for (String kw : keywords) {
                if (cell != null && cell.replaceAll("\\s", "").contains(kw)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String v = row.get(index);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean containsAny(String cell, List<String> keywords) {
        if (cell == null || cell.isBlank()) {
            return false;
        }
        String c = cell.replaceAll("\\s", "");
        return keywords.stream().anyMatch(c::contains);
    }

    /** 写出 xlsx 到响应（文件名 URL 编码，兼容中文） */
    public static void writeToResponse(HttpServletResponse response, String fileName,
                                       String sheetName, List<String> headers,
                                       List<List<String>> data) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 22 * 256);
            }
            for (int r = 0; r < data.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = data.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c) == null ? "" : values.get(c));
                }
            }
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            OutputStream out = response.getOutputStream();
            wb.write(out);
            out.flush();
        } catch (IOException e) {
            throw new BizException("Excel 导出失败：" + e.getMessage());
        }
    }
}
