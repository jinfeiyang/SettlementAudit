package com.zjlt.preaudit.common.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本归一化与相似度工具：清单标准化、定额语义召回的确定性基础
 */
public final class TextUtil {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    private static final Pattern SPACE = Pattern.compile("[\\s\\u3000]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "及", "与", "和", "或", "等", "项", "工程", "安装", "施工", "制作", "敷设", "及其");

    private TextUtil() {
    }

    /** 全角转半角 + 去空白 + 小写 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = SPACE.matcher(sb.toString()).replaceAll("");
        s = s.replace("（", "(").replace("）", ")").replace("、", "");
        return s.toLowerCase(Locale.ROOT).trim();
    }

    /** 单位归一化：平方米->m2，米->m，百米/千米保留（换算校验用） */
    public static String normalizeUnit(String unit) {
        String u = normalize(unit);
        return switch (u) {
            case "平方米", "平米", "m²", "m2", "㎡" -> "m2";
            case "立方米", "m³", "m3", "方" -> "m3";
            case "米", "m", "延米" -> "m";
            case "百米", "100m" -> "百米";
            case "千米", "公里", "km" -> "km";
            case "台", "台套" -> "台";
            case "个", "只", "件" -> "个";
            case "套", "组" -> "套";
            case "处", "段", "条", "根" -> u;
            default -> u;
        };
    }

    /** 单位换算关系：返回「清单单位 / 定额单位」的系数，无法识别返回 null */
    public static BigDecimal unitFactor(String itemUnit, String quotaUnit) {
        String a = normalizeUnit(itemUnit);
        String b = normalizeUnit(quotaUnit);
        if (a.equals(b)) {
            return BigDecimal.ONE;
        }
        if (a.equals("m") && b.equals("百米")) {
            return new BigDecimal("0.01");
        }
        if (a.equals("百米") && b.equals("m")) {
            return new BigDecimal("100");
        }
        if (a.equals("m") && b.equals("km")) {
            return new BigDecimal("0.001");
        }
        if (a.equals("km") && b.equals("m")) {
            return new BigDecimal("1000");
        }
        if (a.equals("个") && b.equals("台") || a.equals("台") && b.equals("个")) {
            return BigDecimal.ONE;
        }
        if (a.equals("m2") && b.equals("m") || a.equals("m") && b.equals("m2")) {
            return null;
        }
        return null;
    }

    /** 关键词切分：中文双字 + 英文/数字词 */
    public static List<String> tokens(String raw) {
        String s = normalize(raw);
        List<String> tokens = new ArrayList<>();
        for (String seg : s.split("[^\\u4e00-\\u9fa5a-z0-9.]+")) {
            if (seg.isBlank()) {
                continue;
            }
            if (seg.matches("[a-z0-9.]+")) {
                tokens.add(seg);
                continue;
            }
            for (int i = 0; i < seg.length() - 1; i++) {
                String bi = seg.substring(i, i + 2);
                if (!STOP_WORDS.contains(bi)) {
                    tokens.add(bi);
                }
            }
            if (seg.length() == 1) {
                tokens.add(seg);
            }
        }
        return tokens;
    }

    /** 双字 Jaccard 相似度（0~1） */
    public static double similarity(String a, String b) {
        Set<String> sa = new LinkedHashSet<>(tokens(a));
        Set<String> sb = new LinkedHashSet<>(tokens(b));
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0d;
        }
        Set<String> inter = new LinkedHashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** 特征关键词命中率：关键词命中的占比 */
    public static double keywordHit(List<String> keys, String text) {
        if (keys == null || keys.isEmpty()) {
            return 0d;
        }
        String t = normalize(text);
        long hit = keys.stream().map(TextUtil::normalize).filter(k -> !k.isBlank() && t.contains(k)).count();
        return (double) hit / keys.size();
    }

    /** 解析首个数字，失败返回 null */
    public static BigDecimal firstNumber(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(raw.replace(",", "").replace("，", ""));
        if (m.find()) {
            try {
                return new BigDecimal(m.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 依据名称/特征推断工程类型 */
    public static String guessEngType(String itemName, String featureDesc, String stdName) {
        String text = normalize(String.join(" ",
                nullToEmpty(itemName), nullToEmpty(featureDesc), nullToEmpty(stdName)));
        if (text.matches(".*(光缆|电缆|管道|架空|线路|杆|接头盒|人手孔|管孔|敷设|直埋|槽道|桥架).*")) {
            return "LINE";
        }
        if (text.matches(".*(设备|机架|机柜|机盘|天线|馈线|电源|蓄电池|开关|交换机|路由器|传输|基站|安装).*")) {
            return "DEVICE";
        }
        return null;
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
