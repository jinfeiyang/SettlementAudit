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

# 图上标注的段长（「5.7」「桥20」「钉65.2余14」这类）。
# 不少竣工图不单独画光缆图层，长度只写在每段路由线旁边，只能从标注反推。
# 一行里常并排写好几段（「桥架229.7余5 线槽5余2 钉0.3」），所以按 token 扫而不是整行匹配；
# 小数只认 1~2 位：坐标（121.241693）也是数字串，靠这条挡掉。
_LENGTH_KIND_WORDS = ("管道|排管|穿管|加挂|附挂|架空|直埋|墙壁|沿墙|槽道|桥架|过桥|顶管|飞线"
                      "|引上|引下|桥|钉|余|槽|管|引|埋")
LENGTH_TOKEN_RE = re.compile(
    rf"(?P<kind>{_LENGTH_KIND_WORDS})?\s*(?<!\d)(?P<value>\d+(?:\.\d{{1,2}})?)(?![\d.A-Za-z])"
    r"\s*(?P<unit>m|米)?",
    re.IGNORECASE,
)
# 光缆段的合计：「合计399.5」「合计：470」。图上印了合计就说明这几段光缆已经汇总过。
LENGTH_TOTAL_RE = re.compile(r"合计\s*[:：]?\s*(?P<value>\d+(?:\.\d{1,2})?)\s*(?:m|米)?",
                             re.IGNORECASE)
# 纯段长行里允许残留的字符（分隔符与说明文字）；残留了数字/字母说明这行不是段长标注
_LENGTH_NOISE_RE = re.compile(r"[A-Za-z0-9]")
# 敷设方式词的首字：「钉65.2余14」里的"余"是下一段的开头，不是 65.2 的单位
_LENGTH_KIND_CHARS = frozenset("管排穿加附架直墙沿槽桥过顶飞引埋钉余")
# 数字后面紧跟汉字基本是规格/单位（成端6芯、1孔、直径20管），不是长度
_LENGTH_SUFFIX_RE = re.compile(r"^[\u4e00-\u9fa5]")
# 行里带「芯」的是光缆明细/规格说明，里面的数字是分层汇总，不能当段长
_ANNOTATION_SPEC_HINT_RE = re.compile(r"芯")

# 标注前缀对应的敷设方式，与前端 cableCategory 的分类键保持一致。
# 通信线路图上「桥20」「钉4」「引上4」「余10」都是段长，前缀只是敷设方式。
LENGTH_KIND_MAP = {
    "管": "pipe", "管道": "pipe", "排管": "pipe", "穿管": "pipe", "顶管": "pipe",
    "加挂": "overhead", "附挂": "overhead", "架空": "overhead",
    "直埋": "buried",
    "墙壁": "wall", "沿墙": "wall", "钉": "wall",
    "槽道": "duct", "桥架": "duct", "桥": "duct", "过桥": "duct", "槽": "duct",
    "引上": "other", "引下": "other", "余": "other", "飞线": "other",
}


def parse_length_labels(text: str) -> tuple[list[tuple[float, str, bool]], list[tuple[float, bool]]]:
    """把一行标注解析成 (逐段长度, [(合计值, 是否纯合计行)])。

    逐段长度只在「整行都是段长」时才采信：去掉段长 token 后还剩文字或数字，说明这行
    是别的东西（「成端6芯」「121.28440」「3号楼B1F弱电间」），数字不是长度。
    合计值不受这条限制——它是明确写着"合计"的汇总行，前后带说明文字也照样取；
    「纯合计行」（整行除合计外没别的）才算一层完整汇总，混在明细里的合计只作参考，
    否则"每段光缆合计 + 按芯数汇总的合计"会被加两遍。
    """
    line = text or ""
    total_matches = [m for m in LENGTH_TOTAL_RE.finditer(line)
                     if 0.5 <= float(m.group("value")) <= 10000]
    totals = [(float(m.group("value")), False) for m in total_matches]

    rest = LENGTH_TOTAL_RE.sub(" ", line)
    spans: list[tuple[int, int]] = []
    tokens: list[tuple[float, str, bool, bool]] = []  # (值, 敷设方式, 是否整数标注, 有无前缀)
    for match in LENGTH_TOKEN_RE.finditer(rest):
        tail = rest[match.end():match.end() + 1]
        if not match.group("unit") and tail and _LENGTH_SUFFIX_RE.match(tail) \
                and tail not in _LENGTH_KIND_CHARS:
            continue  # 「成端6芯」「1孔」「24芯」：数字后面直接跟汉字，是规格不是长度
        # 采信与否都从"残留判定"里扣掉命中片段：「钉0.3」这种太短的段长不该因此把整行判死
        spans.append(match.span())
        value = float(match.group("value"))
        if not 0.5 <= value <= 10000:
            continue
        raw = match.group("value")
        has_unit = bool(match.group("unit"))
        prefix = (match.group("kind") or "").strip()
        kind = LENGTH_KIND_MAP.get(prefix, "")
        tokens.append((value, kind, "." not in raw and not has_unit, bool(prefix)))

    pieces, cursor = [], 0
    for start, end in spans:
        pieces.append(rest[cursor:start])
        cursor = end
    pieces.append(rest[cursor:])
    leftover = "".join(pieces).strip()
    if _LENGTH_NOISE_RE.search(leftover):
        # 这行还夹着别的数字/字母：段长不可信，合计也只是"明细里的汇总"，不算完整一层
        return [], totals
    if _ANNOTATION_SPEC_HINT_RE.search(leftover):
        # 带"芯"的行是光缆明细/规格说明（24芯*12 管1025.7+25…），数字是分层汇总，不是段长
        return [], totals

    # 没写敷设方式的数字只有"整行就一个数"才算段长：
    # 「5.7」「桥20」是段长，「黄岩朱砂街红三路1」「开挖1」「硅1」里的 1、2 是站名/孔数
    pure_line = not leftover
    segments = [(value, kind, is_int) for value, kind, is_int, has_prefix in tokens
                if has_prefix or pure_line]
    totals = [(value, True) for value, _ in totals]
    return segments, totals


# MTEXT 里的控制码：\P 换行、\fSimSun|b0; 字体、{\...} 分组
_MTEXT_CODE_RE = re.compile(r"\\[A-Za-z][^;\\]*;?|[{}]")


def clean_annotation(text: str) -> str:
    """清掉 MTEXT 控制码，避免「5.7\\P」这种带格式的标注漏识别。"""
    cleaned = (text or "").replace("\\P", "\n").replace("\\p", "\n")
    cleaned = _MTEXT_CODE_RE.sub(" ", cleaned)
    return re.sub(r"\s+", " ", cleaned).strip()


def _filter_table_numbers(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    表格里的序号是 1、2、3… 连号，图号/编号又总挤在一起，按这两条把整数剔掉；
    小数标注（5.7、453.7）直接保留，整数只有不成连号、周围也没有一大堆数字时才当段长。
    """
    if not items:
        return []
    # 按图层分组：表格里的序号通常和段长标注不在同一层，分层判断能保住「10」「1」这种整数段长
    by_layer: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        by_layer.setdefault(item["layer"], []).append(item)

    kept: list[dict[str, Any]] = []
    for layer_items in by_layer.values():
        # 连号检测：同一图层里出现 4 个以上连续整数，基本就是表格序号
        int_values = sorted({item["value"] for item in layer_items if item["isInt"]})
        run_drop: set[float] = set()
        start = 0
        for index in range(1, len(int_values) + 1):
            if index == len(int_values) or int_values[index] != int_values[index - 1] + 1:
                if index - start >= 4:
                    run_drop.update(int_values[start:index])
                start = index

        xs = [item["x"] for item in layer_items]
        ys = [item["y"] for item in layer_items]
        span = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
        radius = span * 0.02
        for item in layer_items:
            if not item["isInt"]:
                kept.append(item)
                continue
            if item["value"] in run_drop:
                continue
            neighbours = 0
            for other in layer_items:
                if other is item:
                    continue
                if abs(other["x"] - item["x"]) <= radius and abs(other["y"] - item["y"]) <= radius:
                    neighbours += 1
                    if neighbours >= 4:
                        break
            if neighbours < 4:
                kept.append(item)
    return kept


def _is_close_value(value: float, target: float) -> bool:
    """两个长度是否算同一个值（合计标注与逐段之和会差在四舍五入上）。"""
    return abs(value - target) <= max(0.6, abs(target) * 0.005)


def _sheet_order(sheet: str) -> tuple[int, str]:
    """分张明细按图签张号升序排，跟翻阅图纸的顺序一致（第2张在第13张前面）。"""
    match = re.search(r"\d+", sheet or "")
    return (int(match.group()) if match else 10 ** 6, sheet or "")


def _label_group(item: dict[str, Any]) -> tuple[float, float, str]:
    """同一条标注重（同一位置同一段文字）归为一组：一行里的几段长度要么都算，要么都不算。"""
    return (round(item["x"], 1), round(item["y"], 1), item["text"])


def _sheet_key(frame: dict[str, Any] | None) -> str:
    """按「张」归组的键：一张图可能分幅画成多个图框，它们的标注要合起来看。

    图签上的张号就是纸面上的"第几张"，分幅的多个图框共用同一个张号；
    取不到张号时退回图框编号，至少不会把不同图框的量混在一起。
    """
    if frame and frame.get("sheet"):
        return f"第{frame['sheet']}张"
    if frame:
        return f"图框{frame['no']}"
    return "未归属图框"


def _table_cable_items(tables: list[dict[str, Any]], frames: list[dict[str, Any]]
                       ) -> dict[str, list[dict[str, Any]]]:
    """各张图材料表里「通信光缆」那几行，键与 _sheet_key 一致。

    按行返回而不是只返回合计：核对时要能说出"这张图的 6356.9 米 = 24芯 3104.4 +
    12芯 2612.4 + 8芯 640.1"，才能拿图纸材料表逐行对。
    竣工图常在材料表旁再标一次光缆总长，数值与该表数量一致，那段标注也要靠它识别。
    """
    by_no = {frame["no"]: frame for frame in frames}
    items: dict[str, list[dict[str, Any]]] = {}
    for table in tables:
        columns = table["columns"]
        name_col = _match_column(columns, _MATERIAL_NAME_KEYS)
        qty_col = _match_column(columns, _MATERIAL_QTY_KEYS)
        unit_col = _match_column(columns, _MATERIAL_UNIT_KEYS)
        spec_col = _match_column(columns, _MATERIAL_SPEC_KEYS)
        if name_col is None or qty_col is None:
            continue
        key = _sheet_key(by_no.get(table.get("frame")))
        for row in table["rows"]:
            if max(name_col, qty_col) >= len(row):
                continue
            if not any(hint in row[name_col] for hint in ("光缆", "电缆")):
                continue
            if unit_col is not None and unit_col < len(row) \
                    and row[unit_col].strip().lower() not in ("米", "m"):
                continue
            qty = _to_number(row[qty_col])
            if not qty:
                continue
            spec = row[spec_col].strip() if spec_col is not None and spec_col < len(row) else ""
            label = f"{row[name_col].strip()} {spec}".strip()
            items.setdefault(key, []).append({"label": label, "meters": round(qty, 3)})
    return items


def summarize_annotations(segments: list[dict[str, Any]], totals: list[dict[str, Any]],
                          key_of, table_items: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    """按张汇总图上标注的长度，返回采用值、分张明细与摘掉的重复标注。

    取数优先级按可信度排，一张图（分幅合起来看）选一个：
      1) 材料表里印的线缆数量——画图的人自己汇总过的工程量；
      2) 整行只有「合计 XXX」的汇总行——同样是汇总值，但注意图上常同时印"每段光缆
         的合计"和"按芯数汇总的合计"，两层加起来会翻倍，所以这层只做兜底；
      3) 逐段标注之和——最后的手段，图上把同一段路由写两遍的情况挡不掉。
    材料表旁那张"光缆总长"标注与材料表数量相同，直接摘出来，不参与分段和。

    每张还带上组成明细（items）：用材料表时就是表里那几行光缆，用户可以逐行对图纸。
    """
    table_totals = {sheet: round(sum(row["meters"] for row in rows), 3)
                    for sheet, rows in table_items.items()}
    dropped: list[dict[str, Any]] = []
    # 按"一条标注"整组处理：图纸把整段路由又写成一行（「埋1+管453.7」= 454.7），
    # 这行的每一段都不该再加，否则那段路由就被算了两次
    by_sheet_groups: dict[str, dict[tuple, list[dict[str, Any]]]] = {}
    for item in segments:
        sheet = key_of(item)
        by_sheet_groups.setdefault(sheet, {}).setdefault(_label_group(item), []).append(item)

    counted: list[dict[str, Any]] = []
    segment_sum: dict[str, float] = {}
    segment_count: dict[str, int] = {}
    for sheet, groups in by_sheet_groups.items():
        table_sum = table_totals.get(sheet) or 0.0
        rest_groups: dict[tuple, list[dict[str, Any]]] = {}
        for key, items in groups.items():
            group_sum = sum(item["value"] for item in items)
            if table_sum and _is_close_value(group_sum, table_sum):
                dropped.extend(items)  # 这一行就是该张图的光缆总长，和材料表是同一批长度
            else:
                rest_groups[key] = items
        # 没有材料表时，用"这一行 ≈ 其余各行之和"判断整段路由的汇总行
        if not table_sum and len(rest_groups) >= 3:
            sums = {key: sum(item["value"] for item in items) for key, items in rest_groups.items()}
            group_total = sum(sums.values())
            for key, group_sum in sums.items():
                if _is_close_value(group_sum, group_total - group_sum):
                    dropped.extend(rest_groups.pop(key))
                    break
        for items in rest_groups.values():
            counted.extend(items)
            segment_sum[sheet] = segment_sum.get(sheet, 0.0) + sum(item["value"] for item in items)
            segment_count[sheet] = segment_count.get(sheet, 0) + len(items)

    totals_by_sheet: dict[str, list[float]] = {}
    for item in totals:
        if not item.get("pure"):
            continue
        totals_by_sheet.setdefault(key_of(item), []).append(item["value"])

    sheet_rows = []
    for sheet in set(segment_sum) | set(totals_by_sheet) | set(table_items):
        total_sum = round(sum(totals_by_sheet.get(sheet, [])), 3)
        table_sum = table_totals.get(sheet, 0.0)
        if table_sum:
            meters, source = table_sum, "table"
            items = table_items.get(sheet, [])
        elif total_sum:
            meters, source = total_sum, "summary"
            items = [{"label": f"图上合计（{len(totals_by_sheet.get(sheet, []))} 处）",
                      "meters": total_sum}]
        else:
            meters, source = segment_sum.get(sheet, 0.0), "segments"
            items = []
        sheet_rows.append({
            "sheet": sheet,
            "meters": round(meters, 3),
            "segmentMeters": round(segment_sum.get(sheet, 0.0), 3),
            "totalMeters": total_sum,
            "tableMeters": table_sum,
            "source": source,
            "count": segment_count.get(sheet, 0) + len(totals_by_sheet.get(sheet, [])),
            "items": items,
        })
    sheet_rows.sort(key=lambda row: _sheet_order(row["sheet"]))
    sources = {row["source"] for row in sheet_rows}
    return {
        "meters": round(sum(row["meters"] for row in sheet_rows), 3),
        # 全部按分段取量才能按敷设方式拆开；用了合计/材料表时口径对不上，就不拆
        "basis": sources.pop() if len(sources) == 1 else ("mixed" if sources else "none"),
        "sheets": sheet_rows,
        "segments": counted,
        "dropped": dropped,
    }

# 表格几何块：CAD 把 ACAD_TABLE 的单元格与边框放在匿名块里（展开后是普通 INSERT）
_TABLE_BLOCK_RE = re.compile(r"^\*T\d+$")
# 表头识别用：出现 2 个以上即认为是表头行
_TABLE_HEADER_HINTS = {"序号", "名称", "项目名称", "规格", "规格程式", "型号", "单位", "数量", "备注"}
# CAD 里表头常把字拆开写（「数 量」「规 格 程 式」），比对前先去空白
_HEADER_SPACE_RE = re.compile(r"[\s\u3000]+")
# 合计/小计这类行是独立数据行，拆行合并时要避开
_TABLE_RECAP_HINTS = ("合计", "小计", "总计", "累计")
_TABLE_MAX_TABLES = 50
_TABLE_MAX_ROWS = 300
# 每张表随结果返回的单元格坐标上限：前端只用来把材料名称标红，不需要全图文字
_CELL_META_LIMIT = 400
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
    """把文字按 y 聚成行（行内按 x 升序），供无表格块的图纸还原表格。

    归行阈值拿"本行已归入文字的平均 y"比，而不是拿行里第一个文字比：并排几张表的
    行常常差零点几毫米，用首字当基准会把同一行拆成两行（序号、单位掉到下一行）。
    """
    rows: list[list[tuple[float, float, str]]] = []
    means: list[float] = []
    for item in sorted(items, key=lambda it: -it[1]):
        if rows and abs(item[1] - means[-1]) <= tol:
            rows[-1].append(item)
            means[-1] = (means[-1] * (len(rows[-1]) - 1) + item[1]) / len(rows[-1])
        else:
            rows.append([item])
            means.append(item[1])
    return [sorted(row, key=lambda it: it[0]) for row in rows]


def _norm_header(text: str) -> str:
    """表头归一化：CAD 里常写成「数 量」「规 格 程 式」，去掉空白才认得出。"""
    return _HEADER_SPACE_RE.sub("", text or "")


def _header_hints(text: str) -> set[str]:
    key = _norm_header(text)
    return {hint for hint in _TABLE_HEADER_HINTS if hint in key}


def _is_header_text(text: str) -> bool:
    return bool(_header_hints(text))


def _is_header_row(row) -> bool:
    """至少两个不同类型的表头词才算表头。

    图签里左右各有一个「工程名称」，按命中次数算会凑够 2 个，把图签当成材料表；
    按词种去重后「工程名称」只算一个（名称），不会误判。
    """
    hints: set[str] = set()
    for _, _, text in row:
        hints |= _header_hints(text)
    return len(hints) >= 2


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


def _merge_split_rows(body: list[list[str]]) -> list[list[str]]:
    """把被拆成两行的同一行数据合回来。

    有的图纸「序号、单位」和「名称、规格、数量」基线差一点点，按 y 聚行时会分成两行：
    ['', '普通层绞式通信光缆', 'GYTS-48B1', '', '1787.9'] 与 ['1', '', '', '米', '']。
    后一行只有零星两格、且和上一行不冲突时，补进上一行的空格里。
    """
    merged: list[list[str]] = []
    for row in body:
        filled = sum(1 for cell in row if cell)
        if merged and filled <= 2:
            prev = merged[-1]
            texts = [cell for cell in row if cell]
            conflict = any(prev[i] and row[i] for i in range(min(len(prev), len(row))))
            # 「合计/小计」这类行是独立的数据行，不能并到上一行去
            recap = any(hint in text for text in texts for hint in _TABLE_RECAP_HINTS)
            if not conflict and not recap and sum(1 for cell in prev if cell) >= 2:
                for index, cell in enumerate(row):
                    if cell and index < len(prev) and not prev[index]:
                        prev[index] = cell
                continue
        merged.append(list(row))
    return merged


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
    # 每个文字的字高：前端要按它把材料名称标红，聚行时已经把字高丢了，这里按插入点反查
    height_of = {(round(x, 6), round(y, 6)): height
                 for x, y, height, _ in entries if height and height > 0}
    default_height = statistics.median(heights) if heights else 2.0

    tables: list[dict[str, Any]] = []

    for start, row in enumerate(rows):
        for cluster in _split_row(row):
            if not _is_header_row(cluster):
                continue
            # 表头左右可能粘着同一高度的标注文字（尺寸、编号），按表头关键词位置裁掉
            hint_index = [i for i, (_, _, text) in enumerate(cluster) if _is_header_text(text)]
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
            # 收表区域的文字坐标：图纸没画图框时，前端只能靠它定位到这张表所在的页面
            used: list[tuple[float, float]] = [(x, y) for x, y, _ in cluster]
            # 单元格文字坐标：前端按「材料名称」匹配到这里，在图纸上把该名称标红
            table_cells: list[dict[str, Any]] = [
                _cell_meta(x, y, text, height_of, default_height) for x, y, text in cluster
            ]
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
                    # 按"离哪一列的列心最近"归列：文字插入点是左边缘，长的单元格名
                    # （普通层绞式通信光缆）左边缘会离列心很远，卡死容差会把名称整列丢掉
                    index = min(range(len(centers)), key=lambda i: abs(centers[i] - x))
                    values[index] = f"{values[index]} {text}".strip() if values[index] else text
                    filled += 1
                if not filled:
                    break
                # 序号、名称都没有的"行"其实是表格下方的长度标注（管53.2、加挂25.8 之类）
                if not values[0] and not (len(values) > 1 and values[1]):
                    break
                # 只填了一格的多半是「主要材料(乙供)表」这类表标题或表注，不当数据行
                if filled == 1 and not values[0]:
                    continue
                body.append(values)
                used.extend((x, y) for x, y, _ in cells)
                table_cells.extend(
                    _cell_meta(x, y, text, height_of, default_height) for x, y, text in cells
                )
                if gap > 1e-6:
                    pitches.append(gap)
                prev_y = y
                if len(body) >= _TABLE_MAX_ROWS:
                    break

            # 至少要有一行是真正填了多个单元格的，避免把零散标注当成表
            if not any(sum(1 for cell in values if cell) >= 2 for values in body):
                continue
            body = _merge_split_rows(body)
            tables.append({
                "block": "",
                "columns": columns,
                "rows": body,
                "pos": (cluster[0][0], cluster[0][1]),
                "box": _text_box(used, heights),
                "cells": table_cells[:_CELL_META_LIMIT],
            })
    return tables


def _cell_meta(x: float, y: float, text: str, height_of: dict, default_height: float
               ) -> dict[str, Any]:
    """单元格文字 + 插入点 + 字高，供前端在图纸上定位并标红材料名称。"""
    height = height_of.get((round(x, 6), round(y, 6))) or default_height
    return {"text": text, "x": round(x, 3), "y": round(y, 3), "h": round(height, 3)}


def _text_box(points, heights) -> dict[str, float]:
    """按文字插入点估算表格外框（图纸坐标）。

    TEXT 的插入点是左边缘、基线，不是字的外接框：横向要往右多留几个字高
    （最长的材料名称可能十几字），纵向留一个字高。只用它做「跳到这一页」的
    视野中心，不需要精确到框线。
    """
    if not points:
        return {}
    pad = max(heights) if heights else 2.0
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return {"minX": round(min(xs) - pad, 3), "minY": round(min(ys) - pad, 3),
            "maxX": round(max(xs) + pad * 6, 3), "maxY": round(max(ys) + pad * 2, 3)}


def _build_table(block) -> dict[str, Any] | None:
    """还原单个表格块的列名与数据行。"""
    layouts = _layout_texts(block)
    items = [(x, y, text) for x, y, _, text in layouts]
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
        # 块内坐标：引用到模型空间时在 _tables_from_blocks 里按块引用矩阵换算
        "cells": [{"text": text, "x": round(x, 3), "y": round(y, 3), "h": round(height or 2.0, 3)}
                  for x, y, height, text in layouts[:_CELL_META_LIMIT]],
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
    if not frames:
        # 图纸没做成「图框」块，但图签直接画在模型空间时，按图签的图号识别页
        frames = _title_block_frames(doc, texts)
        if frames:
            return frames
        # 批量导出的图纸既没有图框块也不写图号：按页幅大小的矩形识别图页
        return _page_rect_frames(doc, texts)
    frames.sort(key=lambda f: (-round(f["y"], 1), f["x"]))
    for index, frame in enumerate(frames):
        frame["no"] = index + 1
        _frame_labels(frame, texts)
    return frames


# 图签「图 号」栏：标签右侧同一行的短文字就是这一页的图号
_TITLE_NO_RE = re.compile(r"^图\s*号$")
_TITLE_NO_MAX_DX = 60.0
_TITLE_NO_DY = 1.5
# 页边界的筛选：太小的是符号框，太扁的是图例框
_MIN_SHEET_WIDTH = 250.0
_MIN_SHEET_HEIGHT = 150.0
_MIN_SHEET_RATIO = 0.8
_MAX_SHEET_RATIO = 8.0
# 图签框外面那一层才是整页：只比图签大一圈，太大的是几页合在一起的外框
_PAGE_OVER_TITLE_RATIO = 1.02
_PAGE_MAX_OVER_TITLE = 1.4


def _title_block_frames(doc, texts: list[tuple[float, float, str]]) -> list[dict[str, Any]]:
    """没找到「图框」块时，用图签上的「图 号」识别每一页。

    竣工图常把图签直接画在模型空间：一个"图 号"标签 + 右侧的编号，外面套一圈
    矩形就是这一页的边界。取包住图签的最小闭合矩形当页范围，图号当张号，
    材料表就能归到具体哪一页，点"第几张"直接定位过去。
    """
    labels = [(x, y, text) for x, y, text in texts if _TITLE_NO_RE.match(text)]
    if not labels:
        return []
    rectangles: list[tuple[float, float, float, float, float]] = []
    for entity in doc.modelspace().query("LWPOLYLINE"):
        try:
            if not entity.closed:
                continue
            points = list(entity.get_points("xy"))
        except Exception:  # noqa: BLE001 个别实体取点失败不影响整体
            continue
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        width = max(xs) - min(xs)
        height = max(ys) - min(ys)
        if width < _MIN_SHEET_WIDTH or height < _MIN_SHEET_HEIGHT:
            continue
        if not _MIN_SHEET_RATIO <= width / height <= _MAX_SHEET_RATIO:
            continue
        rectangles.append((width * height, min(xs), min(ys), max(xs), max(ys)))

    by_x = sorted(texts, key=lambda item: item[0])
    frames: list[dict[str, Any]] = []
    seen: set[tuple[float, ...]] = set()
    for x, y, _ in labels:
        sheet = ""
        for tx, ty, text in by_x:
            dx = tx - x
            if 0 < dx <= _TITLE_NO_MAX_DX and abs(ty - y) <= _TITLE_NO_DY:
                sheet = text.strip()
                break
        if not sheet:
            continue
        # 图号标签在图签格里，图签格又套在整页边框里：
        # 先取包住标签的最小矩形（图签），再取比它大一圈的最小矩形（整页）
        title_box = min((rect for rect in rectangles
                         if rect[1] - 1 <= x <= rect[3] + 1 and rect[2] - 1 <= y <= rect[4] + 1),
                        default=None)
        if title_box is None:
            continue
        title_width = title_box[3] - title_box[1]
        title_height = title_box[4] - title_box[2]
        box = min((rect for rect in rectangles
                   if rect[0] > title_box[0] * _PAGE_OVER_TITLE_RATIO
                   and rect[3] - rect[1] <= title_width * _PAGE_MAX_OVER_TITLE
                   and rect[4] - rect[2] <= title_height * _PAGE_MAX_OVER_TITLE
                   and rect[1] - 1 <= title_box[1] and rect[2] - 1 <= title_box[2]
                   and rect[3] + 1 >= title_box[3] and rect[4] + 1 >= title_box[4]),
                  default=title_box)
        key = tuple(round(value, 1) for value in box[1:])
        if key in seen:
            continue
        seen.add(key)
        frames.append({
            "x": x,
            "y": y,
            "minX": box[1],
            "minY": box[2],
            "maxX": box[3],
            "maxY": box[4],
            "sheet": sheet,
            "title": "",
        })
    # 同排图签的 y 会差零点几，按 20 分桶再排，避免第一排被拆开
    frames.sort(key=lambda frame: (-round(frame["y"] / 20), frame["x"]))
    for index, frame in enumerate(frames):
        frame["no"] = index + 1
    return frames


# 页幅框的尺寸范围：A3/A4 按不同出图比例缩放后大致落在 250~1200 × 180~900 之间
_PAGE_RECT_MIN_W, _PAGE_RECT_MAX_W = 250.0, 1200.0
_PAGE_RECT_MIN_H, _PAGE_RECT_MAX_H = 180.0, 900.0
_PAGE_RECT_MIN_RATIO, _PAGE_RECT_MAX_RATIO = 0.8, 2.2


def _page_rect_frames(doc, texts: list[tuple[float, float, str]]) -> list[dict[str, Any]]:
    """第三种画法：把每张图按页幅大小并排画在模型空间，不写图框、不写图号。

    取闭合多段线里页幅大小、且框内有文字的矩形当图页；被别的矩形包住的
    内框（标题栏、图例框）丢掉，只留整页外框。图号无从得知，按阅读顺序
    编号（先上后下、同排从左到右），保证"第几张"能翻到对应位置。
    """
    texts = [item for item in texts if item[2]]
    if len(texts) < 2:
        return []
    candidates: list[tuple[float, float, float, float]] = []
    for entity in doc.modelspace().query("LWPOLYLINE"):
        try:
            if not entity.closed:
                continue
            points = list(entity.get_points("xy"))
        except Exception:  # noqa: BLE001
            continue
        if len(points) < 4:
            continue
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        width = max(xs) - min(xs)
        height = max(ys) - min(ys)
        if not (_PAGE_RECT_MIN_W <= width <= _PAGE_RECT_MAX_W
                and _PAGE_RECT_MIN_H <= height <= _PAGE_RECT_MAX_H
                and _PAGE_RECT_MIN_RATIO <= width / height <= _PAGE_RECT_MAX_RATIO):
            continue
        candidates.append((min(xs), min(ys), max(xs), max(ys)))

    def area(rect: tuple[float, float, float, float]) -> float:
        return (rect[2] - rect[0]) * (rect[3] - rect[1])

    def contains(outer: tuple[float, float, float, float],
                 inner: tuple[float, float, float, float]) -> bool:
        return (inner[0] >= outer[0] - 1 and inner[1] >= outer[1] - 1
                and inner[2] <= outer[2] + 1 and inner[3] <= outer[3] + 1)

    outers = [rect for rect in candidates
              if not any(other is not rect and area(other) > area(rect) and contains(other, rect)
                         for other in candidates)]
    pages: list[tuple[float, float, float, float]] = []
    for rect in outers:
        if any(all(abs(rect[i] - page[i]) < 1 for i in range(4)) for page in pages):
            continue
        pages.append(rect)
    # 框里一个文字都没有的多半是图例/线框，不当图页
    pages = [rect for rect in pages
             if any(rect[0] <= x <= rect[2] and rect[1] <= y <= rect[3] for x, y, _ in texts)]
    if len(pages) < 2:
        return []
    # 排页顺序按视觉阅读顺序：先上排后下排，同排从左到右。
    # 不能按坐标分桶——同排页面的上下边会差几毫米，分桶会把一页挤到下一排去。
    pages.sort(key=lambda rect: -rect[3])
    rows: list[list[tuple[float, float, float, float]]] = []
    for rect in pages:
        center_y = (rect[1] + rect[3]) / 2
        for row in rows:
            ref = row[0]
            if abs(center_y - (ref[1] + ref[3]) / 2) < (ref[3] - ref[1]) * 0.4:
                row.append(rect)
                break
        else:
            rows.append([rect])
    rows.sort(key=lambda row: -max(rect[3] for rect in row))
    pages = [rect for row in rows for rect in sorted(row, key=lambda rect: rect[0])]
    frames: list[dict[str, Any]] = []
    for index, rect in enumerate(pages):
        frame = {
            "x": (rect[0] + rect[2]) / 2,
            "y": (rect[1] + rect[3]) / 2,
            "minX": round(rect[0], 3),
            "minY": round(rect[1], 3),
            "maxX": round(rect[2], 3),
            "maxY": round(rect[3], 3),
            "no": index + 1,
            "sheet": f"{index + 1:02d}",
            "title": "",
        }
        frames.append(frame)
    return frames


def _frame_no_of(point, frames: list[dict[str, Any]]) -> int | None:
    """判断某个点落在哪张图框里；图框外的表格/标注一律不挂页。

    以前兜底取最近的图框，结果画在图页外的汇总材料表被挂到隔壁页，
    点"第 27 张"进去那张图里根本没有这条。宁可标成"未定位"，也不能挂错页。
    """
    if not frames:
        return None
    best: int | None = None
    best_distance = 0.0
    for frame in frames:
        # 表格压在页边框上时算本页，这里留 2% 页幅的余量
        margin_x = max(2.0, (frame["maxX"] - frame["minX"]) * 0.02)
        margin_y = max(2.0, (frame["maxY"] - frame["minY"]) * 0.02)
        if (frame["minX"] - margin_x <= point[0] <= frame["maxX"] + margin_x
                and frame["minY"] - margin_y <= point[1] <= frame["maxY"] + margin_y):
            distance = (frame["x"] - point[0]) ** 2 + (frame["y"] - point[1]) ** 2
            if best is None or distance < best_distance:
                best = frame["no"]
                best_distance = distance
    return best


# 项目情况：标签后带冒号，按后缀识别（避免把"注意："、表格列名之类当成栏目）
_PROJECT_LABEL_RE = re.compile(r"^([\u4e00-\u9fa5A-Za-z]{2,10})\s*[：:]\s*(.*)$")
_PROJECT_LABEL_SUFFIXES = ("名称", "编号", "单位", "地点", "日期", "费用", "费",
                           "负责人", "规模", "性质", "电话")
# 展示顺序：按台账习惯排，未列到的栏目排在后面
_PROJECT_ORDER = ("项目名称", "工程名称", "项目编号", "合同编号", "订单编号",
                  "建设单位", "施工单位", "设计单位", "监理单位", "施工地点",
                  "施工日期", "施工费")
# 图签里栏目名常常不带冒号（表格里一格写栏名、右边格写值），按白名单认列名；
# 用白名单而不是"以名称结尾"，免得把材料表的「材料名称」也当成项目栏目
_PROJECT_LABEL_NAMES = frozenset(_PROJECT_ORDER) | {"工程编号", "设计编号", "建设地点"}
# 图签里「单位」「编 制」这类本身是栏名/签字栏，不能当栏目值
_PROJECT_VALUE_STOP = frozenset({"单位", "名称", "编号", "日期", "费用", "地点", "编制",
                                 "审核", "校核", "制图", "校对", "批准", "图号", "比例"})


def _nearest_right(items, x0: float, y0: float, tol: float) -> str:
    """取标签同一行右侧最近的文字作为栏目值。"""
    best: tuple[float, str] | None = None
    for x, y, _height, text in items:
        if x <= x0 or abs(y - y0) > tol:
            continue
        if _PROJECT_LABEL_RE.match(text) or text.strip() in _PROJECT_LABEL_NAMES:
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
        if match:
            label = match.group(1).strip()
            if not any(label.endswith(suffix) for suffix in _PROJECT_LABEL_SUFFIXES):
                continue
            inline = match.group(2).strip()
        else:
            # 图签表格里栏名不带冒号，只认白名单里的栏目名
            label = text.strip()
            if label not in _PROJECT_LABEL_NAMES:
                continue
            inline = ""
        value = inline or _nearest_right(items, x, y, max(height, 0.5))
        if value and _norm_header(value) not in _PROJECT_VALUE_STOP:
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
            # 列名同样要先归一化：「数 量」「数量(米)」都要能对上「数量」
            if key in _norm_header(name):
                return index
    return None


def _to_number(raw: str) -> float | None:
    try:
        return float((raw or "").replace(",", "").strip())
    except ValueError:
        return None


# 定额编号 + 名称写进同一个 TEXT 的格式：可选序号 + 编号(字母+数字-数字) + 空白 + 名称。
# 短横前要求带数字（TXL4-049），避免误伤材料型号（GYTS-48B1）这类「字母-数字」写法。
_QUOTA_CELL_RE = re.compile(
    r"^\s*(?:(\d{1,3})\s+)?([A-Za-z]{2,8}\d{1,3}[-–—]\d{2,5})\s+(.+?)\s*$"
)
_QUOTA_CODE_KEYS = ("定额编号", "项目编码", "编号")


def _normalize_quota_cells(tables: list[dict[str, Any]]) -> None:
    """拆开「定额编号 + 项目名称」挤在同一格里的单元格，就地修正 tables。

    CAD 里这两列之间常没画竖线，一段 TEXT（如「TXL4-049 光（电）缆上线洞楼层间防火封堵
    光交封堵」）的插入点落在编号列，整段就被归进编号列、名称列空着。后果有两处：
    明细里编号列混着名称、名称列空；归纳统计取名称列时空值整行被丢。这里按定额编号
    模式拆开，编号归编号列、名称归名称列；表格缺编号列时补一列，并把原格里的序号留下。
    """
    for table in tables:
        columns = table["columns"]
        name_col = _match_column(columns, _MATERIAL_NAME_KEYS)
        if name_col is None:
            continue
        code_col = _match_column(columns, _QUOTA_CODE_KEYS)
        seq_col = _match_column(columns, ("序号",))

        # 先找本表所有「编号+名称」合并格，记录 (行号, 原列号, 序号, 编号, 名称)
        merged_cells: list[tuple[int, int, str, str, str]] = []
        for row_index, row in enumerate(table["rows"]):
            for cell_index, cell in enumerate(row):
                match = _QUOTA_CELL_RE.match(cell or "")
                if match and match.group(3).strip():
                    merged_cells.append((row_index, cell_index, match.group(1) or "",
                                         match.group(2), match.group(3).strip()))
                    break
        if not merged_cells:
            continue

        # 缺编号列时补一列（放在名称列左侧），后续名称列及以右的列号整体右移
        if code_col is None:
            columns.insert(name_col, "定额编号")
            for row in table["rows"]:
                row.insert(name_col, "")
            code_col = name_col
            name_col += 1
            if seq_col is not None and seq_col >= code_col:
                seq_col += 1

        for row_index, cell_index, seq, code, name in merged_cells:
            row = table["rows"][row_index]
            # 原合并格若是序号列且带序号，把序号留下；否则清空
            row[cell_index] = seq if (cell_index == seq_col and seq) else ""
            row[name_col] = name
            row[code_col] = code


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
        # 保留 6 位：沟千米这类单位数量很小（0.0058），只留 3 位会被进位成 0.006，
        # 与清单的 0.0058 生成虚假的 3.4% 差异
        item["quantity"] = round(item["quantity"], 6)
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
        etype = entity.dxftype()
        if etype == "INSERT" and _TABLE_BLOCK_RE.match(entity.dxf.name):
            refs.setdefault(entity.dxf.name, []).append(entity)
        elif etype == "ACAD_TABLE":
            # 没走过后处理（未展开 ACAD_TABLE）的图纸也要能定位：几何块名在组码 340
            block_name = entity.dxf.get("geometry")
            if block_name:
                refs.setdefault(block_name, []).append(entity)

    tables: list[dict[str, Any]] = []
    for block in doc.blocks:
        if not _TABLE_BLOCK_RE.match(block.name):
            continue
        table = _build_table(block)
        if not table:
            continue
        refs_of_block = refs.get(block.name) or []
        if not refs_of_block:
            # 没有块引用就落不到模型空间，块内坐标标红会指到别处，宁可丢掉
            table["pos"] = None
            table.pop("cells", None)
            tables.append(table)
            continue
        # 同一个表格块可能被多处引用（分幅复制、整表复制），每个引用位置各出一张表：
        # 只取第一个位置的话，后面的表会全被算到同一张图上，图幅归属和编号都错
        for entity in refs_of_block:
            item = dict(table)
            item["rows"] = [list(row) for row in table["rows"]]  # 各表行数据互不共享
            item["pos"] = (entity.dxf.insert.x, entity.dxf.insert.y)
            item["box"] = _insert_box(entity)
            item["cells"] = _transform_cells(table.get("cells"), entity)
            tables.append(item)
    return tables


def _transform_cells(cells: list[dict[str, Any]] | None, insert) -> list[dict[str, Any]]:
    """块内文字坐标 → 模型空间：同一张表被复制到多个图幅时，各自按引用的位置换算。"""
    if not cells:
        return []
    matrix = insert.matrix44()
    base = matrix.transform((0, 0, 0))
    unit = matrix.transform((1, 0, 0)) - base
    scale = math.hypot(unit.x, unit.y) or 1.0
    result: list[dict[str, Any]] = []
    for cell in cells:
        point = matrix.transform((cell["x"], cell["y"], 0))
        result.append({
            "text": cell["text"],
            "x": round(point.x, 3),
            "y": round(point.y, 3),
            "h": round(cell["h"] * scale, 3),
        })
    return result


def _insert_box(entity) -> dict[str, float]:
    """块引用在模型空间里的包围盒：块内坐标是定义坐标，不能直接拿来定位。"""
    from ezdxf import bbox

    try:
        extents = bbox.extents([entity])
    except Exception:  # noqa: BLE001 ACAD_TABLE 等特殊实体算不出范围时退化为只按插入点定位
        return {}
    if not extents.has_data:
        return {}
    return {"minX": round(extents.extmin.x, 3), "minY": round(extents.extmin.y, 3),
            "maxX": round(extents.extmax.x, 3), "maxY": round(extents.extmax.y, 3)}


def _tables_from_text(doc) -> list[dict[str, Any]]:
    """画法二：普通 LINE 外框 + TEXT 手工画的表格（没有块名可循）。"""
    return _text_tables(_layout_texts(doc.modelspace()))


# 图上「工作量：光缆24芯：549米」这类文字标注：不是表格，但写明了材料和数量。
# 一个 MTEXT 里多行用 \P 分隔，清洗后变成空格，所以按「名称：数量单位」逐个扫。
_WORKLOAD_ITEM_RE = re.compile(
    r"(?P<name>[^：:\s]{1,30})\s*[：:]\s*(?P<qty>\d+(?:\.\d+)?)\s*(?P<unit>[^\s\d：:，,。；;]{1,6})"
)
# 名称里的规格：安装24芯分纤箱 / 光缆24芯 / 安装48芯ODF子框
_NAME_SPEC_RE = re.compile(r"(?P<spec>\d+(?:\.\d+)?)\s*芯")


def _split_name_spec(name: str) -> tuple[str, str]:
    """把「安装24芯分纤箱」拆成名称「安装分纤箱」+ 规格「24芯」，便于和清单逐项比对。"""
    match = _NAME_SPEC_RE.search(name)
    if not match:
        return name, ""
    base = (name[:match.start()] + name[match.end():]).strip(" -_/")
    # 保留「24芯」整体：清单规格列写的就是 24芯，只留数字会对不上
    return base or name, match.group(0).replace(" ", "")


def _tables_from_workload(doc) -> list[dict[str, Any]]:
    """画法三：图上的「工作量：光缆24芯：549米」文字标注（没有画表格的竣工图）。

    每个含「工作量」的 MTEXT/TEXT 生成一张虚拟材料表，列统一为
    材料名称 / 规格 / 单位 / 数量，后续归纳、图页归属与清单比对复用既有流程。
    """
    tables: list[dict[str, Any]] = []
    for entity in doc.modelspace():
        etype = entity.dxftype()
        if etype not in ("TEXT", "MTEXT"):
            continue
        raw = entity.dxf.text if etype == "TEXT" else entity.text
        if not raw or "工作量" not in raw:
            continue
        text = clean_annotation(raw)
        rows: list[list[str]] = []
        for match in _WORKLOAD_ITEM_RE.finditer(text):
            name, spec = _split_name_spec(match.group("name"))
            if not name:
                continue
            rows.append([name, spec, match.group("unit"), match.group("qty")])
        if not rows:
            continue
        try:
            point = entity.dxf.insert
            pos = (float(point.x), float(point.y))
        except Exception:  # noqa: BLE001
            pos = None
        # 标注本身的范围：图纸没有图框时靠它把预览定位到这段文字所在的图页
        box: dict[str, float] = {}
        try:
            from ezdxf import bbox

            extents = bbox.extents([entity])
            if extents.has_data:
                box = {"minX": round(extents.extmin.x, 3), "minY": round(extents.extmin.y, 3),
                       "maxX": round(extents.extmax.x, 3), "maxY": round(extents.extmax.y, 3)}
        except Exception:  # noqa: BLE001
            box = {}
        tables.append({
            "columns": ["材料名称", "规格", "单位", "数量"],
            "rows": rows,
            "pos": pos,
            "box": box,
            "source": "workload",
        })
    return tables


# 表格抽取器注册表：(key, 说明, 抽取函数)。
# 不同设计院/不同软件导出的图纸画法各异，这里把"找表"拆成互不干扰的策略：
# 每个抽取器只认一种画法、只返回候选表，合并/去重/排序/图框归属/编号统一在
# extract_material_tables 里做。以后遇到新画法（表在块定义里、跨图纸参照、
# 表格是 OLE/Excel 附件…）只需加一个函数并注册，主流程和已有图纸都不受影响。
TABLE_EXTRACTORS: tuple[tuple[str, str, Any], ...] = (
    ("block", "CAD 表格对象", _tables_from_blocks),
    ("text", "线框文字表格", _tables_from_text),
    ("workload", "工作量文字标注", _tables_from_workload),
)


def extract_material_tables(doc, diagnostics: dict[str, int] | None = None,
                            frames: list[dict[str, Any]] | None = None
                            ) -> list[dict[str, Any]]:
    """还原图纸里的表格（材料设备清单），同一图幅内重复的表格只保留一份。

    编号按「图框阅读顺序 + 图框内自上而下」排，不按块定义顺序：同一张图的两张表
    （主工程量表、光缆段长统计表）块名可能相隔十几号，按块名排就会变成"表1、表15"，
    看上去像是漏了中间的表。frameIndex 是图框内的序号（表1、表2…），table 是全局
    唯一序号，归纳统计靠它去重计数。

    Args:
        diagnostics: 传入空字典时回填每个抽取器命中多少张表，供前端/日志判断
            这张图是用哪种画法解析出来的。
        frames: 已算好的图框，传进来省一次遍历（标注核算也要用同一份图框）。
    """
    if frames is None:
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
    # 拆开「定额编号+名称」合并格：明细展示与归纳统计都依赖正确的列归属
    _normalize_quota_cells(tables)
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
    length_labels: list[dict[str, Any]] = []
    length_totals: list[dict[str, Any]] = []
    total_entities = 0
    total_length = 0.0

    def collect_text(raw: str, layer: str, x: float = 0.0, y: float = 0.0) -> None:
        """收集标注文本：既进 annotations 明细，也尝试当段长解析（MTEXT 会先按 \P 拆行）"""
        for part in (raw or "").replace("\\P", "\n").replace("\\p", "\n").split("\n"):
            clean = clean_annotation(part)
            if not clean:
                continue
            if len(annotations) < sample_limit:
                annotations.append(clean)
            segments, totals = parse_length_labels(clean)
            for value, kind, is_int in segments:
                if len(length_labels) < 5000:
                    length_labels.append({"text": clean, "value": round(value, 3), "kind": kind,
                                          "isInt": is_int, "x": x, "y": y, "layer": layer})
            for value, pure in totals:
                if len(length_totals) < 500:
                    length_totals.append({"text": clean, "value": round(value, 3), "pure": pure,
                                          "x": x, "y": y, "layer": layer})

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
            # 图块属性里也常写段长（例如带属性的标注块），一并收进来
            for attrib in getattr(entity, "attribs", []):
                try:
                    point = attrib.dxf.insert
                    collect_text(attrib.dxf.text, layer, float(point.x), float(point.y))
                except Exception:  # noqa: BLE001
                    collect_text(getattr(attrib.dxf, "text", ""), layer)
        elif etype in ("TEXT", "MTEXT"):
            text = entity.dxf.text if etype == "TEXT" else entity.text
            try:
                point = entity.dxf.insert
                position = (float(point.x), float(point.y))
            except Exception:  # noqa: BLE001
                position = (0.0, 0.0)
            collect_text(text, layer, position[0], position[1])
        elif len(entities_sample) < sample_limit:
            entities_sample.append({"id": entity.dxf.handle, "type": etype, "layer": layer})

    cable_layers = {k: round(v, 3) for k, v in length_by_layer.items()
                    if any(h in k for h in CABLE_HINTS)}
    device_blocks = {k: v for k, v in block_counts.items() if any(h in k for h in DEVICE_HINTS)}
    device_total = sum(device_blocks.values())
    meters = _to_meters(total_length, units)
    cable_meters = _to_meters(sum(cable_layers.values()), units)
    # 剔掉表格里成堆的序号/图号，只留下像段长的标注
    length_labels = _filter_table_numbers(length_labels)

    # 表格要先抽出来：图上的「光缆总长」标注要靠材料表数量识别，否则会跟逐段长度重复累加
    project_info = extract_project_info(doc)
    frames = _drawing_frames(doc)
    table_hits: dict[str, int] = {}
    materials = extract_material_tables(doc, table_hits, frames)

    # 标注段长合计：图纸没画线缆图层时的长度来源（标注一般直接写米）。
    # 按「张」归组后再合计：合计行、分段、材料表里的光缆数量要能对上同一张图。
    def sheet_key_of(item: dict[str, Any]) -> str:
        frame_no = _frame_no_of((item["x"], item["y"]), frames)
        return _sheet_key(next((f for f in frames if f["no"] == frame_no), None))

    cable_rows_by_sheet = _table_cable_items(materials, frames)
    annotation = summarize_annotations(length_labels, length_totals, sheet_key_of,
                                       cable_rows_by_sheet)
    segments = annotation["segments"]
    label_meters = annotation["meters"]
    # 材料表里印的光缆数量：图纸自己给的工程量，标注缺失时比按段长反推更可信
    material_cable_meters = round(sum(row["meters"] for rows in cable_rows_by_sheet.values()
                                      for row in rows), 3)

    kind_totals: dict[str, float] = {}
    bare_total = 0.0
    for item in segments:
        key = item["kind"] or "bare"
        kind_totals[key] = kind_totals.get(key, 0.0) + item["value"]
        if not item["kind"]:
            bare_total += item["value"]
    # 按敷设方式拆开只在"全部按分段取量"时成立：用了合计/材料表时拆出来的和跟总数对不上，
    # 前端按类别比对会误判，宁可不给分类明细
    annotation_by_kind = ({k: round(v, 3) for k, v in kind_totals.items()}
                          if annotation["basis"] == "segments" else {})

    # 按图层核算：用户要能按图层核对「这个层一共标了多少米」
    layer_totals: dict[str, dict[str, Any]] = {}
    for item in segments:
        bucket = layer_totals.setdefault(item["layer"], {"count": 0, "meters": 0.0})
        bucket["count"] += 1
        bucket["meters"] += item["value"]
    annotation_by_layer = {
        layer: {"count": bucket["count"], "meters": round(bucket["meters"], 3)}
        for layer, bucket in layer_totals.items()
    }

    material_summary = summarize_materials(materials)
    material_items = sum(len(table["rows"]) for table in materials)
    # 记录每种画法各抽出多少张表，出问题时能一眼看出是"没这张表"还是"这种画法没覆盖"
    table_sources = [{"key": key, "label": label, "count": table_hits.get(key, 0)}
                     for key, label, _ in TABLE_EXTRACTORS]

    # 采用值和材料表数量一致时不重复写两遍，免得看着像两个口径
    if material_cable_meters and _is_close_value(label_meters, material_cable_meters):
        cable_note = f"，标注/材料表光缆 {material_cable_meters} m"
    elif material_cable_meters:
        cable_note = f"，标注段长 {label_meters} m、材料表光缆 {material_cable_meters} m"
    elif label_meters:
        cable_note = f"，标注段长 {label_meters} m"
    else:
        cable_note = ""

    summary = (f"图元 {total_entities} 个，图层 {len(layer_counts)} 个；"
               f"线路总长 {round(meters, 2)} m（线缆图层 {round(cable_meters, 2)} m"
               + cable_note + "）；"
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
            # 材料表里印的线缆数量合计：图纸自己算好的工程量，比按标注反推更可信
            "materialCableMeters": material_cable_meters,
            # 图上标注的长度（5.7、桥20、合计399.5 这类）：没画光缆图层时靠它反推线缆长度
            "annotationLengthMeters": label_meters,
            "annotationByKind": annotation_by_kind,
            "annotationByLayer": annotation_by_layer,
            "annotationBareMeters": round(bare_total, 3),
            "annotationCount": len(segments),
            # 图上印的「合计」行：按张列出，跟分段和、材料表光缆数量一起核对
            "annotationTotalMeters": round(sum(row["totalMeters"] for row in annotation["sheets"]), 3),
            "annotationTotalCount": len(length_totals),
            "annotationTotals": [{"text": item["text"], "value": item["value"],
                                  "layer": item["layer"]}
                                 for item in length_totals[:40]],
            "annotationBasis": annotation["basis"],
            "annotationBySheet": annotation["sheets"],
            # 与材料表光缆数量重复的标注（图上的光缆总长）：摘出来只作核对，不参与合计
            "annotationDropped": [{"text": item["text"], "value": item["value"],
                                   "layer": item["layer"]}
                                  for item in annotation["dropped"][:40]],
            "lengthAnnotations": [{"text": item["text"], "value": item["value"],
                                   "kind": item["kind"], "layer": item["layer"]}
                                  for item in segments[:80]],
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
        # 图框清单：材料表用 frame（图框号）标来源，前端要按号找到这张图的张号/图名
        # 和图框范围，才能从材料行跳到那一页图纸上去看
        "frames": [{"no": frame["no"], "sheet": frame.get("sheet"),
                    "title": frame.get("title"),
                    "minX": round(frame["minX"], 3), "minY": round(frame["minY"], 3),
                    "maxX": round(frame["maxX"], 3), "maxY": round(frame["maxY"], 3)}
                   for frame in frames[:200]],
        "materials": materials,
        "materialSummary": material_summary,
        "projectInfo": project_info,
        "materialDiagnostics": {"sources": table_sources, "tableCount": len(materials)},
        "entities": entities_sample,
        "annotations": annotations[:100],
        "hint": "一期图元回溯定位仅做清单行号，图纸图元坐标回溯在二期补齐",
    }
