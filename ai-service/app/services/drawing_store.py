"""图纸转换产物缓存：DWG→DXF 与 DXF 落盘共用一份，按内容哈希去重。

解析（/parse/dxf）与预览（/render/drawing）走同一缓存，避免同一张图纸被反复转换
（一次 ODA 转换 3~7 秒，且容器内耗 CPU）。缓存目录优先 AI_CACHE_DIR（容器挂 PVC
/data/cache），不可写时回退系统临时目录，保证本地开发无需额外配置。

缓存分两层：
  {key}.raw.dxf    转换出来的原始 DXF，只写一次，永不修改
  {key}.dxf        经过后处理、真正返回给前端的 DXF
  {key}.version    后处理流程版本号，版本不一致时自动重建 {key}.dxf
分开存放是为了让后处理逻辑可迭代：逻辑改了只需从 raw 重跑，不必重新做 ODA 转换。
"""
from __future__ import annotations

import hashlib
import logging
import math
import os
import re
import shutil
import tempfile
from pathlib import Path

from app.core.config import get_settings
from app.services.dxf_parser import convert_dwg_to_dxf

logger = logging.getLogger(__name__)

_CACHE_SUBDIR = "dxf"

# DXF 后处理流程版本：后处理步骤有变化时递增，已缓存图纸会从 raw 重新跑一遍后处理。
PIPELINE_VERSION = "6"

# MTEXT 内联字高码：\H3.175x; 或 \H3.175;
_MTEXT_HEIGHT_RE = re.compile(r"\\H([\d.]+)x?;")

# 估算文字宽度用：西文字符相对字高的宽度系数（Noto Sans SC 实测 0.5~0.6）
_ASCII_EM_WIDTH = 0.55
# 折算字高时留 2% 余量，避免刚好卡在折行边界
_HEIGHT_SAFETY = 0.98
# 为放进单元格而缩字高时的下限（相对目标字高），再小就允许折行，避免文字缩成一片
_MIN_HEIGHT_RATIO = 0.55


def cache_dir() -> Path:
    """返回可写的缓存目录；配置目录不可用时回退临时目录。"""
    raw = (get_settings().cache_dir or "").strip()
    candidates: list[Path] = [Path(raw)] if raw else []
    candidates.append(Path(tempfile.gettempdir()) / "preaudit-cache")
    for base in candidates:
        target = base / _CACHE_SUBDIR
        try:
            target.mkdir(parents=True, exist_ok=True)
            if os.access(target, os.W_OK):
                return target
        except OSError as exc:  # 无权限/只读挂载：继续尝试下一个候选
            logger.warning("缓存目录不可用 %s：%s", target, exc)
    return Path(tempfile.mkdtemp(prefix="preaudit-cache-"))


def content_key(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:32]


def _iter_layouts(doc):
    """模型空间 + 全部块定义（图框块、匿名表格块 *T20 等都在这层）。"""
    yield doc.modelspace()
    for block in doc.blocks:
        yield block


def fix_text_align_points(doc) -> int:
    """给「非左对齐但缺对齐点」的 TEXT 补对齐点。

    居中/右对齐的 TEXT，位置取自对齐点（组码 11/21/31），插入点（10/20/30）在 CAD 里
    基本不参与定位。个别图纸只写了 72/73 而漏掉 11/21/31，前端按缺失对齐点计算会得到
    NaN 顶点、整条文字被丢弃。这里把插入点复制成对齐点兜底，对齐方式保持原样。

    注意不要像早期版本那样把 72/73 归零：那会让居中文字改成从对齐点左起绘制，
    表格与图签里的文字整体偏到左上角，看上去"没在正中间"。
    """
    fixed = 0
    for layout in _iter_layouts(doc):
        for entity in layout:
            if entity.dxftype() != "TEXT":
                continue
            if not (entity.dxf.get("halign", 0) or entity.dxf.get("valign", 0)):
                continue
            if "align_point" in entity.dxf.all_existing_dxf_attribs():
                continue
            entity.dxf.align_point = entity.dxf.insert
            fixed += 1
    return fixed


def apply_mtext_inline_height(doc) -> int:
    r"""把 MTEXT 内联字高码（\H3.175x;）折算进字符高度，并从文本里去掉该码。

    表格单元格文字常写成 `{\f宋体|b1|i0|c134;\H3.175x;序号}`：实体字符高度只有 0.667，
    真实显示高度靠内联码放大 3.175 倍。前端 dxf-viewer 不支持内联字号（\H 被跳过），
    渲染出来只有设计高度的 1/3，单元格里又小又偏。这里把倍数乘进字符高度、删掉内联码，
    尺寸与 CAD 一致，居中/对齐方式不变。

    仅处理「整条 MTEXT 只出现一处 \H」的情况；一段文本里混用多种字号的不动，避免失真。

    放大后还要按宽度因子收一收：CAD 里文字按文字样式的宽度因子（这类图纸是 0.7）
    横向压缩过，前端不做压缩，同样的字高会宽出 40%，单元格里的长文本就会被挤到折行。
    这里把宽度因子折进字高（字高同时决定渲染宽度），再用实体的参考宽度（组码 41）
    兜底：万一估算下来仍放不进单元格，就收到刚好放得下为止。
    """
    changed = 0
    for layout in _iter_layouts(doc):
        for entity in layout:
            if entity.dxftype() != "MTEXT":
                continue
            text = entity.text or ""
            found = _MTEXT_HEIGHT_RE.findall(text)
            if len(found) != 1:
                continue
            try:
                mult = float(found[0])
            except ValueError:
                continue
            if mult <= 0:
                continue

            width_factor = _style_width_factor(doc, entity)
            height = (entity.dxf.get("char_height", 1) or 1) * mult * width_factor * _HEIGHT_SAFETY
            width = entity.dxf.get("width", 0) or 0
            plain = entity.plain_text()
            if width > 0 and plain and "\n" not in plain:
                max_height = width / (_estimate_text_em(plain) * 1.02)
                if max_height < height:
                    height = max(max_height, height * _MIN_HEIGHT_RATIO)

            entity.dxf.char_height = height
            entity.text = _MTEXT_HEIGHT_RE.sub("", text)
            changed += 1
    return changed


def _style_width_factor(doc, entity) -> float:
    """取文字样式的宽度因子（STYLE 表组码 41），取不到按 1 处理。"""
    try:
        style = doc.styles.get(entity.dxf.get("style", "Standard"))
        factor = float(style.dxf.get("width", 1) or 1)
    except Exception:  # noqa: BLE001 样式缺失/值异常都不该影响出图
        return 1.0
    return factor if factor > 0 else 1.0


def _estimate_text_em(text: str) -> float:
    """估算一行文字的宽度（以字高的倍数计）：汉字按 1 个字高，西文按 0.55。"""
    total = 0.0
    for char in text:
        total += 1.0 if ord(char) >= 0x2E80 else _ASCII_EM_WIDTH
    return total or 1.0


def expand_tables(doc) -> int:
    """把 ACAD_TABLE 展开为指向其几何块的普通块引用。

    AutoCAD 2018+ 的表格实体只记录「几何在哪个匿名块（*T20 之类）」，单元格文字与
    边框线都在该块内。前端渲染器 dxf-viewer 不支持 ACAD_TABLE，会整块不显示
    （表现为图里的表格只剩空框或干脆没有）。替换为普通 INSERT 后块正常展开。
    """
    msp = doc.modelspace()
    tables = [e for e in msp if e.dxftype() == "ACAD_TABLE"]
    if not tables:
        return 0

    expanded = 0
    for table in tables:
        block_name = table.dxf.get("geometry")
        if not block_name or block_name not in doc.blocks:
            continue
        insert = table.dxf.insert
        ref = msp.add_blockref(block_name, (insert.x, insert.y, insert.z))
        direction = table.dxf.get("horizontal_direction")
        if direction is not None:
            ref.dxf.rotation = math.degrees(math.atan2(direction.y, direction.x))
        msp.delete_entity(table)
        expanded += 1
    return expanded


def compact_outlier_layout(doc, ratio: float = 0.02) -> int:
    """把远离主体的离散图元簇搬到主体旁，避免整体 bounds 被撑大。

    竣工图常见：主体图集中在某区域，另有一张材料表/图签被放在几十万单位之外。
    前端 dxf-viewer 按 scene.bounds 自动 FitView，主体只占视野不到 1%，表现为
    “打开后图纸很小，需手动放大”。这里按 X 方向最大间隙找出离散簇，整体平移
    到主体右侧；不缩放、不旋转，主体坐标不变，图元间的相对位置保持，仅合并布局。
    仅当离散簇占比很小（默认 2%）且间隙远大于主体宽度时才处理，避免误伤分幅图。

    Returns:
        被平移的实体数量。
    """
    from ezdxf import bbox
    from ezdxf.math import Matrix44

    msp = doc.modelspace()
    try:
        cached = bbox.Cache()
        boxes: list[tuple] = []
        for entity in msp:
            try:
                entity_bbox = bbox.extents([entity], cache=cached)
                if entity_bbox.has_data:
                    boxes.append((entity, entity_bbox))
            except Exception:  # noqa: BLE001 单个实体算不出包围盒就跳过
                continue
    except Exception as exc:  # noqa: BLE001 bbox 不可用时静默跳过
        logger.warning("紧凑布局跳过（包围盒计算失败）：%s", exc)
        return 0

    if len(boxes) < 20:
        return 0

    centers_x = sorted((b.extmin.x + b.extmax.x) / 2 for _, b in boxes)
    # 找 X 方向的相对最大间隙，作为主体与离散簇的分界
    gap, split = 0.0, -1
    for i in range(1, len(centers_x)):
        span = centers_x[i] - centers_x[i - 1]
        if span > gap:
            gap, split = span, i
    if split <= 0 or split >= len(centers_x):
        return 0

    main_x = centers_x[split - 1]
    outlier_x = centers_x[split]
    main_width = main_x - centers_x[0]
    left_count, right_count = split, len(centers_x) - split
    minority = min(left_count, right_count)
    # 离散簇必须占比很小，且间隙远大于主体宽度，避免把正常分幅图误判为离散
    if minority > len(centers_x) * ratio or gap < main_width * 2:
        return 0

    if right_count <= left_count:
        moving = [(e, b) for e, b in boxes if (b.extmin.x + b.extmax.x) / 2 >= outlier_x]
        keep = [(e, b) for e, b in boxes if (b.extmin.x + b.extmax.x) / 2 < outlier_x]
    else:
        moving = [(e, b) for e, b in boxes if (b.extmin.x + b.extmax.x) / 2 <= main_x]
        keep = [(e, b) for e, b in boxes if (b.extmin.x + b.extmax.x) / 2 > main_x]
    if not keep or not moving:
        return 0

    keep_max_x = max(b.extmax.x for _, b in keep)
    keep_min_x = min(b.extmin.x for _, b in keep)
    keep_min_y = min(b.extmin.y for _, b in keep)
    move_min_x = min(b.extmin.x for _, b in moving)
    move_min_y = min(b.extmin.y for _, b in moving)

    spacing = max(main_width * 0.05, 1.0)
    dx = keep_max_x + spacing - move_min_x
    dy = keep_min_y - move_min_y
    matrix = Matrix44.translate(dx, dy, 0)
    moved = 0
    for entity, _ in moving:
        try:
            entity.transform(matrix)
            moved += 1
        except Exception:  # noqa: BLE001 个别实体不支持平移则保留原位
            continue
    return moved


def postprocess(dxf_path: Path) -> None:
    """对 DXF 做一次后处理（一次读写完成全部步骤）。"""
    import ezdxf

    try:
        doc = ezdxf.readfile(str(dxf_path))
    except Exception as exc:  # noqa: BLE001 图纸异常不应阻断解析
        logger.warning("图纸后处理跳过（读取失败 %s）：%s", dxf_path.name, exc)
        return

    align = fix_text_align_points(doc)
    height = apply_mtext_inline_height(doc)
    tables = expand_tables(doc)
    compacted = compact_outlier_layout(doc)
    if not (align or height or tables or compacted):
        return
    try:
        doc.saveas(str(dxf_path))
    except Exception as exc:  # noqa: BLE001 保存失败保留原文件即可
        logger.warning("图纸后处理保存失败 %s：%s", dxf_path.name, exc)
        return
    logger.info("图纸后处理（对齐点补全 %d、内联字高折算 %d、表格展开 %d、离散图元归位 %d）：%s",
                align, height, tables, compacted, dxf_path.name)


def _cache_ready(cached: Path, version_file: Path) -> bool:
    if not (cached.exists() and cached.stat().st_size > 0):
        return False
    try:
        return version_file.read_text(encoding="utf-8").strip() == PIPELINE_VERSION
    except OSError:
        return False


def get_or_convert(data: bytes, filename: str, oda_converter: str = "") -> tuple[Path, bool]:
    """取图纸对应的 DXF：命中缓存直接返回，否则转换、后处理后缓存。

    Returns:
        (DXF 文件路径, 是否命中缓存)
    """
    suffix = os.path.splitext(filename or "")[1].lower() or ".dxf"
    key = content_key(data)
    directory = cache_dir()
    raw_path = directory / f"{key}.raw.dxf"
    cached = directory / f"{key}.dxf"
    version_file = directory / f"{key}.version"

    if _cache_ready(cached, version_file):
        return cached, True

    # 原始 DXF：只写一次。旧版本缓存没有 raw，这里会补做一次转换
    if not (raw_path.exists() and raw_path.stat().st_size > 0):
        if suffix == ".dxf":
            raw_path.write_bytes(data)
        else:
            src_dir = Path(tempfile.mkdtemp(prefix="preaudit-dwg-"))
            try:
                src = src_dir / f"source{suffix}"
                src.write_bytes(data)
                produced = convert_dwg_to_dxf(src, oda_converter)
                shutil.move(str(produced), str(raw_path))  # 跨设备时自动降级为复制+删除
            finally:
                shutil.rmtree(src_dir, ignore_errors=True)
        logger.info("图纸转换完成并缓存：%s（%.1f KB）", raw_path.name, raw_path.stat().st_size / 1024)

    # 后处理产物：先写临时文件再原子替换，避免并发请求读到写了一半的 DXF
    tmp_path = directory / f"{key}.tmp{os.getpid()}.dxf"
    shutil.copyfile(raw_path, tmp_path)
    postprocess(tmp_path)
    os.replace(tmp_path, cached)

    try:
        version_file.write_text(PIPELINE_VERSION, encoding="utf-8")
    except OSError as exc:  # 标记写不进去只是下次会从 raw 重新后处理，不影响出图
        logger.warning("后处理版本标记写入失败 %s：%s", version_file.name, exc)
    (directory / f"{key}.done").unlink(missing_ok=True)  # 清掉旧版标记，避免混淆
    return cached, False
