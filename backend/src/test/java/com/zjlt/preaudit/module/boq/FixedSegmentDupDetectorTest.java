package com.zjlt.preaudit.module.boq;

import com.zjlt.preaudit.module.boq.service.FixedSegmentDupDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表七段落重复检测（B4）：起止点反转、名称近似、全角/空白差异。
 */
class FixedSegmentDupDetectorTest {

    @Test
    @DisplayName("起止点反转识别为同组重复")
    void reversedSegments() {
        List<FixedSegmentDupDetector.Result> r = FixedSegmentDupDetector.detect(List.of(
                "黄岩朱砂街红三路1-黄岩朱砂街红三路2",
                "黄岩朱砂街红三路2-黄岩朱砂街红三路1"));
        assertEquals(2, r.size());
        assertTrue(r.get(0).dupGroup() > 0);
        assertEquals(r.get(0).dupGroup(), r.get(1).dupGroup());
        assertEquals("REVERSED", r.get(0).dupType());
        assertEquals(r.get(0).dedupKey(), r.get(1).dedupKey());
    }

    @Test
    @DisplayName("全角连接符与空格不影响反转判定")
    void normalizedReversed() {
        List<FixedSegmentDupDetector.Result> r = FixedSegmentDupDetector.detect(List.of(
                "Ａ段 — Ｂ段",
                "Ｂ段－Ａ段"));
        assertEquals(r.get(0).dupGroup(), r.get(1).dupGroup());
        assertEquals("REVERSED", r.get(1).dupType());
    }

    @Test
    @DisplayName("名称高度相似归为同组")
    void similarNames() {
        List<FixedSegmentDupDetector.Result> r = FixedSegmentDupDetector.detect(List.of(
                "城关街道中山路东段至西段光缆敷设",
                "城关街道中山路东段到西段光缆敷设"));
        assertTrue(r.get(0).dupGroup() > 0);
        assertEquals(r.get(0).dupGroup(), r.get(1).dupGroup());
        assertEquals("SIMILAR", r.get(0).dupType());
    }

    @Test
    @DisplayName("不相关段落不分组，dupGroup 为 0")
    void unrelatedSegments() {
        List<FixedSegmentDupDetector.Result> r = FixedSegmentDupDetector.detect(List.of(
                "黄岩朱砂街红三路1-黄岩朱砂街红三路2",
                "椒江解放路一号段-椒江解放路二号段"));
        assertEquals(0, r.get(0).dupGroup());
        assertEquals(0, r.get(1).dupGroup());
        assertNotEquals(r.get(0).dupGroup(), r.get(1).dupGroup() + 1);
    }
}
