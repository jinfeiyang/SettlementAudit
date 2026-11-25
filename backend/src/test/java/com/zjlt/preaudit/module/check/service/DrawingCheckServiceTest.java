package com.zjlt.preaudit.module.check.service;

import com.zjlt.preaudit.common.enums.Dict;
import com.zjlt.preaudit.module.boq.entity.BoqDrawingMatch;
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

    private final DrawingCheckService service = new DrawingCheckService(null, null, null, null);

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
    void 图纸简称光交能对上清单光缆交接箱且套个按一比一() {
        // 竣工图材料表写「新立光交 576芯 1个」，清单写「光缆交接箱 576芯 2套」——同一件器材
        List<BoqItem> items = List.of(materialItem(26, "光缆交接箱", "576芯", "套", "1"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("新立光交", "576芯", "个", "1"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertTrue(issues.isEmpty(), "简称与全称是同一件器材、套与个按 1:1，数量一致不应出疑点");
    }

    @Test
    void 光交简称匹配后数量偏小仍要出疑点() {
        List<BoqItem> items = List.of(materialItem(26, "光缆交接箱", "576芯", "套", "2"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("新立光交", "576芯", "个", "1"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertEquals(1, issues.size(), "匹配上之后要能按数量报偏差，不能停留在图纸无对应");
        assertEquals("C1-QTY-LESS", issues.get(0).getRuleCode());
        assertTrue(issues.get(0).getTitle().contains("偏小 50.0%"), issues.get(0).getTitle());
    }

    @Test
    void 定额的N芯以上不算芯数冲突() {
        // 定额写「288芯以上」是范围，图纸是 576 芯实物，不能因此判成两种材料
        BoqItem quota = materialItem(11, "安装落地式光缆交接箱 288芯以上", null, "套", "2");
        quota.setItemType(Dict.BOQ_QUOTA);
        List<Issue> issues = service.evaluate(1L, List.of(quota),
                Map.of("k", material("光缆交接箱", "576芯", "个", "2")), List.of("A.dwg"));

        assertTrue(issues.isEmpty(), issues.isEmpty() ? "" : issues.get(0).getTitle());
    }

    @Test
    void ODF子框与ODF框按同一设备匹配() {
        List<BoqItem> items = List.of(materialItem(15, "ODF框", "48芯", "套", "4"));
        Map<String, DrawingMaterial> materials = Map.of("k", material("安装ODF子框", "48芯", "个", "4"));
        List<Issue> issues = service.evaluate(1L, items, materials, List.of("A.dwg"));

        assertTrue(issues.isEmpty(), issues.isEmpty() ? "" : issues.get(0).getTitle());
    }

    @Test
    void 图纸数量偏大也按偏差幅度分级() {
        List<Issue> high = service.evaluate(1L, List.of(materialItem(1, "光缆", "24芯", "米", "10")),
                Map.of("k", material("光缆", "24芯", "米", "13")), List.of("A.dwg"));
        assertEquals(1, high.size());
        assertEquals("C1-QTY-MORE", high.get(0).getRuleCode());
        assertEquals(Dict.LEVEL_HIGH, high.get(0).getLevel(), "偏大 30% 不能只给低风险");

        List<Issue> medium = service.evaluate(1L, List.of(materialItem(1, "光缆", "24芯", "米", "10")),
                Map.of("k", material("光缆", "24芯", "米", "11")), List.of("A.dwg"));
        assertEquals(Dict.LEVEL_MEDIUM, medium.get(0).getLevel());
    }

    @Test
    void 人工取消匹配后不再报图纸无对应() {
        BoqItem item = materialItem(1, "电缆", "", "米", "100");
        item.setId(11L);
        BoqDrawingMatch manual = new BoqDrawingMatch();
        manual.setBoqItemId(11L);
        manual.setManualType("UNMATCH");

        List<Issue> issues = service.evaluate(1L, List.of(item),
                Map.of("k", material("不相关材料", "", "米", "999")), List.of("A.dwg"),
                Map.of(11L, manual));

        assertTrue(issues.isEmpty(), issues.isEmpty() ? "" : issues.get(0).getTitle());
    }

    @Test
    void 人工指定匹配按指定材料比量而不是自动挑最像的() {
        BoqItem item = materialItem(1, "电缆", "", "米", "100");
        item.setId(12L);
        BoqDrawingMatch manual = new BoqDrawingMatch();
        manual.setBoqItemId(12L);
        manual.setManualType("MATCH");
        manual.setDrawingKey("forced");
        Map<String, DrawingMaterial> materials = Map.of(
                "auto", material("电缆", "", "米", "80"),
                "forced", material("人工确认的电缆", "", "米", "100"));

        List<Issue> issues = service.evaluate(1L, List.of(item), materials, List.of("A.dwg"),
                Map.of(12L, manual));

        assertTrue(issues.isEmpty(), issues.isEmpty() ? "" : issues.get(0).getTitle());
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
    void 定额子目行命中材料且单位可换算时也比数量() {
        BoqItem quota = materialItem(9, "光缆接头盒", "48芯", "个", "10");
        quota.setItemType(Dict.BOQ_QUOTA);
        List<Issue> issues = service.evaluate(1L, List.of(quota), Map.of("k",
                material("光缆接头盒", "48芯", "个", "5")), List.of("A.dwg"));

        assertEquals(1, issues.size(), "定额行同单位同口径时应与清单页一致报数量偏小");
        assertEquals("C1-QTY-LESS", issues.get(0).getRuleCode());
    }

    @Test
    void 定额子目行命中材料但单位不可比时不出数量疑点() {
        BoqItem quota = materialItem(9, "光缆接头盒", "48芯", "米", "10");
        quota.setItemType(Dict.BOQ_QUOTA);
        List<Issue> issues = service.evaluate(1L, List.of(quota), Map.of("k",
                material("光缆接头盒", "48芯", "个", "5")), List.of("A.dwg"));

        // 单位冲突时 score=0，按既有规则归到「图纸无对应」，但绝不能出数量偏差疑点
        assertEquals(1, issues.size());
        assertEquals("C1-NO-DRAWING", issues.get(0).getRuleCode());
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
