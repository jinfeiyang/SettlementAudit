"""AI/解析服务入口（独立服务，独立发布；与 Java 核算内核解耦）。"""
from fastapi import FastAPI

from app.api import routes_health, routes_llm, routes_parse, routes_render
from app.core.config import get_settings


def create_app() -> FastAPI:
    s = get_settings()
    app = FastAPI(title=s.app_name, version=s.version)
    app.include_router(routes_health.router)
    app.include_router(routes_parse.router)
    app.include_router(routes_render.router)
    app.include_router(routes_llm.router)
    return app


app = create_app()
