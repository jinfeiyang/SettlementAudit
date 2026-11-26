"""图上段长标注解析的回归样例。

重点覆盖竣工图里的「预留」写法（管留20、35管留、预留15米）：
这些标注和普通段长一样要计入米数，且预留属于对应敷设方式（管留20 = 管道 20 米），
漏掉会让逐段累加和材料表的光缆数量对不上。
"""
from __future__ import annotations

import unittest

from app.services.dxf_parser import parse_length_labels


class ReserveLabelTest(unittest.TestCase):

    def _segments(self, text: str) -> list[tuple[float, str]]:
        segments, _ = parse_length_labels(text)
        return [(value, kind) for value, kind, _ in segments]

    def test_管留写在后方(self):
        self.assertEqual(self._segments('管留20'), [(20.0, 'pipe')])
        self.assertEqual(self._segments('管留25'), [(25.0, 'pipe')])

    def test_管留写在前方(self):
        self.assertEqual(self._segments('10管留'), [(10.0, 'pipe')])
        self.assertEqual(self._segments('35管留'), [(35.0, 'pipe')])

    def test_预留带敷设方式(self):
        self.assertEqual(self._segments('10架留'), [(10.0, 'overhead')])
        self.assertEqual(self._segments('10加架留'), [(10.0, 'overhead')])

    def test_无敷设方式的预留算余线(self):
        self.assertEqual(self._segments('预留15米'), [(15.0, 'extra')])
        self.assertEqual(self._segments('预留20'), [(20.0, 'extra')])
        self.assertEqual(self._segments('留20'), [(20.0, 'extra')])

    def test_芯数预留不算段长(self):
        self.assertEqual(self._segments('32芯预留'), [])
        self.assertEqual(self._segments('13-24芯预留'), [])


class LengthLabelTest(unittest.TestCase):

    def test_普通段长(self):
        segments, _ = parse_length_labels('桥3.5')
        self.assertEqual(segments, [(3.5, 'duct', False)])

    def test_无前缀单个数字(self):
        segments, _ = parse_length_labels('3.5')
        self.assertEqual(segments, [(3.5, '', False)])

    def test_规格数字不算段长(self):
        for text in ('成端6芯', '1孔', '24芯'):
            self.assertEqual(parse_length_labels(text)[0], [], text)

    def test_合计行只取合计值(self):
        segments, totals = parse_length_labels('管253.1余10桥40钉137.5余20合计460.6')
        self.assertEqual([value for value, _ in totals], [460.6])


if __name__ == '__main__':
    unittest.main()
