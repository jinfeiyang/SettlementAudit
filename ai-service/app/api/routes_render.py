"""图纸预览路由：DWG/DXF → DXF（前端交互渲染）/ SVG（服务端快照）。

与 /parse/dxf 共用 drawing_store 缓存：同一张图纸只转换一次。
"""
from fastapi import APIRouter, File, HTTPException, Query, Response, UploadFile
from fastapi.concurrency import run_in_threadpool

from app.core.config import get_settings
from app.services.drawing_render import render_svg
from app.services.drawing_store import get_or_convert

router = APIRouter(prefix="/render", tags=["render"])


@router.post("/drawing")
async def render_drawing(
    file: UploadFile = File(...),
    format: str = Query("dxf", pattern="^(dxf|svg)$"),
    width_mm: float = Query(420, gt=50, le=2000),
    height_mm: float = Query(297, gt=50, le=2000),
):
    """图纸预览。

    - `format=dxf`：返回转换后的 DXF，供前端 dxf-viewer 交互渲染（可点选图元）
    - `format=svg`：返回服务端渲染的只读快照，用于超大图纸降级与报告插图
    """
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")

    settings = get_settings()
    try:
        dxf_path, cached = await run_in_threadpool(
            get_or_convert, data, file.filename or "drawing.dxf", settings.oda_converter_path)
    except Exception as exc:  # 转换失败需把原因带回（未装 ODA / 图纸版本不兼容 / 加密）
        return Response(content=f"图纸转换失败：{exc}".encode("utf-8"), status_code=422,
                        media_type="text/plain; charset=utf-8")

    headers = {"X-Cache": "hit" if cached else "miss"}
    if format == "svg":
        svg_text = await run_in_threadpool(render_svg, dxf_path, width_mm, height_mm)
        return Response(content=svg_text.encode("utf-8"), media_type="image/svg+xml", headers=headers)
    return Response(content=dxf_path.read_bytes(), media_type="application/dxf", headers=headers)
