"""DXF → SVG 出图：服务端只读预览快照。

用途：超大图纸前端渲染卡顿时的降级、报告插图。
交互式浏览（图层开关、点选图元）由前端直接解析 DXF 完成，不走本模块。
ezdxf 默认把文字转为路径输出，因此不依赖容器内是否装有中文字体。
"""
from __future__ import annotations

import logging
from pathlib import Path

logger = logging.getLogger(__name__)


def render_svg(dxf_path: Path, width_mm: float = 420, height_mm: float = 297,
               margin_mm: float = 5.0) -> str:
    """把 DXF 按指定页面尺寸（毫米，默认 A3）渲染为 SVG 字符串。"""
    import ezdxf
    from ezdxf.addons.drawing import Frontend, RenderContext, layout, svg

    doc = ezdxf.readfile(str(dxf_path))
    backend = svg.SVGBackend()
    Frontend(RenderContext(doc), backend).draw_layout(doc.modelspace())
    page = layout.Page(width_mm, height_mm, layout.Units.mm,
                       margins=layout.Margins.all(margin_mm))
    return backend.get_string(page)
