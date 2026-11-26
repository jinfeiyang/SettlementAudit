"""「工作量」文字标注解析与材料归并的回归样例。

覆盖两类真实图纸写法（台州联通竣工图）：
  1. 同一块标注里混着「带冒号」和「不带冒号」两种行，还有带颜色码的分组；
  2. 同一件器材在不同张上写成「新立光交」和「新建光缆交接箱」，必须合成一条。
运行：cd ai-service && python -m unittest discover -s tests
"""
from __future__ import annotations

import unittest

import ezdxf

from app.services.dxf_parser import (
    _infer_columns,
    _normalize_seq_name_cells,
    _split_row,
    _tables_from_workload,
    summarize_materials,
)


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


class InferColumnsTest(unittest.TestCase):

    def test_表头只写序号和单位时按数据行补出材料名称规格数量(self):
        centers = [56099.888, 56166.167]
        columns = ["序号", "单位"]
        body_cells = [[
            (56099.888, 9816.7, "1"),
            (56114.221, 9817.0, "电杆"),
            (56147.557, 9816.9, "7米"),
            (56166.973, 9816.8, "根"),
            (56174.811, 9816.9, "1"),
        ]]
        centers, columns = _infer_columns(centers, columns, body_cells)
        self.assertEqual(5, len(centers))
        self.assertEqual(["序号", "材料名称", "规格程式", "单位", "数量"], columns)
        values = ["" for _ in centers]
        for x, _, text in body_cells[0]:
            index = min(range(len(centers)), key=lambda i: abs(centers[i] - x))
            values[index] = text
        self.assertEqual(["1", "电杆", "7米", "根", "1"], values)


class SplitRowTest(unittest.TestCase):

    def test_宽项目名称列后面的单位数量不应被拆成另一张表(self):
        row = [
            (0.0, 0.0, "序号"),
            (12.0, 0.0, "定额编号"),
            (60.0, 0.0, "项目名称"),
            (300.0, 0.0, "单位"),
            (320.0, 0.0, "数量"),
        ]
        clusters = _split_row(row)
        self.assertEqual(1, len(clusters))
        self.assertEqual(["序号", "定额编号", "项目名称", "单位", "数量"],
                         [text for _, _, text in clusters[0]])

    def test_真正并排的第二张表仍要拆开(self):
        row = [
            (0.0, 0.0, "序号"),
            (20.0, 0.0, "材料名称"),
            (80.0, 0.0, "单位"),
            (500.0, 0.0, "序号"),
            (520.0, 0.0, "名称"),
            (580.0, 0.0, "数量"),
        ]
        clusters = _split_row(row)
        self.assertEqual(2, len(clusters))
        self.assertEqual(["单位"], [text for _, _, text in clusters[0][-1:]])
        self.assertEqual("序号", clusters[1][0][2])


class SeqNameCellTest(unittest.TestCase):

    def test_序号和材料名称写在同一格时拆开并进入归纳统计(self):
        table = {
            "table": 1,
            "columns": ["序号", "材料名称", "规格程式", "单位", "数 量"],
            "rows": [["1 普通层绞式通信光缆", "", "GYTS-12B1", "米", "477"]],
        }
        _normalize_seq_name_cells([table])
        self.assertEqual(["1", "普通层绞式通信光缆", "GYTS-12B1", "米", "477"],
                         table["rows"][0])
        summary = summarize_materials([table])
        self.assertEqual(1, len(summary), summary)
        self.assertEqual(477.0, summary[0]["quantity"])
        self.assertEqual("GYTS-12B1", summary[0]["spec"])


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
