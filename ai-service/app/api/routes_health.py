from fastapi import APIRouter

from app.core.config import get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
def health():
    s = get_settings()
    return {
        "service": "preaudit-ai-service",
        "version": s.version,
        "llm_configured": bool(s.llm_base_url and s.llm_api_key),
    }
