"""真实图纸画法样本回归。

`samples/*.dxf` 是从实际竣工图问题里抽出来的最小表格片段，保留原始文字坐标和
排版特征；`samples/expected/*.json` 是正确解析结果。解析代码每次改动后跑一遍，
防止宽列误拆、序号名称合并、表头缺列等老问题复现。
"""
from __future__ import annotations

import json
import os
import unittest
from pathlib import Path

# 本地/CI 跑回归时不碰容器挂载目录，避免只读警告
os.environ.setdefault('AI_CACHE_DIR', '/tmp/preaudit-test-cache')

from app.services.dxf_parser import parse_dxf_bytes

SAMPLES = Path(__file__).parent / 'samples'


def find_summary(items: list[dict], expected: dict) -> dict | None:
    keys = ('name', 'spec', 'unit')
    for item in items:
        if all(item.get(key, '') == expected.get(key, '') for key in keys):
            return item
    return None


def find_sheet(items: list[dict], sheet: str) -> dict | None:
    return next((item for item in items if item.get('sheet') == sheet), None)


class SampleRegressionTest(unittest.TestCase):

    def test_real_drawing_samples(self):
        samples = sorted(SAMPLES.glob('*.dxf'))
        self.assertTrue(samples, 'samples 目录里没有 DXF 样本')
        for sample in samples:
            with self.subTest(sample=sample.name):
                expected = json.loads(
                    (SAMPLES / 'expected' / f'{sample.stem}.json').read_text(encoding='utf-8')
                )
                result = parse_dxf_bytes(sample.read_bytes(), sample.name)
                self.assertEqual('SUCCESS', result.get('status'), result.get('message'))

                actual_tables = result.get('materials') or []
                for want in expected.get('tables', []):
                    hit = any(
                        table.get('columns') == want['columns']
                        and table.get('rows') == want['rows']
                        for table in actual_tables
                    )
                    self.assertTrue(hit, f'{sample.name} 表格未按预期归列：{want}')

                # 表格文字在块定义里，搜索/复制模式依赖 textLabels，必须一并索引
                label_positions = {
                    (label.get('text'), label.get('x'), label.get('y'))
                    for label in result.get('textLabels') or []
                }
                for table in actual_tables:
                    for cell in table.get('cells') or []:
                        self.assertIn(
                            (cell['text'], cell['x'], cell['y']), label_positions,
                            f'{sample.name} 表格单元格未进入文字索引：{cell["text"]}',
                        )

                actual_sheets = (result.get('measure') or {}).get('annotationBySheet') or []
                for want in expected.get('sheets', []):
                    item = find_sheet(actual_sheets, want['sheet'])
                    self.assertIsNotNone(item, f'{sample.name} 缺少按张核对结果：{want["sheet"]}')
                    for key, value in want.items():
                        if key == 'sheet':
                            continue
                        if key == 'segmentParts':
                            for part_key, part_value in value.items():
                                self.assertAlmostEqual(
                                    float(part_value), float(item['segmentParts'][part_key]),
                                )
                        elif isinstance(value, (int, float)):
                            self.assertAlmostEqual(float(value), float(item[key]))
                        else:
                            self.assertEqual(value, item[key])

                actual_summary = result.get('materialSummary') or []
                for want in expected.get('summary', []):
                    item = find_summary(actual_summary, want)
                    self.assertIsNotNone(item, f'{sample.name} 归纳统计缺少材料：{want}')
                    self.assertAlmostEqual(float(want['quantity']), float(item.get('quantity', 0)))
                    if 'tableCount' in want:
                        self.assertEqual(want['tableCount'], item.get('tableCount'))
                    if 'quantityParts' in want:
                        for key, value in want['quantityParts'].items():
                            self.assertAlmostEqual(float(value), float(item['quantityParts'][key]))


if __name__ == '__main__':
    unittest.main()
