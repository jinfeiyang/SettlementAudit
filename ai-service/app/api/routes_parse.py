"""解析路由：CAD(DWG/DXF) 解析与清单解析占位。

一期路线：ODA File Converter(DWG->DXF) + ezdxf 解析 + shapely 量算，
量算聚焦"线路长度、设备数量"两类（见 docs/03 开发计划 A1）。
"""
from fastapi import APIRouter, UploadFile, File

router = APIRouter(prefix="/parse", tags=["parse"])


@router.post("/dxf")
async def parse_dxf(file: UploadFile = File(...)):
    """DXF 矢量解析占位：提取图层/块属性/标注，量算线路长度与设备数量。"""
    # TODO(W1~W2): ODA 转换 + ezdxf 解析 + shapely 量算
    return {"file": file.filename, "status": "NOT_IMPLEMENTED", "hint": "ezdxf 解析在 W1~W2 接入"}


@router.post("/boq-excel")
async def parse_boq_excel(file: UploadFile = File(...)):
    """工程量清单 Excel 解析占位：输出清单行（含行号，用于回溯）。"""
    # TODO(W1~W2): openpyxl/pandas 解析 + 清单标准化
    return {"file": file.filename, "status": "NOT_IMPLEMENTED", "hint": "清单解析在 W1~W2 接入"}
