package com.zjlt.preaudit.module.boq.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表七段落名称重复检测（B4）。
 *
 * <p>两类重复：
 * <ol>
 *   <li>起止点反转：A-B 与 B-A 是同一段，规范化后按段排序得到相同 dedupKey；</li>
 *   <li>名称高度相似：编辑距离归一化相似度超过阈值，视为同一段的两种写法
 *       （中文段落名只差一两个字时，Jaccard 二元组会被分母放大而漏判，故用编辑距离）。</li>
 * </ol>
 *
 * <p>只做提示不做自动核减，遵循「宁可少报不可乱报」，相似阈值取 0.88 偏保守。
 */
public final class FixedSegmentDupDetector {

    /** 编辑距离归一化相似度阈值，超过才判为高度相似 */
    private static final double SIMILAR_THRESHOLD = 0.88;

    private FixedSegmentDupDetector() {
    }

    public record Result(String dedupKey, int dupGroup, String dupType) {
    }

    /** 对一组段落名做重复分组，返回与输入等长的结果列表 */
    public static List<Result> detect(List<String> names) {
        int n = names.size();
        List<Result> results = new ArrayList<>(n);
        if (n == 0) {
            return results;
        }
        List<String> keys = new ArrayList<>(n);
        List<String> norms = new ArrayList<>(n);
        for (String name : names) {
            String norm = normalize(name);
            norms.add(norm);
            keys.add(dedupKey(norm));
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        // 起止点反转或完全同名：dedupKey 相同即同组
        Map<String, Integer> firstByKey = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Integer prev = firstByKey.putIfAbsent(keys.get(i), i);
            if (prev != null) {
                union(parent, prev, i);
            }
        }
        // 名称高度相似：不同键之间两两比对后合并
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (find(parent, i) == find(parent, j)) {
                    continue;
                }
                if (similarity(norms.get(i), norms.get(j)) >= SIMILAR_THRESHOLD) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        // 组号按最早出现的下标排序，保证结果稳定
        List<Map.Entry<Integer, List<Integer>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((a, b) -> Integer.compare(a.getValue().get(0), b.getValue().get(0)));
        int[] groupNo = new int[n];
        int[] typeByGroup = new int[ordered.size() + 1];
        for (int g = 0; g < ordered.size(); g++) {
            List<Integer> members = ordered.get(g).getValue();
            boolean dup = members.size() > 1;
            // 组内 dedupKey 全相同 → 起止反转/完全同名；否则是靠相似度合并 → 名称近似
            String firstKey = keys.get(members.get(0));
            boolean reversed = dup && members.stream().allMatch(idx -> firstKey.equals(keys.get(idx)));
            typeByGroup[g + 1] = !dup ? 0 : (reversed ? 1 : 2);
            for (int idx : members) {
                groupNo[idx] = dup ? g + 1 : 0;
            }
        }

        for (int i = 0; i < n; i++) {
            int type = groupNo[i] == 0 ? 0 : typeByGroup[groupNo[i]];
            results.add(new Result(keys.get(i), groupNo[i],
                    type == 1 ? "REVERSED" : type == 2 ? "SIMILAR" : null));
        }
        return results;
    }

    /** 去空白、全角转半角、连接符统一为 - */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isWhitespace(c) || c == '\u3000') {
                continue;
            }
            char x = c;
            if (x >= '\uFF01' && x <= '\uFF5E') {
                x = (char) (x - 0xFEE0);
            }
            if (x == '-' || x == '\u2013' || x == '\u2014' || x == '\uFF0D' || x == '~') {
                x = '-';
            }
            sb.append(x);
        }
        return sb.toString().toLowerCase();
    }

    /** A-B 与 B-A 得到同一个键；非双段段落用规范化名本身 */
    private static String dedupKey(String norm) {
        String[] parts = norm.split("-");
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            String[] sorted = parts.clone();
            Arrays.sort(sorted);
            return sorted[0] + "-" + sorted[1];
        }
        return norm;
    }

    /** 编辑距离归一化相似度：1 - 距离 / 较长串长度 */
    private static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - levenshtein(a, b) * 1.0 / max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
