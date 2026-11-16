#!/usr/bin/env python3
"""ODA File Converter 测试替身（仅用于本地验证容器行为，不具备真实 DWG 转换能力）。

调用约定与真实 ODA 一致：
    ODAFileConverter <in_dir> <out_dir> <version> <type> <recurse> <audit> <file_name>

本脚本做两件事：
    1) 把 $DISPLAY 打到 stderr —— 用于证明 dxf_parser 自动加的 xvfb-run 已把虚显传入子进程；
    2) 产出一个固定 DXF —— 用于验证「DWG → 转换 → ezdxf 解析 → 图层/块/长度量算」整条链路。
"""
import os
import sys
from pathlib import Path

import ezdxf

out_dir = Path(sys.argv[2])
name = Path(sys.argv[-1])
print(f"[oda-stub] DISPLAY={os.environ.get('DISPLAY', '')!r} file={name.name}", file=sys.stderr)

doc = ezdxf.new("R2018", setup=True)
doc.header["$INSUNITS"] = 6
msp = doc.modelspace()
msp.add_lwpolyline([(0, 0), (120, 0), (120, 80)], dxfattribs={"layer": "通信线路-光缆"})
msp.add_line((0, 0), (0, 50), dxfattribs={"layer": "通信线路-光缆"})
doc.blocks.new(name="设备-机柜")
msp.add_blockref("设备-机柜", (5, 5))
msp.add_text("引上光缆 24 芯", dxfattribs={"layer": "标注"})
doc.saveas(out_dir / f"{name.stem}.dxf")
