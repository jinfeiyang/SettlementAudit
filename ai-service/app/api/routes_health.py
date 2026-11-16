from fastapi import APIRouter

from app.core.config import get_settings
from app.services.dxf_parser import resolve_oda_converter

router = APIRouter(tags=["health"])


def _capabilities() -> dict:
    caps = {}
    try:
        import ezdxf  # noqa: F401
        caps["ezdxf"] = True
    except ImportError:
        caps["ezdxf"] = False
    try:
        import openpyxl  # noqa: F401
        caps["openpyxl"] = True
    except ImportError:
        caps["openpyxl"] = False
    return caps


@router.get("/health")
def health():
    s = get_settings()
    converter = resolve_oda_converter(s.oda_converter_path)
    return {
        "service": "preaudit-ai-service",
        "version": s.version,
        "llm_configured": bool(s.llm_base_url and s.llm_api_key),
        "oda_configured": bool(converter),
        "oda_converter": converter,
        "capabilities": _capabilities(),
    }
