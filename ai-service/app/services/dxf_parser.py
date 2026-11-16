"""CAD 矢量解析与量算（A1）：DWG→DXF 转换 + 图层/块/标注提取 + 线路长度、设备数量量算。

一期范围：DXF 直解；DWG 需配置 ODA File Converter。
量算聚焦「线路长度、设备数量」两类，异常图纸降级为人工辅助标注（返回明确提示）。
"""
from __future__ import annotations

import logging
import math
import os
import shutil
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
)
ODA_DOWNLOAD_URL = "https://www.opendesign.com/guestfiles/oda_file_converter"
DEVICE_HINTS = ("设备", "机架", "机柜", "天线", "开关", "电源", "盒", "箱", "盘", "架")
CABLE_HINTS = ("光缆", "电缆", "纤", "线缆", "线路")


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


def resolve_oda_converter(configured: str = "") -> str:
    """定位 ODA File Converter：优先 AI_ODA_CONVERTER_PATH，其次 PATH 与常见安装路径自动探测。

    返回可执行文件绝对路径；未安装返回空串（供健康检查与失败提示复用）。
    """
    if configured:
        path = Path(configured).expanduser()
        if path.exists():
            return str(path)
        logger.warning("AI_ODA_CONVERTER_PATH 指向的文件不存在：%s，转为自动探测", configured)
    found = shutil.which("ODAFileConverter")
    if found:
        return found
    for candidate in ODA_CANDIDATES:
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
            "安装后设置 AI_ODA_CONVERTER_PATH（macOS 一般为 "
            "/Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter，"
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

    summary = (f"图元 {total_entities} 个，图层 {len(layer_counts)} 个；"
               f"线路总长 {round(meters, 2)} m（线缆图层 {round(cable_meters, 2)} m）；"
               f"设备块 {device_total} 个")

    return {
        "status": "SUCCESS",
        "summary": summary,
        "unit": units,
        "measure": {
            "totalEntities": total_entities,
            "totalLengthMeters": round(meters, 3),
            "cableLengthMeters": round(cable_meters, 3),
            "deviceCount": device_total,
            "lengthByLayer": {k: round(v, 3) for k, v in sorted(
                length_by_layer.items(), key=lambda kv: kv[1], reverse=True)[:30]},
            "cableLayers": cable_layers,
            "blockCounts": dict(sorted(block_counts.items(), key=lambda kv: kv[1], reverse=True)[:30]),
            "deviceBlocks": device_blocks,
            "layers": dict(sorted(layer_counts.items(), key=lambda kv: kv[1], reverse=True)[:50]),
        },
        "entities": entities_sample,
        "annotations": annotations[:100],
        "hint": "一期图元回溯定位仅做清单行号，图纸图元坐标回溯在二期补齐",
    }
