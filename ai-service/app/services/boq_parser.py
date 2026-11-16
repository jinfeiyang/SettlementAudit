"""工程量清单 Excel 解析（A2）：表头自适应、行号保留、标准化建议。

与 Java 端 POI 解析结果保持一致的口径（行号 / 工程量 / 单价 / 合价 / 可解析率），
供 AI 语义对齐与规则库补充时独立调用。
"""
from __future__ import annotations

import io
import re
from typing import Any

HEADER_KEYWORDS = ("项目编码", "清单编码", "定额编号", "项目名称", "项目特征",
                   "计量单位", "工程量", "单价", "合价", "金额")
SKIP_NAMES = ("合计", "小计", "总计", "总价", "以下空白", "说明")
NUMBER_RE = re.compile(r"[-+]?\d+(\.\d+)?")
LINE_HINTS = ("光缆", "电缆", "管道", "架空", "线路", "杆", "接头盒", "人手孔", "敷设", "直埋", "槽道", "桥架")
DEVICE_HINTS = ("设备", "机架", "机柜", "天线", "馈线", "电源", "蓄电池", "开关", "传输", "基站")


def _num(raw: Any) -> float | None:
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return float(raw)
    match = NUMBER_RE.search(str(raw).replace(",", ""))
    return float(match.group()) if match else None


def _normalize(value: Any) -> str:
    if value is None:
        return ""
    text = str(value)
    chars = []
    for ch in text:
        code = ord(ch)
        if 0xFF01 <= code <= 0xFF5E:
            chars.append(chr(code - 0xFEE0))
        elif code == 0x3000:
            chars.append(" ")
        else:
            chars.append(ch)
    return re.sub(r"\s+", "", "".join(chars)).lower()


def _guess_eng_type(name: str, feature: str) -> str | None:
    text = _normalize(f"{name} {feature}")
    if any(hint in text for hint in LINE_HINTS):
        return "LINE"
    if any(hint in text for hint in DEVICE_HINTS):
        return "DEVICE"
    return None


def _find_header(rows: list[list[Any]], limit: int = 10) -> int:
    best_row, best_hit = -1, 1
    for index, row in enumerate(rows[:limit]):
        hit = sum(1 for cell in row if cell and any(k in str(cell) for k in HEADER_KEYWORDS))
        if hit > best_hit:
            best_row, best_hit = index, hit
    return best_row


def _find_col(header: list[Any], *keywords: str) -> int:
    for index, cell in enumerate(header):
        text = _normalize(cell)
        if cell and any(_normalize(k) in text for k in keywords):
            return index
    return -1


def _cell(row: list[Any], index: int) -> str | None:
    if index < 0 or index >= len(row):
        return None
    value = row[index]
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def parse_boq_bytes(data: bytes, filename: str) -> dict[str, Any]:
    try:
        from openpyxl import load_workbook
    except ImportError:
        return {"status": "FAILED", "message": "未安装 openpyxl，无法解析清单 Excel"}

    try:
        workbook = load_workbook(io.BytesIO(data), data_only=True, read_only=True)
    except Exception as exc:  # noqa: BLE001
        return {"status": "FAILED", "message": f"清单文件读取失败：{exc}"}

    sheet = workbook.worksheets[0]
    rows = [list(row) for row in sheet.iter_rows(values_only=True)]
    header_row = _find_header(rows)
    if header_row < 0:
        return {"status": "FAILED",
                "message": "未识别到清单表头（需包含：项目编码/项目名称/单位/工程量/单价/合价）",
                "parseRate": 0, "rows": []}

    header = rows[header_row]
    idx_no = _find_col(header, "序号", "行号")
    idx_code = _find_col(header, "项目编码", "清单编码", "定额编号", "编号")
    idx_name = _find_col(header, "项目名称", "分项名称", "清单名称", "名称")
    idx_feature = _find_col(header, "项目特征", "特征描述", "工作内容")
    idx_unit = _find_col(header, "计量单位", "单位")
    idx_qty = _find_col(header, "工程量", "工程数量", "数量")
    idx_price = _find_col(header, "综合单价", "单价")
    idx_total = _find_col(header, "合价", "金额", "合计", "总价")

    items: list[dict[str, Any]] = []
    candidates = 0
    for offset, row in enumerate(rows[header_row + 1:], start=header_row + 2):
        name = _cell(row, idx_name)
        code = _cell(row, idx_code)
        if name is None and code is None:
            continue
        if name and any(skip in name for skip in SKIP_NAMES) and len(name.strip()) <= 6:
            continue
        candidates += 1
        if name is None:
            continue
        quantity = _num(_cell(row, idx_qty))
        price = _num(_cell(row, idx_price))
        total = _num(_cell(row, idx_total))
        if total is None and quantity is not None and price is not None:
            total = round(quantity * price, 2)
        seq = _num(_cell(row, idx_no))
        items.append({
            "rowNo": int(seq) if seq is not None else offset,
            "itemCode": code,
            "itemName": name,
            "featureDesc": _cell(row, idx_feature),
            "unit": _cell(row, idx_unit),
            "quantity": quantity,
            "unitPrice": price,
            "totalPrice": total,
            "engType": _guess_eng_type(name, _cell(row, idx_feature) or ""),
            "stdItemName": _normalize(name),
        })

    rate = round(len(items) * 100.0 / candidates, 2) if candidates else 0.0
    eng_types = [i["engType"] for i in items if i["engType"]]
    eng_type = max(set(eng_types), key=eng_types.count) if eng_types else None
    return {
        "status": "SUCCESS",
        "summary": f"识别表头第 {header_row + 1} 行，数据行 {candidates}，成功解析 {len(items)} 行，可解析率 {rate}%",
        "parseRate": rate,
        "candidateRows": candidates,
        "engType": eng_type,
        "rows": items,
    }
