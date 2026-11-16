"""CAD 矢量解析与量算（A1）：DWG→DXF 转换 + 图层/块/标注提取 + 线路长度、设备数量量算。

一期范围：DXF 直解；DWG 需配置 ODA File Converter。
量算聚焦「线路长度、设备数量」两类，同时从图纸的表格实体还原「材料设备清单」；
异常图纸降级为人工辅助标注（返回明确提示）。
"""
from __future__ import annotations

import logging
import math
import os
import re
import shutil
import statistics
import subprocess
import tempfile
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

LINE_TYPES = {"LINE", "LWPOLYLINE", "POLYLINE", "SPLINE"}

# ODA File Converter 常见安装位置（macOS 本地开发 / Linux 容器 / Windows 运维机）
ODA_CANDIDATES = (
    "/Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter",
    "/usr/bin/ODAFileConverter",
    "/usr/local/bin/ODAFileConverter",
    "/opt/ODAFileConverter/ODAFileConverter",
    "/opt/oda/ODAFileConverter",
    r"C:\Program Files\ODA\ODAFileConverter\ODAFileConverter.exe",
    r"C:\Program Files (x86)\ODA\ODAFileConverter\ODAFileConverter.exe",
)
ODA_DOWNLOAD_URL = "https://www.opendesign.com/guestfiles/oda_file_converter"
DEVICE_HINTS = ("设备", "机架", "机柜", "天线", "开关", "电源", "盒", "箱", "盘", "架")
CABLE_HINTS = ("光缆", "电缆", "纤", "线缆", "线路")

# 表格几何块：CAD 把 ACAD_TABLE 的单元格与边框放在匿名块里（展开后是普通 INSERT）
_TABLE_BLOCK_RE = re.compile(r"^\*T\d+$")
# 表头识别用：出现 2 个以上即认为是表头行
_TABLE_HEADER_HINTS = {"序号", "名称", "项目名称", "规格", "规格程式", "型号", "单位", "数量", "备注"}
_TABLE_MAX_TABLES = 50
_TABLE_MAX_ROWS = 300
# 无表格块时按文字还原：行容差取行距中位数的比例，列容差取最小列距的比例
_ROW_TOL_RATIO = 0.4
_COLUMN_TOL_RATIO = 0.55
# 行距突然放大到中位数的这么多倍，就认为表格结束了（下面多半是别的图元文字）
_TABLE_ROW_GAP_FACTOR = 4.0

# 图框块（不同单位叫法不一，按名字里含「图框」识别）
_FRAME_BLOCK_HINT = "图框"
# 图签在右下角：横向从图框宽度的 55% 起、纵向只取底部 18%
# （图签高约 32mm / A4 高 210mm，取太大会把表格里的数字当成「第 X 张」）
_TITLE_BLOCK_X_RATIO = 0.55
_TITLE_BLOCK_Y_RATIO = 0.18
# 「第 X 张」的填写值（图签栏目名在块定义里，模型空间只剩填进去的数字）
_SHEET_NO_RE = re.compile(r"^\d{1,3}$")
_CJK_RE = re.compile(r"[\u4e00-\u9fa5]")
# 归纳统计取列用：不同表格的列名写法不同，按关键词匹配
_MATERIAL_NAME_KEYS = ("项目名称", "材料名称", "名称", "设备名称")
_MATERIAL_SPEC_KEYS = ("规格程式", "规格型号", "规格", "型号", "程式")
_MATERIAL_UNIT_KEYS = ("单位",)
_MATERIAL_QTY_KEYS = ("数量", "工程量", "合计数量")


def _insunits_label(code: int) -> str:
    return {
        0: "unitless",
        1: "inch",
        2: "feet",
        4: "mm",
        5: "cm",
        6: "m",
        7: "km",
    }.get(code, f"code{code}")


def _to_meters(length: float, units: str) -> float:
    factor = {"mm": 0.001, "cm": 0.01, "m": 1.0, "km": 1000.0}.get(units, 1.0)
    return length * factor


def _windows_oda_candidates() -> list[str]:
    """Windows 安装包会把 exe 放进带版本号的子目录（如 ODAFileConverter 27.9），需展开探测。"""
    if os.name != "nt":
        return []
    roots = {
        os.environ.get("ProgramW6432"),
        os.environ.get("ProgramFiles"),
        os.environ.get("ProgramFiles(x86)"),
    }
    found: list[str] = []
    for root in sorted(r for r in roots if r):
        base = Path(root) / "ODA"
        if not base.is_dir():
            continue
        direct = base / "ODAFileConverter.exe"
        if direct.is_file():
            found.append(str(direct))
        for exe in sorted(base.glob("*/ODAFileConverter.exe"), reverse=True):
            found.append(str(exe))
    return found


def resolve_oda_converter(configured: str = "") -> str:
    """定位 ODA File Converter：优先 AI_ODA_CONVERTER_PATH，其次 PATH 与常见安装路径自动探测。

    返回可执行文件绝对路径；未安装返回空串（供健康检查与失败提示复用）。
    """
    if configured:
        path = Path(configured).expanduser()
        if path.exists():
            return str(path)
        logger.warning("AI_ODA_CONVERTER_PATH 指向的文件不存在：%s，转为自动探测", configured)
    for name in ("ODAFileConverter", "ODAFileConverter.exe"):
        found = shutil.which(name)
        if found:
            return found
    for candidate in (*ODA_CANDIDATES, *_windows_oda_candidates()):
        if Path(candidate).exists():
            return candidate
    return ""


def _oda_command(exe: str, src: Path, out_dir: Path) -> list[str]:
    """组装 ODA 命令行；Linux 版依赖 Qt/X11，容器内无 DISPLAY 时用 Xvfb 虚显。"""
    cmd = [exe, str(src.parent), str(out_dir), "ACAD2018", "DXF", "0", "1", src.name]
    if os.name == "posix" and not os.environ.get("DISPLAY"):
        xvfb = shutil.which("xvfb-run")
        if xvfb and shutil.which("xauth"):
            cmd = [xvfb, "-a"] + cmd
        elif xvfb:
            # xvfb-run 依赖 xauth 生成授权文件，缺了会直接报 "xauth command not found"
            logger.warning("存在 xvfb-run 但缺少 xauth，DWG 转换可能因无 X 显示失败；"
                           "容器内需安装 xauth（Debian）或 xorg-x11-xauth（RPM 系）")
    return cmd


def convert_dwg_to_dxf(src: Path, converter: str) -> Path:
    """调用 ODA File Converter 将 DWG 转为 DXF（容器云需预装 ODA）。"""
    exe = resolve_oda_converter(converter)
    if not exe:
        raise RuntimeError(
            "DWG 无法直接解析，需先装 ODA File Converter 转 DXF："
            "安装后设置 AI_ODA_CONVERTER_PATH（Windows 一般为 "
            r"C:\Program Files\ODA\ODAFileConverter <版本>\ODAFileConverter.exe，"
            "macOS 为 /Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter，"
            "Linux 为 /usr/bin/ODAFileConverter），留空时也会自动探测上述路径；"
            f"或把图纸另存为 DXF 后重新上传。下载：{ODA_DOWNLOAD_URL}"
        )
    out_dir = Path(tempfile.mkdtemp(prefix="preaudit-dxf-"))
    cmd = _oda_command(exe, src, out_dir)
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=180, check=False)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("ODA 转换超时（180s）：图纸过大或转换器无响应，建议人工转换为 DXF") from exc
    candidates = [p for p in out_dir.iterdir() if p.suffix.lower() == ".dxf"]
    if not candidates:
        detail = (proc.stderr or proc.stdout or b"").decode("utf-8", "ignore").strip()
        if "x server" in detail.lower() or "display" in detail.lower() or "xauth" in detail.lower():
            detail += "（容器内需安装 xvfb 且不能缺 xauth：apt install xvfb xauth / yum install xorg-x11-server-Xvfb xorg-x11-xauth）"
        raise RuntimeError(
            f"ODA 转换未产出 DXF（exit {proc.returncode}）：{detail[:300] or '转换器无输出'}"
            "；请确认图纸版本与转换器兼容、文件未加密"
        )
    same_name = [p for p in candidates if p.stem.lower() == src.stem.lower()]
    return (same_name or candidates)[0]


def _table_grid(block) -> tuple[list[float], list[float]]:
    """取表格块的网格线：返回 (列边界 x 升序, 行边界 y 降序)。"""
    xs: set[float] = set()
    ys: set[float] = set()
    for entity in block:
        etype = entity.dxftype()
        try:
            if etype == "LINE":
                start, end = entity.dxf.start, entity.dxf.end
                if abs(start.y - end.y) < 1e-6:
                    ys.add(round(start.y, 4))
                elif abs(start.x - end.x) < 1e-6:
                    xs.add(round(start.x, 4))
            elif etype == "LWPOLYLINE":
                points = [(round(p[0], 4), round(p[1], 4)) for p in entity.get_points("xy")]
                for i, (x1, y1) in enumerate(points):
                    x2, y2 = points[(i + 1) % len(points)]
                    if abs(y1 - y2) < 1e-6:
                        ys.add(y1)
                    elif abs(x1 - x2) < 1e-6:
                        xs.add(x1)
        except Exception:  # noqa: BLE001 单条线异常不影响整表还原
            continue
    return sorted(xs), sorted(ys, reverse=True)


def _clean_cell_text(text: str) -> str:
    """清理单元格文本：MTEXT 里字面反斜杠写成 \\\\，连续空白压成一个空格。"""
    return re.sub(r"[ \t]+", " ", text.replace("\\\\", "\\")).strip()


def _layout_texts(layout) -> list[tuple[float, float, float, str]]:
    """取布局里的文字、定位点与字高（居中对齐用对齐点，其余用插入点）。"""
    items: list[tuple[float, float, float, str]] = []
    for entity in layout:
        etype = entity.dxftype()
        try:
            if etype == "MTEXT":
                text = _clean_cell_text(entity.plain_text() or "")
                if text:
                    height = entity.dxf.get("char_height", 1) or 1
                    items.append((entity.dxf.insert.x, entity.dxf.insert.y, height, text))
            elif etype == "TEXT":
                text = _clean_cell_text(entity.dxf.text or "")
                if not text:
                    continue
                if entity.dxf.get("halign", 0) in (1, 4) and "align_point" in \
                        entity.dxf.all_existing_dxf_attribs():
                    point = entity.dxf.align_point
                else:
                    point = entity.dxf.insert
                height = entity.dxf.get("height", 1) or 1
                items.append((point.x, point.y, height, text))
        except Exception:  # noqa: BLE001
            continue
    return items


def _table_texts(layout) -> list[tuple[float, float, str]]:
    """只要定位点与文字（表格块还原用；字高只在无块可依时才有用）。"""
    return [(x, y, text) for x, y, _, text in _layout_texts(layout)]


def _place_in_grid(items, xs: list[float], ys: list[float]) -> list[list[str]]:
    """按网格线把文字放进单元格，空单元格留空（合并单元格自然表现为某行全空）。"""
    rows = len(ys) - 1
    cols = len(xs) - 1
    grid = [["" for _ in range(cols)] for _ in range(rows)]
    for x, y, text in items:
        row = None
        for r in range(rows):
            if ys[r] + 1e-6 >= y >= ys[r + 1] - 1e-6:
                row = r
                break
        col = None
        for c in range(cols):
            if xs[c] - 1e-6 <= x < xs[c + 1] + 1e-6:
                col = c
                break
        if col is None:
            col = cols - 1 if x >= xs[-1] else 0
        if row is None:
            continue
        grid[row][col] = f"{grid[row][col]} {text}".strip() if grid[row][col] else text
    return [row for row in grid if any(cell.strip() for cell in row)]


def _rows_by_text(items) -> list[list[str]]:
    """没有网格线时的兜底：按 y 分行、行内按 x 排序。"""
    ys = sorted({round(y, 3) for _, y, _ in items}, reverse=True)
    gaps = [ys[i] - ys[i + 1] for i in range(len(ys) - 1) if ys[i] - ys[i + 1] > 1e-6]
    tol = min(gaps) * 0.4 if gaps else 1.0
    groups: list[list[tuple[float, float, str]]] = []
    for item in sorted(items, key=lambda it: -it[1]):
        if groups and abs(item[1] - groups[-1][0][1]) <= tol:
            groups[-1].append(item)
        else:
            groups.append([item])
    return [[text for _, _, text in sorted(group, key=lambda it: it[0])] for group in groups]


def _cluster_rows(items, tol: float) -> list[list[tuple[float, float, str]]]:
    """把文字按 y 聚成行（行内按 x 升序），供无表格块的图纸还原表格。"""
    rows: list[list[tuple[float, float, str]]] = []
    for item in sorted(items, key=lambda it: -it[1]):
        if rows and abs(item[1] - rows[-1][0][1]) <= tol:
            rows[-1].append(item)
        else:
            rows.append([item])
    return [sorted(row, key=lambda it: it[0]) for row in rows]


def _is_header_row(row) -> bool:
    return sum(1 for _, _, text in row if text in _TABLE_HEADER_HINTS) >= 2


def _split_row(row) -> list[list[tuple[float, float, str]]]:
    """把同一行里并排的两张表拆开：列距中位数的 4 倍以上算跨表。

    多张图横向排在一起时，两处表格的表头常常 y 完全相同，不拆就会把两张表合成
    一张列数翻倍的表。
    """
    gaps = [row[i + 1][0] - row[i][0] for i in range(len(row) - 1)]
    if not gaps:
        return [row]
    limit = statistics.median(gaps) * 4
    clusters: list[list[tuple[float, float, str]]] = [[row[0]]]
    for index, item in enumerate(row[1:], start=1):
        if gaps[index - 1] > limit:
            clusters.append([item])
        else:
            clusters[-1].append(item)
    return clusters


def _text_tables(entries) -> list[dict[str, Any]]:
    """把「线框 + 文字」画出来的表格还原成列名与数据行。

    这类图纸的工程量清单不是 ACAD_TABLE，只是普通 LINE 外框 + 一堆 TEXT，
    没有块名可循。做法是：找表头行（含"序号/名称/单位/数量"等 2 个以上），
    以表头各单元格的 x 作列中心，逐行往下收，直到行对不上列或又碰到表头。

    行容差按字高取（表格行距通常是字高的 2~3 倍），不能按全图行距中位数：
    图里密排的标注会把中位数压到很小，表格的相邻行会被并成一行。
    """
    heights = [height for _, _, height, _ in entries if height and height > 0]
    row_tol = max(statistics.median(heights) * _ROW_TOL_RATIO, 1e-6) if heights else 1.0
    rows = _cluster_rows([(x, y, text) for x, y, _, text in entries], row_tol)

    tables: list[dict[str, Any]] = []

    for start, row in enumerate(rows):
        for cluster in _split_row(row):
            if not _is_header_row(cluster):
                continue
            # 表头左右可能粘着同一高度的标注文字（尺寸、编号），按表头关键词位置裁掉
            hint_index = [i for i, (_, _, text) in enumerate(cluster) if text in _TABLE_HEADER_HINTS]
            cluster = cluster[hint_index[0]:hint_index[-1] + 1]
            centers = [x for x, _, _ in cluster]
            columns = [text or f"列{i + 1}" for i, (_, _, text) in enumerate(cluster)]
            gaps = [centers[i + 1] - centers[i] for i in range(len(centers) - 1)
                    if centers[i + 1] - centers[i] > 1e-6]
            if not gaps:
                continue
            tol = min(gaps) * _COLUMN_TOL_RATIO
            left, right = centers[0] - tol, centers[-1] + tol

            body: list[list[str]] = []
            pitches: list[float] = []
            prev_y = cluster[0][1]
            for j in range(start + 1, len(rows)):
                # 同一行里可能有别处表格的文字，只认落在本表列范围内且对得上列的
                cells = [cell for cell in rows[j] if left <= cell[0] <= right]
                if not cells:
                    continue
                if _is_header_row(cells):
                    break
                y = cells[0][1]
                gap = prev_y - y
                if pitches and gap > _TABLE_ROW_GAP_FACTOR * statistics.median(pitches):
                    break
                values = ["" for _ in centers]
                filled = 0
                for x, _, text in cells:
                    index = min(range(len(centers)), key=lambda i: abs(centers[i] - x))
                    if abs(centers[index] - x) > tol:
                        continue
                    values[index] = f"{values[index]} {text}".strip() if values[index] else text
                    filled += 1
                if not filled:
                    break
                # 序号、名称都没有的"行"其实是表格下方的长度标注（管53.2、加挂25.8 之类）
                if not values[0] and not (len(values) > 1 and values[1]):
                    break
                body.append(values)
                if gap > 1e-6:
                    pitches.append(gap)
                prev_y = y
                if len(body) >= _TABLE_MAX_ROWS:
                    break

            # 至少要有一行是真正填了多个单元格的，避免把零散标注当成表
            if not any(sum(1 for cell in values if cell) >= 2 for values in body):
                continue
            tables.append({
                "block": "",
                "columns": columns,
                "rows": body,
                "pos": (cluster[0][0], cluster[0][1]),
            })
    return tables


def _build_table(block) -> dict[str, Any] | None:
    """还原单个表格块的列名与数据行。"""
    items = _table_texts(block)
    if not items:
        return None
    xs, ys = _table_grid(block)
    if len(xs) >= 2 and len(ys) >= 2:
        rows = _place_in_grid(items, xs, ys)
    else:
        rows = _rows_by_text(items)
    if not rows:
        return None

    header_idx = None
    for i, row in enumerate(rows[:3]):
        if sum(1 for cell in row if cell in _TABLE_HEADER_HINTS) >= 2:
            header_idx = i
            break
    if header_idx is not None and len(rows[header_idx]) >= 2:
        columns = [cell if cell else f"列{i + 1}" for i, cell in enumerate(rows[header_idx])]
        body = rows[:header_idx] + rows[header_idx + 1:]
    else:
        width = max(len(row) for row in rows)
        columns = [f"列{i + 1}" for i in range(width)]
        body = rows
    body = [row for row in body if any(cell.strip() for cell in row)]
    if not body:
        return None
    return {
        "block": block.name,
        "columns": columns,
        "rows": [[cell.strip() for cell in row] for row in body[:_TABLE_MAX_ROWS]],
    }


def _frame_labels(frame: dict[str, Any], texts: list[tuple[float, float, str]]) -> None:
    """从图签区域读「第 X 张」与图名，就地写进 frame。

    图签固定在图框右下角：横向取 55% 之后、纵向取底部 30%。图签里的固定栏目名
    （"图    号"、"共 14 张"之类）在图框块定义里，不在模型空间，所以这一段里的
    纯数字基本就是「第 X 张」的填写值；最长的一段中文就是图名。
    """
    width = frame["maxX"] - frame["minX"]
    height = frame["maxY"] - frame["minY"]
    left = frame["minX"] + width * _TITLE_BLOCK_X_RATIO
    bottom = frame["minY"] + height * _TITLE_BLOCK_Y_RATIO
    # 四个方向都要卡住：只给左/下界会把隔壁图框图签里的张号一起捞进来
    region = [(x, y, text) for x, y, text in texts
              if left <= x <= frame["maxX"] and frame["minY"] <= y <= bottom]

    sheet = None
    for _, _, text in sorted(region, key=lambda it: it[1]):
        if _SHEET_NO_RE.match(text):
            sheet = text
            break
    title = ""
    for _, _, text in region:
        if text == sheet or not _CJK_RE.search(text):
            continue
        if len(text) > len(title):
            title = text
    frame["sheet"] = sheet
    frame["title"] = title


def _drawing_frames(doc) -> list[dict[str, Any]]:
    """图框块参照的包围盒，按阅读顺序（先上后下、同排从左到右）编号。

    图纸里的表格块名是 CAD 自动生成的匿名块（*T20 之类），跟图号没关系；
    用图框编号作来源标识，用户才能对上"这是第几张图的表"。图签里还印着图纸自己
    的「第 X 张」和图名，一并取出来：合成序号只保证阅读顺序，跟纸上印的张号
    未必一致（一张图分幅画时会出现多个图框同属一张）。
    """
    from ezdxf import bbox

    texts = _table_texts(doc.modelspace())
    frames: list[dict[str, Any]] = []
    for entity in doc.modelspace():
        if entity.dxftype() != "INSERT" or _FRAME_BLOCK_HINT not in entity.dxf.name:
            continue
        try:
            # 不能用 fast=True：快速模式不展开块内容，图框范围会退化成一个点，
            # 表格就会被算到错误的图幅上去
            extents = bbox.extents([entity])
            if not extents.has_data:
                continue
        except Exception:  # noqa: BLE001 单个图框算不出范围就跳过
            continue
        insert = entity.dxf.insert
        frames.append({
            "x": insert.x,
            "y": insert.y,
            "minX": extents.extmin.x,
            "minY": extents.extmin.y,
            "maxX": extents.extmax.x,
            "maxY": extents.extmax.y,
        })
    frames.sort(key=lambda f: (-round(f["y"], 1), f["x"]))
    for index, frame in enumerate(frames):
        frame["no"] = index + 1
        _frame_labels(frame, texts)
    return frames


def _frame_no_of(point, frames: list[dict[str, Any]]) -> int | None:
    """判断某个点落在哪张图框里；不在任何图框内时取最近的图框。"""
    if not frames:
        return None
    for frame in frames:
        if (frame["minX"] - 1 <= point[0] <= frame["maxX"] + 1
                and frame["minY"] - 1 <= point[1] <= frame["maxY"] + 1):
            return frame["no"]
    return min(frames, key=lambda f: (f["x"] - point[0]) ** 2 + (f["y"] - point[1]) ** 2)["no"]


# 项目情况：标签后带冒号，按后缀识别（避免把"注意："、表格列名之类当成栏目）
_PROJECT_LABEL_RE = re.compile(r"^([\u4e00-\u9fa5A-Za-z]{2,10})\s*[：:]\s*(.*)$")
_PROJECT_LABEL_SUFFIXES = ("名称", "编号", "单位", "地点", "日期", "费用", "费",
                           "负责人", "规模", "性质", "电话")
# 展示顺序：按台账习惯排，未列到的栏目排在后面
_PROJECT_ORDER = ("项目名称", "工程名称", "项目编号", "合同编号", "订单编号",
                  "建设单位", "施工单位", "设计单位", "监理单位", "施工地点",
                  "施工日期", "施工费")


def _nearest_right(items, x0: float, y0: float, tol: float) -> str:
    """取标签同一行右侧最近的文字作为栏目值。"""
    best: tuple[float, str] | None = None
    for x, y, _height, text in items:
        if x <= x0 or abs(y - y0) > tol:
            continue
        if _PROJECT_LABEL_RE.match(text):
            continue  # 右边紧挨着的是下一个标签，说明本栏没填
        if best is None or x < best[0]:
            best = (x, text)
    return best[1] if best else ""


def extract_project_info(doc) -> list[dict[str, str]]:
    """抽取图纸的项目情况（项目名称、各类编号、参建单位、施工费等）。

    这类栏目统一写成"标签：值"左对齐排布，值在标签右侧同一行。图纸里可能有多份
    文件（如多页隐蔽工程签证记录）都印着同一个工程名称，按标签去重只保留第一次。
    """
    items = _layout_texts(doc.modelspace())
    pairs: list[tuple[str, str]] = []
    for x, y, height, text in items:
        match = _PROJECT_LABEL_RE.match(text)
        if not match:
            continue
        label = match.group(1).strip()
        if not any(label.endswith(suffix) for suffix in _PROJECT_LABEL_SUFFIXES):
            continue
        inline = match.group(2).strip()
        value = inline or _nearest_right(items, x, y, max(height, 0.5))
        if value:
            pairs.append((label, value))

    result: list[dict[str, str]] = []
    seen: set[str] = set()
    for label, value in pairs:
        if label in seen:
            continue
        seen.add(label)
        result.append({"label": label, "value": value})
    order = {label: index for index, label in enumerate(_PROJECT_ORDER)}
    result.sort(key=lambda item: order.get(item["label"], len(order)))
    return result


def _sheet_rank(frame: dict[str, Any] | None) -> tuple[int, int]:
    """排序用：图签上的「第 X 张」优先，取不到张号的图框排在最后。

    图幅在纸面上的位置顺序（先上后下、从左到右）跟图签上的张号未必一致：
    有的图纸从右往左排图，阅读顺序是 1、7、9、10…，按位置排出来的清单序号就乱了。
    """
    sheet = (frame or {}).get("sheet")
    if sheet and sheet.isdigit():
        return (0, int(sheet))
    return (1, 0)


def _match_column(columns: list[str], keys: tuple[str, ...]) -> int | None:
    for key in keys:
        for index, name in enumerate(columns):
            if key in name:
                return index
    return None


def _to_number(raw: str) -> float | None:
    try:
        return float((raw or "").replace(",", "").strip())
    except ValueError:
        return None


def summarize_materials(tables: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按「名称 + 规格 + 单位」汇总各表数量，得到归纳统计表。"""
    merged: dict[tuple[str, str, str], dict[str, Any]] = {}
    for table in tables:
        columns = table["columns"]
        name_col = _match_column(columns, _MATERIAL_NAME_KEYS)
        if name_col is None:
            continue
        spec_col = _match_column(columns, _MATERIAL_SPEC_KEYS)
        unit_col = _match_column(columns, _MATERIAL_UNIT_KEYS)
        qty_col = _match_column(columns, _MATERIAL_QTY_KEYS)
        if qty_col is None:
            continue
        for row in table["rows"]:
            if name_col >= len(row) or qty_col >= len(row):
                continue
            name = row[name_col].strip()
            qty = _to_number(row[qty_col])
            if not name or qty is None:
                continue
            spec = row[spec_col].strip() if spec_col is not None and spec_col < len(row) else ""
            unit = row[unit_col].strip() if unit_col is not None and unit_col < len(row) else ""
            key = (name, spec, unit)
            item = merged.setdefault(key, {"name": name, "spec": spec, "unit": unit,
                                           "quantity": 0.0, "tables": []})
            item["quantity"] += qty
            if table["table"] not in item["tables"]:
                item["tables"].append(table["table"])

    result = []
    for item in merged.values():
        item["quantity"] = round(item["quantity"], 3)
        item["tableCount"] = len(item["tables"])
        item["tables"] = item["tables"][:20]
        result.append(item)
    result.sort(key=lambda kv: (-kv["quantity"], kv["name"]))
    return result


def _tables_from_blocks(doc) -> list[dict[str, Any]]:
    """画法一：ACAD_TABLE（AutoCAD 表格对象）。

    AutoCAD 只把表格几何与文字放在 *T20 这类匿名块里，模型空间留一个块引用，
    所以按块名找表、按块引用位置算图幅归属。
    """
    refs: dict[str, list] = {}
    for entity in doc.modelspace():
        if entity.dxftype() == "INSERT" and _TABLE_BLOCK_RE.match(entity.dxf.name):
            refs.setdefault(entity.dxf.name, []).append(entity.dxf.insert)

    tables: list[dict[str, Any]] = []
    for block in doc.blocks:
        if not _TABLE_BLOCK_RE.match(block.name):
            continue
        table = _build_table(block)
        if not table:
            continue
        points = refs.get(block.name) or []
        table["pos"] = (points[0].x, points[0].y) if points else None
        tables.append(table)
    return tables


def _tables_from_text(doc) -> list[dict[str, Any]]:
    """画法二：普通 LINE 外框 + TEXT 手工画的表格（没有块名可循）。"""
    return _text_tables(_layout_texts(doc.modelspace()))


# 表格抽取器注册表：(key, 说明, 抽取函数)。
# 不同设计院/不同软件导出的图纸画法各异，这里把"找表"拆成互不干扰的策略：
# 每个抽取器只认一种画法、只返回候选表，合并/去重/排序/图框归属/编号统一在
# extract_material_tables 里做。以后遇到新画法（表在块定义里、跨图纸参照、
# 表格是 OLE/Excel 附件…）只需加一个函数并注册，主流程和已有图纸都不受影响。
TABLE_EXTRACTORS: tuple[tuple[str, str, Any], ...] = (
    ("block", "CAD 表格对象", _tables_from_blocks),
    ("text", "线框文字表格", _tables_from_text),
)


def extract_material_tables(doc, diagnostics: dict[str, int] | None = None
                            ) -> list[dict[str, Any]]:
    """还原图纸里的表格（材料设备清单），同一图幅内重复的表格只保留一份。

    编号按「图框阅读顺序 + 图框内自上而下」排，不按块定义顺序：同一张图的两张表
    （主工程量表、光缆段长统计表）块名可能相隔十几号，按块名排就会变成"表1、表15"，
    看上去像是漏了中间的表。frameIndex 是图框内的序号（表1、表2…），table 是全局
    唯一序号，归纳统计靠它去重计数。

    Args:
        diagnostics: 传入空字典时回填每个抽取器命中多少张表，供前端/日志判断
            这张图是用哪种画法解析出来的。
    """
    frames = _drawing_frames(doc)

    built: list[tuple[tuple, int | None, dict[str, Any]]] = []
    for key, _label, extractor in TABLE_EXTRACTORS:
        try:
            found = extractor(doc)
        except Exception as exc:  # noqa: BLE001 单个抽取器出错不该拖垮整张图
            logger.warning("表格抽取器 %s 执行失败：%s", key, exc)
            found = []
        if diagnostics is not None:
            diagnostics[key] = len(found)
        for table in found:
            point = table.pop("pos", None)
            frame_no = _frame_no_of(point, frames) if point else None
            frame = next((f for f in frames if f["no"] == frame_no), None)
            # 张号升序 → 同张内部按图框 → 图框内自上而下（y 降序）→ 自左而右
            pos = (_sheet_rank(frame),
                   frame_no if frame_no is not None else 10 ** 6,
                   -round(point[1], 1) if point else 0.0,
                   point[0] if point else 0.0)
            built.append((pos, frame_no, table))
    built.sort(key=lambda item: item[0])

    tables: list[dict[str, Any]] = []
    seen: set[tuple] = set()
    per_frame: dict[int | None, int] = {}
    for _, frame_no, table in built:
        # 只合并同一图幅内重复出现的表，跨图的内容相同不能合并：多站点图纸里
        # 每张图的"安装 1 套光分纤箱"就是各自独立的工作量，合掉就等于少算。
        key = (frame_no, tuple(table["columns"]), tuple(tuple(row) for row in table["rows"]))
        if key in seen:
            continue
        seen.add(key)
        frame = next((f for f in frames if f["no"] == frame_no), None)
        table["table"] = len(tables) + 1
        table["frame"] = frame_no
        table["frameSheet"] = frame["sheet"] if frame else None
        table["frameTitle"] = frame["title"] if frame else None
        index = per_frame.get(frame_no, 0) + 1
        per_frame[frame_no] = index
        table["frameIndex"] = index
        tables.append(table)
        if len(tables) >= _TABLE_MAX_TABLES:
            break
    return tables


def parse_dxf_bytes(data: bytes, filename: str, sample_limit: int = 300,
                    oda_converter: str = "") -> dict[str, Any]:
    try:
        import ezdxf  # 延迟导入：未安装时给出可读提示
    except ImportError:
        return {
            "status": "FAILED",
            "message": "AI 服务未安装 ezdxf，无法解析 DXF（pip install ezdxf）",
            "hint": "图纸解析失败不影响清单核查主链路",
        }

    try:
        # 延迟导入：drawing_store 依赖本模块的转换函数，避免循环导入
        from app.services.drawing_store import get_or_convert

        target, cached = get_or_convert(data, filename or "drawing.dxf", oda_converter)
        if cached:
            logger.info("图纸转换缓存命中：%s", target.name)
        doc = ezdxf.readfile(str(target))
    except Exception as exc:  # noqa: BLE001 图纸异常需明确降级
        logger.warning("图纸解析失败(%s): %s", filename, exc)
        return {"status": "FAILED", "message": f"图纸解析失败：{exc}",
                "hint": "加密/外部参照/Xref 图纸建议人工辅助标注"}

    msp = doc.modelspace()
    units = _insunits_label(int(doc.header.get("$INSUNITS", 0) or 0))

    length_by_layer: dict[str, float] = {}
    block_counts: dict[str, int] = {}
    layer_counts: dict[str, int] = {}
    entities_sample: list[dict[str, Any]] = []
    annotations: list[str] = []
    total_entities = 0
    total_length = 0.0

    for entity in msp:
        total_entities += 1
        etype = entity.dxftype()
        layer = getattr(entity.dxf, "layer", "0") or "0"
        layer_counts[layer] = layer_counts.get(layer, 0) + 1

        if etype == "LINE":
            start = entity.dxf.start
            end = entity.dxf.end
            length = math.dist((start.x, start.y, start.z), (end.x, end.y, end.z))
            total_length += length
            length_by_layer[layer] = length_by_layer.get(layer, 0.0) + length
            if len(entities_sample) < sample_limit:
                entities_sample.append({"id": entity.dxf.handle, "type": etype, "layer": layer,
                                        "length": round(length, 3)})
        elif etype in ("LWPOLYLINE", "POLYLINE"):
            points = []
            try:
                points = [(p[0], p[1]) for p in entity.get_points("xy")] if etype == "LWPOLYLINE" \
                    else [(v.dxf.location.x, v.dxf.location.y) for v in entity.vertices]
            except Exception:  # noqa: BLE001
                points = []
            length = sum(math.dist(points[i], points[i + 1]) for i in range(len(points) - 1))
            total_length += length
            length_by_layer[layer] = length_by_layer.get(layer, 0.0) + length
            if len(entities_sample) < sample_limit:
                entities_sample.append({"id": entity.dxf.handle, "type": etype, "layer": layer,
                                        "length": round(length, 3)})
        elif etype == "INSERT":
            name = entity.dxf.name
            block_counts[name] = block_counts.get(name, 0) + 1
            if len(entities_sample) < sample_limit:
                entities_sample.append({"id": entity.dxf.handle, "type": etype, "layer": layer,
                                        "block": name})
        elif etype in ("TEXT", "MTEXT"):
            text = entity.dxf.text if etype == "TEXT" else entity.text
            if text and len(annotations) < sample_limit:
                annotations.append(text.strip())
        elif len(entities_sample) < sample_limit:
            entities_sample.append({"id": entity.dxf.handle, "type": etype, "layer": layer})

    cable_layers = {k: round(v, 3) for k, v in length_by_layer.items()
                    if any(h in k for h in CABLE_HINTS)}
    device_blocks = {k: v for k, v in block_counts.items() if any(h in k for h in DEVICE_HINTS)}
    device_total = sum(device_blocks.values())
    meters = _to_meters(total_length, units)
    cable_meters = _to_meters(sum(cable_layers.values()), units)

    project_info = extract_project_info(doc)
    table_hits: dict[str, int] = {}
    materials = extract_material_tables(doc, table_hits)
    material_summary = summarize_materials(materials)
    material_items = sum(len(table["rows"]) for table in materials)
    # 记录每种画法各抽出多少张表，出问题时能一眼看出是"没这张表"还是"这种画法没覆盖"
    table_sources = [{"key": key, "label": label, "count": table_hits.get(key, 0)}
                     for key, label, _ in TABLE_EXTRACTORS]

    summary = (f"图元 {total_entities} 个，图层 {len(layer_counts)} 个；"
               f"线路总长 {round(meters, 2)} m（线缆图层 {round(cable_meters, 2)} m）；"
               f"设备块 {device_total} 个；材料表 {len(materials)} 张 / {material_items} 条"
               f"（归纳 {len(material_summary)} 种）")

    return {
        "status": "SUCCESS",
        "summary": summary,
        "unit": units,
        "measure": {
            "totalEntities": total_entities,
            "totalLengthMeters": round(meters, 3),
            "cableLengthMeters": round(cable_meters, 3),
            "deviceCount": device_total,
            "materialTableCount": len(materials),
            "materialItemCount": material_items,
            "materialKindCount": len(material_summary),
            "lengthByLayer": {k: round(v, 3) for k, v in sorted(
                length_by_layer.items(), key=lambda kv: kv[1], reverse=True)[:30]},
            "cableLayers": cable_layers,
            "blockCounts": dict(sorted(block_counts.items(), key=lambda kv: kv[1], reverse=True)[:30]),
            "deviceBlocks": device_blocks,
            "layers": dict(sorted(layer_counts.items(), key=lambda kv: kv[1], reverse=True)[:50]),
        },
        "materials": materials,
        "materialSummary": material_summary,
        "projectInfo": project_info,
        "materialDiagnostics": {"sources": table_sources, "tableCount": len(materials)},
        "entities": entities_sample,
        "annotations": annotations[:100],
        "hint": "一期图元回溯定位仅做清单行号，图纸图元坐标回溯在二期补齐",
    }
