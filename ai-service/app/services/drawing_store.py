"""图纸转换产物缓存：DWG→DXF 与 DXF 落盘共用一份，按内容哈希去重。

解析（/parse/dxf）与预览（/render/drawing）走同一缓存，避免同一张图纸被反复转换
（一次 ODA 转换 3~7 秒，且容器内耗 CPU）。缓存目录优先 AI_CACHE_DIR（容器挂 PVC
/data/cache），不可写时回退系统临时目录，保证本地开发无需额外配置。
"""
from __future__ import annotations

import hashlib
import logging
import math
import os
import shutil
import tempfile
from pathlib import Path

from app.core.config import get_settings
from app.services.dxf_parser import convert_dwg_to_dxf

logger = logging.getLogger(__name__)

_CACHE_SUBDIR = "dxf"

# DXF 后处理流程版本：normalize_text_alignment / explode_tables 等步骤有变化时递增，
# 已缓存图纸会被重新跑一遍后处理（不必重新做 ODA 转换）。旧缓存没有标记文件，同样会补跑。
PIPELINE_VERSION = "2"


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


def normalize_text_alignment(dxf_path: Path) -> int:
    """把「非左对齐」的 TEXT 改成左对齐，规避前端渲染器丢字。

    真实竣工图的图签文字常用居中/右对齐（组码 72/73 非 0），此时文字位置取自对齐点
    （组码 11/21/31）。前端 dxf-viewer 在该路径下会算出 NaN 顶点，整条文字被丢弃
    （日志表现为 `Skipped malformed TEXT entity ... Non-finite vertex`）。
    这里把对齐点坐标搬到插入点（10/20/30）、对齐方式归零、删除对齐点组码，
    文字位置保持在同一处，只是由“居中”变为“左起”。

    只在文本层面替换目标组码，不整体重写 DXF，避免影响 ACAD_TABLE 等复杂实体。
    """
    try:
        raw = dxf_path.read_text(encoding="utf-8", errors="surrogateescape")
    except OSError as exc:
        logger.warning("文字对齐规范化跳过（读取失败 %s）：%s", dxf_path.name, exc)
        return 0

    lines = raw.split("\n")
    out: list[str] = []
    fixed = 0
    i, n = 0, len(lines)
    while i + 1 < n:
        if lines[i].strip() == "0" and lines[i + 1].strip() == "TEXT":
            body: list[tuple[str, str]] = []
            j = i + 2
            while j + 1 < n and lines[j].strip() != "0":
                body.append((lines[j].strip(), lines[j + 1]))
                j += 2
            codes = dict(body)
            halign = _int_or_zero(codes.get("72"))
            valign = _int_or_zero(codes.get("73"))
            if halign or valign:
                alias = {c: codes.get(c2) for c, c2 in (("10", "11"), ("20", "21"), ("30", "31"))}
                rebuilt: list[tuple[str, str]] = []
                for code, value in body:
                    if code in ("72", "73"):
                        rebuilt.append((code, "0"))
                    elif code in ("11", "21", "31"):
                        continue
                    elif code in alias and alias[code] is not None:
                        rebuilt.append((code, alias[code]))
                    else:
                        rebuilt.append((code, value))
                body = rebuilt
                fixed += 1
            out.append(lines[i])
            out.append(lines[i + 1])
            for code, value in body:
                out.append(code)
                out.append(value)
            i = j
            continue
        out.append(lines[i])
        out.append(lines[i + 1])
        i += 2
    if i < n:  # 末尾残余行（无配对组码）原样保留
        out.extend(lines[i:])

    if not fixed:
        return 0
    dxf_path.write_text("\n".join(out), encoding="utf-8", errors="surrogateescape")
    logger.info("文字对齐规范化 %d 处：%s", fixed, dxf_path.name)
    return fixed


def explode_tables(dxf_path: Path) -> int:
    """把 ACAD_TABLE 展开为指向其几何块的普通块引用。

    AutoCAD 2018+ 的表格实体只记录「几何在哪个匿名块（*T20 之类）」，
    单元格文字与边框线都在该块内。前端渲染器 dxf-viewer 不支持 ACAD_TABLE，
    会整块不显示（表现为图里的表格只剩空框或干脆没有）。
    替换为普通 INSERT 后块被正常展开，表格线与单元格文字都能渲染。

    该步骤需要用 ezdxf 整体重写 DXF，仅在有表格时执行；实测实体数量不变。
    """
    import ezdxf

    try:
        doc = ezdxf.readfile(str(dxf_path))
    except Exception as exc:  # noqa: BLE001 图纸异常不应阻断解析
        logger.warning("表格展开跳过（读取失败 %s）：%s", dxf_path.name, exc)
        return 0

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

    if not expanded:
        return 0
    try:
        doc.saveas(str(dxf_path))
    except Exception as exc:  # noqa: BLE001 保存失败保留原文件即可
        logger.warning("表格展开后保存失败 %s：%s", dxf_path.name, exc)
        return 0
    logger.info("表格展开为块引用 %d 个：%s", expanded, dxf_path.name)
    return expanded


def _int_or_zero(raw: str | None) -> int:
    try:
        return int((raw or "0").strip())
    except ValueError:
        return 0


def _ensure_postprocessed(cached: Path) -> None:
    """确保缓存的 DXF 已跑完当前版本的后处理（文字对齐规范化、表格展开）。

    早期缓存是在这些步骤加入前生成的，若只看文件是否存在就直接复用，
    图纸里的 ACAD_TABLE 会一直是未展开状态，前端只画得出表格边框、画不出单元格文字。
    这里用标记文件记录后处理版本，缓存命中时也能自愈。
    """
    marker = cached.with_suffix(".done")
    try:
        if marker.read_text(encoding="utf-8").strip() == PIPELINE_VERSION:
            return
    except OSError:
        pass
    normalize_text_alignment(cached)
    explode_tables(cached)
    try:
        marker.write_text(PIPELINE_VERSION, encoding="utf-8")
    except OSError as exc:  # 标记写不进去只是下次会重跑后处理，不影响出图
        logger.warning("后处理标记写入失败 %s：%s", marker.name, exc)


def get_or_convert(data: bytes, filename: str, oda_converter: str = "") -> tuple[Path, bool]:
    """取图纸对应的 DXF：命中缓存直接返回，否则转换/落盘后缓存。

    Returns:
        (DXF 文件路径, 是否命中缓存)
    """
    suffix = os.path.splitext(filename or "")[1].lower() or ".dxf"
    cached = cache_dir() / f"{content_key(data)}.dxf"
    if cached.exists() and cached.stat().st_size > 0:
        _ensure_postprocessed(cached)
        return cached, True

    if suffix == ".dxf":
        cached.write_bytes(data)
    else:
        src_dir = Path(tempfile.mkdtemp(prefix="preaudit-dwg-"))
        try:
            src = src_dir / f"source{suffix}"
            src.write_bytes(data)
            produced = convert_dwg_to_dxf(src, oda_converter)
            shutil.move(str(produced), str(cached))  # 跨设备时自动降级为复制+删除
        finally:
            shutil.rmtree(src_dir, ignore_errors=True)
    logger.info("图纸转换完成并缓存：%s（%.1f KB）", cached.name, cached.stat().st_size / 1024)
    _ensure_postprocessed(cached)
    return cached, False
