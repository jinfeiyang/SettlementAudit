package com.zjlt.preaudit.module.check.service;

import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import com.zjlt.preaudit.module.check.service.DrawingCheckService.DrawingMaterial;
import com.zjlt.preaudit.module.issue.entity.Issue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 图纸-清单核对：材料匹配与数量偏差的判定口径。
 */
class DrawingCheckServiceTest {

    private final DrawingCheckService service = new DrawingCheckService(null);

    @Test
    void 图纸材料与清单一致时不出疑点() {
        List<BoqItem> items = List.of(materialItem(1, "光缆接头盒", "48芯", "个", "10"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("光缆接头盒", "48芯", "个", "10.2"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));
        assertTrue(issues.isEmpty(), "偏差 2% 低于 5% 阈值不应出疑点");
    }

    @Test
    void 图纸数量偏小超过阈值出疑点() {
        List<BoqItem> items = List.of(materialItem(3, "光缆接头盒", "48芯", "个", "100"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("光缆接头盒", "48芯", "个", "94.6"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertEquals(1, issues.size());
        Issue issue = issues.get(0);
        assertEquals(Dict.ISSUE_DRAWING, issue.getIssueType());
        assertEquals("C1-QTY-LESS", issue.getRuleCode());
        assertTrue(issue.getTitle().contains("偏小 5.4%"), issue.getTitle());
        assertEquals(Dict.LEVEL_MEDIUM, issue.getLevel());
    }

    @Test
    void 图纸没有对应材料时出图纸无对应() {
        List<BoqItem> items = List.of(materialItem(5, "光分纤箱", "24芯", "个", "3"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("光缆接头盒", "48芯", "个", "10"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertEquals(1, issues.size());
        assertEquals("C1-NO-DRAWING", issues.get(0).getRuleCode());
        assertEquals("BOQ_ROW", issues.get(0).getRefType());
    }

    @Test
    void 芯数不同的同名材料不算匹配() {
        List<BoqItem> items = List.of(materialItem(7, "普通层绞式通信光缆", "GYTS-48B1", "米", "1000"));
        Map<String, DrawingMaterial> materials = Map.of("k",
                material("普通层绞式通信光缆", "GYTS-24B1", "米", "1000"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertEquals(1, issues.size());
        assertEquals("C1-NO-DRAWING", issues.get(0).getRuleCode());
    }

    @Test
    void 定额子目行没有对应材料时也出图纸无对应() {
        BoqItem quota = materialItem(9, "打人(手)孔墙洞 砖砌人孔 3孔管以下", null, "处", "8");
        quota.setItemType(Dict.BOQ_QUOTA);
        List<Issue> issues = service.evaluate(1L, List.of(quota), Map.of("k",
                material("普通层绞式通信光缆", "GYTS-48B1", "米", "28344")), List.of("A.dwg"));

        assertEquals(1, issues.size(), "清单页对定额子目行也标「图纸无对应」，疑点必须对得上");
        assertEquals("C1-NO-DRAWING", issues.get(0).getRuleCode());
        assertEquals(9L, issues.get(0).getBoqItemId());
    }

    @Test
    void 定额子目行命中材料时不比数量() {
        BoqItem quota = materialItem(9, "光缆接头盒", "48芯", "个", "10");
        quota.setItemType(Dict.BOQ_QUOTA);
        List<Issue> issues = service.evaluate(1L, List.of(quota), Map.of("k",
                material("光缆接头盒", "48芯", "个", "5")), List.of("A.dwg"));

        assertTrue(issues.isEmpty(), "工序量与材料表数量不是同一口径，定额行只判有没有，不比数量");
    }

    @Test
    void 光缆数量偏差只按清单行出结论不另出总量疑点() {
        List<BoqItem> items = List.of(
                materialItem(4, "普通层绞式通信光缆", "GYTS-48B1", "米", "29973"),
                materialItem(5, "普通层绞式通信光缆", "GYTS-96B1", "米", "2658"));
        Map<String, DrawingMaterial> materials = new HashMap<>();
        materials.put("a", material("普通层绞式通信光缆", "GYTS-48B1", "米", "28344"));
        materials.put("b", material("普通层绞式通信光缆", "GYTS-96B1", "米", "2649"));

        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertEquals(1, issues.size(), "总量口径的 5% 偏差落不到清单行，不应再单独出疑点");
        assertEquals("C1-QTY-LESS", issues.get(0).getRuleCode());
        assertEquals(4L, issues.get(0).getBoqItemId());
    }

    // ------------------------------------------------------------------

    private BoqItem materialItem(int rowNo, String name, String spec, String unit, String qty) {
        BoqItem item = new BoqItem();
        item.setId((long) rowNo);
        item.setRowNo(rowNo);
        item.setItemType(Dict.BOQ_SUPPLY_MATERIAL);
        item.setItemName(name);
        item.setFeatureDesc(spec);
        item.setUnit(unit);
        item.setQuantity(new BigDecimal(qty));
        return item;
    }

    private DrawingMaterial material(String name, String spec, String unit, String qty) {
        return new DrawingMaterial(name, spec, unit, new BigDecimal(qty), new ArrayList<>(List.of("A.dwg")));
    }
}
