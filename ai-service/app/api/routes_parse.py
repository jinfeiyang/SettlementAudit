"""解析路由：CAD(DWG/DXF) 矢量解析与清单 Excel 解析。

一期路线：ODA File Converter(DWG->DXF) + ezdxf 解析，量算聚焦「线路长度、设备数量」；
Java 业务服务对解析失败做降级处理，不影响清单核查主链路。
"""
from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from app.core.config import get_settings
from app.services.boq_parser import parse_boq_bytes
from app.services.dxf_parser import parse_dxf_bytes

router = APIRouter(prefix="/parse", tags=["parse"])


@router.post("/dxf")
async def parse_dxf(file: UploadFile = File(...)):
    """DXF/DWG 矢量解析：图层、块、标注提取与线缆长度/设备数量量算。"""
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")
    settings = get_settings()
    result = await run_in_threadpool(
        parse_dxf_bytes, data, file.filename or "drawing.dxf",
        settings.dxf_sample_limit, settings.oda_converter_path,
    )
    result["file"] = file.filename
    return result


@router.post("/boq-excel")
async def parse_boq_excel(file: UploadFile = File(...)):
    """工程量清单 Excel 解析：输出清单行（含行号，用于回溯）与标准化建议。"""
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")
    result = await run_in_threadpool(parse_boq_bytes, data, file.filename or "boq.xlsx")
    result["file"] = file.filename
    return result
