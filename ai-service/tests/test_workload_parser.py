"""「工作量」文字标注解析与材料归并的回归样例。

覆盖两类真实图纸写法（台州联通竣工图）：
  1. 同一块标注里混着「带冒号」和「不带冒号」两种行，还有带颜色码的分组；
  2. 同一件器材在不同张上写成「新立光交」和「新建光缆交接箱」，必须合成一条。
运行：cd ai-service && python -m unittest discover -s tests
"""
from __future__ import annotations

import unittest

import ezdxf

from app.services.dxf_parser import _tables_from_workload, summarize_materials


def _workload_tables(*texts: str) -> list[dict]:
    doc = ezdxf.new()
    msp = doc.modelspace()
    for text in texts:
        msp.add_mtext(text)
    return _tables_from_workload(doc)


def _rows(tables: list[dict]) -> list[tuple[str, str, str, str]]:
    return [tuple(row) for table in tables for row in table["rows"]]


class WorkloadParserTest(unittest.TestCase):

    def test_带冒号的写法照旧解析(self):
        tables = _workload_tables("工作量：\\P光缆24芯：1400米\\P光缆熔接：360芯")
        self.assertEqual([("光缆", "24芯", "米", "1400"), ("光缆熔接", "", "芯", "360")],
                         _rows(tables))

    def test_不带冒号的写法按整行解析(self):
        tables = _workload_tables(
            "工作量：\\P光缆24芯：1400米\\P{\\C256;新建576芯光缆交接箱1个\\P新建光交底座0.51立方米}"
        )
        rows = _rows(tables)
        self.assertIn(("光缆", "24芯", "米", "1400"), rows)
        self.assertIn(("新建光缆交接箱", "576芯", "个", "1"), rows)
        self.assertIn(("新建光交底座", "", "立方米", "0.51"), rows)

    def test_说明句与汇总行不被当成材料(self):
        tables = _workload_tables("工作量以200米计取\\P合计100米\\P12#杆")
        self.assertEqual([], _rows(tables))

    def test_一样材料只出现一次(self):
        tables = _workload_tables("工作量：\\P光缆24芯：1400米")
        self.assertEqual([("光缆", "24芯", "米", "1400")], _rows(tables))


class MaterialSummaryTest(unittest.TestCase):

    def test_同物异名合并后数量相加(self):
        columns = ["材料名称", "规格", "单位", "数量"]
        tables = [
            {"table": 1, "columns": columns, "rows": [["新建光缆交接箱", "576芯", "个", "1"]]},
            {"table": 2, "columns": columns, "rows": [["新立光交", "576芯", "个", "1"]]},
        ]
        summary = summarize_materials(tables)
        self.assertEqual(1, len(summary), summary)
        self.assertEqual(2.0, summary[0]["quantity"])
        self.assertEqual("576芯", summary[0]["spec"])

    def test_相同名称不同单位的材料不合并(self):
        columns = ["材料名称", "规格", "单位", "数量"]
        tables = [
            {"table": 1, "columns": columns, "rows": [["光缆接头盒", "48芯", "个", "2"]]},
            {"table": 2, "columns": columns, "rows": [["光缆接头盒", "48芯", "头", "2"]]},
        ]
        self.assertEqual(2, len(summarize_materials(tables)))


if __name__ == "__main__":
    unittest.main()
